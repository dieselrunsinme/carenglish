package app.bureung.english.ai

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

class GeminiError(val code: Int, message: String) : Exception("HTTP $code: $message")

/**
 * A minimal Gemini API client (REST generateContent). Blocking: call it from a background thread. The key comes
 * from local.properties through BuildConfig and is never committed.
 */
class Gemini(
    private val key: String,
    /** sent so a key restricted to this Android app (package + signing SHA-1) is accepted */
    private val androidPackage: String? = null,
    private val androidCert: String? = null,
) {
    val hasKey: Boolean get() = key.isNotBlank()

    fun generate(model: String, body: JSONObject, readTimeoutMs: Int = 25_000): JSONObject {
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
        val c = url.openConnection() as HttpURLConnection
        // the connection is not closed afterwards: the next call (the voice, the next turn) reuses it and skips
        // the handshake, which a waiting child would hear as silence
        run {
            c.requestMethod = "POST"
            c.connectTimeout = 8_000
            c.readTimeout = readTimeoutMs
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.setRequestProperty("x-goog-api-key", key)
            androidPackage?.let { c.setRequestProperty("X-Android-Package", it) }
            androidCert?.let { c.setRequestProperty("X-Android-Cert", it) }
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw GeminiError(code, errorMessage(text))
            return JSONObject(text)
        }
    }

    /** Opens a connection ahead of the first question, so the first answer does not wait for the handshake. */
    fun warm() {
        if (!hasKey) return
        runCatching {
            val c = URL("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1").openConnection() as HttpURLConnection
            c.connectTimeout = 5_000
            c.readTimeout = 5_000
            c.setRequestProperty("x-goog-api-key", key)
            androidPackage?.let { c.setRequestProperty("X-Android-Package", it) }
            androidCert?.let { c.setRequestProperty("X-Android-Cert", it) }
            (if (c.responseCode in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes() }
        }
    }

    private fun errorMessage(text: String): String =
        runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrDefault(text).take(200)

    companion object {
        /** the parts of the first candidate (thought parts left out) */
        fun parts(response: JSONObject): List<JSONObject> {
            val parts = response.optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts") ?: return emptyList()
            return (0 until parts.length()).mapNotNull { parts.optJSONObject(it) }.filter { !it.optBoolean("thought", false) }
        }
    }
}
