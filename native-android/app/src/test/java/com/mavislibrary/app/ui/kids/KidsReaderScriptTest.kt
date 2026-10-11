package com.mavislibrary.app.ui.kids

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class KidsReaderScriptTest {
    @Test fun childRequestsDropOwnerHeaderAndAddKidsHeader() {
        val script = kidsReaderScript("#/book/gutenberg%3A11")
        listOf("window.fetch", "new Request(", "headers.delete('x-mavis-access')",
            "headers.set('x-mavis-kids', '1')", "XMLHttpRequest.prototype.setRequestHeader",
            "XMLHttpRequest.prototype.send", "String(name).toLowerCase() === 'x-mavis-access'",
            "setHeader.call(this, 'x-mavis-kids', '1')"
        ).forEach { assertTrue(it, script.contains(it)) }
    }

    @Test fun childDocumentSuppressesSelectionAndShare() {
        val script = kidsReaderScript("#/bible/Gen/1")
        listOf("html, body, *", "-webkit-user-select:none", "-webkit-touch-callout:none",
            "contextmenu", "dragstart", "selectstart", "navigator, 'share'", "navigator, 'canShare'",
            "window, 'open'").forEach { assertTrue(it, script.contains(it)) }
    }
}
