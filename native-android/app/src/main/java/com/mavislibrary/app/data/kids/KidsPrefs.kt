package com.mavislibrary.app.data.kids

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.*

private val Context.kidsDataStore by preferencesDataStore(name = "kids_settings")
fun Context.kidsPrefs() = KidsPrefs(applicationContext.kidsDataStore)

val KIDS_SHELF = listOf(14838,11757,67098,11,55,16,289,113,2781,236,271,1874,902,146,1448,500,11339,2591,120,45,23661)
fun kidsBook(source: String, id: String, kids: Boolean = false, subjects: List<String> = emptyList(), shelves: List<String> = emptyList()) =
    kids || (source == "gutenberg" && id.toIntOrNull() in KIDS_SHELF) || (subjects + shelves).any { Regex("juvenile|children", RegexOption.IGNORE_CASE).containsMatchIn(it) }
fun adultRoutesAllowed(kidsMode: Boolean) = !kidsMode

data class KidsDay(val date: LocalDate, val minutes: Int, val star: Boolean)
data class KidsState(val enabled: Boolean = false, val name: String = "", val goal: Int = 15, val log: Map<LocalDate, Long> = emptyMap()) {
    fun minutes(today: LocalDate) = ((log[today] ?: 0) / 60000).toInt()
    fun week(today: LocalDate) = (6 downTo 0).map { today.minusDays(it.toLong()).let { d -> KidsDay(d, minutes(d), (log[d] ?: 0) >= goal * 60000L) } }
    fun streak(today: LocalDate): Int {
        var d = if ((log[today] ?: 0) >= goal * 60000L) today else today.minusDays(1)
        var count = 0
        while ((log[d] ?: 0) >= goal * 60000L) { count++; d = d.minusDays(1) }
        return count
    }
}
object KidsPin {
    fun valid(pin: String) = Regex("[0-9]{4}").matches(pin)
    fun create(pin: String): String {
        require(valid(pin))
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        return "$salt:${hash(pin, salt)}"
    }
    private fun hash(pin: String, salt: String) = MessageDigest.getInstance("SHA-256").digest("mavis-kids|$salt|$pin".toByteArray()).joinToString("") { "%02x".format(it) }
    fun verifies(pin: String, saved: String): Boolean {
        val parts = saved.split(':')
        return valid(pin) && parts.size == 2 && MessageDigest.isEqual(hash(pin, parts[0]).toByteArray(), parts[1].toByteArray())
    }
}
sealed interface PinResult {
    data object Correct : PinResult
    data object Wrong : PinResult
    data class Locked(val seconds: Long) : PinResult
}
/** Separate singleton DataStore follows SettingsStore's atomic edit pattern. Only salted digest is persisted. */
class KidsPrefs(private val store: DataStore<Preferences>, private val clock: () -> Long = System::currentTimeMillis) {
    val state = store.data.map { p -> KidsState(p[ON] ?: false, p[NAME] ?: "", p[GOAL] ?: 15, decodeLog(p[LOG])) }
    suspend fun start(name: String, goal: Int, pin: String) {
        require(goal in listOf(10,15,20,30))
        val hash = KidsPin.create(pin)
        store.edit { it[PIN] = hash; it[NAME] = name.trim().take(30); it[GOAL] = goal; it[ON] = true; it[MISSES] = 0; it[UNTIL] = 0 }
    }
    suspend fun leave(pin: String): PinResult {
        var result: PinResult = PinResult.Wrong
        store.edit { p ->
            val now = clock(); val until = p[UNTIL] ?: 0
            if (until > now) result = PinResult.Locked((until - now + 999) / 1000)
            else if (KidsPin.verifies(pin, p[PIN].orEmpty())) {
                p[ON] = false; p[MISSES] = 0; p[UNTIL] = 0; result = PinResult.Correct
            } else {
                val misses = (if (until != 0L) 0 else p[MISSES] ?: 0) + 1
                p[MISSES] = misses
                if (misses >= 5) { p[UNTIL] = now + 30000; result = PinResult.Locked(30) }
                else p[UNTIL] = 0
            }
        }
        return result
    }
    suspend fun addTime(durations: Map<LocalDate, Long>) {
        store.edit { p ->
            val log = decodeLog(p[LOG]).toMutableMap()
            durations.filterValues { it > 0 }.forEach { (d, ms) -> log[d] = (log[d] ?: 0) + ms }
            p[LOG] = log.toSortedMap().entries.takeLast(120).joinToString(";") { "${it.key}=${it.value}" }
        }
    }
    private companion object {
        val ON = booleanPreferencesKey("kids_mode"); val NAME = stringPreferencesKey("child_name")
        val GOAL = intPreferencesKey("daily_goal"); val PIN = stringPreferencesKey("salted_pin_hash")
        val LOG = stringPreferencesKey("reading_log_ms"); val MISSES = intPreferencesKey("pin_misses"); val UNTIL = longPreferencesKey("pin_locked_until")
        fun decodeLog(value: String?) = value.orEmpty().split(';').mapNotNull { entry -> runCatching { val pair = entry.split('='); LocalDate.parse(pair[0]) to pair[1].toLong().coerceAtLeast(0) }.getOrNull() }.toMap()
    }
}

/** One combined reading/listening interval, split at local midnight; drains preserve sub-minute time. */
class ReadingAccumulator(private val clock: () -> Instant = Instant::now, private val zone: () -> ZoneId = ZoneId::systemDefault) {
    private var since: Instant? = null
    private val pending = mutableMapOf<LocalDate, Long>()
    fun update(active: Boolean) {
        val now = clock()
        since?.let { start ->
            var cursor = start
            while (cursor < now) {
                val date = cursor.atZone(zone()).toLocalDate()
                val end = minOf(now, date.plusDays(1).atStartOfDay(zone()).toInstant())
                pending[date] = (pending[date] ?: 0) + Duration.between(cursor, end).toMillis()
                cursor = end
            }
        }
        since = if (active) now else null
    }
    fun drain(): Map<LocalDate, Long> = pending.toMap().also { pending.clear() }
}
