package io.legado.app.ui.book.read.epub

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EpubDirectAnnotationContractTest {

    @Test
    fun `runtime recognizes epub3 note semantics and encoded fragments`() {
        val source = sourceOf("main/assets/epub/direct-runtime.js")

        assertTrue("noteref|footnote|endnote" in source)
        assertTrue("node.getAttribute('epub:type')" in source)
        assertTrue("node.getAttribute('role')" in source)
        assertTrue("role=\"doc-backlink\"" in source)
        assertTrue("decodeURIComponent(raw)" in source)
        assertTrue("decodeAnnotationFragment(url.hash)" in source)
    }

    @Test
    fun `cross document annotations stay on the epub resource origin`() {
        val source = sourceOf("main/assets/epub/direct-runtime.js")

        assertTrue("url.origin===base.origin" in source)
        assertTrue("fetch(documentUrl,{cache:'no-store',credentials:'omit'})" in source)
        assertTrue("new DOMParser().parseFromString(source,'text/html')" in source)
        assertTrue("annotationBaseUrl(doc,documentUrl)" in source)
        assertTrue("absoluteAnnotationUrl(source,baseUrl,true)" in source)
        assertTrue("String(node.localName||node.tagName||'').toUpperCase()" in source)
    }

    @Test
    fun `annotation overlay is isolated sanitized and gesture safe`() {
        val source = sourceOf("main/assets/epub/direct-runtime.js")

        assertTrue("host.attachShadow({mode:'open'})" in source)
        assertTrue("script,style,link,meta,base,iframe,frame,object,embed,form" in source)
        assertTrue("name.indexOf('on')===0" in source)
        assertTrue("name==='hidden'" in source)
        assertTrue("clone=document.createElement('div')" in source)
        assertTrue("protocol==='javascript:'" in source)
        assertTrue("allowDataImage&&/^data:image\\//i" in source)
        assertTrue("#legado-epub-annotation-overlay" in source)
        assertTrue("touch-action:pan-y" in source)
        assertTrue("overscroll-behavior:contain" in source)
        assertTrue("background:var(--legado-bg,#fff)" in source)
        assertTrue("panel.setAttribute('aria-labelledby',heading.id)" in source)
        assertFalse("color-mix(" in source)
    }

    @Test
    fun `annotation stack closes locally and falls back only after resolution failure`() {
        val source = sourceOf("main/assets/epub/direct-runtime.js")

        assertTrue("annotationStack.push(entry)" in source)
        assertTrue("annotationStack.pop();renderAnnotation" in source)
        assertTrue("if(isAnnotationBacklink(anchor)){closeAnnotation(true);return;}" in source)
        assertTrue("noteback|backlink|note[-_]?ref|footnote[-_]?ref" in source)
        assertTrue("closeAnnotation(true);" in source)
        assertTrue("bridge.onFootnote(activeToken,url.href)" in source)
        assertTrue("dismissAnnotation:dismissAnnotation" in source)
        assertTrue("annotationVisible:function(){return annotationVisible;}" in source)
    }

    @Test
    fun `backlink marker targets expand to their note container`() {
        val source = sourceOf("main/assets/epub/direct-runtime.js")

        assertTrue("function annotationContentTarget(target)" in source)
        assertTrue("target.closest('p,li,dd,dt,aside,section,blockquote')" in source)
        assertTrue("content.length>marker.length?container:target" in source)
        assertTrue("target:annotationContentTarget(localTarget)" in source)
        assertTrue("target:annotationContentTarget(target)" in source)
    }

    @Test
    fun `qqreader image footnotes use their alt text instead of the image viewer`() {
        val source = sourceOf("main/assets/epub/direct-runtime.js")

        assertTrue("img.qqreader-footnote" in source)
        assertTrue("function showInlineImageFootnote(image)" in source)
        assertTrue("image.getAttribute('alt')" in source)
        assertTrue("content.textContent=text" in source)
        assertTrue("inlineImageFootnoteTarget(image)" in source)
        assertTrue("showInlineImageFootnote(inlineNote)" in source)
    }

    @Test
    fun `native back handling waits for the actual javascript dismissal result`() {
        val layer = sourceOf("main/java/io/legado/app/ui/book/read/epub/EpubDirectWebLayer.kt")
        val readView = sourceOf("main/java/io/legado/app/ui/book/read/epub/EpubReadView.kt")
        val activity = sourceOf("main/java/io/legado/app/ui/book/read/ReadBookActivity.kt")
        val dismissBody = kotlinFunctionBody(layer, "dismissAnnotation")
        val bridgeBody = kotlinFunctionBody(layer, "onAnnotationState")
        val delegateBody = kotlinFunctionBody(readView, "dismissDirectAnnotation")

        assertTrue("evaluateJavascript(DISMISS_ANNOTATION_SCRIPT)" in dismissBody)
        assertTrue("ANNOTATION_DISMISS_CALLBACK_TIMEOUT_MS" in dismissBody)
        assertTrue("completed.compareAndSet(false, true)" in dismissBody)
        assertTrue("view.token == token" in dismissBody)
        assertTrue("token == generation" in dismissBody)
        assertFalse("!annotationVisible" in dismissBody)
        assertTrue("reportAnnotationState(token, visible)" in bridgeBody)
        assertTrue("initializedDirectLayer()?.dismissAnnotation(onResult)" in delegateBody)

        val callback = activity.indexOf("onBackPressedDispatcher.addCallback(this)")
        val dismiss = activity.indexOf("dismissDirectAnnotation { dismissed ->", callback)
        val resultGate = activity.indexOf("if (!dismissed) handleBackPressedAfterEpubAnnotation()", dismiss)
        val fallback = activity.indexOf("private fun handleBackPressedAfterEpubAnnotation()", resultGate)
        assertTrue(callback >= 0 && dismiss > callback && resultGate > dismiss && fallback > resultGate)
        assertTrue("epubAnnotationBackCheckPending" in activity)
    }

    private fun sourceOf(relativePath: String): String {
        val file = sequenceOf(
            File("src/$relativePath"),
            File("app/src/$relativePath")
        ).firstOrNull(File::isFile) ?: error("Source not found: $relativePath")
        return file.readText()
    }

    private fun kotlinFunctionBody(source: String, name: String): String {
        val start = source.indexOf("fun $name(")
        check(start >= 0) { "Kotlin function not found: $name" }
        val bodyStart = source.indexOf('{', start)
        check(bodyStart >= 0)
        var depth = 0
        for (index in bodyStart until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(bodyStart + 1, index)
            }
        }
        error("Unterminated function: $name")
    }
}
