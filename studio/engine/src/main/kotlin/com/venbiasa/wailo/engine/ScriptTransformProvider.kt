package com.venbiasa.wailo.engine

import com.venbiasa.wailo.protocol.ScriptTransformRequest
import com.venbiasa.wailo.protocol.ScriptTransformResult

/**
 * Executes a daemon-owned Script phase without teaching the capture engine about a JavaScript runtime.
 * A missing or failed provider is handled by the engine as an identity transform.
 */
fun interface ScriptTransformProvider {
    suspend fun transform(request: ScriptTransformRequest): ScriptTransformResult
}

internal fun ScriptTransformRequest.identityResult() = ScriptTransformResult(
    correlation_id = correlation_id,
    request = request,
    response = response,
)
