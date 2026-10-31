package com.mavislibrary.app.work

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.core.content.IntentCompat
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.mavislibrary.app.AppContainer
import com.mavislibrary.app.BuildConfig
import com.mavislibrary.app.MainActivity
import com.mavislibrary.app.MavisApp
import com.mavislibrary.app.R
import com.mavislibrary.app.audio.PlaybackController
import com.mavislibrary.app.audio.ResumePosition
import com.mavislibrary.app.data.db.BookEntity
import com.mavislibrary.app.data.db.ReadingProgressEntity
import com.mavislibrary.app.data.kids.PinResult
import com.mavislibrary.app.data.kids.kidsPrefs
import com.mavislibrary.app.data.podcast.PodcastDraft
import com.mavislibrary.app.data.podcast.PodcastJob
import com.mavislibrary.app.data.podcast.SavedPodcast
import com.mavislibrary.app.data.secure.Secret
import com.mavislibrary.app.data.secure.SecretCipher
import com.mavislibrary.app.ui.lounge.PodcastLoungeScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class InteractiveWidgets084Test {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temp = TemporaryFolder()

    private lateinit var app: MavisApp

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication() as MavisApp
        WidgetPlayback.mediaId = ""
        WidgetPlayback.playing = false
        app.getSharedPreferences("widget-selections", Context.MODE_PRIVATE).edit().clear().commit()
        shadowOf(app).clearNextStartedActivities()
        shadowOf(app).clearBroadcastIntents()
    }

    @After
    fun tearDown() {
        WidgetPlayback.mediaId = ""
        WidgetPlayback.playing = false
        app.getSharedPreferences("widget-selections", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun bindWidget(provider: Class<*>, widgetId: Int) {
        val manager = AppWidgetManager.getInstance(app)
        shadowOf(manager).bindAppWidgetId(widgetId, ComponentName(app, provider))
    }

    private fun renderedView(widgetId: Int): View {
        val manager = AppWidgetManager.getInstance(app)
        val remote = shadowOf(manager).getViewFor(widgetId)
        assertNotNull("Expected rendered widget view for $widgetId", remote)
        return remote
    }

    private fun bookEntity(
        key: String,
        id: String,
        title: String,
        formats: String = "EPUB|true",
        epubUrl: String? = "https://www.gutenberg.org/ebooks/$id.epub.images",
        updatedAt: Long = 1_000L,
    ) = BookEntity(
        key = key,
        source = "GUTENBERG",
        sourceId = id,
        title = title,
        authors = "Author",
        coverUrl = null,
        formats = formats,
        year = 1813,
        languages = "en",
        epubUrl = epubUrl,
        sourceUrl = null,
        updatedAt = updatedAt,
    )

    @Test
    fun providersDeclareResizingPreviewsAndDescriptions() {
        val pm = app.packageManager
        val providers = listOf(
            PodcastLoungeWidget::class.java to R.xml.widget_podcast,
            ContinueReadingWidget::class.java to R.xml.widget_continue,
            VerseOfDayWidget::class.java to R.xml.widget_verse,
        )
        for ((cls, expectedXml) in providers) {
            val receiver = pm.getReceiverInfo(ComponentName(app, cls), android.content.pm.PackageManager.GET_META_DATA)
            assertTrue(receiver.exported)
            assertEquals(expectedXml, receiver.metaData.getInt("android.appwidget.provider"))
        }
        val podcastXml = app.resources.getXml(R.xml.widget_podcast)
        val attrs = mutableMapOf<String, String>()
        while (podcastXml.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (podcastXml.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && podcastXml.name == "appwidget-provider") {
                for (i in 0 until podcastXml.attributeCount) {
                    attrs[podcastXml.getAttributeName(i)] = podcastXml.getAttributeValue(i)
                }
            }
        }
        podcastXml.close()
        assertEquals("4", attrs["targetCellWidth"])
        assertEquals("2", attrs["targetCellHeight"])
        assertEquals("180.0dip", attrs["minResizeWidth"])
        assertEquals("40.0dip", attrs["minResizeHeight"])
        val resizeFlags = attrs["resizeMode"]?.removePrefix("0x")?.toInt(16)
        assertEquals(AppWidgetProviderInfo.RESIZE_HORIZONTAL or AppWidgetProviderInfo.RESIZE_VERTICAL, resizeFlags)
        assertNotNull(attrs["previewLayout"])
        assertNotNull(attrs["description"])
        assertTrue(app.getString(R.string.widget_podcast_description).isNotBlank())
        assertTrue(app.getString(R.string.widget_continue_description).isNotBlank())
        assertTrue(app.getString(R.string.widget_verse_description).isNotBlank())
    }

    @Test
    fun podcastWidgetRendersStatusesFollowsPlaybackAndRoutesPlayPauseAndNewPodcast() = runBlocking {
        val cipher = object : SecretCipher {
            override fun encrypt(plain: ByteArray, associatedData: ByteArray) = plain
            override fun decrypt(sealed: ByteArray, associatedData: ByteArray) = sealed
        }
        val dsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val c = AppContainer(
            app,
            customCipher = cipher,
            customSettingsDataStore = PreferenceDataStoreFactory.create(scope = dsScope) { temp.newFile("pw1.preferences_pb") },
            customSecureDataStore = PreferenceDataStoreFactory.create(scope = dsScope) { temp.newFile("pw2.preferences_pb") },
        )
        try {
            assertTrue(c.secureStore.write(Secret.OWNER_ACCESS_CODE, "fixture-owner"))
            val widgetId = 101
            bindWidget(PodcastLoungeWidget::class.java, widgetId)

            // 1. Empty state
            WidgetUpdater.update(app, c = c)
            var view = renderedView(widgetId)
            assertEquals("Podcast Lounge", view.findViewById<TextView>(R.id.widget_heading).text.toString())
            assertEquals("Your Podcast Lounge", view.findViewById<TextView>(R.id.widget_title).text.toString())
            assertEquals("Make your first podcast", view.findViewById<TextView>(R.id.widget_detail).text.toString())
            assertEquals(View.GONE, view.findViewById<View>(R.id.widget_action1).visibility)
            assertEquals(View.VISIBLE, view.findViewById<View>(R.id.widget_action2).visibility)
            assertEquals("＋ New podcast", view.findViewById<TextView>(R.id.widget_action2).text.toString())

            // 2. Ready episode -> "7 min · Ready", Play button controls playback service directly (no Activity launch)
            val readyEpisode = SavedPodcast(
                job = PodcastJob(
                    id = "ep-ready",
                    status = "ready",
                    title = "Hebrews 12:1-3 — Faith",
                    kind = "chapter",
                    style = "deep-dive",
                    durationSec = 420,
                    createdAt = "2026-10-31T10:00:00Z",
                ),
                studio = BuildConfig.API_BASE,
                accountId = "owner",
            )
            c.podcastSettings.save(readyEpisode)
            WidgetUpdater.update(app, c = c)
            view = renderedView(widgetId)
            assertEquals("Hebrews 12:1-3 — Faith", view.findViewById<TextView>(R.id.widget_title).text.toString())
            assertEquals("7 min · Ready", view.findViewById<TextView>(R.id.widget_detail).text.toString())
            val playButton = view.findViewById<TextView>(R.id.widget_action1)
            assertEquals(View.VISIBLE, playButton.visibility)
            assertEquals("▶ Play", playButton.text.toString())

            shadowOf(app).clearNextStartedActivities()
            shadowOf(app).clearBroadcastIntents()
            playButton.performClick()
            assertNull("Play/Pause on widget must not launch MainActivity", shadowOf(app).nextStartedActivity)
            val broadcast = shadowOf(app).broadcastIntents.single()
            assertEquals(PodcastPlaybackAction.TOGGLE, broadcast.action)
            assertEquals("ep-ready", broadcast.getStringExtra("job"))
            assertEquals(ComponentName(app, PodcastPlaybackAction::class.java), broadcast.component)
            assertEquals("load", playbackAction(toggle = true, sameEpisode = false, playing = false))
            assertEquals("pause", playbackAction(toggle = true, sameEpisode = true, playing = true))
            assertEquals("resume", playbackAction(toggle = true, sameEpisode = true, playing = false))

            // 3. Player starts playing -> button switches to "⏸ Pause"
            WidgetPlayback.mediaId = readyEpisode.resumeKey
            WidgetPlayback.playing = true
            WidgetUpdater.update(app, c = c)
            view = renderedView(widgetId)
            assertEquals("⏸ Pause", view.findViewById<TextView>(R.id.widget_action1).text.toString())

            // 4. Player stops near end -> "7 min · Played" and "▶ Play"
            WidgetPlayback.playing = false
            c.resumePositionStore.save(ResumePosition(readyEpisode.resumeKey, 0, 405_000L, 1f))
            WidgetUpdater.update(app, c = c)
            view = renderedView(widgetId)
            assertEquals("7 min · Played", view.findViewById<TextView>(R.id.widget_detail).text.toString())
            assertEquals("▶ Play", view.findViewById<TextView>(R.id.widget_action1).text.toString())

            // 5. Two newer jobs in progress -> "7 min · Making… (2 in progress)" while keeping ready episode playable
            c.podcastSettings.save(SavedPodcast(PodcastJob("ep-q1", "queued", title = "Job 1", kind = "chapter", style = "deep-dive", createdAt = "2026-10-31T11:00:00Z"), BuildConfig.API_BASE, accountId = "owner"))
            c.podcastSettings.save(SavedPodcast(PodcastJob("ep-q2", "writing", title = "Job 2", kind = "chapter", style = "deep-dive", createdAt = "2026-10-31T11:05:00Z"), BuildConfig.API_BASE, accountId = "owner"))
            WidgetUpdater.update(app, c = c)
            view = renderedView(widgetId)
            assertEquals("Hebrews 12:1-3 — Faith", view.findViewById<TextView>(R.id.widget_title).text.toString())
            assertEquals("7 min · Making… (2 in progress)", view.findViewById<TextView>(R.id.widget_detail).text.toString())
            assertEquals(View.VISIBLE, view.findViewById<View>(R.id.widget_action1).visibility)

            // 6. Tapping title opens Lounge; tapping "+ New podcast" opens "new-podcast"
            shadowOf(app).clearNextStartedActivities()
            view.findViewById<View>(R.id.widget_title).performClick()
            val loungeIntent = shadowOf(app).nextStartedActivity
            assertEquals(MainActivity.LOUNGE, loungeIntent.getStringExtra(MainActivity.WIDGET_DESTINATION))

            view.findViewById<View>(R.id.widget_action2).performClick()
            val newPodcastIntent = shadowOf(app).nextStartedActivity
            assertEquals("new-podcast", newPodcastIntent.getStringExtra(MainActivity.WIDGET_DESTINATION))
            assertTrue(widgetDestinationAllowed("new-podcast"))

            // 7. Compact 3x1 resize hides heading so title, detail, and controls fit in one row
            AppWidgetManager.getInstance(app).updateAppWidgetOptions(widgetId, Bundle().apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 60)
            })
            WidgetUpdater.update(app, c = c)
            view = renderedView(widgetId)
            assertEquals(View.GONE, view.findViewById<View>(R.id.widget_heading).visibility)
            assertEquals(View.VISIBLE, view.findViewById<View>(R.id.widget_title).visibility)
            assertEquals(View.VISIBLE, view.findViewById<View>(R.id.widget_action2).visibility)
        } finally {
            dsScope.cancel()
        }
    }

    @Test
    fun newPodcastDraftOpensDirectlyOnSourcePickerCardsSheet() {
        val cipher = object : SecretCipher {
            override fun encrypt(plain: ByteArray, associatedData: ByteArray) = plain
            override fun decrypt(sealed: ByteArray, associatedData: ByteArray) = sealed
        }
        val fakeGateway: suspend (okhttp3.Request) -> Response = { req ->
            val body = when (req.url.encodedPath) {
                "/api/podcast/list" -> """{"jobs":[],"cursor":null}"""
                "/api/podcast/subscription/episodes" -> """{"episodes":[]}"""
                else -> "{}"
            }
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }
        val dsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val container = AppContainer(
            app,
            onlineOverride = { true },
            podcastTransport = fakeGateway,
            customCipher = cipher,
            customSettingsDataStore = PreferenceDataStoreFactory.create(scope = dsScope) { temp.newFile("s1.preferences_pb") },
            customSecureDataStore = PreferenceDataStoreFactory.create(scope = dsScope) { temp.newFile("s2.preferences_pb") },
        )
        val playback = PlaybackController(app, connect = false)
        try {
            runBlocking { container.secureStore.write(Secret.OWNER_ACCESS_CODE, "fixture-owner") }
            compose.setContent {
                MaterialTheme {
                    PodcastLoungeScreen(
                        container = container,
                        playback = playback,
                        initial = PodcastDraft(pickSource = true),
                        onSettings = {},
                        onBack = {},
                        onFirstPlay = {},
                    )
                }
            }
            compose.waitUntil(5_000) {
                compose.onAllNodes(androidx.compose.ui.test.hasTestTag("new_podcast_sheet")).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("new_podcast_sheet").assertIsDisplayed()
            compose.onNodeWithText("Make podcast").assertDoesNotExist()
        } finally {
            playback.close()
            dsScope.cancel()
        }
    }

    @Test
    fun continueReadingWidgetShowsCoverProgressListenGateAndCyclesRecentAdultBooks() = runBlocking {
        val c = app.container
        val epubBook1 = bookEntity("gutenberg:1342", "1342", "Pride and Prejudice", updatedAt = 3_000L)
        val printOnlyBook2 = BookEntity(
            key = "openlibrary:OL1W",
            source = "OPEN_LIBRARY",
            sourceId = "OL1W",
            title = "Modern Print Book",
            authors = "Author",
            coverUrl = null,
            formats = "Print or buy|false",
            year = 2020,
            languages = "en",
            epubUrl = null,
            sourceUrl = null,
            updatedAt = 2_000L,
        )
        val epubBook3 = bookEntity("gutenberg:84", "84", "Frankenstein", updatedAt = 1_500L)
        val olderBook4 = bookEntity("gutenberg:11", "11", "Older Fourth Book", updatedAt = 500L)
        val kidsStory = bookEntity("gutenberg:14838", "14838", "The Tale of Peter Rabbit", updatedAt = 9_000L)

        listOf(epubBook1, printOnlyBook2, epubBook3, olderBook4, kidsStory).forEach { c.database.books().upsert(it) }
        c.database.readingProgress().put(ReadingProgressEntity(epubBook1.key, "epubcfi(/6/8!/4/2)", 0.42f, 3_000L))
        c.database.readingProgress().put(ReadingProgressEntity(printOnlyBook2.key, null, 0.15f, 2_000L))
        c.database.readingProgress().put(ReadingProgressEntity(epubBook3.key, "epubcfi(/6/4!/4/1)", 0.80f, 1_500L))
        c.settings.setLastOpenedBook(epubBook1.key)

        val widgetId = 202
        bindWidget(ContinueReadingWidget::class.java, widgetId)
        WidgetUpdater.update(app)

        // Book 1: cover + title + 42% progress bar + Listen visible + saved-position route
        var view = renderedView(widgetId)
        assertEquals(View.VISIBLE, view.findViewById<View>(R.id.widget_cover).visibility)
        assertEquals("Pride and Prejudice", view.findViewById<TextView>(R.id.widget_title).text.toString())
        assertEquals("42% read", view.findViewById<TextView>(R.id.widget_detail).text.toString())
        val progress = view.findViewById<ProgressBar>(R.id.widget_progress)
        assertEquals(View.VISIBLE, progress.visibility)
        assertEquals(1000, progress.max)
        assertEquals(420, progress.progress)
        assertEquals(View.VISIBLE, view.findViewById<View>(R.id.widget_action1).visibility)
        assertEquals("▶ Listen", view.findViewById<TextView>(R.id.widget_action1).text.toString())
        assertEquals("Next book →", view.findViewById<TextView>(R.id.widget_action2).text.toString())

        shadowOf(app).clearNextStartedActivities()
        view.findViewById<View>(R.id.widget_title).performClick()
        assertEquals("read:gutenberg%3A1342", shadowOf(app).nextStartedActivity.getStringExtra(MainActivity.WIDGET_DESTINATION))
        assertEquals(
            "#/read/gutenberg%3A1342?at=epubcfi(%2F6%2F8!%2F4%2F2)",
            readingWidgetRoute(epubBook1, "epubcfi(/6/8!/4/2)", listen = false),
        )

        view.findViewById<View>(R.id.widget_action1).performClick()
        assertEquals("listen:gutenberg%3A1342", shadowOf(app).nextStartedActivity.getStringExtra(MainActivity.WIDGET_DESTINATION))
        assertEquals(
            "#/read/gutenberg%3A1342?at=epubcfi(%2F6%2F8!%2F4%2F2)&listen=1",
            readingWidgetRoute(epubBook1, "epubcfi(/6/8!/4/2)", listen = true),
        )

        // Cycle to Book 2 (print-only): Listen button must be hidden!
        val cycleIntent = Intent(app, ContinueReadingWidget::class.java)
            .setAction(WidgetUpdater.NEXT_BOOK)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        assertTrue(WidgetUpdater.advanceSelection(app, ContinueReadingWidget::class.java, cycleIntent))
        WidgetUpdater.update(app)
        view = renderedView(widgetId)
        assertEquals("Modern Print Book", view.findViewById<TextView>(R.id.widget_title).text.toString())
        assertEquals("15% read", view.findViewById<TextView>(R.id.widget_detail).text.toString())
        assertEquals(View.GONE, view.findViewById<View>(R.id.widget_action1).visibility)

        // Cycle to Book 3: Frankenstein (80% read, Listen visible)
        assertTrue(WidgetUpdater.advanceSelection(app, ContinueReadingWidget::class.java, cycleIntent))
        WidgetUpdater.update(app)
        view = renderedView(widgetId)
        assertEquals("Frankenstein", view.findViewById<TextView>(R.id.widget_title).text.toString())
        assertEquals("80% read", view.findViewById<TextView>(R.id.widget_detail).text.toString())
        assertEquals(View.VISIBLE, view.findViewById<View>(R.id.widget_action1).visibility)

        // Cycle wraps back to Book 1 (never shows 4th older book or Kids book)
        assertTrue(WidgetUpdater.advanceSelection(app, ContinueReadingWidget::class.java, cycleIntent))
        WidgetUpdater.update(app)
        view = renderedView(widgetId)
        assertEquals("Pride and Prejudice", view.findViewById<TextView>(R.id.widget_title).text.toString())

        // Also verify 2-book list alternates cleanly across all 6 steps without modulo stutter
        val twoBooks = recentReadingBooks(listOf(epubBook1, printOnlyBook2, kidsStory), epubBook1.key)
        assertEquals(listOf(epubBook1.key, printOnlyBook2.key), twoBooks.map { it.key })
        WidgetUpdater.resetBookSelection(app)
        val seen = (0 until 6).map {
            val idx = app.getSharedPreferences("widget-selections", Context.MODE_PRIVATE).getInt("${WidgetUpdater.NEXT_BOOK}/$widgetId", 0)
            val picked = twoBooks[idx % twoBooks.size].key
            WidgetUpdater.advanceSelection(app, ContinueReadingWidget::class.java, cycleIntent)
            picked
        }
        assertEquals(
            listOf(epubBook1.key, printOnlyBook2.key, epubBook1.key, printOnlyBook2.key, epubBook1.key, printOnlyBook2.key),
            seen,
        )
    }

    @Test
    fun verseWidgetReadLessonShareAndAnotherVerse() = runBlocking {
        val widgetId = 303
        bindWidget(VerseOfDayWidget::class.java, widgetId)
        // Day 16 is Psalms 23:1
        val psalm23 = VerseOfDay.forDay(16)
        assertEquals("Psalms 23:1", psalm23.reference)

        WidgetUpdater.update(app, todayDayOfYear = 16)
        var view = renderedView(widgetId)
        assertEquals("Verse of the day", view.findViewById<TextView>(R.id.widget_heading).text.toString())
        assertEquals(psalm23.text, view.findViewById<TextView>(R.id.widget_title).text.toString())
        assertEquals("Psalms 23:1", view.findViewById<TextView>(R.id.widget_detail).text.toString())
        assertEquals("Read", view.findViewById<TextView>(R.id.widget_action1).text.toString())
        assertEquals("Lesson", view.findViewById<TextView>(R.id.widget_action2).text.toString())
        assertEquals("Share", view.findViewById<TextView>(R.id.widget_action3).text.toString())
        assertEquals("⟳", view.findViewById<TextView>(R.id.widget_action4).text.toString())

        // Read button opens Bible at that exact verse
        shadowOf(app).clearNextStartedActivities()
        view.findViewById<View>(R.id.widget_action1).performClick()
        assertEquals("#/bible/Ps/23?v=1&t=kjv", shadowOf(app).nextStartedActivity.getStringExtra(MainActivity.WIDGET_DESTINATION))

        // Lesson button opens New podcast with that chapter pre-filled as a Bible lesson (same path as Bible mic)
        view.findViewById<View>(R.id.widget_action2).performClick()
        val lessonDestination = shadowOf(app).nextStartedActivity.getStringExtra(MainActivity.WIDGET_DESTINATION)
        assertEquals("lesson:#/bible/Ps/23?v=1&t=kjv", lessonDestination)
        assertTrue(widgetDestinationAllowed(lessonDestination!!))
        val draft = widgetLessonDraft(app, psalm23.route)
        assertNotNull(draft)
        assertEquals("chapter", draft!!.kind)
        assertEquals("Bible - Psalm 23", draft.title)
        assertTrue("Unexpected draft text: ${draft.text.take(80)}", draft.text.startsWith("Psalm 23 (KJV)\n\n1 The Lord is my shepherd; I shall not want."))

        // Share button opens Android share sheet with verse text + reference (and no custom scheme URI breaking ChooserActivity)
        view.findViewById<View>(R.id.widget_action3).performClick()
        val chooser = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertNull("Implicit ACTION_CHOOSER must not carry a custom data URI", chooser.data)
        val send = IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java)
        assertNotNull(send)
        assertEquals(Intent.ACTION_SEND, send!!.action)
        assertEquals("text/plain", send.type)
        assertEquals("${psalm23.text} — ${psalm23.reference}", send.getStringExtra(Intent.EXTRA_TEXT))

        // ⟳ cycles to another verse (Day 17 = Psalms 23:4)
        val cycleIntent = Intent(app, VerseOfDayWidget::class.java)
            .setAction(WidgetUpdater.NEXT_VERSE)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        assertTrue(WidgetUpdater.advanceSelection(app, VerseOfDayWidget::class.java, cycleIntent))
        WidgetUpdater.update(app, todayDayOfYear = 16)
        view = renderedView(widgetId)
        assertEquals(VerseOfDay.forDay(17).text, view.findViewById<TextView>(R.id.widget_title).text.toString())
        assertEquals("Psalms 23:4", view.findViewById<TextView>(R.id.widget_detail).text.toString())
    }

    @Test
    fun allPendingIntentsAreImmutableWithUniqueRequestCodesAndKidsModeSuppressesContent() = runBlocking {
        val verse = VerseOfDay.forDay(1)
        val book = bookEntity("gutenberg:1342", "1342", "Pride and Prejudice")
        val saved = SavedPodcast(PodcastJob("ep-1", "ready", title = "Episode", kind = "chapter", style = "deep-dive", durationSec = 300), BuildConfig.API_BASE, accountId = "owner")

        // Collect every PendingIntent created by WidgetUpdater helpers across 3 distinct widget IDs
        val intents = listOf(
            WidgetUpdater.activity(app, 11, 0, verse.route),
            WidgetUpdater.activity(app, 11, 1, verse.route),
            WidgetUpdater.activity(app, 11, 2, "lesson:${verse.route}"),
            WidgetUpdater.activity(app, 11, 6, verse.route),
            WidgetUpdater.cycle(app, 11, false),
            WidgetUpdater.activity(app, 12, 0, "read:gutenberg%3A1342"),
            WidgetUpdater.activity(app, 12, 1, "listen:gutenberg%3A1342"),
            WidgetUpdater.activity(app, 12, 5, "read:gutenberg%3A1342"),
            WidgetUpdater.activity(app, 12, 6, "read:gutenberg%3A1342"),
            WidgetUpdater.cycle(app, 12, true),
            WidgetUpdater.activity(app, 13, 0, MainActivity.LOUNGE),
            WidgetUpdater.activity(app, 13, 2, "new-podcast"),
            WidgetUpdater.activity(app, 13, 6, MainActivity.LOUNGE),
            PodcastPlaybackAction.pending(app, saved.job.id, true),
        )
        intents.forEach { pi -> assertTrue("PendingIntent must be immutable", pi.isImmutable) }
        val requestCodes = intents.map { shadowOf(it).requestCode }
        assertEquals("Every PendingIntent must have a unique requestCode", intents.size, requestCodes.toSet().size)

        // Verify verseView, readingView, podcastView inflate cleanly without crashing
        listOf(
            WidgetUpdater.verseView(app, 11, verse),
            WidgetUpdater.readingView(app, 12, book, 0.5f),
            WidgetUpdater.podcastView(app, 13, saved, 0, false),
        ).forEach { rv -> assertNotNull(rv.apply(app, FrameLayout(app))) }

        // Destination validation rejects untrusted or control-character routes
        assertFalse(widgetDestinationAllowed("https://evil.example"))
        assertFalse(widgetDestinationAllowed("lesson:#/bible/Evil/1"))
        assertFalse(widgetDestinationAllowed("read:%00bad"))
        assertFalse(widgetDestinationAllowed("listen:"))

        // Kids mode suppresses all 3 widgets to neutral "Mavis Library / Open Mavis"
        bindWidget(PodcastLoungeWidget::class.java, 401)
        bindWidget(ContinueReadingWidget::class.java, 402)
        bindWidget(VerseOfDayWidget::class.java, 403)
        val prefs = app.kidsPrefs()
        prefs.start("Sam", 15, "1234")
        try {
            WidgetUpdater.update(app)
            for (id in listOf(401, 402, 403)) {
                val v = renderedView(id)
                assertEquals("Mavis Library", v.findViewById<TextView>(R.id.widget_heading).text.toString())
                assertEquals("Open Mavis", v.findViewById<TextView>(R.id.widget_title).text.toString())
                assertEquals("", v.findViewById<TextView>(R.id.widget_detail).text.toString())
                assertEquals(View.GONE, v.findViewById<View>(R.id.widget_action1).visibility)
                assertEquals(View.GONE, v.findViewById<View>(R.id.widget_action2).visibility)
            }
        } finally {
            assertEquals(PinResult.Correct, prefs.leave("1234"))
        }
    }
}
