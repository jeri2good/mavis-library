package com.mavislibrary.app.web

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.URLEncoder

/** The Search sheet opens a book in the web app with "#/book/<encoded key>"; the web router decodes it. */
class BookRouteTest {
    private fun route(key: String) = "#/book/" + URLEncoder.encode(key, "UTF-8").replace("+", "%20")

    @Test fun gutenbergKeyIsEscapedLikeEncodeURIComponent() = assertEquals("#/book/gutenberg%3A1342", route("gutenberg:1342"))
    @Test fun openLibraryKeyIsEscaped() = assertEquals("#/book/openlibrary%3AOL893414W", route("openlibrary:OL893414W"))
    @Test fun spacesBecomePercent20NotPlus() = assertEquals("#/book/a%20b", route("a b"))
}
