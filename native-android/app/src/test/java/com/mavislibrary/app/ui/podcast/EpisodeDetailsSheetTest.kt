package com.mavislibrary.app.ui.podcast

import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.content.IntentCompat
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.mavislibrary.app.AppContainer
import com.mavislibrary.app.audio.PlaybackController
import com.mavislibrary.app.data.podcast.FakeDownloadsMediaProvider
import com.mavislibrary.app.data.podcast.PodcastJob
import com.mavislibrary.app.data.podcast.SAVED_MP3_SNACKBAR
import com.mavislibrary.app.data.podcast.fakeSignedAudio
import com.mavislibrary.app.data.podcast.resetShareFileProvider
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
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EpisodeDetailsSheetTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temp = TemporaryFolder()

    @Before
    fun setUp() {
        resetShareFileProvider(RuntimeEnvironment.getApplication())
    }

    @After
    fun tearDown() {
        resetShareFileProvider(RuntimeEnvironment.getApplication())
    }

    private fun job(status: String) = PodcastJob(
        id = "ep1",
        status = status,
        title = "Psalm 23 - Bible lesson",
        kind = "chapter",
        style = "deep-dive",
        durationSec = 600,
        createdAt = "2026-10-30T10:00:00Z",
    )

    @Test
    fun finishedEpisodeShowsSaveMp3AndShareActions() {
        var dismissed = 0
        var saved = 0
        var shared = 0
        compose.setContent {
            MaterialTheme {
                EpisodeDetailsSheet(
                    job = job("ready"),
                    onDismiss = { dismissed++ },
                    onPlay = {},
                    onSaveMp3 = { saved++ },
                    onShare = { shared++ },
                )
            }
        }
        compose.onNodeWithTag("episode_details_play").assertIsEnabled()
        compose.onNodeWithTag("episode_details_save_mp3").assertExists().assertIsEnabled().assertTextContains("Save MP3")
        compose.onNodeWithTag("episode_details_share").assertExists().assertIsEnabled().assertTextContains("Share")

        compose.onNodeWithTag("episode_details_save_mp3").performClick()
        assertEquals(1, saved)
        assertEquals(1, dismissed)

        compose.onNodeWithTag("episode_details_share").performClick()
        assertEquals(1, shared)
        assertEquals(2, dismissed)
    }

    @Test
    fun unfinishedAndFailedEpisodesHideSaveMp3AndShare() {
        val status = androidx.compose.runtime.mutableStateOf("writing")
        compose.setContent {
            MaterialTheme {
                EpisodeDetailsSheet(
                    job = job(status.value),
                    onDismiss = {},
                    onPlay = {},
                    onSaveMp3 = {},
                    onShare = {},
                )
            }
        }
        compose.onNodeWithTag("episode_details_play").assertIsNotEnabled()
        compose.onNodeWithTag("episode_details_save_mp3").assertDoesNotExist()
        compose.onNodeWithTag("episode_details_share").assertDoesNotExist()

        status.value = "failed"
        compose.waitForIdle()
        compose.onNodeWithTag("episode_details_play").assertIsNotEnabled()
        compose.onNodeWithTag("episode_details_save_mp3").assertDoesNotExist()
        compose.onNodeWithTag("episode_details_share").assertDoesNotExist()
    }

    @Test
    fun loungeSaveMp3ShowsSnackbarWithOpenAndShareLaunchesAudioChooser() {
        val app = RuntimeEnvironment.getApplication()
        val media = Robolectric.setupContentProvider(FakeDownloadsMediaProvider::class.java, MediaStore.AUTHORITY)
        Shadows.shadowOf(app).clearNextStartedActivities()
        val rawMp3 = "ID3-TIT2:Psalm 23 - Bible lesson|TPE1:Mavis Podcast Lounge|INTRO|AUDIO|OUTRO".toByteArray()
        val jobsJson = """{"jobs":[
            {"id":"ep1","status":"ready","title":"Psalm 23 - Bible lesson","kind":"chapter","style":"deep-dive","durationSec":600,"createdAt":"2026-10-30T10:00:00Z"},
            {"id":"ep2","status":"ready","title":"John 3 - Bible lesson","kind":"chapter","style":"deep-dive","durationSec":540,"createdAt":"2026-10-30T09:00:00Z"}
        ],"cursor":null}"""
        val fakeGateway: suspend (okhttp3.Request) -> Response = { req ->
            fakeSignedAudio(req) ?: when (req.url.encodedPath) {
                "/api/podcast/list" -> Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(jobsJson.toResponseBody("application/json".toMediaType())).build()
                "/api/podsub/today" -> Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body("[]".toResponseBody("application/json".toMediaType())).build()
                "/api/podsub/status" -> Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body("""{"meter":"0 of 3 today","episodes":[]}""".toResponseBody("application/json".toMediaType())).build()
                else -> if (req.url.encodedPath.endsWith("/audio")) {
                    Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                        .body(rawMp3.toResponseBody("audio/mpeg".toMediaType())).build()
                } else {
                    Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                        .body("{}".toResponseBody("application/json".toMediaType())).build()
                }
            }
        }
        val cipher = object : SecretCipher {
            override fun encrypt(plain: ByteArray, associatedData: ByteArray) = plain
            override fun decrypt(sealed: ByteArray, associatedData: ByteArray) = sealed
        }
        val dsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val settingsDs = PreferenceDataStoreFactory.create(scope = dsScope) { temp.newFile("settings.preferences_pb") }
        val secureDs = PreferenceDataStoreFactory.create(scope = dsScope) { temp.newFile("secure.preferences_pb") }
        val container = AppContainer(
            app,
            onlineOverride = { true },
            podcastTransport = fakeGateway,
            customCipher = cipher,
            customSettingsDataStore = settingsDs,
            customSecureDataStore = secureDs,
        )
        val playback = PlaybackController(app, connect = false)
        try {
            runBlocking { container.secureStore.write(Secret.OWNER_ACCESS_CODE, "fixture-owner") }
            compose.setContent {
                MaterialTheme {
                    PodcastLoungeScreen(
                        container = container,
                        playback = playback,
                        initial = null,
                        onSettings = {},
                        onBack = {},
                        onFirstPlay = {},
                    )
                }
            }
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("Studio connected").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("podcast_lounge").performScrollToKey("records")
            compose.onNodeWithTag("podcast_lounge").performTouchInput { swipeUp() }
            // Open details sheet -> tap Save MP3 -> snackbar "Saved to Downloads/Mavis Podcasts" with Open action.
            compose.onNodeWithTag("record_info").performClick()
            compose.onNodeWithTag("episode_details_save_mp3").performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText(SAVED_MP3_SNACKBAR).fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals("Psalm 23 - Bible lesson.mp3", media.entries.values.single().values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME))
            assertArrayEquals(rawMp3, media.entries.values.single().file.readBytes())

            compose.onNodeWithText("Open").performClick()
            compose.waitForIdle()
            val opened = Shadows.shadowOf(app).nextStartedActivity
            assertNotNull(opened)
            assertEquals(Intent.ACTION_VIEW, opened.action)
            assertEquals("audio/mpeg", opened.type)
            assertNotEquals(0, opened.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)

            // Share from Up next row overflow menu -> launches ACTION_CHOOSER wrapping ACTION_SEND audio/mpeg.
            Shadows.shadowOf(app).clearNextStartedActivities()
            compose.onNodeWithTag("podcast_lounge").performTouchInput { swipeUp() }
            compose.waitForIdle()
            compose.onNodeWithTag("record_next_menu_ep2").performClick()
            compose.waitForIdle()
            compose.onNodeWithText("Share").performClick()
            compose.waitUntil(5_000) {
                Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                Shadows.shadowOf(app).peekNextStartedActivity() != null
            }
            val chooser = Shadows.shadowOf(app).nextStartedActivity
            assertNotNull(chooser)
            assertEquals(Intent.ACTION_CHOOSER, chooser.action)
            val send = IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java)!!
            assertEquals(Intent.ACTION_SEND, send.action)
            assertEquals("audio/mpeg", send.type)
            assertEquals("John 3 - Bible lesson", send.getStringExtra(Intent.EXTRA_SUBJECT))
            assertEquals("John 3 - Bible lesson — from Mavis Library", send.getStringExtra(Intent.EXTRA_TEXT))
            val sharedUri = IntentCompat.getParcelableExtra(send, Intent.EXTRA_STREAM, Uri::class.java)!!
            assertEquals("content", sharedUri.scheme)
        } finally {
            playback.close()
            container.database.close()
            container.scope.cancel()
            dsScope.cancel()
        }
    }
}
