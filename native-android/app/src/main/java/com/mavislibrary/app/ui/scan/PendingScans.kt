package com.mavislibrary.app.ui.scan

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.pendingScanStore by preferencesDataStore(name = "pending_scans")
private val pendingKey = stringSetPreferencesKey("isbn")

class PendingScans(private val context: Context) {
    val isbns: Flow<Set<String>> = context.pendingScanStore.data.map { it[pendingKey].orEmpty() }
    suspend fun add(isbn: String) {
        require(IsbnValidator.normalize(isbn) == isbn)
        context.pendingScanStore.edit { it[pendingKey] = it[pendingKey].orEmpty() + isbn }
    }
    suspend fun remove(isbn: String) {
        context.pendingScanStore.edit { it[pendingKey] = it[pendingKey].orEmpty() - isbn }
    }
}
