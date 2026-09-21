package io.legado.app.ui.book.read.epub

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.text.TextPaint
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.model.localBook.epubcore.archive.EpubArchive
import io.legado.app.model.localBook.epubcore.direct.EpubDirectChapter
import io.legado.app.model.localBook.epubcore.direct.EpubDirectDocumentBuilder
import io.legado.app.model.localBook.epubcore.direct.EpubDirectPosition
import io.legado.app.model.localBook.epubcore.direct.EpubDirectResourceFactory
import io.legado.app.model.localBook.epubcore.direct.EpubDirectSession
import io.legado.app.model.localBook.epubcore.layout.EpubCoreLayoutConfig
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class EpubDirectWebResourceRenderTest {

    private var activity: EpubDirectWebResourceTestActivity? = null
    private var layer: EpubDirectWebLayer? = null

    @After
    fun tearDown() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            layer?.destroy()
            activity?.finish()
        }
        layer = null
        activity = null
    }

    @Test
    fun productionLayerRendersPublisherImagesBackgroundNestedCssAndFont() {
        val host = EpubDirectSession.HOST
        val entries = mapOf(
            "OPS/Styles/main.css" to """
                @import "Nested/theme.css";
                @font-face { font-family: PublisherFixture; src: url('../Fonts/%E6%AD%A3%E6%96%87.ttf') format('truetype'); }
                html { background-image: url('../Images/background%20image.png'); background-repeat: repeat; }
                #fontProbe { font-family: PublisherFixture, monospace; }
            """.trimIndent().toByteArray(),
            "OPS/Styles/Nested/theme.css" to "#nestedProbe{color:rgb(17,34,51)}".toByteArray(),
            "OPS/Styles/second.css" to """
                @import "Nested/second.css";
                @font-face { font-family: PublisherFixture; src: url('../Fonts/%E6%AC%A1%E7%AB%A0.ttf') format('truetype'); }
                html { background-image: url('../Images/second%20background.png'); background-repeat: repeat; }
                #fontProbe { font-family: PublisherFixture, monospace; }
            """.trimIndent().toByteArray(),
            "OPS/Styles/Nested/second.css" to "#nestedProbe{color:rgb(68,85,102)}".toByteArray(),
            COVER_PATH to solidPng(Color.RED),
            BACKGROUND_PATH to solidPng(Color.GREEN),
            FONT_PATH to systemFont().readBytes(),
            SECOND_COVER_PATH to solidPng(Color.BLUE),
            SECOND_BACKGROUND_PATH to solidPng(Color.YELLOW),
            SECOND_FONT_PATH to systemFont().readBytes()
        )
        val archive = MemoryArchive(entries)
        val requested = Collections.synchronizedList(arrayListOf<String>())
        val config = EpubCoreLayoutConfig(
            pageWidthPx = 320,
            pageHeightPx = 480,
            textPaint = TextPaint().apply {
                textSize = 18f
                color = Color.BLACK
            },
            backgroundColor = Color.WHITE
        )
        val source = """
            <!doctype html><html><head><base href="../">
            <script>
              window.__fixtureResourceErrors=[];
              window.addEventListener('error',function(event){
                var target=event.target;
                if(target&&target!==window)window.__fixtureResourceErrors.push({
                  tag:String(target.tagName||''),
                  url:String(target.currentSrc||target.src||target.href||''),
                  message:String(event.message||'resource error')
                });
              },true);
            </script>
            <link rel="stylesheet" href="Styles/main.css">
            </head><body style="margin:0">
            <div id="nestedProbe">nested import</div>
            <div id="fontProbe">MMMMMMMM</div>
            <img id="cover" src="Images/%E5%B0%81%E9%9D%A2.png" width="64" height="64">
            </body></html>
        """.trimIndent()
        val chapter = EpubDirectDocumentBuilder.build(
            chapterIndex = 0,
            href = "OPS/Text/chapter.xhtml",
            title = "fixture",
            sourceHtml = source,
            config = config,
            density = 1f,
            resourceHost = host
        )
        val secondChapter = EpubDirectDocumentBuilder.build(
            chapterIndex = 1,
            href = "OPS/Text/second.xhtml",
            title = "second fixture",
            sourceHtml = source
                .replace("Styles/main.css", "Styles/second.css")
                .replace("Images/%E5%B0%81%E9%9D%A2.png", "Images/second%20cover.png"),
            config = config,
            density = 1f,
            resourceHost = host
        )
        assertTrue(chapter.html.contains("<base href=\"../\">"))
        assertFalse(chapter.html.contains("Content-Security-Policy"))
        assertFalse(chapter.html.contains("https://$host"))
        val session = directSession(host, archive, requested, listOf(chapter, secondChapter))
        val firstReady = CountDownLatch(1)
        val secondReady = CountDownLatch(1)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val intent = Intent(
            ApplicationProvider.getApplicationContext(),
            EpubDirectWebResourceTestActivity::class.java
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        @Suppress("DEPRECATION")
        activity = instrumentation.startActivitySync(intent) as EpubDirectWebResourceTestActivity
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            layer = EpubDirectWebLayer(activity!!).apply {
                setListener(object : EpubDirectWebLayer.Listener {
                    override fun onReady(position: EpubDirectPosition) {
                        when (position.chapterIndex) {
                            0 -> firstReady.countDown()
                            1 -> secondReady.countDown()
                        }
                    }
                })
                bindSession(session)
            }
            activity!!.setContentView(layer)
            layer!!.showChapter(chapter, config, initialPageIndex = 0)
        }

        assertTrue("first direct chapter did not become ready", firstReady.await(15, TimeUnit.SECONDS))
        val state = waitForResourceState("chapter.xhtml", "background%20image.png")
        val diagnostic = diagnostic(state, requested)
        assertEquals(diagnostic, "https://$host", state.getString("origin"))
        assertEquals(diagnostic, "https://$host/OPS/", state.getString("baseURI"))
        assertEquals(diagnostic, "", state.getString("csp"))
        assertEquals(diagnostic, 16, state.getInt("naturalWidth"))
        assertTrue(diagnostic, state.getString("backgroundImage").contains("background"))
        assertEquals(diagnostic, "rgb(17, 34, 51)", state.getString("nestedColor"))
        assertTrue(diagnostic, state.getBoolean("fontLoaded"))
        assertTrue(diagnostic, state.getString("fontFamily").contains("PublisherFixture"))

        val expected = setOf(
            "OPS/Styles/main.css",
            "OPS/Styles/Nested/theme.css",
            COVER_PATH,
            BACKGROUND_PATH,
            FONT_PATH
        )
        assertTrue(diagnostic, requested.containsAll(expected))

        val colors = captureLayerColors()
        assertTrue(diagnostic, colors.any {
            Color.red(it) > 180 && Color.green(it) < 100 && Color.blue(it) < 100
        })
        assertTrue(diagnostic, colors.any {
            Color.green(it) > 100 && Color.red(it) < 100 && Color.blue(it) < 100
        })

        synchronized(requested) { requested.clear() }
        instrumentation.runOnMainSync {
            layer!!.showChapter(secondChapter, config, initialPageIndex = 0)
        }
        assertTrue(
            "second direct chapter did not become ready after session reuse",
            secondReady.await(15, TimeUnit.SECONDS)
        )
        val secondState = waitForResourceState("second.xhtml", "second%20background.png")
        val secondDiagnostic = diagnostic(secondState, requested)
        assertEquals(secondDiagnostic, "https://$host/OPS/", secondState.getString("baseURI"))
        assertEquals(secondDiagnostic, "", secondState.getString("csp"))
        assertEquals(secondDiagnostic, 16, secondState.getInt("naturalWidth"))
        assertTrue(secondDiagnostic, secondState.getString("backgroundImage").contains("second"))
        assertEquals(secondDiagnostic, "rgb(68, 85, 102)", secondState.getString("nestedColor"))
        assertTrue(secondDiagnostic, secondState.getBoolean("fontLoaded"))
        assertTrue(secondDiagnostic, secondState.getString("fontFamily").contains("PublisherFixture"))
        assertTrue(
            secondDiagnostic,
            requested.containsAll(
                setOf(
                    "OPS/Styles/second.css",
                    "OPS/Styles/Nested/second.css",
                    SECOND_COVER_PATH,
                    SECOND_BACKGROUND_PATH,
                    SECOND_FONT_PATH
                )
            )
        )
        val secondColors = captureLayerColors()
        assertTrue(secondDiagnostic, secondColors.any {
            Color.blue(it) > 180 && Color.red(it) < 100 && Color.green(it) < 100
        })
        assertTrue(secondDiagnostic, secondColors.any {
            Color.red(it) > 180 && Color.green(it) > 180 && Color.blue(it) < 100
        })
        assertFailedHandoffRemovesSnapshot()
    }

    private fun directSession(
        host: String,
        archive: EpubArchive,
        requested: MutableList<String>,
        chapters: List<EpubDirectChapter>
    ) = EpubDirectSession(
        bookUrl = "instrumentation.epub",
        resourceHost = host,
        chapterLoader = { index, _ -> chapters[index] },
        resourceLoader = { path, range ->
            requested.add(path)
            EpubDirectResourceFactory.open(
                archive = archive,
                path = path,
                declaredMimeType = null,
                rangeHeader = range
            )
        },
        linkResolver = { _, _ -> null },
        closeAction = {}
    )

    private fun waitForResourceState(
        expectedChapterPath: String,
        expectedBackgroundPath: String
    ): JSONObject {
        val deadline = SystemClock.uptimeMillis() + 12_000L
        var latest = JSONObject()
        while (SystemClock.uptimeMillis() < deadline) {
            latest = JSONObject(
                evaluateJavascript(expectedChapterPath, STATE_SCRIPT).orEmpty().ifBlank { "{}" }
            )
            if (latest.optString("href").contains(expectedChapterPath) &&
                latest.optInt("naturalWidth") > 0 &&
                latest.optBoolean("fontLoaded") &&
                latest.optString("backgroundImage").contains(expectedBackgroundPath)
            ) return latest
            SystemClock.sleep(100)
        }
        return latest
    }

    private fun diagnostic(state: JSONObject, requested: List<String>): String {
        val requestSnapshot = synchronized(requested) { requested.toList() }
        return "state=$state, requested=$requestSnapshot"
    }

    private fun assertFailedHandoffRemovesSnapshot() {
        evaluateJavascript("second.xhtml", "window.__legadoEpub=null;true")
        val callback = CountDownLatch(1)
        var result = true
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            layer!!.navigateToFragment("missing-fragment") {
                result = it
                callback.countDown()
            }
        }
        assertTrue("failed handoff did not complete", callback.await(5, TimeUnit.SECONDS))
        assertFalse(result)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertTrue(
                "stale handoff snapshot remained mounted",
                descendants(layer!!).none { it is EpubDirectRecoverySnapshotOverlay }
            )
        }
    }

    private fun evaluateJavascript(expectedChapterPath: String, script: String): String? {
        val latch = CountDownLatch(1)
        var decoded: String? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            currentWebView(expectedChapterPath).evaluateJavascript(script) { raw ->
                decoded = runCatching { JSONArray("[$raw]").optString(0) }.getOrNull()
                latch.countDown()
            }
        }
        assertTrue("JavaScript callback timed out", latch.await(3, TimeUnit.SECONDS))
        return decoded
    }

    private fun currentWebView(expectedChapterPath: String): WebView {
        val candidates = descendants(layer!!).filterIsInstance<WebView>()
            .filter { it.visibility == View.VISIBLE }
            .toList()
        return candidates.singleOrNull { it.url?.contains(expectedChapterPath) == true }
            ?: error(
                "No unique visible WebView for $expectedChapterPath; " +
                    "visible=${candidates.map { it.url }}"
            )
    }

    private fun captureLayerColors(): IntArray {
        lateinit var pixels: IntArray
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val target = layer!!
            val bitmap = Bitmap.createBitmap(target.width, target.height, Bitmap.Config.ARGB_8888)
            target.draw(Canvas(bitmap))
            pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            bitmap.recycle()
        }
        return pixels
    }

    private fun descendants(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) yieldAll(descendants(root.getChildAt(index)))
        }
    }

    private fun solidPng(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        return ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            bitmap.recycle()
            output.toByteArray()
        }
    }

    private fun systemFont(): File {
        val candidates = listOf(
            "/system/fonts/Roboto-Regular.ttf",
            "/system/fonts/RobotoStatic-Regular.ttf",
            "/system/fonts/NotoSans-Regular.ttf"
        )
        return candidates.asSequence().map(::File).firstOrNull(File::isFile)
            ?: error("No instrumentation font fixture is available")
    }

    private class MemoryArchive(private val entries: Map<String, ByteArray>) : EpubArchive {
        override fun exists(path: String): Boolean = entries.containsKey(path)
        override fun list(): List<String> = entries.keys.toList()
        override fun readBytes(path: String, maxBytes: Long): ByteArray = entries.getValue(path)
        override fun entrySize(path: String): Long? = entries[path]?.size?.toLong()
        override fun openStream(path: String) = ByteArrayInputStream(entries.getValue(path))
        override fun close() = Unit
    }

    companion object {
        private const val COVER_PATH = "OPS/Images/\u5C01\u9762.png"
        private const val BACKGROUND_PATH = "OPS/Images/background image.png"
        private const val FONT_PATH = "OPS/Fonts/\u6B63\u6587.ttf"
        private const val SECOND_COVER_PATH = "OPS/Images/second cover.png"
        private const val SECOND_BACKGROUND_PATH = "OPS/Images/second background.png"
        private const val SECOND_FONT_PATH = "OPS/Fonts/\u6B21\u7AE0.ttf"
        private const val STATE_SCRIPT = """
            (function(){
              var image=document.getElementById('cover');
              var root=getComputedStyle(document.documentElement);
              var body=getComputedStyle(document.body);
              var nested=getComputedStyle(document.getElementById('nestedProbe'));
              var probe=getComputedStyle(document.getElementById('fontProbe'));
              var loaded=false;
              var fonts=[];
              if(document.fonts)document.fonts.forEach(function(face){
                var family=String(face.family||'').replace(/["']/g,'');
                fonts.push({family:family,status:String(face.status||'')});
                if(family==='PublisherFixture'&&face.status==='loaded')loaded=true;
              });
              return JSON.stringify({
                href:location.href,
                origin:location.origin,
                baseURI:document.baseURI,
                csp:(document.querySelector('meta[http-equiv="Content-Security-Policy"]')||{}).content||'',
                imageSrc:image.src,
                imageCurrentSrc:image.currentSrc,
                imageComplete:image.complete,
                naturalWidth:image.naturalWidth,
                backgroundImage:root.backgroundImage,
                bodyBackgroundImage:body.backgroundImage,
                nestedColor:nested.color,
                fontLoaded:loaded,
                fontFamily:probe.fontFamily,
                fontSetStatus:document.fonts?document.fonts.status:'unsupported',
                fonts:fonts,
                errors:window.__fixtureResourceErrors||[],
                resources:(performance.getEntriesByType?performance.getEntriesByType('resource'):[]).map(function(entry){
                  return {name:entry.name,type:entry.initiatorType,duration:entry.duration};
                })
              });
            })();
        """
    }
}
