package com.mavislibrary.app.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.mavislibrary.app.ui.theme.LookChoice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalTime

/** Non-secret app settings (look). Secrets live in [com.mavislibrary.app.data.secure.SecureStore]. */
class SettingsStore(private val store: DataStore<Preferences>) {
    val lookChoice: Flow<LookChoice> = store.data.map { LookChoice.parse(it[LOOK]) }
    val matchRoomLight: Flow<Boolean> = store.data.map { it[MATCH_ROOM_LIGHT] ?: false }
    val lastOpenedBook: Flow<String?> = store.data.map { it[LAST_OPENED_BOOK] }
    val reminder: Flow<ReminderSettings> = store.data.map { prefs ->
        ReminderSettings(prefs[REMINDER_ENABLED] ?: false, LocalTime.of((prefs[REMINDER_HOUR] ?: 20).coerceIn(0, 23), (prefs[REMINDER_MINUTE] ?: 0).coerceIn(0, 59)))
    }
    suspend fun setReminderEnabled(enabled: Boolean) = store.edit { it[REMINDER_ENABLED] = enabled }
    suspend fun setReminderTime(time: LocalTime) = store.edit { it[REMINDER_HOUR] = time.hour; it[REMINDER_MINUTE] = time.minute }
    suspend fun setLastOpenedBook(key: String) = store.edit { it[LAST_OPENED_BOOK] = key }
    suspend fun setMatchRoomLight(enabled: Boolean) = store.edit { it[MATCH_ROOM_LIGHT] = enabled }
    val playback: Flow<PlaybackSettings> = store.data.map { prefs ->
        PlaybackSettings(
            defaultSleepMinutes = prefs[DEFAULT_SLEEP] ?: 30,
            driveModeEnabled = prefs[DRIVE_MODE] ?: false,
            volumePageTurns = prefs[VOLUME_PAGE_TURNS] ?: false,
            headsetPageTurns = prefs[HEADSET_PAGE_TURNS] ?: true,
            hapticPageTurns = prefs[HAPTIC_PAGE_TURNS] ?: true,
            defaultSpeed = (prefs[DEFAULT_SPEED] ?: 1f).coerceIn(.75f, 2f),
        )
    }

    suspend fun setLook(choice: LookChoice) {
        store.edit { it[LOOK] = choice.storedValue() }
    }

    suspend fun setDefaultSleep(minutes: Int) = store.edit { it[DEFAULT_SLEEP] = minutes.takeIf { n -> n in setOf(15, 30, 45, 60) } ?: 30 }
    suspend fun setDriveMode(enabled: Boolean) = store.edit { it[DRIVE_MODE] = enabled }
    suspend fun setVolumePageTurns(enabled: Boolean) = store.edit { it[VOLUME_PAGE_TURNS] = enabled }
    suspend fun setHeadsetPageTurns(enabled: Boolean) = store.edit { it[HEADSET_PAGE_TURNS] = enabled }
    suspend fun setHapticPageTurns(enabled: Boolean) = store.edit { it[HAPTIC_PAGE_TURNS] = enabled }
    suspend fun setDefaultSpeed(speed: Float) = store.edit { it[DEFAULT_SPEED] = speed.coerceIn(.75f, 2f) }

    private companion object {
        val LOOK = stringPreferencesKey("look")
        val MATCH_ROOM_LIGHT = booleanPreferencesKey("match_room_light")
        val LAST_OPENED_BOOK = stringPreferencesKey("last_opened_adult_book")
        val REMINDER_ENABLED = booleanPreferencesKey("reading_reminder_enabled")
        val REMINDER_HOUR = intPreferencesKey("reading_reminder_hour")
        val REMINDER_MINUTE = intPreferencesKey("reading_reminder_minute")
        val DEFAULT_SLEEP = intPreferencesKey("audio_default_sleep_minutes")
        val DRIVE_MODE = booleanPreferencesKey("drive_mode_enabled")
        val VOLUME_PAGE_TURNS = booleanPreferencesKey("reader_volume_page_turns")
        val HEADSET_PAGE_TURNS = booleanPreferencesKey("reader_headset_page_turns")
        val HAPTIC_PAGE_TURNS = booleanPreferencesKey("reader_haptic_page_turns")
        val DEFAULT_SPEED = floatPreferencesKey("audio_default_speed")
    }
}

data class ReminderSettings(val enabled: Boolean = false, val time: LocalTime = LocalTime.of(20, 0))

data class PlaybackSettings(
    val defaultSleepMinutes: Int = 30,
    val driveModeEnabled: Boolean = false,
    val volumePageTurns: Boolean = false,
    val headsetPageTurns: Boolean = true,
    val hapticPageTurns: Boolean = true,
    val defaultSpeed: Float = 1f,
)
