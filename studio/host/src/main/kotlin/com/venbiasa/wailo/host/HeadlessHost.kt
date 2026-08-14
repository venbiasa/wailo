package com.venbiasa.wailo.host

import com.venbiasa.wailo.engine.CapturedExchange
import com.venbiasa.wailo.engine.ConnectedDevice
import com.venbiasa.wailo.engine.MapLocalBodyProvider
import com.venbiasa.wailo.engine.PausedExchange
import com.venbiasa.wailo.engine.WailoEngine
import com.venbiasa.wailo.engine.pairing.PairingManager
import com.venbiasa.wailo.engine.pairing.InMemoryPairingKeyStore
import com.venbiasa.wailo.engine.pairing.PairingKeyStore
import com.venbiasa.wailo.protocol.BreakpointRule
import com.venbiasa.wailo.protocol.HttpRequest
import com.venbiasa.wailo.protocol.HttpResponse
import com.venbiasa.wailo.protocol.MapLocalRule
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
 * UI-free orchestration over [WailoEngine] for CLI / MCP / Appium frontends (ADR-0055). Owns Seed
 * auto-spend, query helpers, and the rule-push surface so those frontends do not reimplement what
 * `desktopApp` already does for the Compose viewer.
 *
 * The desktop app may keep talking to [engine] directly for Compose state; it shares Seed spend via
 * [spendSeedOn]. Headless frontends should use this type as their only entry point.
 */
class HeadlessHost private constructor(
    val engine: WailoEngine,
    private val scope: CoroutineScope,
) {
    val queries = TrafficQueries(engine)

    private val seedMutex = Mutex()
    private val _seedQueue = MutableStateFlow<List<HostSeed>>(emptyList())
    val seedQueue: StateFlow<List<HostSeed>> = _seedQueue.asStateFlow()

    @Volatile
    private var seedsEnabledValue = true

    var seedsEnabled: Boolean
        get() = seedsEnabledValue
        set(value) {
            seedsEnabledValue = value
            if (value) scope.launch { triage(engine.pausedExchanges.value) }
        }

    @Volatile
    private var seedResponseProviderValue: SeedResponseProvider? = null

    var seedResponseProvider: SeedResponseProvider?
        get() = seedResponseProviderValue
        set(value) {
            seedResponseProviderValue = value
            if (value != null) scope.launch { triage(engine.pausedExchanges.value) }
        }

    private val mapLocalMutex = Mutex()
    private val _mapLocalRules = MutableStateFlow<List<HostMapLocalRule>>(emptyList())
    val mapLocalRules: StateFlow<List<HostMapLocalRule>> = _mapLocalRules.asStateFlow()

    @Volatile
    private var mapLocalEnabled = true

    @Volatile
    private var fallbackBodyProvider: MapLocalBodyProvider? = null

    private val breakpointMutex = Mutex()
    private val _breakpointRules = MutableStateFlow<List<HostBreakpointRule>>(emptyList())
    val breakpointRules: StateFlow<List<HostBreakpointRule>> = _breakpointRules.asStateFlow()

    @Volatile
    private var breakpointsEnabled = true

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

        // Same arrival-only triage the desktop runs (ADR-0044): a response-phase hold a queued seed
        // matches is answered here; everything else is left for the caller to resume/abort.
        scope.launch {
            engine.pausedExchanges.collect(::triage)
        }
    }

    fun start(): Boolean = engine.start()

    fun stop() {
        engine.stop()
        scope.cancel()
    }

    suspend fun rebind(port: Int): Boolean = engine.rebind(port)

    fun clear() = engine.clear()

    fun setCapturing(enabled: Boolean) = engine.setCapturing(enabled)

    fun setMaxRetained(n: Int) = engine.setMaxRetained(n)

    fun updateRules(rules: List<MapLocalRule>) = engine.updateRules(rules)

    fun updateCaptureFilter(
        allowlistEnabled: Boolean,
        allowPatterns: List<String>,
        blocklistEnabled: Boolean,
        blockPatterns: List<String>,
    ) = engine.updateCaptureFilter(allowlistEnabled, allowPatterns, blocklistEnabled, blockPatterns)

    fun updateBreakpointRules(rules: List<BreakpointRule>) = engine.updateBreakpointRules(rules)

    suspend fun upsertMapLocalRule(rule: HostMapLocalRule) {
        require(rule.id.isNotBlank()) { "Map Local id must not be blank" }
        require(rule.urlPattern.isNotBlank()) { "Map Local URL pattern must not be blank" }
        require(rule.statusCode in 100..599) { "Map Local status must be between 100 and 599" }
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

    fun listDevices(): List<ConnectedDevice> = engine.connectedDevices.value

    fun listExchanges(): List<CapturedExchange> = engine.exchanges.value

    fun listHolds(): List<PausedExchange> = engine.pausedExchanges.value

    fun resumeHold(
        correlationId: String,
        editedRequest: HttpRequest? = null,
        editedResponse: HttpResponse? = null,
    ) = engine.resumeBreakpoint(correlationId, editedRequest, editedResponse)

    fun abortHold(correlationId: String) = engine.abortBreakpoint(correlationId)

    /** Replace the armed Seed queue (Fill). Does not spend against waiting holds — see [fillSeeds]. */
    fun armSeeds(seeds: List<HostSeed>) {
        _seedQueue.value = seeds
    }

    /**
     * Arm [seeds] (or the current queue when null) and spend against every hold already waiting —
     * the headless form of the desktop Fill button (ADR-0044).
     */
    suspend fun fillSeeds(seeds: List<HostSeed>? = null) {
        val provider = seedResponseProvider ?: return
        seedMutex.withLock {
            if (seeds != null) _seedQueue.value = seeds
            if (!seedsEnabled) {
                _seedQueue.value = emptyList()
                return
            }
            var queue = _seedQueue.value
            engine.pausedExchanges.value.forEach { hold ->
                val spent = spendSeedOn(engine, queue, hold, provider) ?: return@forEach
                queue = spent
                _seedQueue.value = queue
            }
        }
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
            val provider = seedResponseProviderValue ?: return
            if (!seedsEnabledValue) return

            holds.forEach { hold ->
                if (hold.correlationId in triaged) return@forEach
                val spent = try {
                    spendSeedOn(engine, _seedQueue.value, hold, provider)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                // A provider was ready and this hold had one complete attempt. No match / missing body
                // remains visible for an explicit Fill or manual decision rather than retrying forever.
                triaged += hold.correlationId
                if (spent != null) _seedQueue.value = spent
            }
        }
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
            scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        ): HeadlessHost {
            val engine = WailoEngine(
                port = port,
                maxRetained = maxRetained,
                requirePairing = requirePairing,
                pairings = PairingManager(pairingKeyStore),
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
