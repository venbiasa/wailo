package com.venbiasa.wailo.daemon

import com.venbiasa.wailo.host.HostScript
import com.venbiasa.wailo.host.ScriptExecutor
import com.venbiasa.wailo.host.ScriptRuntimeIssue
import com.venbiasa.wailo.host.ScriptRuntimeIssueCode
import com.venbiasa.wailo.host.ScriptValidation
import com.venbiasa.wailo.protocol.ScriptPhase
import com.venbiasa.wailo.protocol.ScriptTransformRequest
import com.venbiasa.wailo.protocol.ScriptTransformResult
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

internal class DaemonScriptExecutor : ScriptExecutor {
    private val issueLock = Any()
    private val issuesByRule = linkedMapOf<String, ScriptRuntimeIssue>()
    private val _issues = MutableStateFlow<List<ScriptRuntimeIssue>>(emptyList())
    override val issues: StateFlow<List<ScriptRuntimeIssue>> = _issues.asStateFlow()

    private val running = AtomicBoolean(true)
    private val queue = ArrayBlockingQueue<Work>(MAX_QUEUE)
    private val rpcExecutor = Executors.newThreadPerTaskExecutor(
        Thread.ofVirtual().name("wailo-script-rpc-", 0).factory(),
    )

    @Volatile
    private var workerProcess: WorkerProcess? = null

    private val dispatcher = Thread.ofPlatform()
        .name("wailo-script-dispatch")
        .daemon(true)
        .start(::dispatch)

    override suspend fun validate(source: String): ScriptValidation {
        require(source.toByteArray().size <= MAX_SOURCE_BYTES) { "Script source exceeds 64 KiB" }
        val response = submit(
            ScriptWorkerRequest(kind = "validate", source = source),
            VALIDATION_TIMEOUT_MS,
        ) ?: throw IllegalStateException("Script worker queue is full")
        val validation = response.validation
        require(response.ok && validation != null) {
            "Script must contain a synchronous onRequest or onResponse function"
        }
        return ScriptValidation(validation.onRequest, validation.onResponse)
    }

    override suspend fun execute(
        scripts: List<HostScript>,
        request: ScriptTransformRequest,
    ): ScriptTransformResult {
        val identity = request.identityResult()
        if (scripts.isEmpty()) return identity
        val workerRequest = ScriptWorkerRequest(
            kind = "execute",
            scripts = scripts.map { ScriptWorkerScript(it.id, it.source) },
            transformBase64 = request.encode().encodeBase64(),
        )
        val response = try {
            submit(workerRequest, PHASE_TIMEOUT_MS)
        } catch (failure: ScriptRpcFailure) {
            recordFailure(scripts, request.phase, failure.code)
            return identity
        } catch (_: Throwable) {
            recordFailure(scripts, request.phase, ScriptRuntimeIssueCode.WORKER_FAILURE)
            return identity
        }
        if (response == null || !response.ok || response.resultBase64 == null) {
            recordFailure(scripts, request.phase, ScriptRuntimeIssueCode.PIPELINE_TIMEOUT)
            return identity
        }
        recordOutcomes(response.outcomes, request.phase)
        return runCatching {
            ScriptTransformResult.ADAPTER.decode(response.resultBase64.decodeBase64())
        }.getOrElse {
            recordFailure(scripts, request.phase, ScriptRuntimeIssueCode.WORKER_FAILURE)
            identity
        }
    }

    override fun clearIssue(ruleId: String) {
        synchronized(issueLock) {
            if (issuesByRule.remove(ruleId) != null) publishIssues()
        }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        dispatcher.interrupt()
        queue.forEach { it.result.completeExceptionally(IOException("Script executor closed")) }
        queue.clear()
        replaceWorker(null)
        rpcExecutor.shutdownNow()
    }

    private suspend fun submit(request: ScriptWorkerRequest, timeoutMs: Long): ScriptWorkerResponse? =
        withContext(Dispatchers.IO) {
            if (!running.get()) return@withContext null
            val work = Work(
                request = request,
                deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs),
            )
            if (!queue.offer(work)) return@withContext null
            try {
                work.result.get(timeoutMs + WAIT_GRACE_MS, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                throw ScriptRpcFailure(ScriptRuntimeIssueCode.PIPELINE_TIMEOUT)
            } catch (failure: java.util.concurrent.ExecutionException) {
                throw (failure.cause ?: failure)
            }
        }

    private fun dispatch() {
        while (running.get()) {
            val work = try {
                queue.take()
            } catch (_: InterruptedException) {
                return
            }
            val remaining = work.deadlineNanos - System.nanoTime()
            if (remaining <= 0) {
                work.result.completeExceptionally(
                    ScriptRpcFailure(ScriptRuntimeIssueCode.PIPELINE_TIMEOUT),
                )
                continue
            }
            try {
                work.result.complete(callWorker(work.request, remaining))
            } catch (failure: Throwable) {
                work.result.completeExceptionally(failure)
                if (failure is ScriptRpcFailure && running.get()) {
                    runCatching {
                        callWorker(
                            ScriptWorkerRequest(kind = "ping"),
                            TimeUnit.MILLISECONDS.toNanos(VALIDATION_TIMEOUT_MS),
                        )
                    }
                }
            }
        }
    }

