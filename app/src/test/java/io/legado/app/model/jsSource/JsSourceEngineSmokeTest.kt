package io.legado.app.model.jsSource

import com.script.ScriptBindings
import com.script.rhino.RhinoScriptEngine
import org.junit.Assert
import org.junit.Test
import org.mozilla.javascript.Function
import org.mozilla.javascript.ScriptableObject
import java.io.File

/**
 * JS 单文件源的运行时冒烟测试。
 *
 * 只覆盖不依赖 Android 环境的部分：CryptoJS 资源可用性、脚本内 config 与顶层函数可被读取、
 * 返回值归一化。写在这里是为了在改引擎或升级 Rhino 时能第一时间发现回归。
 */
class JsSourceEngineSmokeTest {

    private val cryptoJs: String by lazy {
        File("src/main/assets/scripts/cryptojs.min.js").readText()
    }

    @Test
    fun cryptoJsLoadsAndWorks() {
        val scope = RhinoScriptEngine.getRuntimeScope(ScriptBindings())
        RhinoScriptEngine.eval(cryptoJs, scope, null)
        val md5 = RhinoScriptEngine.eval("CryptoJS.MD5('abc').toString()", scope, null)
        Assert.assertEquals("900150983cd24fb0d6963f7d28e17f72", md5)
    }

    @Test
    fun configObjectAndTopLevelFunctionReadable() {
        val scope = RhinoScriptEngine.getRuntimeScope(ScriptBindings())
        RhinoScriptEngine.eval(
            """
            var config = { bookSourceUrl: 'https://example.com', bookSourceName: '示例' };
            function search(key, page) {
                return [{ name: key, bookUrl: 'https://example.com/' + key }];
            }
            """.trimIndent(),
            scope,
            null
        )
        val config = ScriptableObject.getProperty(scope, "config")
        Assert.assertTrue(config !== ScriptableObject.NOT_FOUND)
        Assert.assertTrue(ScriptableObject.getProperty(scope, "search") is Function)
        val json = RhinoScriptEngine.eval("JSON.stringify(search('斗破', 1))", scope, null)
        Assert.assertTrue(json.toString().contains("https://example.com/"))
    }

    @Test
    fun normalizeJsResultHandlesCommonTypes() {
        val scope = RhinoScriptEngine.getRuntimeScope(ScriptBindings())
        RhinoScriptEngine.eval(
            """
            var obj = { a: 1, b: 'x' };
            var arr = [1, 2];
            """.trimIndent(),
            scope,
            null
        )
        val objJson = JsSourceEngine.normalizeJsResult(
            ScriptableObject.getProperty(scope, "obj")
        )
        Assert.assertEquals("""{"a":1,"b":"x"}""", objJson)
        val arrJson = JsSourceEngine.normalizeJsResult(
            ScriptableObject.getProperty(scope, "arr")
        )
        Assert.assertEquals("[1,2]", arrJson)
        Assert.assertNull(JsSourceEngine.normalizeJsResult(null))
    }
}
