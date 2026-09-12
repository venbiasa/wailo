package com.venbiasa.wailo.daemon

import com.venbiasa.wailo.host.ScriptRuntimeIssueCode
import com.venbiasa.wailo.protocol.Header
import com.venbiasa.wailo.protocol.HttpRequest
import com.venbiasa.wailo.protocol.HttpResponse
import com.venbiasa.wailo.protocol.ScriptPhase
import com.venbiasa.wailo.protocol.ScriptTransformRequest
import com.venbiasa.wailo.protocol.ScriptTransformResult
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.LinkedHashMap
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Engine
import org.graalvm.polyglot.EnvironmentAccess
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.PolyglotAccess
import org.graalvm.polyglot.PolyglotException
import org.graalvm.polyglot.ResourceLimits
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.io.IOAccess
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * The isolated Script worker entry point. Stdout is the framed protocol only; guest output is discarded.
 */
fun main() {
    val input = DataInputStream(System.`in`.buffered())
    val output = DataOutputStream(System.out.buffered())
    ScriptWorkerRuntime().use { runtime ->
        while (true) {
            val request = runCatching { ScriptWorkerWire.readRequest(input) }.getOrNull() ?: return
            val response = runCatching { runtime.handle(request) }
                .getOrElse { ScriptWorkerResponse(ok = false) }
            runCatching { ScriptWorkerWire.writeResponse(output, response) }.getOrElse { return }
        }
    }
}

private class ScriptWorkerRuntime : AutoCloseable {
    private val engine = Engine.newBuilder()
        .option("engine.WarnInterpreterOnly", "false")
        .build()

