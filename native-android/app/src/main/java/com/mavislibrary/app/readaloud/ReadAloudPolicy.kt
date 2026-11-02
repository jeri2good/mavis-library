package com.mavislibrary.app.readaloud

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class VoiceEngineKind { NATURAL, PHONE }

data class NaturalVoice(val id: String, val speakerId: Int, val label: String, val lang: String) {
    val uri get() = NATURAL_PREFIX + id
    val displayName get() = "Natural — $label (Kokoro)"
}

const val NATURAL_PREFIX = "kokoro:"
const val DEFAULT_NATURAL_VOICE = "af_heart"

/** Speaker ids from the sherpa-onnx kokoro-int8-multi-lang-v1_0 speaker table. */
val NATURAL_VOICES = listOf(
    NaturalVoice("af_heart", 3, "Heart", "en-US"),
    NaturalVoice("af_bella", 2, "Bella", "en-US"),
    NaturalVoice("am_michael", 16, "Michael", "en-US"),
    NaturalVoice("bf_emma", 21, "Emma", "en-GB"),
    NaturalVoice("bm_george", 26, "George", "en-GB"),
)

/** [engine] null means nobody has chosen: Kokoro if downloaded, otherwise the phone voice. */
data class ReadAloudSettings(val engine: VoiceEngineKind? = null, val naturalVoice: String = DEFAULT_NATURAL_VOICE)

class ReadAloudPrefs(private val store: DataStore<Preferences>) {
    private val engineKey = stringPreferencesKey("read_aloud_engine")
    private val voiceKey = stringPreferencesKey("read_aloud_natural_voice")
    val settings: Flow<ReadAloudSettings> = store.data.map { p ->
        ReadAloudSettings(
            VoiceEngineKind.entries.find { it.name == p[engineKey] },
            p[voiceKey]?.takeIf { saved -> NATURAL_VOICES.any { it.id == saved } } ?: DEFAULT_NATURAL_VOICE,
        )
    }
    suspend fun setEngine(engine: VoiceEngineKind) { store.edit { it[engineKey] = engine.name } }
    suspend fun setNaturalVoice(id: String) { store.edit { it[voiceKey] = id } }
}

/** The engine used when an utterance names no voice. A chosen natural voice can't play until its model is downloaded. */
fun defaultEngine(settings: ReadAloudSettings, naturalInstalled: Boolean): VoiceEngineKind =
    if (naturalInstalled && settings.engine != VoiceEngineKind.PHONE) VoiceEngineKind.NATURAL else VoiceEngineKind.PHONE

/** Kokoro is configured for English, so other languages without a named voice go to the phone. */
fun engineFor(voiceUri: String?, lang: String, settings: ReadAloudSettings, naturalInstalled: Boolean): VoiceEngineKind = when {
    voiceUri?.startsWith(NATURAL_PREFIX) == true && naturalInstalled -> VoiceEngineKind.NATURAL
    voiceUri?.startsWith(PhoneVoiceEngine.PHONE_PREFIX) == true -> VoiceEngineKind.PHONE
    lang.isNotBlank() && !lang.lowercase().startsWith("en") -> VoiceEngineKind.PHONE
    else -> defaultEngine(settings, naturalInstalled)
}

data class ShimVoice(val uri: String, val name: String, val lang: String, val isDefault: Boolean)

/**
 * The list behind getVoices(). The web screens take the first local English voice unless one was saved, so the
 * default engine's voices come first; offline phone voices come before network ones.
 */
fun voiceList(
    settings: ReadAloudSettings,
    naturalInstalled: Boolean,
    phoneVoices: List<PhoneVoice>,
    deviceLang: String = java.util.Locale.getDefault().toLanguageTag(),
): List<ShimVoice> {
    val default = defaultEngine(settings, naturalInstalled)
    val natural = if (!naturalInstalled) emptyList() else {
        val chosen = NATURAL_VOICES.filter { it.id == settings.naturalVoice }
        (chosen + (NATURAL_VOICES - chosen.toSet())).map { ShimVoice(it.uri, it.displayName, it.lang, default == VoiceEngineKind.NATURAL && it.id == settings.naturalVoice) }
    }
    // Seen on device: name order put en-AU first, so a US phone read in an Australian voice. Prefer the phone's own
    // locale, then en-US, then any English.
    val tag = deviceLang.replace('_', '-').lowercase()
    fun langRank(lang: String): Int { val l = lang.replace('_', '-').lowercase(); return when {
        l == tag -> 0; l == "en-us" -> 1; l.startsWith("en") -> 2; else -> 3 } }
    val phone = phoneVoices.sortedWith(compareBy({ !it.offline }, { langRank(it.lang) }, { it.name }))
        .mapIndexed { index, voice -> ShimVoice(PhoneVoiceEngine.PHONE_PREFIX + voice.name, voice.name, voice.lang, default == VoiceEngineKind.PHONE && index == 0) }
    return if (default == VoiceEngineKind.NATURAL) natural + phone else phone + natural
}
