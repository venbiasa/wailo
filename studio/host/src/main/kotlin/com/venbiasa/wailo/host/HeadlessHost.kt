package com.venbiasa.wailo.host

import com.venbiasa.wailo.engine.BodyRef
import com.venbiasa.wailo.engine.BodyStore
import com.venbiasa.wailo.engine.CapturedExchange
import com.venbiasa.wailo.engine.ConnectedDevice
import com.venbiasa.wailo.engine.InMemoryBodyStore
import com.venbiasa.wailo.engine.MapLocalBodyProvider
import com.venbiasa.wailo.engine.PausedExchange
import com.venbiasa.wailo.engine.ScriptTransformProvider
import com.venbiasa.wailo.engine.WailoEngine
import com.venbiasa.wailo.engine.pairing.PairingManager
import com.venbiasa.wailo.engine.pairing.InMemoryPairingKeyStore
import com.venbiasa.wailo.engine.pairing.PairingKeyStore
import com.venbiasa.wailo.protocol.BreakpointRule
import com.venbiasa.wailo.protocol.CaptureFilter
import com.venbiasa.wailo.protocol.HttpRequest
import com.venbiasa.wailo.protocol.HttpResponse
import com.venbiasa.wailo.protocol.MapLocalRule
import com.venbiasa.wailo.protocol.ScriptPhase
import com.venbiasa.wailo.protocol.ScriptTransformRequest
import com.venbiasa.wailo.protocol.ScriptTransformResult
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * UI-free orchestration over [WailoEngine] for CLI / MCP / Appium frontends (ADR-0055). Owns the Map
 * Local, Script, breakpoint, and Seed registries with their feature masters, Seed auto-spend, and query helpers,
 * so no frontend reimplements them.
 *
 * Every frontend reaches this through the daemon that owns it (ADR-0058), Studio included: a seed is
 * spent here, once, whether or not a window is open (ADR-0067).
 */