    private val sources = object : LinkedHashMap<String, Source>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Source>?): Boolean =
            size > MAX_CACHED_SOURCES
    }

    fun handle(request: ScriptWorkerRequest): ScriptWorkerResponse = when (request.kind) {
        "ping" -> ScriptWorkerResponse(ok = true)
        "validate" -> validate(requireNotNull(request.source))
        "execute" -> execute(request)
        else -> ScriptWorkerResponse(ok = false)
    }

    private fun validate(source: String): ScriptWorkerResponse {
        if (source.toByteArray().size > MAX_SOURCE_BYTES) return ScriptWorkerResponse(ok = false)
        val validation = runCatching { validateWithContext(source) }.getOrNull()
            ?: return ScriptWorkerResponse(ok = false)
        if (!validation.onRequest && !validation.onResponse) return ScriptWorkerResponse(ok = false)
        return ScriptWorkerResponse(
            ok = true,
            validation = validation,
        )
    }

    private fun execute(request: ScriptWorkerRequest): ScriptWorkerResponse {
        val original = requireNotNull(request.transformBase64)
            .decodeBase64()
            .let(ScriptTransformRequest.ADAPTER::decode)
        var currentRequest = requireNotNull(original.request)
        var currentResponse = original.response
        var delayMillis = 0
        val outcomes = mutableListOf<ScriptWorkerOutcome>()

        for (script in request.scripts) {
            val attempt = try {
                executeOne(script.source, original, currentRequest, currentResponse, delayMillis)
            } catch (failure: PolyglotException) {
                val code = if (failure.isResourceExhausted) {
                    ScriptRuntimeIssueCode.STATEMENT_LIMIT
                } else {
                    ScriptRuntimeIssueCode.EXECUTION_ERROR
                }
                outcomes += ScriptWorkerOutcome(script.id, code.name)
                continue
            } catch (failure: OutputProblem) {
                outcomes += ScriptWorkerOutcome(script.id, failure.code.name)
                continue
            } catch (_: Throwable) {
                outcomes += ScriptWorkerOutcome(script.id, ScriptRuntimeIssueCode.EXECUTION_ERROR.name)
                continue
            }
            currentRequest = attempt.request
            currentResponse = attempt.response
            delayMillis = attempt.delayMillis
            outcomes += ScriptWorkerOutcome(script.id)
        }

        val result = ScriptTransformResult(
            correlation_id = original.correlation_id,
            request = currentRequest,
            response = currentResponse,
            request_body_replaced = original.request?.body != currentRequest.body,
            response_body_replaced = original.response?.body != currentResponse?.body,
            delay_ms = delayMillis,
        )
        return ScriptWorkerResponse(
            ok = true,
            resultBase64 = result.encode().encodeBase64(),
            outcomes = outcomes,
        )
    }

    private fun executeOne(
        source: String,
        original: ScriptTransformRequest,
        request: HttpRequest,
        response: HttpResponse?,
        delayMillis: Int,
    ): Attempt {
        if (source.toByteArray().size > MAX_SOURCE_BYTES) {
            throw OutputProblem(ScriptRuntimeIssueCode.OUTPUT_TOO_LARGE)
        }
        newContext().use { context ->
            val module = context.eval(sourceFor(source))
            val hook = when (original.phase) {
                ScriptPhase.SCRIPT_PHASE_REQUEST -> module.getMember("onRequest").asBoolean()
                ScriptPhase.SCRIPT_PHASE_RESPONSE -> module.getMember("onResponse").asBoolean()
                else -> false
            }
            if (!hook) return Attempt(request, response, delayMillis)
            val input = guestInput(original, request, response, delayMillis)
            val output = module.getMember("run")
                .execute(original.phase.name, input.toString())
                .asString()
                .let(DaemonJson::parseToJsonElement)
                .jsonObject
            when (output.string("kind")) {
                "invalid_return" -> throw OutputProblem(ScriptRuntimeIssueCode.INVALID_RETURN)
                "invalid_body" -> throw OutputProblem(ScriptRuntimeIssueCode.INVALID_BODY)
                "ok" -> Unit
                else -> throw OutputProblem(ScriptRuntimeIssueCode.INVALID_RETURN)
            }
            return when (original.phase) {
                ScriptPhase.SCRIPT_PHASE_REQUEST -> Attempt(
                    request = output.getValue("request").jsonObject.toRequest(
                        request,
                        bodyState(request, original.request_body_replayable),
                    ),
                    response = response,
                    delayMillis = delayMillis,
                )
                ScriptPhase.SCRIPT_PHASE_RESPONSE -> Attempt(
                    request = request,
                    response = output.getValue("response").jsonObject.toResponse(
                        requireNotNull(response),
                        bodyState(response),
                    ),
                    delayMillis = output.getValue("response").jsonObject.int("delayMs")
                        .also { if (it !in 0..MAX_DELAY_MS) throw OutputProblem(ScriptRuntimeIssueCode.INVALID_DELAY) },
                )
                else -> throw OutputProblem(ScriptRuntimeIssueCode.INVALID_RETURN)
            }
        }
    }

    private fun sourceFor(source: String): Source = synchronized(sources) {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray())
            .joinToString("") { "%02x".format(it) }
        sources.getOrPut(digest) {
            Source.newBuilder("js", moduleSource(source), "script-$digest.js")
                .cached(true)
                .build()
        }
    }

    private fun newContext(): Context = Context.newBuilder("js")
        .engine(engine)
        .allowHostAccess(HostAccess.NONE)
        .allowHostClassLookup { false }
        .allowPolyglotAccess(PolyglotAccess.NONE)
        .allowEnvironmentAccess(EnvironmentAccess.NONE)
        .allowIO(IOAccess.NONE)
        .allowCreateThread(false)
        .allowNativeAccess(false)
        .resourceLimits(
            ResourceLimits.newBuilder()
                .statementLimit(MAX_STATEMENTS) { true }
                .build(),
        )
        .out(OutputStream.nullOutputStream())
        .err(OutputStream.nullOutputStream())
        .build()

    override fun close() {
        engine.close(true)
    }

    private fun validateWithContext(source: String): ScriptWorkerValidation = newContext().use { context ->
        val module = context.eval(sourceFor(source))
        ScriptWorkerValidation(
            onRequest = module.getMember("onRequest").asBoolean(),
            onResponse = module.getMember("onResponse").asBoolean(),
        ).also {
            if (module.getMember("asyncHook").asBoolean()) throw IllegalArgumentException()
        }
    }

    private data class Attempt(
        val request: HttpRequest,
        val response: HttpResponse?,
        val delayMillis: Int,
    )

    private companion object {
        const val MAX_CACHED_SOURCES = 128
        const val MAX_SOURCE_BYTES = 64 * 1024
        const val MAX_DELAY_MS = 60_000
        const val MAX_STATEMENTS = 1_000_000L
    }
}

