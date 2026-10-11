package com.mavislibrary.app.ui.kids

import com.mavislibrary.app.data.kids.KIDS_SHELF
import java.net.URI
import java.net.URLDecoder

data class BibleStory(val title: String, val book: String, val chapter: Int, val verse: Int = 1) {
    val route get() = "#/bible/$book/$chapter" + if (verse > 1) "?v=$verse" else ""
}
val BIBLE_STORIES = listOf(
    BibleStory("In the beginning", "Gen", 1), BibleStory("Noah’s ark", "Gen", 6, 9),
    BibleStory("Joseph’s colorful coat", "Gen", 37), BibleStory("Crossing the Red Sea", "Exod", 14),
    BibleStory("David and Goliath", "1Sam", 17), BibleStory("Daniel and the lions", "Dan", 6),
    BibleStory("Jonah and the big fish", "Jonah", 1), BibleStory("Jesus is born", "Luke", 2),
    BibleStory("The Good Samaritan", "Luke", 10, 25), BibleStory("The lost sheep", "Luke", 15),
    BibleStory("Jesus feeds five thousand", "John", 6), BibleStory("Zacchaeus climbs a tree", "Luke", 19),
)
/** Fail closed: only the selected curated book or selected Bible passage can be opened. */
fun kidsWebRouteAllowed(url: String, selectedRoute: String): Boolean = runCatching {
    val uri = URI(url)
    if (uri.scheme != "https" || uri.rawAuthority != "localhost" || uri.rawUserInfo != null || uri.rawQuery != null || uri.path != "/") return false
    val hash = "#" + URLDecoder.decode(uri.rawFragment.orEmpty(), "UTF-8")
    val selected = URLDecoder.decode(selectedRoute, "UTF-8")
    if (selected.startsWith("#/book/gutenberg:")) {
        val id = selected.substringAfterLast(':').toIntOrNull()
        id in KIDS_SHELF && hash in setOf("#/book/gutenberg:$id", "#/read/gutenberg:$id")
    } else selected in BIBLE_STORIES.map { it.route } && hash == selected
}.getOrDefault(false)

internal fun kidsNavigationAllowed(url: String, selectedRoute: String, mainFrame: Boolean): Boolean =
    kidsWebRouteAllowed(url, selectedRoute) || (!mainFrame &&
        (url == "about:blank" || url.startsWith("blob:https://localhost/") || url.startsWith("data:")))
