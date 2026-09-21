package io.legado.app.model.localBook.epubcore.web

import java.net.URI
import java.net.URLDecoder

/**
 * WebView can normalize percent encoding or omit the synthetic load token from
 * callbacks emitted for [android.webkit.WebView.loadDataWithBaseURL].
 */
internal object EpubWebMainDocumentUrlMatcher {

    fun matches(expectedUrl: String?, callbackUrl: String?): Boolean {
        val expected = expectedUrl?.let(::parse) ?: return false
        val actual = callbackUrl?.let(::parse) ?: return false
        if (!expected.scheme.equals(actual.scheme, ignoreCase = true) ||
            !expected.host.equals(actual.host, ignoreCase = true) ||
            expected.port != actual.port ||
            expected.path != actual.path
        ) {
            return false
        }

        val expectedLoadToken = expected.queryParameter(LoadToken)
        val actualLoadToken = actual.queryParameter(LoadToken)
        return when {
            expectedLoadToken == null -> actual.rawQuery.isNullOrBlank()
            actualLoadToken == null -> actual.rawQuery.isNullOrBlank()
            else -> actualLoadToken == expectedLoadToken
        }
    }

    private fun parse(value: String): ParsedUrl? {
        val uri = runCatching { URI(value).normalize() }.getOrNull() ?: return null
        val scheme = uri.scheme ?: return null
        val host = uri.host ?: return null
        return ParsedUrl(
            scheme = scheme,
            host = host,
            port = normalizedPort(scheme, uri.port),
            path = uri.path.orEmpty().ifBlank { "/" },
            rawQuery = uri.rawQuery
        )
    }

    private fun normalizedPort(scheme: String, port: Int): Int {
        if (port >= 0) return port
        return when (scheme.lowercase()) {
            "https" -> 443
            "http" -> 80
            else -> -1
        }
    }

    private fun ParsedUrl.queryParameter(name: String): String? {
        return rawQuery
            ?.split('&')
            ?.asSequence()
            ?.map { component -> component.substringBefore('=') to component.substringAfter('=', "") }
            ?.firstOrNull { (key, _) -> decode(key) == name }
            ?.second
            ?.let(::decode)
    }

    private fun decode(value: String): String {
        return runCatching { URLDecoder.decode(value, Charsets.UTF_8.name()) }.getOrDefault(value)
    }

    private data class ParsedUrl(
        val scheme: String,
        val host: String,
        val port: Int,
        val path: String,
        val rawQuery: String?
    )

    private const val LoadToken = "__legado_load__"
}
