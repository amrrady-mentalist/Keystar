package com.notesapp.offline.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Full diagnostic result of a [InjectApiClient.fetchDebug] call — used only
 * by the "Test connection" button in Magic Settings so a performer can see
 * *why* --value-- isn't resolving (bad URL, wrong field name, timeout,
 * non-2xx, unparsable body) without needing adb/logcat. The silent
 * [InjectApiClient.fetchValue] used by the actual reveal flow stays as
 * simple "String? or null" — this is purely for the debug UI.
 */
data class InjectFetchDebugResult(
    val httpCode: Int? = null,
    val rawBody: String? = null,
    val parsedValue: String? = null,
    val error: String? = null
)

/**
 * The network half of the Inject feature — one plain URL used in both
 * directions:
 * - [fetchValue] GETs it and pulls out the "value" field, e.g. from
 *   `{"count":1322,"value":"Test","receiveCount":463,...}` this returns
 *   "Test". Every other field in that response is whatever the other
 *   "magic app" needs for its own purposes and is ignored here entirely —
 *   parsed as a loose JsonObject rather than a strict data class so
 *   unrelated fields never break this.
 * - [sendValue] POSTs `{"value": "<value>"}` to the same URL — the shape
 *   for outgoing data wasn't specified, so this is the simplest reasonable
 *   default; if the receiving side expects something else (a different
 *   field name, raw text, form fields), this is the one place to change.
 *
 * Deliberately implemented on plain java.net.HttpURLConnection rather than
 * pulling in a new HTTP library dependency — this app has no networking
 * anywhere else, so one small hand-rolled client is a smaller footprint
 * than adding Retrofit/OkHttp/Ktor for two simple calls.
 */
class InjectApiClient {

    private val json = Json { ignoreUnknownKeys = true }

    /** Keys tried, in order, when pulling the value out of the response
     *  object — the receiving "magic app" on the other end isn't always
     *  consistent about casing, so this is deliberately forgiving rather
     *  than requiring an exact lowercase "value". */
    private val valueKeys = listOf("value", "Value", "VALUE", "result", "data")

    private fun extractValue(obj: JsonObject): String? {
        for (key in valueKeys) {
            val el = obj[key] ?: continue
            val prim = el as? JsonPrimitive ?: continue
            val content = prim.contentOrNull ?: continue
            if (content.isNotEmpty()) return content
        }
        return null
    }

    suspend fun fetchValue(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }
            connection.disconnect()
            if (code !in 200..299 || text.isNullOrBlank()) return@runCatching null
            val obj = json.parseToJsonElement(text) as? JsonObject ?: return@runCatching null
            extractValue(obj)
        }.getOrNull()
    }

    /** Same GET as [fetchValue] but returns full diagnostics instead of
     *  collapsing every failure to null — powers the "Test connection"
     *  button in Magic Settings. Never throws. */
    suspend fun fetchDebug(url: String): InjectFetchDebugResult = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext InjectFetchDebugResult(error = "No API URL set.")
        runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }
            connection.disconnect()
            if (code !in 200..299) {
                return@withContext InjectFetchDebugResult(httpCode = code, rawBody = text, error = "Server returned HTTP $code.")
            }
            if (text.isNullOrBlank()) {
                return@withContext InjectFetchDebugResult(httpCode = code, rawBody = text, error = "Response body was empty.")
            }
            val obj = json.parseToJsonElement(text) as? JsonObject
                ?: return@withContext InjectFetchDebugResult(httpCode = code, rawBody = text, error = "Response wasn't a JSON object.")
            val value = extractValue(obj)
            if (value == null) {
                InjectFetchDebugResult(
                    httpCode = code,
                    rawBody = text,
                    error = "No \"value\" field (or value was empty) — the object's keys were: ${obj.keys.joinToString()}."
                )
            } else {
                InjectFetchDebugResult(httpCode = code, rawBody = text, parsedValue = value)
            }
        }.getOrElse { e ->
            InjectFetchDebugResult(error = e.message ?: e.javaClass.simpleName)
        }
    }

    suspend fun sendValue(url: String, value: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            val body = "{\"value\":${jsonQuote(value)}}"
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(body) }
            val code = connection.responseCode
            connection.disconnect()
            code in 200..299
        }.getOrDefault(false)
    }

    /** Minimal JSON string escaping — just enough for arbitrary note text
     *  (quotes, backslashes, newlines) to round-trip safely inside the
     *  hand-built request body above. */
    private fun jsonQuote(raw: String): String {
        val escaped = buildString {
            append('"')
            for (c in raw) {
                when (c) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(c)
                }
            }
            append('"')
        }
        return escaped
    }

    companion object {
        private const val TIMEOUT_MS = 6000
    }
}
