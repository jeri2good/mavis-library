package com.mavislibrary.app.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.mavislibrary.app.ui.theme.LookChoice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Non-secret app settings (look). Secrets live in [com.mavislibrary.app.data.secure.SecureStore]. */
class SettingsStore(private val store: DataStore<Preferences>) {
    val lookChoice: Flow<LookChoice> = store.data.map { LookChoice.parse(it[LOOK]) }

    suspend fun setLook(choice: LookChoice) {
        store.edit { it[LOOK] = choice.storedValue() }
    }

    private companion object {
        val LOOK = stringPreferencesKey("look")
    }
}
