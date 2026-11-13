package com.shimmer.reader.help.source

import com.shimmer.reader.constant.SourceType
import com.shimmer.reader.data.entities.BaseSource
import com.shimmer.reader.data.entities.BookSource
import com.shimmer.reader.data.entities.RssSource
import com.shimmer.reader.model.SharedJsScope
import com.shimmer.reader.help.http.dns.DnsScope
import org.mozilla.javascript.Scriptable
import kotlin.coroutines.CoroutineContext

fun BaseSource.getShareScope(coroutineContext: CoroutineContext? = null, dnsScope: DnsScope = getNetworkDnsScope()): Scriptable? {
    return SharedJsScope.getScope(jsLib, coroutineContext, dnsScope)
}

fun BaseSource.getSourceType(): Int {
    return when (this) {
        is BookSource -> SourceType.book
        is RssSource -> SourceType.rss
        else -> error("unknown source type: ${this::class.simpleName}.")
    }
}
