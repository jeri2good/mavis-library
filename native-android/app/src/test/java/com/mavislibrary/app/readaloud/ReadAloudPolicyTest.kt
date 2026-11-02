package com.mavislibrary.app.readaloud

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ReadAloudPolicyTest {
    @get:Rule val tmp = TemporaryFolder()
    private val phoneVoices = listOf(PhoneVoice("net-en", "en-US", false), PhoneVoice("he-il", "he-IL", true), PhoneVoice("local-en", "en-US", true))

    @Test fun kokoroIsTheDefaultOnlyOnceDownloaded() {
        assertEquals(VoiceEngineKind.PHONE, defaultEngine(ReadAloudSettings(), naturalInstalled = false))
        assertEquals(VoiceEngineKind.NATURAL, defaultEngine(ReadAloudSettings(), naturalInstalled = true))
        assertEquals(VoiceEngineKind.PHONE, defaultEngine(ReadAloudSettings(VoiceEngineKind.PHONE), naturalInstalled = true))
        // Kids mode and removed models land here too: no prompt, just the phone voice.
        assertEquals(VoiceEngineKind.PHONE, defaultEngine(ReadAloudSettings(VoiceEngineKind.NATURAL), naturalInstalled = false))
    }

    @Test fun namedVoicesAndForeignLanguagesRoute() {
        val auto = ReadAloudSettings()
        assertEquals(VoiceEngineKind.NATURAL, engineFor("kokoro:bm_george", "en-GB", ReadAloudSettings(VoiceEngineKind.PHONE), true))
        assertEquals(VoiceEngineKind.PHONE, engineFor("kokoro:bm_george", "en-GB", auto, false))
        assertEquals(VoiceEngineKind.PHONE, engineFor("android:local-en", "en-US", auto, true))
        assertEquals(VoiceEngineKind.PHONE, engineFor(null, "he", auto, true))
        assertEquals(VoiceEngineKind.NATURAL, engineFor(null, "en-US", auto, true))
        assertEquals(VoiceEngineKind.NATURAL, engineFor(null, "", auto, true))
    }

    @Test fun getVoicesPutsTheChosenDefaultFirst() {
        val natural = voiceList(ReadAloudSettings(naturalVoice = "bf_emma"), true, phoneVoices)
        assertEquals("kokoro:bf_emma", natural.first().uri)
        assertEquals(listOf("kokoro:bf_emma"), natural.filter { it.isDefault }.map { it.uri })
        assertEquals("Natural — Emma (Kokoro)", natural.first().name)
        assertEquals(NATURAL_VOICES.size + phoneVoices.size, natural.size)

        val phone = voiceList(ReadAloudSettings(VoiceEngineKind.PHONE), true, phoneVoices)
        assertEquals(listOf("android:local-en", "android:he-il", "android:net-en"), phone.take(3).map { it.uri })
        assertEquals(listOf("android:local-en"), phone.filter { it.isDefault }.map { it.uri })

        val absent = voiceList(ReadAloudSettings(), false, phoneVoices)
        assertTrue(absent.none { it.uri.startsWith(NATURAL_PREFIX) })
    }

    @Test fun speakerIdsMatchTheModelTable() {
        assertEquals(mapOf("af_heart" to 3, "af_bella" to 2, "am_michael" to 16, "bf_emma" to 21, "bm_george" to 26), NATURAL_VOICES.associate { it.id to it.speakerId })
    }

    @Test fun choicesPersistAcrossStoreInstances() = runTest {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val file = tmp.newFile("read_aloud.preferences_pb")
        file.delete()
        val prefs = ReadAloudPrefs(PreferenceDataStoreFactory.create(scope = scope.backgroundScope) { file })
        assertEquals(ReadAloudSettings(), prefs.settings.first())
        prefs.setEngine(VoiceEngineKind.PHONE)
        prefs.setNaturalVoice("am_michael")
        assertEquals(ReadAloudSettings(VoiceEngineKind.PHONE, "am_michael"), prefs.settings.first())
        // An unknown stored voice falls back rather than failing later at speak time.
        prefs.setNaturalVoice("zz_gone")
        assertEquals(DEFAULT_NATURAL_VOICE, prefs.settings.first().naturalVoice)
    }

    @Test fun parsesShimMessagesAndRejectsTheRest() {
        val speak = parseSpeechCommand("""{"op":"speak","id":7,"text":"Hi","voice":null,"lang":"en","rate":1.5,"pitch":1,"volume":0.5}""") as SpeechCommand.Speak
        assertEquals(SpeechRequest(7, "Hi", null, "en", 1.5f, 1f, .5f), speak.request)
        assertEquals("kokoro:af_heart", (parseSpeechCommand("""{"op":"speak","id":7,"text":"Hi","voice":"kokoro:af_heart"}""") as SpeechCommand.Speak).request.voiceUri)
        assertEquals(SpeechCommand.Cancel, parseSpeechCommand("""{"op":"cancel"}"""))
        assertNull(parseSpeechCommand("not json"))
        assertNull(parseSpeechCommand("""{"op":"nuke"}"""))
        assertNull(parseSpeechCommand("""{"op":"speak","id":1,"text":"${"x".repeat(32_768)}"}"""))
    }

    @Test fun eventsSerializeForTheShim() {
        val boundary = JSONObject(speechEventJson(SpeechEvent.Boundary(4, "word", 2, 3)))
        assertEquals("boundary", boundary.getString("ev"))
        assertEquals(2, boundary.getInt("charIndex"))
        assertEquals("interrupted", JSONObject(speechEventJson(SpeechEvent.Error(4, "interrupted"))).getString("error"))
        assertFalse(JSONObject(voicesJson(voiceList(ReadAloudSettings(), false, phoneVoices))).getJSONArray("voices").length() == 0)
    }
}
