package com.venbiasa.wailo.daemon

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
internal data class ScriptWorkerScript(
    val id: String,
    val source: String,
)

@Serializable
internal data class ScriptWorkerRequest(
    val kind: String,
    val source: String? = null,
    val scripts: List<ScriptWorkerScript> = emptyList(),
    val transformBase64: String? = null,
)

@Serializable
internal data class ScriptWorkerValidation(
    val onRequest: Boolean,
    val onResponse: Boolean,
)

@Serializable
internal data class ScriptWorkerOutcome(
    val ruleId: String,
    val issue: String? = null,
)

@Serializable
internal data class ScriptWorkerResponse(
    val ok: Boolean,
    val validation: ScriptWorkerValidation? = null,
    val resultBase64: String? = null,
    val outcomes: List<ScriptWorkerOutcome> = emptyList(),
)

internal object ScriptWorkerWire {
    fun readRequest(input: DataInputStream): ScriptWorkerRequest? =
        readFrame(input)?.let { DaemonJson.decodeFromString(ScriptWorkerRequest.serializer(), it) }

    fun writeRequest(output: DataOutputStream, request: ScriptWorkerRequest) =
        writeFrame(output, DaemonJson.encodeToString(request))

    fun readResponse(input: DataInputStream): ScriptWorkerResponse? =
        readFrame(input)?.let { DaemonJson.decodeFromString(ScriptWorkerResponse.serializer(), it) }

    fun writeResponse(output: DataOutputStream, response: ScriptWorkerResponse) =
        writeFrame(output, DaemonJson.encodeToString(response))

    private fun readFrame(input: DataInputStream): String? {
        val length = try {
            input.readInt()
        } catch (_: EOFException) {
            return null
        }
        require(length in 1..MAX_FRAME_BYTES) { "Invalid Script worker frame" }
        return input.readNBytes(length).also {
            require(it.size == length) { "Incomplete Script worker frame" }
        }.toString(Charsets.UTF_8)
    }

    private fun writeFrame(output: DataOutputStream, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..MAX_FRAME_BYTES) { "Script worker frame is too large" }
        output.writeInt(bytes.size)
        output.write(bytes)
        output.flush()
    }

    private const val MAX_FRAME_BYTES = 32 * 1024 * 1024
}