class HeadlessHost private constructor(
    val engine: WailoEngine,
    private val scope: CoroutineScope,
) {
    val queries = TrafficQueries(engine)

    private val seedMutex = Mutex()

    /** The authored seed library, in priority order. Fill arms the enabled ones out of it. */
    private val _seeds = MutableStateFlow<List<HostSeed>>(emptyList())
    val seeds: StateFlow<List<HostSeed>> = _seeds.asStateFlow()

    /**
     * What Fill armed, minus whatever has been spent. Session state even here: a half-spent queue is a
     * position in a run, not a preference, so it dies with the process rather than being restored into a
     * script whose first half never happened (ADR-0041).
     */
    private val _seedQueue = MutableStateFlow<List<HostSeed>>(emptyList())
    val seedQueue: StateFlow<List<HostSeed>> = _seedQueue.asStateFlow()

    @Volatile
    private var seedsEnabled = true

    /**
     * Holds this host has already decided about — matched and answered, or left alone. It is the only
     * way a frontend can tell "no seed wanted this hold" from "the spend has not run yet", and Studio
     * needs that difference: opening a window on first sight would flash one open for every hold a seed
     * is about to answer (ADR-0067).
     */
    private val _triagedHolds = MutableStateFlow<Set<String>>(emptySet())
    val triagedHolds: StateFlow<Set<String>> = _triagedHolds.asStateFlow()

    private val mapLocalMutex = Mutex()
    private val _mapLocalRules = MutableStateFlow<List<HostMapLocalRule>>(emptyList())
    val mapLocalRules: StateFlow<List<HostMapLocalRule>> = _mapLocalRules.asStateFlow()

    @Volatile
    private var mapLocalEnabled = true

    private val _captureFilter = MutableStateFlow(CaptureFilter())

    /**
     * The capture filter as authored: each list's own armed state, before [isCaptureFilterEnabled] is
     * folded in. The engine holds the folded form — what devices and the proxy actually apply — so this
     * is what a frontend edits and what the daemon persists, which is how turning the master off and on
     * again restores exactly what was armed (ADR-0030).
     */
    val captureFilter: StateFlow<CaptureFilter> = _captureFilter.asStateFlow()

    @Volatile
    private var captureFilterEnabled = true

    @Volatile
    private var fallbackBodyProvider: MapLocalBodyProvider? = null

    private val breakpointMutex = Mutex()
    private val _breakpointRules = MutableStateFlow<List<HostBreakpointRule>>(emptyList())
    val breakpointRules: StateFlow<List<HostBreakpointRule>> = _breakpointRules.asStateFlow()

    @Volatile
    private var breakpointsEnabled = true

    private val scriptMutex = Mutex()
    private val _scripts = MutableStateFlow<List<HostScript>>(emptyList())
    val scripts: StateFlow<List<HostScript>> = _scripts.asStateFlow()

    @Volatile
    private var scriptsEnabled = true

    @Volatile
    private var scriptExecutor: ScriptExecutor? = null

    /**
     * Optional fallback for rules pushed through the low-level [updateRules] API. Rules registered with
     * [upsertMapLocalRule] always resolve from the host's in-memory registry first.
     */
    var bodyProvider: MapLocalBodyProvider?
        get() = fallbackBodyProvider
        set(value) {
            fallbackBodyProvider = value
        }

    private val triaged = mutableSetOf<String>()

    init {
        engine.bodyProvider = MapLocalBodyProvider { ruleId, url, method ->
            val registered = _mapLocalRules.value.firstOrNull { it.id == ruleId }
            if (registered != null) {
                if (mapLocalEnabled) registered.serve(url, method) else null
            } else {
                fallbackBodyProvider?.serve(ruleId, url, method)
            }
        }

        // Arrival-only triage (ADR-0044): a response-phase hold a queued seed matches is answered here;
        // everything else is left for a frontend to resume/abort, or for an explicit Fill to sweep.
        scope.launch {
            engine.pausedExchanges.collect(::triage)
        }
    }

    fun start(): Boolean = engine.start()

    fun stop() {
        runCatching { scriptExecutor?.close() }
        engine.stop()
        scope.cancel()
    }

    suspend fun rebind(port: Int): Boolean = engine.rebind(port)

    fun clear() = engine.clear()

    fun setCapturing(enabled: Boolean) = engine.setCapturing(enabled)

    fun setMaxRetained(n: Int) = engine.setMaxRetained(n)

    fun updateRules(rules: List<MapLocalRule>) = engine.updateRules(rules)

    /**
     * Replaces the authored filter, and the master with it when [masterEnabled] is given — one lock and
     * one push, so neither the engine nor a polling frontend can observe the lists of this edit beside
     * the master of the last one. Null leaves the master as it is, for an edit that is only about hosts.
     */
    @Synchronized
    fun updateCaptureFilter(
        allowlistEnabled: Boolean,
        allowPatterns: List<String>,
        blocklistEnabled: Boolean,
        blockPatterns: List<String>,
        masterEnabled: Boolean? = null,
    ) {
        _captureFilter.value = CaptureFilter(
            allowlist_enabled = allowlistEnabled,
            allow_patterns = allowPatterns,
            blocklist_enabled = blocklistEnabled,
            block_patterns = blockPatterns,
        )
        masterEnabled?.let { captureFilterEnabled = it }
        pushCaptureFilter()
    }

    /** Flips the filter's feature master, leaving each list's armed state and hosts untouched. */
    @Synchronized
    fun setCaptureFilterEnabled(enabled: Boolean) {
        captureFilterEnabled = enabled
        pushCaptureFilter()
    }

    fun isCaptureFilterEnabled(): Boolean = captureFilterEnabled

    fun updateBreakpointRules(rules: List<BreakpointRule>) = engine.updateBreakpointRules(rules)

    suspend fun upsertMapLocalRule(rule: HostMapLocalRule) {
        require(rule.id.isNotBlank()) { "Map Local id must not be blank" }
        require(rule.urlPattern.isNotBlank()) { "Map Local URL pattern must not be blank" }
        require(rule.statusCode in 100..599) { "Map Local status must be between 100 and 599" }
        require(rule.delayMillis >= 0) { "Map Local delay must not be negative" }
        mapLocalMutex.withLock {
            val current = _mapLocalRules.value
            val index = current.indexOfFirst { it.id == rule.id }
            _mapLocalRules.value = if (index == -1) {
                current + rule
            } else {
                current.toMutableList().also { it[index] = rule }
            }
            pushRegisteredMapLocalRules()
        }
    }

    suspend fun removeMapLocalRule(id: String): Boolean = mapLocalMutex.withLock {
        val current = _mapLocalRules.value
        val next = current.filterNot { it.id == id }
        if (next.size == current.size) return@withLock false
        _mapLocalRules.value = next
        pushRegisteredMapLocalRules()
        true
    }

    suspend fun setMapLocalEnabled(enabled: Boolean) {
        mapLocalMutex.withLock {
            mapLocalEnabled = enabled
            pushRegisteredMapLocalRules()
        }
    }

    suspend fun replaceMapLocalRules(rules: List<HostMapLocalRule>, enabled: Boolean) {
        require(rules.map { it.id }.distinct().size == rules.size) { "Map Local rule ids must be unique" }
        rules.forEach {
            require(it.id.isNotBlank()) { "Map Local id must not be blank" }
            require(it.urlPattern.isNotBlank()) { "Map Local URL pattern must not be blank" }
            require(it.statusCode in 100..599) { "Map Local status must be between 100 and 599" }
            require(it.delayMillis >= 0) { "Map Local delay must not be negative" }
        }
        mapLocalMutex.withLock {
            _mapLocalRules.value = rules.toList()
            mapLocalEnabled = enabled
            pushRegisteredMapLocalRules()
        }
    }

    fun isMapLocalEnabled(): Boolean = mapLocalEnabled

    suspend fun upsertBreakpointRule(rule: HostBreakpointRule) {
        require(rule.id.isNotBlank()) { "Breakpoint id must not be blank" }
        require(rule.urlPattern.isNotBlank()) { "Breakpoint URL pattern must not be blank" }
        require(rule.onRequest || rule.onResponse) { "Breakpoint must pause requests, responses, or both" }
        breakpointMutex.withLock {
            val current = _breakpointRules.value
            val index = current.indexOfFirst { it.id == rule.id }
            _breakpointRules.value = if (index == -1) {
                current + rule
            } else {
                current.toMutableList().also { it[index] = rule }
            }
            pushRegisteredBreakpointRules()
        }
    }

    suspend fun removeBreakpointRule(id: String): Boolean = breakpointMutex.withLock {
        val current = _breakpointRules.value
        val next = current.filterNot { it.id == id }
        if (next.size == current.size) return@withLock false
        _breakpointRules.value = next
        pushRegisteredBreakpointRules()
        true
    }

    suspend fun setBreakpointsEnabled(enabled: Boolean) {
        breakpointMutex.withLock {
            breakpointsEnabled = enabled
            pushRegisteredBreakpointRules()
        }
    }

    suspend fun replaceBreakpointRules(rules: List<HostBreakpointRule>, enabled: Boolean) {
        require(rules.map { it.id }.distinct().size == rules.size) { "Breakpoint ids must be unique" }
        rules.forEach {
            require(it.id.isNotBlank()) { "Breakpoint id must not be blank" }
            require(it.urlPattern.isNotBlank()) { "Breakpoint URL pattern must not be blank" }
            require(it.onRequest || it.onResponse) { "Breakpoint must pause requests, responses, or both" }
        }
        breakpointMutex.withLock {
            _breakpointRules.value = rules.toList()
            breakpointsEnabled = enabled
            pushRegisteredBreakpointRules()
        }
    }

    fun areBreakpointsEnabled(): Boolean = breakpointsEnabled

    fun installScriptExecutor(executor: ScriptExecutor) {
        scriptExecutor = executor
        engine.scriptTransformProvider = ScriptTransformProvider(::transformScripts)
    }

    suspend fun validateScript(source: String): ScriptValidation =
        requireNotNull(scriptExecutor) { "Script executor is not installed" }.validate(source)

    suspend fun upsertScript(script: HostScript) {
        validateScriptDefinition(script)
        scriptMutex.withLock {
            val current = _scripts.value
            val index = current.indexOfFirst { it.id == script.id }
            _scripts.value = if (index == -1) {
                current + script
            } else {
                current.toMutableList().also { it[index] = script }
            }
            scriptExecutor?.clearIssue(script.id)
            pushRegisteredScripts()
        }
    }

    suspend fun removeScript(id: String): Boolean = scriptMutex.withLock {
        val current = _scripts.value
        val next = current.filterNot { it.id == id }
        if (next.size == current.size) return@withLock false
        _scripts.value = next
        scriptExecutor?.clearIssue(id)
        pushRegisteredScripts()
        true
    }

    suspend fun replaceScripts(scripts: List<HostScript>, enabled: Boolean) {
        require(scripts.map { it.id }.distinct().size == scripts.size) { "Script ids must be unique" }
        scripts.forEach(::validateScriptDefinition)
        scriptMutex.withLock {
            val nextById = scripts.associateBy { it.id }
            _scripts.value
                .filter { current -> nextById[current.id] != current }
                .forEach { scriptExecutor?.clearIssue(it.id) }
            _scripts.value = scripts.toList()
            scriptsEnabled = enabled
            pushRegisteredScripts()
        }
    }

    suspend fun setScriptsEnabled(enabled: Boolean) {
        scriptMutex.withLock {
            scriptsEnabled = enabled
            pushRegisteredScripts()
        }
    }

    fun areScriptsEnabled(): Boolean = scriptsEnabled

    val scriptIssues: List<ScriptRuntimeIssue>
        get() = scriptExecutor?.issues?.value.orEmpty()

    suspend fun transformScripts(request: ScriptTransformRequest): ScriptTransformResult {
        val input = request.identityResult()
        if (!scriptsEnabled) return input
        val matches = _scripts.value.filter { script ->
            script.enabled &&
                when (request.phase) {
                    ScriptPhase.SCRIPT_PHASE_REQUEST -> script.onRequest
                    ScriptPhase.SCRIPT_PHASE_RESPONSE -> script.onResponse
                    else -> false
                } &&
                urlPatternMatches(script.urlPattern, request.request?.url.orEmpty()) &&
                methodPatternMatches(script.method, request.request?.method.orEmpty())
        }
        if (matches.isEmpty()) return input
        return runCatching { scriptExecutor?.execute(matches, request) }.getOrNull() ?: input
    }

    fun listDevices(): List<ConnectedDevice> = engine.connectedDevices.value

    fun listExchanges(): List<CapturedExchange> = engine.exchanges.value

    /**
     * Read a captured body back by range. Every frontend goes through this rather than getting bytes
     * with the row: a poll that carried them would put a session's whole traffic on the wire, and into
     * the client's heap, to render a list of URLs.
     */
    fun readBody(ref: BodyRef, offset: Long, length: Int): ByteArray = engine.readBody(ref, offset, length)

    fun listHolds(): List<PausedExchange> = engine.pausedExchanges.value

    fun resumeHold(
        correlationId: String,
        editedRequest: HttpRequest? = null,
        editedResponse: HttpResponse? = null,
    ) = engine.resumeBreakpoint(correlationId, editedRequest, editedResponse)

    fun abortHold(correlationId: String) = engine.abortBreakpoint(correlationId)

    suspend fun upsertSeed(seed: HostSeed) {
        require(seed.id.isNotBlank()) { "Seed id must not be blank" }
        require(seed.urlPattern.isNotBlank()) { "Seed URL pattern must not be blank" }
        require(seed.statusCode in 100..599) { "Seed status must be between 100 and 599" }
        require(seed.delayMillis >= 0) { "Seed delay must not be negative" }
        seedMutex.withLock {
            val current = _seeds.value
            val index = current.indexOfFirst { it.id == seed.id }
            _seeds.value = if (index == -1) {
                current + seed
            } else {
                current.toMutableList().also { it[index] = seed }
            }
            reconcileSeedQueue()
        }
    }

    suspend fun removeSeed(id: String): Boolean = seedMutex.withLock {
        val current = _seeds.value
        val next = current.filterNot { it.id == id }
        if (next.size == current.size) return@withLock false
        _seeds.value = next
        reconcileSeedQueue()
        true
    }

    suspend fun replaceSeeds(seeds: List<HostSeed>, enabled: Boolean) {
        require(seeds.map { it.id }.distinct().size == seeds.size) { "Seed ids must be unique" }
        seeds.forEach {
            require(it.id.isNotBlank()) { "Seed id must not be blank" }
            require(it.urlPattern.isNotBlank()) { "Seed URL pattern must not be blank" }
            require(it.statusCode in 100..599) { "Seed status must be between 100 and 599" }
            require(it.delayMillis >= 0) { "Seed delay must not be negative" }
        }
        seedMutex.withLock {
            _seeds.value = seeds.toList()
            seedsEnabled = enabled
            reconcileSeedQueue()
        }
    }

    suspend fun setSeedsEnabled(enabled: Boolean) {
        seedMutex.withLock {
            seedsEnabled = enabled
            // A hold decided while the master was off never met a seed, so turning it on is its first
            // chance rather than a retry — forget the decisions and take them again.
            if (enabled) triaged.clear()
        }
        if (enabled) triage(engine.pausedExchanges.value)
    }

    fun areSeedsEnabled(): Boolean = seedsEnabled

    /**
     * Arm the enabled library in order and spend against every hold already waiting — the headless form
     * of the desktop Fill button (ADR-0044). Re-filling replaces the queue, which is how a partly-spent
     * sequence is reset mid-run. Returns how many seeds are still armed once the sweep is done, so a
     * scripted caller can tell "nothing matched" from "the queue answered everything".
     */
    suspend fun fillSeeds(): Int = seedMutex.withLock {
        if (!seedsEnabled) {
            _seedQueue.value = emptyList()
            return@withLock 0
        }
        var queue = _seeds.value.filter { it.enabled }
        _seedQueue.value = queue
        engine.pausedExchanges.value.forEach { hold ->
            val spent = spendSeedOn(engine, queue, hold) ?: return@forEach
            queue = spent
            _seedQueue.value = queue
        }
        queue.size
    }

    suspend fun clearSeedQueue() {
        seedMutex.withLock { _seedQueue.value = emptyList() }
    }

    fun findExchangeById(id: String): CapturedExchange? = queries.findExchangeById(id)

    fun searchTraffic(
        urlContains: String? = null,
        urlPattern: String? = null,
        method: String? = null,
        statusCode: Int? = null,
        appId: String? = null,
    ): List<CapturedExchange> = queries.findExchangesByUrl(urlContains, urlPattern, method, statusCode, appId)

    suspend fun waitForExchange(
        timeout: Duration = 30.seconds,
        urlContains: String? = null,
        urlPattern: String? = null,
        method: String? = null,
        statusCode: Int? = null,
    ): CapturedExchange? = queries.waitForExchange(timeout, urlContains, urlPattern, method, statusCode)

    suspend fun waitForHold(
        timeout: Duration = 30.seconds,
        predicate: (PausedExchange) -> Boolean = { true },
    ): PausedExchange? = queries.waitForHold(timeout, predicate)

    fun summarizeExchanges(limit: Int = 50): String = queries.summarizeExchanges(limit)

    fun summarizeHolds(): String = queries.summarizeHolds()

    private suspend fun triage(holds: List<PausedExchange>) {
        seedMutex.withLock {
            triaged.retainAll(holds.mapTo(mutableSetOf()) { it.correlationId })
            holds.forEach { hold ->
                // Marked before the attempt, and marked even with the master off: one complete decision
                // per hold. A no-match, a missing body, or a disabled master leaves it for an explicit
                // Fill or a human rather than retrying forever.
                if (!triaged.add(hold.correlationId)) return@forEach
                if (!seedsEnabled) return@forEach
                val spent = try {
                    spendSeedOn(engine, _seedQueue.value, hold)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                if (spent != null) _seedQueue.value = spent
            }
            _triagedHolds.value = triaged.toSet()
        }
    }

    /**
     * Re-project the armed queue onto the library after an edit: order preserved, deleted seeds dropped,
     * edited ones picked up. Without it the queue would keep answering with a fixture the user has just
     * changed or removed, and no surface would show why.
     */
    private fun reconcileSeedQueue() {
        val byId = _seeds.value.associateBy { it.id }
        _seedQueue.value = _seedQueue.value.mapNotNull { byId[it.id] }
    }

    // Mirrors pushRegisteredMapLocalRules: the master gates only what reaches the engine, so an off master
    // captures everything while each list keeps the armed state it will come back with.
    private fun pushCaptureFilter() {
        val authored = _captureFilter.value
        engine.updateCaptureFilter(
            allowlistEnabled = captureFilterEnabled && authored.allowlist_enabled,
            allowPatterns = authored.allow_patterns,
            blocklistEnabled = captureFilterEnabled && authored.blocklist_enabled,
            blockPatterns = authored.block_patterns,
        )
    }

    private fun pushRegisteredMapLocalRules() {
        engine.updateRules(
            if (mapLocalEnabled) {
                _mapLocalRules.value.map(HostMapLocalRule::toProtocolRule)
            } else {
                emptyList()
            },
        )
    }

    private fun pushRegisteredBreakpointRules() {
        engine.updateBreakpointRules(
            if (breakpointsEnabled) {
                _breakpointRules.value.map(HostBreakpointRule::toProtocolRule)
            } else {
                emptyList()
            },
        )
    }

    private fun pushRegisteredScripts() {
        engine.updateScriptRules(
            if (scriptsEnabled) {
                _scripts.value.map(HostScript::toProtocolRule)
            } else {
                emptyList()
            },
        )
    }

    private fun validateScriptDefinition(script: HostScript) {
        require(script.id.isNotBlank()) { "Script id must not be blank" }
        require(script.urlPattern.isNotBlank()) { "Script URL pattern must not be blank" }
        require(script.onRequest || script.onResponse) { "Script must define onRequest, onResponse, or both" }
    }

    private fun ScriptTransformRequest.identityResult() = ScriptTransformResult(
        correlation_id = correlation_id,
        request = request,
        response = response,
    )

    companion object {
        /**
         * Construct a host around a fresh engine and start listening. Pairing defaults to an
         * in-memory store so CI never needs Keychain; pass a durable [PairingKeyStore] when trust
         * must survive process restart.
         */
        fun start(
            port: Int = WailoEngine.DEFAULT_PORT,
            maxRetained: Int = WailoEngine.DEFAULT_MAX_RETAINED,
            requirePairing: Boolean = false,
            pairingKeyStore: PairingKeyStore = InMemoryPairingKeyStore(),
            bodyStore: BodyStore = InMemoryBodyStore(),
            scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        ): HeadlessHost {
            val engine = WailoEngine(
                port = port,
                maxRetained = maxRetained,
                requirePairing = requirePairing,
                pairings = PairingManager(pairingKeyStore),
                bodyStore = bodyStore,
            )
            val host = HeadlessHost(engine, scope)
            if (!host.start()) {
                host.stop()
                error("Could not bind Wailo capture port $port")
            }
            return host
        }

        /** Wrap an existing engine (e.g. tests that already own one) without calling [WailoEngine.start]. */
        fun wrap(
            engine: WailoEngine,
            scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        ): HeadlessHost = HeadlessHost(engine, scope)
    }
}