    private fun callWorker(request: ScriptWorkerRequest, timeoutNanos: Long): ScriptWorkerResponse {
        val process = acquireWorker()
        val call = rpcExecutor.submit<ScriptWorkerResponse> {
            ScriptWorkerWire.writeRequest(process.output, request)
            ScriptWorkerWire.readResponse(process.input) ?: throw IOException("Script worker stopped")
        }
        return try {
            call.get(timeoutNanos, TimeUnit.NANOSECONDS)
        } catch (_: TimeoutException) {
            call.cancel(true)
            replaceWorker(process)
            throw ScriptRpcFailure(ScriptRuntimeIssueCode.PIPELINE_TIMEOUT)
        } catch (failure: Throwable) {
            replaceWorker(process)
            throw ScriptRpcFailure(
                ScriptRuntimeIssueCode.WORKER_FAILURE,
                failure,
            )
        }
    }

    @Synchronized
    private fun acquireWorker(): WorkerProcess {
        workerProcess?.takeIf { it.process.isAlive }?.let { return it }
        workerProcess?.close()
        val started = startWorker()
        if (!running.get()) {
            started.close()
            throw IOException("Script executor closed")
        }
        workerProcess = started
        return started
    }

    private fun startWorker(): WorkerProcess {
        val java = Path.of(
            System.getProperty("java.home"),
            "bin",
            if (System.getProperty("os.name").startsWith("Windows", true)) "java.exe" else "java",
        )
        val builder = ProcessBuilder(
            java.toString(),
            "-Xmx512m",
            "-cp",
            System.getProperty("java.class.path"),
            "com.venbiasa.wailo.daemon.ScriptWorkerMainKt",
        ).redirectError(ProcessBuilder.Redirect.DISCARD)
        builder.environment().clear()
        val process = builder.start()
        return WorkerProcess(
            process,
            DataInputStream(process.inputStream.buffered()),
            DataOutputStream(process.outputStream.buffered()),
        )
    }

    /**
     * Null closes the current process; a non-null argument closes it only if it is still current.
     */
    @Synchronized
    private fun replaceWorker(expected: WorkerProcess?) {
        val current = workerProcess
        if (expected != null && current !== expected) return
        workerProcess = null
        current?.close()
    }

    private fun recordOutcomes(outcomes: List<ScriptWorkerOutcome>, phase: ScriptPhase) {
        val now = System.currentTimeMillis()
        synchronized(issueLock) {
            outcomes.forEach { outcome ->
                val code = outcome.issue?.let {
                    runCatching { ScriptRuntimeIssueCode.valueOf(it) }
                        .getOrDefault(ScriptRuntimeIssueCode.EXECUTION_ERROR)
                }
                if (code == null) {
                    issuesByRule.remove(outcome.ruleId)
                } else {
                    issuesByRule[outcome.ruleId] = ScriptRuntimeIssue(outcome.ruleId, phase, now, code)
                }
            }
            publishIssues()
        }
    }

    private fun recordFailure(
        scripts: List<HostScript>,
        phase: ScriptPhase,
        code: ScriptRuntimeIssueCode,
    ) {
        val now = System.currentTimeMillis()
        synchronized(issueLock) {
            scripts.forEach { issuesByRule[it.id] = ScriptRuntimeIssue(it.id, phase, now, code) }
            publishIssues()
        }
    }

    private fun publishIssues() {
        _issues.value = issuesByRule.values.toList()
    }

    private data class Work(
        val request: ScriptWorkerRequest,
        val deadlineNanos: Long,
        val result: CompletableFuture<ScriptWorkerResponse> = CompletableFuture(),
    )

    private class WorkerProcess(
        val process: Process,
        val input: DataInputStream,
        val output: DataOutputStream,
    ) : AutoCloseable {
        override fun close() {
            runCatching { output.close() }
            runCatching { input.close() }
            process.destroyForcibly()
            runCatching { process.waitFor(1, TimeUnit.SECONDS) }
        }
    }

    private class ScriptRpcFailure(
        val code: ScriptRuntimeIssueCode,
        cause: Throwable? = null,
    ) : RuntimeException(null, cause, false, false)

    private fun ScriptTransformRequest.identityResult() = ScriptTransformResult(
        correlation_id = correlation_id,
        request = request,
        response = response,
    )

    private companion object {
        const val MAX_QUEUE = 32
        const val MAX_SOURCE_BYTES = 64 * 1024
        const val PHASE_TIMEOUT_MS = 1_000L
        const val VALIDATION_TIMEOUT_MS = 5_000L
        const val WAIT_GRACE_MS = 250L
    }
}