private class OutputProblem(val code: ScriptRuntimeIssueCode) : RuntimeException(null, null, false, false)

private data class BodyState(
    val type: String,
    val bytes: ByteArray,
    val json: JsonElement? = null,
    val text: String? = null,
) {
    fun equivalent(other: BodyState): Boolean =
        type == other.type && when (type) {
            "json" -> json == other.json
            else -> bytes.contentEquals(other.bytes)
        }
}

private fun bodyState(request: HttpRequest, replayable: Boolean): BodyState =
    classifyBody(request.headers, request.body, request.body_size, request.body_truncated || !replayable)

private fun bodyState(response: HttpResponse): BodyState =
    classifyBody(response.headers, response.body, response.body_size, response.body_truncated)

private fun classifyBody(
    headers: List<Header>,
    body: ByteString,
    declaredSize: Long,
    unavailable: Boolean,
): BodyState {
    if (unavailable || declaredSize > ScriptLimits.MAX_BODY_BYTES || body.size > ScriptLimits.MAX_BODY_BYTES) {
        return BodyState("unavailable", ByteArray(0))
    }
    val bytes = body.toByteArray()
    val contentType = headers.firstOrNull { it.name.equals("Content-Type", true) }
        ?.value_
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase()
        .orEmpty()
    val text = strictUtf8(bytes)
    val wantsJson = contentType == "application/json" || contentType.endsWith("+json")
    val sniffsJson = text?.trimStart()?.firstOrNull() in setOf('{', '[')
    if (text != null && (wantsJson || sniffsJson)) {
        runCatching { DaemonJson.parseToJsonElement(text) }.getOrNull()?.let {
            return BodyState("json", bytes, json = it)
        }
    }
    if (text != null && !contentType.isKnownBinary() && !bytes.lookBinary()) {
        return BodyState("text", bytes, text = text)
    }
    return BodyState("binary", bytes)
}

private fun strictUtf8(bytes: ByteArray): String? = runCatching {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
}.getOrNull()

private fun String.isKnownBinary(): Boolean =
    startsWith("image/") || startsWith("audio/") || startsWith("video/") ||
        this in setOf(
            "application/octet-stream",
            "application/pdf",
            "application/zip",
            "application/gzip",
            "application/x-gzip",
        )

private fun ByteArray.lookBinary(): Boolean = any { byte ->
    val value = byte.toInt() and 0xff
    value == 0 || (value < 0x09) || (value in 0x0e..0x1f)
}

private fun guestInput(
    original: ScriptTransformRequest,
    request: HttpRequest,
    response: HttpResponse?,
    delayMillis: Int,
) = buildJsonObject {
    put("request", request.toGuest(bodyState(request, original.request_body_replayable)))
    response?.let { put("response", it.toGuest(bodyState(it), delayMillis)) }
}

private fun HttpRequest.toGuest(body: BodyState) = buildJsonObject {
    put("method", JsonPrimitive(method))
    put("url", JsonPrimitive(url))
    put("headers", headers.toGuest())
    putBody(body)
}

private fun HttpResponse.toGuest(body: BodyState, delayMillis: Int) = buildJsonObject {
    put("statusCode", JsonPrimitive(code))
    put("headers", headers.toGuest())
    put("delayMs", JsonPrimitive(delayMillis))
    putBody(body)
}

