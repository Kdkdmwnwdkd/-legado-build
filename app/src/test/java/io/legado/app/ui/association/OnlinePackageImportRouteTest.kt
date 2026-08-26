package io.legado.app.ui.association

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlinePackageImportRouteTest {

    @Test
    fun parsesParagraphRuleRoute() {
        val route = OnlinePackageImportRoute.parse(
            scheme = "legado",
            host = "import",
            path = "/paragraphRule",
            sourceUrl = " https://example.com/rules.json "
        )

        assertEquals(
            OnlinePackageImportRoute.ParagraphRule(" https://example.com/rules.json "),
            route
        )
    }

    @Test
    fun parsesBubbleAliasesAndYueduSchemeCaseInsensitively() {
        val route = OnlinePackageImportRoute.parse(
            scheme = "YUEDU",
            host = "IMPORT",
            path = "/BubblePackage",
            sourceUrl = "https://example.com/bubble.zip"
        )

        assertEquals(
            OnlinePackageImportRoute.Bubble("https://example.com/bubble.zip"),
            route
        )
    }

    @Test
    fun keepsUnrelatedLegacyRoutesOutsideNewDispatcher() {
        val route = OnlinePackageImportRoute.parse(
            scheme = "legado",
            host = "import",
            path = "/bookSource",
            sourceUrl = "https://example.com/source.json"
        )

        assertSame(OnlinePackageImportRoute.Other, route)
    }

    @Test
    fun rejectsTargetRouteWithWrongHost() {
        val route = OnlinePackageImportRoute.parse(
            scheme = "legado",
            host = "other",
            path = "/bubblePackage",
            sourceUrl = "https://example.com/bubble.zip"
        )

        assertTrue(route is OnlinePackageImportRoute.Invalid)
    }

    @Test
    fun preservesLegacyBubbleLinksWithoutImportHost() {
        val withoutHost = OnlinePackageImportRoute.parse(
            scheme = "legado",
            host = null,
            path = "/bubble",
            sourceUrl = "https://example.com/one.zip"
        )
        val customHost = OnlinePackageImportRoute.parse(
            scheme = "legado",
            host = "anything",
            path = "/bubble",
            sourceUrl = "https://example.com/two.zip"
        )

        assertEquals(OnlinePackageImportRoute.Bubble("https://example.com/one.zip"), withoutHost)
        assertEquals(OnlinePackageImportRoute.Bubble("https://example.com/two.zip"), customHost)
    }

    @Test
    fun rejectsMissingSource() {
        val route = OnlinePackageImportRoute.parse(
            scheme = "legado",
            host = "import",
            path = "/paragraphRules",
            sourceUrl = "  "
        )

        assertTrue(route is OnlinePackageImportRoute.Invalid)
    }
}
