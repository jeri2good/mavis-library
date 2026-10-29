package com.mavislibrary.app.ui.shelf

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.mavislibrary.app.AppContainer
import com.mavislibrary.app.data.api.TechItem
import com.mavislibrary.app.data.feeds.DiscoveryResult
import com.mavislibrary.app.data.feeds.FeedFetcher
import com.mavislibrary.app.data.feeds.FeedParser
import com.mavislibrary.app.data.feeds.FeedResponse
import com.mavislibrary.app.data.feeds.FeedSource
import com.mavislibrary.app.data.feeds.FeedStory
import com.mavislibrary.app.data.model.Book
import com.mavislibrary.app.data.model.BookSource
import com.mavislibrary.app.data.secure.Secret
import com.mavislibrary.app.data.secure.SecretCipher
import com.mavislibrary.app.ui.sites.SiteScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ShelfScreenTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temp = TemporaryFolder()

    private val opened = mutableListOf<Book>()
    private val removed = mutableListOf<Book>()
    private val podcasts = mutableListOf<Book>()
    private val sites = mutableListOf<String>()
    private var webShelf = 0
    private var scans = 0
    private var retries = 0

    private fun show(state: ShelfUiState) = compose.setContent {
        ShelfContent(state, { opened += it }, { webShelf++ }, { scans++ }, { podcasts += it }, { sites += it }, { removed += it }, { retries++ })
    }

    @Test fun shelvesRenderInOrderAndOpenBooksAndSites() {
        show(ShelfUiState(
            books = listOf(book(1)), loading = false, activity = mapOf("gutenberg:1" to 1L),
            popular = listOf(PopularShelf("popular", "Most popular", "", listOf(book(7)))),
            siteSources = listOf(FeedSource("s1", "Future Tools", "https://s1.example/feed", "https://s1.example", 0L)),
        ))
        compose.onNodeWithTag("shelf_grid").assertExists()
        compose.onNodeWithText("MY BOOKS", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("open_web_shelf").performClick()
        assertEquals(1, webShelf)
        listOf("mine", "continue", "popular", "sites").forEach { compose.onNodeWithTag("shelf_grid").performScrollToNode(hasTestTag("shelf_row_$it")) }
        compose.onNodeWithTag("shelf_grid").performScrollToNode(hasTestTag("shelf_row_popular"))
        compose.onNodeWithText("MOST POPULAR", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("shelf_book_gutenberg:7").performClick()
        assertEquals(listOf(book(7)), opened)
        compose.onNodeWithTag("shelf_grid").performScrollToNode(hasTestTag("shelf_row_sites"))
        compose.onNodeWithTag("shelf_site_s1").assertContentDescriptionContains("Future Tools", substring = true).performClick()
        assertEquals(listOf("s1"), sites)
    }

    @Test fun longPressOnMyBooksOffersPodcastAndRemove() {
        show(ShelfUiState(books = listOf(book(1)), loading = false))
        compose.onAllNodesWithTag("shelf_book_gutenberg:1").onFirst().performTouchInput { longClick() }
        compose.onNodeWithText("Make a podcast").assertExists()
        compose.onNodeWithText("Remove from shelf").performClick()
        assertEquals(listOf(book(1)), removed)
        assertTrue(opened.isEmpty())
    }

    @Test fun emptyShelfShowsFriendlyPlankScanAndPopularRetry() {
        show(ShelfUiState(loading = false, popularError = "Popular picks couldn't load. Try again when you're online."))
        compose.onNodeWithText("Save books from Search to see them here").assertExists()
        compose.onNodeWithTag("shelf_scan").performClick()
        assertEquals(1, scans)
        compose.onNodeWithTag("shelf_row_continue").assertDoesNotExist()
        compose.onNodeWithTag("shelf_row_sites").assertDoesNotExist()
        compose.onNodeWithText("Try again").performClick()
        assertEquals(1, retries)
    }

    @Test fun longRowsLoopAndShortRowsDont() {
        val many = (101..112).map(::book)
        show(ShelfUiState(loading = false, books = listOf(book(1)), popular = listOf(PopularShelf("popular", "Most popular", "", many))))
        compose.onAllNodesWithTag("shelf_book_gutenberg:1").assertCountEquals(1)
        compose.onNodeWithTag("shelf_grid").performScrollToNode(hasTestTag("shelf_row_popular"))
        // The row loops: the last book sits just before the first one.
        val first = compose.onNodeWithTag("shelf_book_gutenberg:101").fetchSemanticsNode().boundsInRoot
        val last = compose.onNodeWithTag("shelf_book_gutenberg:112").fetchSemanticsNode().boundsInRoot
        assertTrue(last.right <= first.left)
        compose.onNodeWithTag("shelf_row_popular").performTouchInput { swipeRight(startX = centerX - 60f, endX = centerX + 60f, durationMillis = 1_000) }
        val moved = compose.onNodeWithTag("shelf_book_gutenberg:112").fetchSemanticsNode().boundsInRoot
        assertTrue("scrolling back past the first book keeps going", moved.left > last.left)
    }

    @Test fun eachBookShowsItsOwnAuthors() {
        show(ShelfUiState(loading = false, books = listOf(book(1, listOf("Jane Austen")), book(2, listOf("Herman Melville")), book(3, listOf("Karl Marx", "Friedrich Engels")))))
        compose.onAllNodesWithTag("shelf_book_gutenberg:1").onFirst().assertContentDescriptionEquals("Book 1, Jane Austen")
        compose.onAllNodesWithTag("shelf_book_gutenberg:2").onFirst().assertContentDescriptionEquals("Book 2, Herman Melville")
        compose.onAllNodesWithTag("shelf_book_gutenberg:3").onFirst().assertContentDescriptionEquals("Book 3, Karl Marx, Friedrich Engels")
        compose.onAllNodesWithText("Herman Melville", useUnmergedTree = true).onFirst().assertExists()
    }

    @Test fun brassPlatesSitAboveTheirRowOfBooksWithPlankUnderBooks() {
        show(ShelfUiState(
            books = listOf(book(1)), loading = false,
            popular = listOf(PopularShelf("popular", "Most popular", "", listOf(book(7)))),
            siteSources = listOf(FeedSource("s1", "Future Tools", "https://s1.example/feed", "https://s1.example", 0L)),
            sitePosts = mapOf("s1" to listOf(FeedStory(TechItem("p1", "Post", "https://s1.example/p1"), 1L))),
        ))
        listOf(
            Triple("mine", "shelf_book_gutenberg:1", "MY BOOKS"),
            Triple("popular", "shelf_book_gutenberg:7", "MOST POPULAR"),
            Triple("sites", "shelf_site_s1", "SITES"),
        ).forEach { (id, itemTag, title) ->
            compose.onNodeWithTag("shelf_grid").performScrollToNode(hasTestTag("shelf_row_$id"))
            compose.onNodeWithText(title, useUnmergedTree = true).assertExists()
            val row = compose.onNodeWithTag("shelf_row_$id").fetchSemanticsNode().boundsInRoot
            val plate = compose.onNodeWithTag("shelf_plate_$id").fetchSemanticsNode().boundsInRoot
            val item = compose.onNodeWithTag(itemTag).fetchSemanticsNode().boundsInRoot
            val plank = compose.onNodeWithTag("shelf_plank_$id").fetchSemanticsNode().boundsInRoot
            assertTrue("$id plate sits above its books ($plate vs $item)", plate.bottom <= item.top)
            assertTrue("$id plate is top-left in its bay", plate.left < row.center.x && plate.top - row.top < 30f)
            assertTrue("$id plank stays under its books ($item vs $plank)", item.bottom <= plank.top + 1f)
        }
    }

    @Test fun realFeedStoreSitesRenderWithUnreadRibbonsAndTapOpensSiteArticleList() {
        val ftRss = "<rss><channel><title>Future Tools</title>" +
            "<item><guid>ft1</guid><title>AI Mode Expands</title><link>https://ft.example/1</link><description>Summary 1</description></item>" +
            "<item><guid>ft2</guid><title>New Open Model</title><link>https://ft.example/2</link><description>Summary 2</description></item>" +
            "<item><guid>ft3</guid><title>Already Read Post</title><link>https://ft.example/3</link><description>Summary 3</description></item>" +
            "</channel></rss>"
        val ignRss = "<rss><channel><title>IGN</title>" +
            "<item><guid>ign1</guid><title>Handheld Review</title><link>https://ign.example/1</link></item>" +
            "</channel></rss>"
        val arsRss = "<rss><channel><title>Ars Technica</title>" +
            "<item><guid>ars1</guid><title>Kernel Deep Dive</title><link>https://ars.example/1</link></item>" +
            "</channel></rss>"
        val fakeFeeds = FeedFetcher { url ->
            val xml = when {
                "ft.example" in url -> ftRss
                "ign.example" in url -> ignRss
                else -> arsRss
            }
            FeedResponse(url, 200, xml)
        }
        val fakeCatalogHttp = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"shelves":[],"results":[],"count":0}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val fakeGateway: suspend (Request) -> Response = { req ->
            val body = when (req.url.encodedPath) {
                "/api/podsub" -> if (req.method == "PUT") "{}" else """{"version":1,"sites":[
                    {"id":"ign_srv","name":"IGN","feedUrl":"https://ign.example/rss","siteUrl":"https://ign.example/","mode":"top","count":1,"delivery":"briefing","priority":0,"source":"feed"}
                ],"updatedAt":"saved"}"""
                "/api/podsub/today" -> "[]"
                else -> "{}"
            }
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }
        val passthroughCipher = object : SecretCipher {
            override fun encrypt(plain: ByteArray, associatedData: ByteArray) = plain
            override fun decrypt(sealed: ByteArray, associatedData: ByteArray) = sealed
        }
        val dsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val settingsDataStore = PreferenceDataStoreFactory.create(scope = dsScope) { temp.root.resolve("test_settings.preferences_pb") }
        val secureDataStore = PreferenceDataStoreFactory.create(scope = dsScope) { temp.root.resolve("test_secure.preferences_pb") }
        val container = AppContainer(
            RuntimeEnvironment.getApplication(),
            customFeedFetcher = fakeFeeds,
            onlineOverride = { true },
            podcastTransport = fakeGateway,
            customApiHttp = fakeCatalogHttp,
            customCipher = passthroughCipher,
            customSettingsDataStore = settingsDataStore,
            customSecureDataStore = secureDataStore,
        )
        try {
            val (ft, ars) = runBlocking {
                container.secureStore.write(Secret.OWNER_ACCESS_CODE, "fixture-owner")
                val ftSource = container.feedStore.add(DiscoveryResult.Found("https://ft.example/rss", "Future Tools", 3, null, "https://ft.example"))
                val arsSource = container.feedStore.add(DiscoveryResult.Found("https://ars.example/rss", "Ars Technica", 1, null, "https://ars.example"))
                // Mark 1 of Future Tools' 3 posts read (2 unread left) and Ars Technica's post read (0 unread left).
                container.readState.markRead(ftSource.id, listOf(FeedParser.stableId("ft3")))
                container.readState.markRead(arsSource.id, listOf(FeedParser.stableId("ars1")))
                ftSource to arsSource
            }
            compose.setContent {
                val nav = rememberNavController()
                NavHost(nav, startDestination = "shelf") {
                    composable("shelf") {
                        ShelfScreen(
                            container = container,
                            onOpenBook = {},
                            onOpenWebShelf = {},
                            onOpenSite = { id -> nav.navigate("site/$id") { launchSingleTop = true } },
                        )
                    }
                    composable("site/{id}") { route ->
                        SiteScreen(
                            container = container,
                            id = route.arguments?.getString("id").orEmpty(),
                            onBack = { nav.popBackStack() },
                            onPodcast = {},
                            onOpenReader = {},
                        )
                    }
                }
            }
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag("shelf_row_sites").fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodesWithTag("shelf_site_unread_${ft.id}", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodesWithTag("shelf_site_unread_ign_srv", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("shelf_grid").performScrollToNode(hasTestTag("shelf_row_sites"))

            // Every followed site from FeedStore (local + mirrored from /api/podsub) renders with its unread ribbon.
            // With 3 sites in a 320dp viewport (2 fit), CircularRow loops, so the last site also peeks before the first.
            compose.onAllNodesWithTag("shelf_site_${ft.id}").onFirst().assertContentDescriptionEquals("Future Tools site, 2 unread")
            compose.onAllNodesWithTag("shelf_site_unread_${ft.id}", useUnmergedTree = true).onFirst().onChildren().assertAny(hasText("2"))
            compose.onAllNodesWithTag("shelf_site_ign_srv").onLast().assertContentDescriptionEquals("IGN site, 1 unread")
            compose.onAllNodesWithTag("shelf_site_unread_ign_srv", useUnmergedTree = true).onLast().onChildren().assertAny(hasText("1"))
            compose.onAllNodesWithTag("shelf_site_${ars.id}").onFirst().assertContentDescriptionEquals("Ars Technica site")
            compose.onAllNodesWithTag("shelf_site_unread_${ars.id}", useUnmergedTree = true).assertCountEquals(0)

            // Tapping a site book opens that site's article list (SiteScreen).
            compose.onAllNodesWithTag("shelf_site_${ft.id}").onFirst().performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag("site_page").fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodesWithText("AI Mode Expands").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Future Tools").assertExists()
            compose.onNodeWithText("AI Mode Expands").assertExists()
            compose.onNodeWithText("New Open Model").assertExists()

            // Back returns to the Shelf with the Sites row intact.
            compose.onNodeWithTag("back_button").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("shelf_row_sites").fetchSemanticsNodes().isNotEmpty() }
        } finally {
            container.database.close()
            container.scope.cancel()
            dsScope.cancel()
        }
    }

    private fun book(id: Int, authors: List<String> = listOf("Author")) =
        Book("gutenberg:$id", BookSource.GUTENBERG, "$id", "Book $id", authors, null, emptyList(), null, listOf("en"), null, null)
}