private fun List<Header>.toGuest() = buildJsonArray {
    forEach { header ->
        add(buildJsonArray {
            add(JsonPrimitive(header.name))
            add(JsonPrimitive(header.value_))
        })
    }
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putBody(body: BodyState) {
    put("bodyType", JsonPrimitive(body.type))
    when (body.type) {
        "json" -> put("bodyJson", body.json ?: JsonNull)
        "text" -> put("bodyText", JsonPrimitive(body.text.orEmpty()))
        "binary" -> put("bodyBase64", JsonPrimitive(body.bytes.encodeBase64()))
    }
}

private fun JsonObject.toRequest(original: HttpRequest, initialBody: BodyState): HttpRequest {
    val method = string("method")
    if (!HTTP_TOKEN.matches(method)) throw OutputProblem(ScriptRuntimeIssueCode.INVALID_METHOD)
    val url = string("url")
    val uri = runCatching { URI(url) }.getOrNull()
    if (uri == null || !uri.isAbsolute || uri.host.isNullOrBlank() || uri.scheme.lowercase() !in setOf("http", "https")) {
        throw OutputProblem(ScriptRuntimeIssueCode.INVALID_URL)
    }
    val headers = headers()
    val outputBody = outputBody()
    if ((initialBody.type == "unavailable") != (outputBody.type == "unavailable")) {
        throw OutputProblem(ScriptRuntimeIssueCode.INVALID_BODY)
    }
    val changed = !initialBody.equivalent(outputBody)
    val bytes = if (changed) outputBody.bytes.toByteString() else original.body
    return original.copy(
        method = method,
        url = url,
        headers = headers,
        body = bytes,
        body_size = if (changed) bytes.size.toLong() else original.body_size,
        body_truncated = if (changed) false else original.body_truncated,
    )
}

private fun JsonObject.toResponse(original: HttpResponse, initialBody: BodyState): HttpResponse {
    val status = int("statusCode")
    if (status !in 100..599) throw OutputProblem(ScriptRuntimeIssueCode.INVALID_STATUS)
    val headers = headers()
    val outputBody = outputBody()
    if ((initialBody.type == "unavailable") != (outputBody.type == "unavailable")) {
        throw OutputProblem(ScriptRuntimeIssueCode.INVALID_BODY)
    }
    val changed = !initialBody.equivalent(outputBody)
    val bytes = if (changed) outputBody.bytes.toByteString() else original.body
    return original.copy(
        code = status,
        message = original.message.takeIf { status == original.code }.orEmpty(),
        headers = headers,
        body = bytes,
        body_size = if (changed) bytes.size.toLong() else original.body_size,
        body_truncated = if (changed) false else original.body_truncated,
    )
}

private fun JsonObject.headers(): List<Header> = getValue("headers").jsonArray.map { pairValue ->
    val pair = pairValue.jsonArray
    if (pair.size != 2) throw OutputProblem(ScriptRuntimeIssueCode.INVALID_RETURN)
    val name = pair[0].jsonPrimitive.content
    val value = pair[1].jsonPrimitive.content
    if (!HTTP_TOKEN.matches(name)) throw OutputProblem(ScriptRuntimeIssueCode.INVALID_HEADER_NAME)
    if (value.any { it == '\r' || it == '\n' || it == '\u0000' }) {
        throw OutputProblem(ScriptRuntimeIssueCode.INVALID_HEADER_VALUE)
    }
    Header(name = name, value_ = value)
}

private fun JsonObject.outputBody(): BodyState {
    val type = string("bodyType")
    val state = when (type) {
        "unavailable" -> BodyState(type, ByteArray(0))
        "json" -> {
            val json = this["bodyJson"] ?: throw OutputProblem(ScriptRuntimeIssueCode.INVALID_BODY)
            BodyState(type, DaemonJson.encodeToString(JsonElement.serializer(), json).toByteArray(), json = json)
        }
        "text" -> {
            val text = string("bodyText")
            BodyState(type, text.toByteArray(), text = text)
        }
        "binary" -> {
            val bytes = runCatching { string("bodyBase64").decodeBase64() }
                .getOrElse { throw OutputProblem(ScriptRuntimeIssueCode.INVALID_BODY) }
            BodyState(type, bytes)
        }
        else -> throw OutputProblem(ScriptRuntimeIssueCode.INVALID_BODY)
    }
    if (state.bytes.size > ScriptLimits.MAX_BODY_BYTES) {
        throw OutputProblem(ScriptRuntimeIssueCode.OUTPUT_TOO_LARGE)
    }
    return state
}

private fun JsonObject.string(name: String): String =
    this[name]?.jsonPrimitive?.contentOrNull ?: throw OutputProblem(ScriptRuntimeIssueCode.INVALID_RETURN)

private fun JsonObject.int(name: String): Int =
    this[name]?.jsonPrimitive?.intOrNull ?: throw OutputProblem(ScriptRuntimeIssueCode.INVALID_RETURN)

private object ScriptLimits {
    const val MAX_BODY_BYTES = 8 * 1024 * 1024
}

private val HTTP_TOKEN = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")

private fun moduleSource(source: String): String = """
    (() => {
      "use strict";
      const freeze = Object.freeze;
      class WailoHeaders {
        #pairs;
        #mutable;
        constructor(pairs, mutable) {
          this.#pairs = pairs.map(pair => [String(pair[0]), String(pair[1])]);
          this.#mutable = mutable;
        }
        get(name) {
          const key = String(name).toLowerCase();
          const found = this.#pairs.find(pair => pair[0].toLowerCase() === key);
          return found === undefined ? null : found[1];
        }
        getAll(name) {
          const key = String(name).toLowerCase();
          return this.#pairs.filter(pair => pair[0].toLowerCase() === key).map(pair => pair[1]);
        }
        set(name, value) {
          this._write();
          this.delete(name);
          this.#pairs.push([String(name), String(value)]);
        }
        append(name, value) {
          this._write();
          this.#pairs.push([String(name), String(value)]);
        }
        delete(name) {
          this._write();
          const key = String(name).toLowerCase();
          this.#pairs = this.#pairs.filter(pair => pair[0].toLowerCase() !== key);
        }
        _write() {
          if (!this.#mutable) throw new TypeError("Read-only request");
        }
        pairs() {
          return this.#pairs.map(pair => [pair[0], pair[1]]);
        }
      }
      freeze(WailoHeaders.prototype);
      freeze(WailoHeaders);

      const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
      function decodeBase64(value) {
        const clean = value.replace(/=+$/, "");
        const bytes = [];
        let buffer = 0;
        let bits = 0;
        for (const char of clean) {
          const index = alphabet.indexOf(char);
          if (index < 0) throw new TypeError("Invalid binary body");
          buffer = (buffer << 6) | index;
          bits += 6;
          if (bits >= 8) {
            bits -= 8;
            bytes.push((buffer >> bits) & 255);
          }
        }
        return new Uint8Array(bytes);
      }
      function encodeBase64(bytes) {
        let result = "";
        for (let index = 0; index < bytes.length; index += 3) {
          const a = bytes[index];
          const b = index + 1 < bytes.length ? bytes[index + 1] : 0;
          const c = index + 2 < bytes.length ? bytes[index + 2] : 0;
          const packed = (a << 16) | (b << 8) | c;
          result += alphabet[(packed >> 18) & 63];
          result += alphabet[(packed >> 12) & 63];
          result += index + 1 < bytes.length ? alphabet[(packed >> 6) & 63] : "=";
          result += index + 2 < bytes.length ? alphabet[packed & 63] : "=";
        }
        return result;
      }
      function body(raw) {
        switch (raw.bodyType) {
          case "json": return JSON.parse(JSON.stringify(raw.bodyJson));
          case "text": return raw.bodyText;
          case "binary": return decodeBase64(raw.bodyBase64);
          case "unavailable": return undefined;
          default: throw new TypeError("Invalid body type");
        }
      }
      function message(raw, mutable, response) {
        const value = {
          headers: new WailoHeaders(raw.headers, mutable),
          bodyType: raw.bodyType,
          body: body(raw),
        };
        freeze(value.headers);
        if (response) {
          value.statusCode = raw.statusCode;
          value.delayMs = raw.delayMs;
        } else {
          value.method = raw.method;
          value.url = raw.url;
        }
        if (!mutable) {
          if (value.body instanceof Uint8Array) value.body = readOnlyBytes(value.body);
          deepFreeze(value.body);
          for (const key of ["method", "url", "bodyType", "body"]) {
            if (key in value) Object.defineProperty(value, key, { writable: false });
          }
          freeze(value);
        }
        return value;
      }
      function readOnlyBytes(bytes) {
        const mutators = new Set(["copyWithin", "fill", "reverse", "set", "sort"]);
        let proxy;
        proxy = new Proxy(bytes, {
          get(target, property) {
            if (property === "buffer") {
              return target.buffer.slice(target.byteOffset, target.byteOffset + target.byteLength);
            }
            if (property === "valueOf") return () => proxy;
            if (property === "subarray") {
              return (start, end) => readOnlyBytes(target.slice(start, end));
            }
            if (mutators.has(property)) {
              return () => { throw new TypeError("Read-only request"); };
            }
            const member = Reflect.get(target, property, target);
            return typeof member === "function" ? member.bind(target) : member;
          },
          set() {
            throw new TypeError("Read-only request");
          },
          defineProperty() {
            throw new TypeError("Read-only request");
          },
          deleteProperty() {
            throw new TypeError("Read-only request");
          },
        });
        return proxy;
      }
      function deepFreeze(value) {
        if (value === null || typeof value !== "object" || value instanceof Uint8Array) return value;
        for (const child of Object.values(value)) deepFreeze(child);
        return freeze(value);
      }
      function encodedBody(value) {
        switch (value.bodyType) {
          case "json": return { bodyType: "json", bodyJson: value.body };
          case "text":
            if (typeof value.body !== "string") return null;
            return { bodyType: "text", bodyText: value.body };
          case "binary":
            if (!(value.body instanceof Uint8Array)) return null;
            return { bodyType: "binary", bodyBase64: encodeBase64(value.body) };
          case "unavailable":
            if (value.body !== undefined) return null;
            return { bodyType: "unavailable" };
          default: return null;
        }
      }
      function encodedMessage(value, response) {
        if (value === null || typeof value !== "object" || !(value.headers instanceof WailoHeaders)) return null;
        const encoded = encodedBody(value);
        if (encoded === null) return null;
        encoded.headers = value.headers.pairs();
        if (response) {
          encoded.statusCode = value.statusCode;
          encoded.delayMs = value.delayMs;
        } else {
          encoded.method = value.method;
          encoded.url = value.url;
        }
        return encoded;
      }

      const userHooks = (() => {
        $source
        return [
          typeof onRequest === "function" ? onRequest : null,
          typeof onResponse === "function" ? onResponse : null,
        ];
      })();
      const requestHook = userHooks[0];
      const responseHook = userHooks[1];
      const asyncHook =
        (requestHook !== null && requestHook.constructor.name === "AsyncFunction") ||
        (responseHook !== null && responseHook.constructor.name === "AsyncFunction");
      return freeze({
        onRequest: requestHook !== null,
        onResponse: responseHook !== null,
        asyncHook,
        run(phase, inputJson) {
          const raw = JSON.parse(inputJson);
          const requestPhase = phase === "SCRIPT_PHASE_REQUEST";
          const request = message(raw.request, requestPhase, false);
          const response = requestPhase ? undefined : message(raw.response, true, true);
          const hook = requestPhase ? requestHook : responseHook;
          let returned;
          try {
            returned = hook(requestPhase ? { request } : { request, response });
          } catch (failure) {
            throw failure;
          }
          if (returned !== null && typeof returned === "object" && typeof returned.then === "function") {
            return JSON.stringify({ kind: "invalid_return" });
          }
          const encoded = encodedMessage(returned, !requestPhase);
          if (encoded === null) return JSON.stringify({ kind: "invalid_body" });
          try {
            return JSON.stringify({
              kind: "ok",
              [requestPhase ? "request" : "response"]: encoded,
            });
          } catch (failure) {
            return JSON.stringify({ kind: "invalid_return" });
          }
        },
      });
    })()
""".trimIndent()
