package com.mavislibrary.app.data.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.mavislibrary.app.data.kids.kidsPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipFile
import org.json.JSONArray
import org.json.JSONObject

const val EPUB_MIME = "application/epub+zip"
const val EPUB_LIMIT = 60L * 1024 * 1024
const val SHARE_HELP = "Mavis can open EPUB books and Bible verses"
class ImportRejected(message: String) : Exception(message)

/** Bounded copying applies even when a provider omits or lies about its size. */
fun copyEpub(input: InputStream, file: File, limit: Long = EPUB_LIMIT) {
    file.outputStream().use { out ->
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > limit) throw ImportRejected("That book is too large. Please choose an EPUB under 60 MB.")
            out.write(buffer, 0, n)
        }
    }
}
/** Inspect ZIP headers and the tiny mimetype entry only. No extraction or EPUB/XML parsing. */
fun validateEpub(file: File, mime: String): Boolean = runCatching {
    if (mime != EPUB_MIME || file.length() !in 4..EPUB_LIMIT) return false
    if (!file.inputStream().use { ByteArray(4).also { bytes -> java.io.DataInputStream(it).readFully(bytes) } }.contentEquals(byteArrayOf(80, 75, 3, 4))) return false
    ZipFile(file).use { zip ->
        var total = 0L
        var count = 0
        var found = false
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val e = entries.nextElement()
            if (++count > 10000 || e.size < 0 || e.size > EPUB_LIMIT || e.size > EPUB_LIMIT - total ||
                e.name.startsWith('/') || e.name.contains('\\') || e.name.split('/').any { it == ".." || it == "." } ||
                e.name.any { it.isISOControl() }) return false
            total += e.size
            if (e.name == "mimetype") {
                if (found || e.size != EPUB_MIME.length.toLong()) return false
                found = zip.getInputStream(e).use { it.readBytesBounded(EPUB_MIME.length + 1).contentEquals(EPUB_MIME.toByteArray()) }
                if (!found) return false
            }
        }
        found
    }
}.getOrDefault(false)

private val Context.importDataStore by preferencesDataStore("imported_books")
data class ImportedBook(val id: String, val title: String)

fun importedRouteAllowed(id: String, allowedIds: Collection<String>, kidsMode: Boolean): Boolean =
    !kidsMode && runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false) && id in allowedIds

class EpubImports(context: Context) {
    private val app = context.applicationContext
    private val store = app.importDataStore
    private val key = stringPreferencesKey("books")
    private fun decode(raw: String?): List<ImportedBook> {
        val list = JSONArray(raw ?: "[]")
        return (0 until list.length()).map { list.getJSONObject(it).let { b -> ImportedBook(b.getString("id"), b.getString("title")) } }
    }
    val books = store.data.map { decode(it[key]) }
    suspend fun open(id: String): File? = withContext(Dispatchers.IO) {
        if (!importedRouteAllowed(id, books.first().map { it.id }, app.kidsPrefs().state.first().enabled)) return@withContext null
        val root = File(app.filesDir, "imports").canonicalFile
        val file = File(root, "$id.epub").canonicalFile
        file.takeIf { it.parentFile == root && it.isFile && validateEpub(it, EPUB_MIME) && !app.kidsPrefs().state.first().enabled }
    }
    suspend fun import(uri: Uri, mime: String): ImportedBook? = withContext(Dispatchers.IO) {
        if (app.kidsPrefs().state.first().enabled) return@withContext null
        if (mime != EPUB_MIME || uri.scheme != "content") throw ImportRejected(SHARE_HELP)
        val dir = File(app.filesDir, "imports").apply { mkdirs() }
        val id = UUID.randomUUID().toString()
        val file = File(dir, "$id.epub")
        try {
            var name = "Imported book"
            app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val size = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (size >= 0 && !cursor.isNull(size) && cursor.getLong(size) > EPUB_LIMIT) throw ImportRejected("That book is too large. Please choose an EPUB under 60 MB.")
                    val title = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (title >= 0) name = cursor.getString(title).orEmpty().substringAfterLast('/').substringAfterLast('\\').replace(Regex("(?i)\\.epub$"), "").filter { !it.isISOControl() }.take(160).ifBlank { "Imported book" }
                }
            }
            app.contentResolver.openInputStream(uri)?.use { copyEpub(it, file) } ?: throw ImportRejected("Mavis couldn't read that book.")
            if (!validateEpub(file, mime)) throw ImportRejected("That file isn't a supported EPUB book (maximum 60 MB expanded).")
            if (app.kidsPrefs().state.first().enabled) { file.delete(); return@withContext null }
            val book = ImportedBook(id, name)
            store.edit { p ->
                val array = JSONArray()
                (decode(p[key]) + book).forEach { array.put(JSONObject().put("id", it.id).put("title", it.title)) }
                p[key] = array.toString()
            }
            book
        } catch (e: Exception) { file.delete(); throw e }
    }
}

@Suppress("DEPRECATION")
fun sharedEpubUri(intent: Intent): Uri? = when (intent.action) {
    Intent.ACTION_SEND -> intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
    Intent.ACTION_VIEW -> intent.data
    else -> null
}

private fun InputStream.readBytesBounded(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    repeat(limit) { val b = read(); if (b == -1) return out.toByteArray(); out.write(b) }
    return out.toByteArray()
}
