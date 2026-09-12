package com.venbiasa.wailo.host

import com.venbiasa.wailo.protocol.ScriptPhase
import com.venbiasa.wailo.protocol.ScriptRule
import com.venbiasa.wailo.protocol.ScriptTransformRequest
import com.venbiasa.wailo.protocol.ScriptTransformResult
import kotlinx.coroutines.flow.StateFlow

data class HostScript(
    val id: String,
    val name: String = "",
    val enabled: Boolean = true,
    val urlPattern: String,
    val method: String = "",
    val source: String,
    val onRequest: Boolean,
    val onResponse: Boolean,
) {
    internal fun toProtocolRule() = ScriptRule(
        id = id,
        enabled = enabled,
        url_pattern = urlPattern,
        methods = listOfNotNull(method.takeIf { it.isNotBlank() }),
        on_request = onRequest,
        on_response = onResponse,
    )
}

data class ScriptValidation(
    val onRequest: Boolean,
    val onResponse: Boolean,
)

enum class ScriptRuntimeIssueCode {
    EXECUTION_ERROR,
    STATEMENT_LIMIT,
    INVALID_RETURN,
    INVALID_METHOD,
    INVALID_URL,
    INVALID_STATUS,
    INVALID_HEADER_NAME,
    INVALID_HEADER_VALUE,
    INVALID_BODY,
    INVALID_DELAY,
    OUTPUT_TOO_LARGE,
    PIPELINE_TIMEOUT,
    WORKER_FAILURE,
}

data class ScriptRuntimeIssue(
    val ruleId: String,
    val phase: ScriptPhase,
    val timestampEpochMs: Long,
    val code: ScriptRuntimeIssueCode,
)

interface ScriptExecutor : AutoCloseable {
    val issues: StateFlow<List<ScriptRuntimeIssue>>

    suspend fun validate(source: String): ScriptValidation

    suspend fun execute(
        scripts: List<HostScript>,
        request: ScriptTransformRequest,
    ): ScriptTransformResult

    fun clearIssue(ruleId: String)

    override fun close()
}
