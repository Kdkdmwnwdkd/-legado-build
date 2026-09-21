package io.legado.app.model.localBook.epubcore.facade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpubSpineOwnershipPolicyTest {

    private val owners = listOf(
        EpubSpineOwnershipPolicy.Owner(2, 0, "a.xhtml", "A"),
        EpubSpineOwnershipPolicy.Owner(8, 1, "b.xhtml", "B")
    )

    @Test
    fun `pages before first logical chapter remain unowned`() {
        assertNull(EpubSpineOwnershipPolicy.ownerAt(1, owners))
    }

    @Test
    fun `unlisted pages inherit the nearest preceding logical chapter`() {
        assertEquals("A", EpubSpineOwnershipPolicy.ownerAt(5, owners)?.title)
        assertEquals("B", EpubSpineOwnershipPolicy.ownerAt(9, owners)?.title)
    }
}
