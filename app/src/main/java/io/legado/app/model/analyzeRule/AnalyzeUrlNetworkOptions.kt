package io.legado.app.model.analyzeRule

/**
 * AnalyzeUrl 的网络选项解析辅助函数（与上游保持一致）
 */

private const val MIN_DERIVED_CALL_TIMEOUT_MILLIS = 60_000L
private const val MAX_OKHTTP_TIMEOUT_MILLIS = Int.MAX_VALUE.toLong()

internal fun parseRequestTimeoutMillis(value: Any?): Long? {
    val timeout = when (value) {
        is Byte, is Short, is Int, is Long -> (value as Number).toLong()
        is Float, is Double -> value.toDouble().takeIf {
            it.isFinite() && it % 1.0 == 0.0 && it <= Long.MAX_VALUE.toDouble()
        }?.toLong()
        is Number -> value.toLong()
        is String -> value.trim().toLongOrNull()
        else -> null
    }
    return timeout?.takeIf { it in 1..MAX_OKHTTP_TIMEOUT_MILLIS }
}

internal fun parseBooleanOption(value: Any?): Boolean? {
    return when (value) {
        is Boolean -> value
        is Number -> when (value.toDouble()) {
            0.0 -> false
            1.0 -> true
            else -> null
        }
        is String -> when (value.trim().lowercase()) {
            "true", "1" -> true
            "false", "0" -> false
            else -> null
        }
        else -> null
    }
}

/**
 * 书源显式关闭重定向时，3xx 直接返回给调用方，不再丢给 WebView 加载
 */
internal fun shouldReturnRedirectBeforeWebView(
    followRedirects: Boolean?,
    responseCode: Int,
): Boolean = followRedirects == false && responseCode in 300..399

internal fun derivedCallTimeoutMillis(readTimeoutMillis: Long): Long {
    val doubled = if (readTimeoutMillis > MAX_OKHTTP_TIMEOUT_MILLIS / 2) {
        MAX_OKHTTP_TIMEOUT_MILLIS
    } else {
        readTimeoutMillis * 2
    }
    return maxOf(MIN_DERIVED_CALL_TIMEOUT_MILLIS, doubled)
}
