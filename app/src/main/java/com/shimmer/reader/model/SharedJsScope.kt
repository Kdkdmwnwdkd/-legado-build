package com.shimmer.reader.model

import androidx.collection.LruCache
import com.google.gson.reflect.TypeToken
import com.script.ScriptBindings
import com.script.rhino.RhinoScriptEngine
import com.shimmer.reader.constant.AppLog
import com.shimmer.reader.exception.NoStackTraceException
import com.shimmer.reader.help.http.newCallStrResponse
import com.shimmer.reader.help.http.getHttpClient
import com.shimmer.reader.help.http.dns.DnsScope
import com.shimmer.reader.utils.ACache
import com.shimmer.reader.utils.GSON
import com.shimmer.reader.utils.MD5Utils
import com.shimmer.reader.utils.isAbsUrl
import com.shimmer.reader.utils.isJsonObject
import kotlinx.coroutines.runBlocking
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import splitties.init.appCtx
import java.io.File
import java.lang.ref.WeakReference
import kotlin.coroutines.CoroutineContext

object SharedJsScope {

    private val cacheFolder = File(appCtx.cacheDir, "shareJs")
    private val aCache = ACache.get(cacheFolder)

    private val scopeMap = LruCache<String, WeakReference<Scriptable>>(16)

    fun getScope(jsLib: String?, coroutineContext: CoroutineContext?, dnsScope: DnsScope = DnsScope.READING): Scriptable? {
        if (jsLib.isNullOrBlank()) {
            return null
        }
        val key = MD5Utils.md5Encode(jsLib)
        var scope = scopeMap[key]?.get()
        if (scope == null) {
            scope = RhinoScriptEngine.run {
                getRuntimeScope(ScriptBindings())
            }
            if (jsLib.isJsonObject()) {
                val jsMap: Map<String, String> = GSON.fromJson(
                    jsLib,
                    TypeToken.getParameterized(
                        Map::class.java,
                        String::class.java,
                        String::class.java
                    ).type
                )
                jsMap.values.forEach { value ->
                    if (value.isAbsUrl()) {
                        val fileName = MD5Utils.md5Encode(value)
                        var js = aCache.getAsString(fileName)
                        if (js == null) {
                            js = runBlocking {
                                getHttpClient(dnsScope).newCallStrResponse {
                                    url(value)
                                }.body
                            }
                            if (js != null) {
                                aCache.put(fileName, js)
                            } else {
                                throw NoStackTraceException("下载jsLib-${value}失败")
                            }
                        }
                        RhinoScriptEngine.eval(js, scope, coroutineContext)
                    }
                }
            } else {
                RhinoScriptEngine.eval(jsLib, scope, coroutineContext)
            }
            if (scope is ScriptableObject) {
                /**
                 * 阻止新全局增加（即函数内未用var的隐性全局变量创建）,会直接隐性创建失败,提示变量未定义
                 */
                scope.preventExtensions()
            }
            scopeMap.put(key, WeakReference(scope))
        }
        return scope
    }

    // ---------------- CryptoJS 作用域（JS 单文件源依赖全局 CryptoJS） ----------------

    private const val CRYPTO_JS_ASSET = "scripts/cryptojs.min.js"

    @Volatile
    private var cryptoJsScript: com.script.CompiledScript? = null
    private val cryptoCompileLock = Any()

    private val cryptoScopeMap = LruCache<String, WeakReference<Scriptable>>(16)
    private val cryptoScopeLock = Any()

    private fun getCompiledCryptoJs(): com.script.CompiledScript? {
        cryptoJsScript?.let { return it }
        synchronized(cryptoCompileLock) {
            cryptoJsScript?.let { return it }
            val text = try {
                appCtx.assets.open(CRYPTO_JS_ASSET).bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                AppLog.putDebug("加载CryptoJS失败: ${e.message}", e)
                return null
            }
            return RhinoScriptEngine.compile(text).also { cryptoJsScript = it }
        }
    }

    /**
     * 为 owner 分配一个带 CryptoJS 的共享作用域，同一 owner 同一线程复用
     */
    fun getCryptoScope(owner: Any, coroutineContext: CoroutineContext?): Scriptable? {
        val script = getCompiledCryptoJs() ?: return null
        val key = "${System.identityHashCode(owner)}-${Thread.currentThread().id}"
        synchronized(cryptoScopeLock) {
            cryptoScopeMap[key]?.get()?.let { return it }
            val scope = RhinoScriptEngine.getRuntimeScope(ScriptBindings())
            script.eval(scope, coroutineContext)
            if (scope is ScriptableObject) {
                scope.preventExtensions()
            }
            cryptoScopeMap.put(key, WeakReference(scope))
            return scope
        }
    }

    /**
     * 往已有作用域里注入 CryptoJS，用于解析 JS 源配置时提前拿到 config 对象
     */
    internal fun installCryptoJs(scope: Scriptable, coroutineContext: CoroutineContext?): Boolean {
        val script = getCompiledCryptoJs() ?: return false
        script.eval(scope, coroutineContext)
        return true
    }

    fun remove(jsLib: String?) {
        if (jsLib.isNullOrBlank()) {
            return
        }
        if (jsLib.isJsonObject()) {
            val jsMap: Map<String, String> = GSON.fromJson(
                jsLib,
                TypeToken.getParameterized(
                    Map::class.java,
                    String::class.java,
                    String::class.java
                ).type
            )
            jsMap.values.forEach { value ->
                if (value.isAbsUrl()) {
                    val fileName = MD5Utils.md5Encode(value)
                    aCache.remove(fileName)
                }
            }
        }
        val key = MD5Utils.md5Encode(jsLib)
        scopeMap.remove(key)
    }

}
