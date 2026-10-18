package com.damonearl.fantasymultiview.data

import android.util.Log
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import okio.GzipSource
import okio.buffer

/**
 * Logs the full exchange for any request that comes back with an error status, as it went over
 * the wire (installed as a network interceptor, so headers OkHttp adds, like User-Agent, show up,
 * and each redirect hop is logged). Credentials are redacted. View with: adb logcat -s HttpDebug
 */
class FailedRequestLogger : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (response.code >= 400) {
            Log.w(TAG, buildString {
                appendLine("${request.method} ${redact(request.url)} -> HTTP ${response.code} ${response.message}".trimEnd())
                appendLine("request headers:")
                append(format(request.headers))
                appendLine("response headers:")
                append(format(response.headers))
                body(response).takeIf { it.isNotBlank() }?.let { append("body: ").append(it) }
            })
        }
        return response
    }

    private fun body(response: Response): String = runCatching {
        val peek = response.peekBody(BODY_LIMIT)
        // Network interceptors see the body before OkHttp's transparent gunzip.
        if (response.header("Content-Encoding").equals("gzip", ignoreCase = true)) {
            GzipSource(peek.source()).buffer().use { it.readUtf8() }
        } else {
            peek.string()
        }
    }.getOrDefault("(unreadable body)").take(BODY_LIMIT.toInt()).replace(Regex("""\s+"""), " ").trim()

    private fun format(headers: Headers) = buildString {
        headers.forEach { (name, value) ->
            appendLine("  $name: ${if (name.lowercase() in SECRET_HEADERS) "<redacted>" else value}")
        }
    }

    companion object {
        private const val TAG = "HttpDebug"
        private const val BODY_LIMIT = 500L
        private val SECRET_HEADERS = setOf("cookie", "set-cookie", "authorization", "proxy-authorization")
        private val SECRET_PARAMS = setOf("password", "pass", "pwd", "token", "access_token", "auth", "key", "apikey", "api_key", "deviceauth", "username", "user")

        /** The URL with credential-like query parameters blanked. Credentials embedded in the path aren't detected. */
        fun redact(url: HttpUrl): String {
            // Rebuilt in the original order so the logged URL lines up with the real one.
            val builder = url.newBuilder().query(null)
            for (i in 0 until url.querySize) {
                val name = url.queryParameterName(i)
                builder.addQueryParameter(name, if (name.lowercase() in SECRET_PARAMS) "***" else url.queryParameterValue(i))
            }
            return builder.build().toString()
        }
    }
}
