package com.mavislibrary.app.work

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver.PendingResult
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import coil3.toBitmap
import com.mavislibrary.app.BuildConfig
import com.mavislibrary.app.MainActivity
import com.mavislibrary.app.MavisApp
import com.mavislibrary.app.R
import com.mavislibrary.app.data.db.BookEntity
import com.mavislibrary.app.data.kids.kidsBook
import com.mavislibrary.app.data.kids.kidsPrefs
import com.mavislibrary.app.data.podcast.PodcastJob
import com.mavislibrary.app.data.podcast.SavedPodcast
import com.mavislibrary.app.ui.lounge.visiblePodcasts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.concurrent.TimeUnit

private val verses = listOf(
    "Genesis|1|1|In the beginning God created the heaven and the earth.",
    "Genesis|1|3|And God said, Let there be light: and there was light.",
    "Genesis|1|31|And God saw every thing that he had made, and, behold, it was very good.",
    "Exodus|14|14|The LORD shall fight for you, and ye shall hold your peace.",
    "Numbers|6|24|The LORD bless thee, and keep thee:",
    "Deuteronomy|6|5|And thou shalt love the LORD thy God with all thine heart, and with all thy soul, and with all thy might.",
    "Joshua|1|9|Be strong and of a good courage; be not afraid, neither be thou dismayed: for the LORD thy God is with thee whithersoever thou goest.",
    "Ruth|1|16|Whither thou goest, I will go; and where thou lodgest, I will lodge: thy people shall be my people, and thy God my God:",
    "1 Samuel|16|7|For the LORD seeth not as man seeth; for man looketh on the outward appearance, but the LORD looketh on the heart.",
    "2 Samuel|22|31|As for God, his way is perfect; the word of the LORD is tried: he is a buckler to all them that trust in him.",
    "1 Chronicles|16|11|Seek the LORD and his strength, seek his face continually.",
    "Nehemiah|8|10|For the joy of the LORD is your strength.",
    "Job|19|25|For I know that my redeemer liveth, and that he shall stand at the latter day upon the earth:",
    "Psalms|4|8|I will both lay me down in peace, and sleep: for thou, LORD, only makest me dwell in safety.",
    "Psalms|19|14|Let the words of my mouth, and the meditation of my heart, be acceptable in thy sight, O LORD, my strength, and my redeemer.",
    "Psalms|23|1|The LORD is my shepherd; I shall not want.",
    "Psalms|23|4|Yea, though I walk through the valley of the shadow of death, I will fear no evil: for thou art with me; thy rod and thy staff they comfort me.",
    "Psalms|27|1|The LORD is my light and my salvation; whom shall I fear? the LORD is the strength of my life; of whom shall I be afraid?",
    "Psalms|34|8|O taste and see that the LORD is good: blessed is the man that trusteth in him.",
    "Psalms|37|4|Delight thyself also in the LORD; and he shall give thee the desires of thine heart.",
    "Psalms|46|1|God is our refuge and strength, a very present help in trouble.",
    "Psalms|46|10|Be still, and know that I am God: I will be exalted among the heathen, I will be exalted in the earth.",
    "Psalms|51|10|Create in me a clean heart, O God; and renew a right spirit within me.",
    "Psalms|55|22|Cast thy burden upon the LORD, and he shall sustain thee: he shall never suffer the righteous to be moved.",
    "Psalms|56|3|What time I am afraid, I will trust in thee.",
    "Psalms|90|12|So teach us to number our days, that we may apply our hearts unto wisdom.",
    "Psalms|91|2|I will say of the LORD, He is my refuge and my fortress: my God; in him will I trust.",
    "Psalms|100|4|Enter into his gates with thanksgiving, and into his courts with praise: be thankful unto him, and bless his name.",
    "Psalms|103|1|Bless the LORD, O my soul: and all that is within me, bless his holy name.",
    "Psalms|118|24|This is the day which the LORD hath made; we will rejoice and be glad in it.",
    "Psalms|119|105|Thy word is a lamp unto my feet, and a light unto my path.",
    "Psalms|121|2|My help cometh from the LORD, which made heaven and earth.",
    "Psalms|133|1|Behold, how good and how pleasant it is for brethren to dwell together in unity!",
    "Psalms|139|14|I will praise thee; for I am fearfully and wonderfully made: marvellous are thy works; and that my soul knoweth right well.",
    "Psalms|150|6|Let every thing that hath breath praise the LORD. Praise ye the LORD.",
    "Proverbs|3|5|Trust in the LORD with all thine heart; and lean not unto thine own understanding.",
    "Proverbs|3|6|In all thy ways acknowledge him, and he shall direct thy paths.",
    "Proverbs|4|23|Keep thy heart with all diligence; for out of it are the issues of life.",
    "Proverbs|10|12|Hatred stirreth up strifes: but love covereth all sins.",
    "Proverbs|15|1|A soft answer turneth away wrath: but grievous words stir up anger.",
    "Proverbs|16|9|A man's heart deviseth his way: but the LORD directeth his steps.",
    "Proverbs|17|17|A friend loveth at all times, and a brother is born for adversity.",
    "Proverbs|18|10|The name of the LORD is a strong tower: the righteous runneth into it, and is safe.",
    "Proverbs|27|17|Iron sharpeneth iron; so a man sharpeneth the countenance of his friend.",
    "Ecclesiastes|3|1|To every thing there is a season, and a time to every purpose under the heaven:",
    "Isaiah|40|31|But they that wait upon the LORD shall renew their strength; they shall mount up with wings as eagles; they shall run, and not be weary; and they shall walk, and not faint.",
    "Isaiah|41|10|Fear thou not; for I am with thee: be not dismayed; for I am thy God: I will strengthen thee; yea, I will help thee; yea, I will uphold thee with the right hand of my righteousness.",
    "Jeremiah|29|11|For I know the thoughts that I think toward you, saith the LORD, thoughts of peace, and not of evil, to give you an expected end.",
    "Lamentations|3|23|They are new every morning: great is thy faithfulness.",
    "Micah|6|8|He hath shewed thee, O man, what is good; and what doth the LORD require of thee, but to do justly, and to love mercy, and to walk humbly with thy God?",
    "Matthew|5|9|Blessed are the peacemakers: for they shall be called the children of God.",
    "Matthew|6|21|For where your treasure is, there will your heart be also.",
    "Matthew|7|7|Ask, and it shall be given you; seek, and ye shall find; knock, and it shall be opened unto you:",
    "Matthew|11|28|Come unto me, all ye that labour and are heavy laden, and I will give you rest.",
    "Mark|10|27|With men it is impossible, but not with God: for with God all things are possible.",
    "Luke|6|31|And as ye would that men should do to you, do ye also to them likewise.",
    "John|3|16|For God so loved the world, that he gave his only begotten Son, that whosoever believeth in him should not perish, but have everlasting life.",
    "John|8|12|I am the light of the world: he that followeth me shall not walk in darkness, but shall have the light of life.",
    "Romans|12|12|Rejoicing in hope; patient in tribulation; continuing instant in prayer;",
    "Philippians|4|4|Rejoice in the Lord alway: and again I say, Rejoice.",
)

data class DailyVerse(val book: String, val chapter: Int, val verse: Int, val text: String) {
    val reference get() = "$book $chapter:$verse"
    val route get() = "#/bible/${bookIds.getValue(book)}/$chapter?v=$verse&t=kjv"

    private val bookIds get() = mapOf(
        "Genesis" to "Gen", "Exodus" to "Exod", "Numbers" to "Num", "Deuteronomy" to "Deut",
        "Joshua" to "Josh", "Ruth" to "Ruth", "1 Samuel" to "1Sam", "2 Samuel" to "2Sam",
        "1 Chronicles" to "1Chr", "Nehemiah" to "Neh", "Job" to "Job", "Psalms" to "Ps",
        "Proverbs" to "Prov", "Ecclesiastes" to "Eccl", "Isaiah" to "Isa", "Jeremiah" to "Jer",
        "Lamentations" to "Lam", "Micah" to "Mic", "Matthew" to "Matt", "Mark" to "Mark",
        "Luke" to "Luke", "John" to "John", "Romans" to "Rom", "Philippians" to "Phil",
    )
}

object VerseOfDay {
    val size get() = verses.size
    fun forDay(dayOfYear: Int): DailyVerse {
        require(dayOfYear in 1..366)
        val parts = verses[(dayOfYear - 1) % verses.size].split('|', limit = 4)
        return DailyVerse(parts[0], parts[1].toInt(), parts[2].toInt(), parts[3])
    }
}

fun continueReading(books: List<BookEntity>, lastOpenedKey: String?): BookEntity? =
    books.firstOrNull { it.key == lastOpenedKey && !kidsBook(it.source.lowercase(), it.sourceId) }

class ContinueReadingWidget : InteractiveLibraryWidget()
class VerseOfDayWidget : InteractiveLibraryWidget()
class PodcastLoungeWidget : InteractiveLibraryWidget()

open class InteractiveLibraryWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        WidgetUpdater.refresh(context, goAsync())
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        WidgetUpdater.refresh(context, goAsync())
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        WidgetUpdater.clearSelections(context, appWidgetIds)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (WidgetUpdater.advanceSelection(context, javaClass, intent)) {
            WidgetUpdater.refresh(context, goAsync())
        }
    }
}

fun recentReadingBooks(books: List<BookEntity>, last: String?): List<BookEntity> =
    books.filterNot { kidsBook(it.source.lowercase(), it.sourceId) }
        .sortedByDescending { it.key == last }
        .distinctBy { it.key }
        .take(3)

fun readingWidgetRoute(book: BookEntity, locator: String?, listen: Boolean = false): String =
    "#/read/${Uri.encode(book.key)}" +
        listOfNotNull(
            locator?.takeIf { it.startsWith("epubcfi(") }?.let { "at=${Uri.encode(it)}" },
            "listen=1".takeIf { listen },
        ).joinToString("&").let { if (it.isEmpty()) "" else "?$it" }

fun bookCanListen(book: BookEntity) = !book.epubUrl.isNullOrBlank() || book.formats.contains("epub", true)

fun podcastWidgetStatus(job: PodcastJob?, pending: Int, played: Boolean): String = when {
    pending > 0 -> "Making… ($pending in progress)"
    job == null -> "Make your first podcast"
    job.status == "failed" -> "Couldn't be made"
    !job.terminal -> "Making…"
    played -> "Played"
    else -> "Ready"
}

/** Prefers the active playing episode, then the newest ready episode (keeping Play usable while new jobs are being made), then any newest job. */
fun selectPodcastWidgetEpisode(
    library: List<SavedPodcast>,
    activeMediaId: String = WidgetPlayback.mediaId,
    isPlaying: Boolean = WidgetPlayback.playing,
): SavedPodcast? {
    val active = library.find { it.resumeKey == activeMediaId && it.job.status == "ready" }
    return active?.takeIf { isPlaying } ?: library.firstOrNull { it.job.status == "ready" } ?: library.firstOrNull()
}

/** Actual live state, never a persisted 'playing' flag that could survive process death. */
object WidgetPlayback {
    @Volatile var mediaId: String = ""
    @Volatile var playing: Boolean = false
    fun changed(context: Context, id: String, isPlaying: Boolean) {
        mediaId = id
        playing = isPlaying
        WidgetUpdater.refresh(context)
    }
}

object WidgetUpdater {
    const val NEXT_BOOK = "com.mavislibrary.app.WIDGET_NEXT_BOOK"
    const val NEXT_VERSE = "com.mavislibrary.app.WIDGET_NEXT_VERSE"
    private const val PREFS_NAME = "widget-selections"
    // lcm(1, 2, 3) = 6 so cycling modulo 1, 2, or 3 recent books never stutters on wrap-around.
    private const val BOOK_CYCLE_MODULUS = 6

    fun schedule(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "mavis-widgets",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<WidgetRefreshWorker>(1, TimeUnit.DAYS).build(),
        )
    }

    fun refresh(context: Context, result: PendingResult? = null) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                update(context.applicationContext)
            } catch (_: Exception) {
            } finally {
                result?.finish()
            }
        }
    }

    fun advanceSelection(context: Context, providerClass: Class<out AppWidgetProvider>, intent: Intent): Boolean {
        val expectedAction = when (providerClass) {
            ContinueReadingWidget::class.java -> NEXT_BOOK
            VerseOfDayWidget::class.java -> NEXT_VERSE
            else -> return false
        }
        if (intent.action != expectedAction) return false
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return false
        val manager = AppWidgetManager.getInstance(context)
        if (id !in manager.getAppWidgetIds(ComponentName(context, providerClass))) return false
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = "$expectedAction/$id"
        val modulus = if (expectedAction == NEXT_BOOK) BOOK_CYCLE_MODULUS else VerseOfDay.size
        prefs.edit().putInt(key, (prefs.getInt(key, 0) + 1) % modulus).apply()
        return true
    }

    fun resetBookSelection(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val bookKeys = prefs.all.keys.filter { it.startsWith("$NEXT_BOOK/") }
        if (bookKeys.isEmpty()) return
        val editor = prefs.edit()
        bookKeys.forEach(editor::remove)
        editor.apply()
    }

    fun clearSelections(context: Context, ids: IntArray) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        ids.forEach { id ->
            editor.remove("$NEXT_BOOK/$id")
            editor.remove("$NEXT_VERSE/$id")
        }
        editor.apply()
    }

    fun isCompactHeight(options: Bundle?): Boolean {
        val minHeight = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) ?: 0
        return minHeight in 1..84
    }

    fun activity(context: Context, id: Int, slot: Int, destination: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.WIDGET_DESTINATION, destination)
            .setData(Uri.parse("mavis://widget/$id/$slot"))
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, id * 16 + slot, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun cycle(context: Context, id: Int, book: Boolean): PendingIntent {
        val intent = Intent(context, if (book) ContinueReadingWidget::class.java else VerseOfDayWidget::class.java)
            .setAction(if (book) NEXT_BOOK else NEXT_VERSE)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            .setData(Uri.parse("mavis://widget/$id/cycle"))
        return PendingIntent.getBroadcast(context, id * 16 + 4, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun base(context: Context, heading: String, title: String, detail: String, compact: Boolean = false): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_interactive).apply {
            setTextViewText(R.id.widget_heading, heading)
            setViewVisibility(R.id.widget_heading, if (compact) View.GONE else View.VISIBLE)
            setTextViewText(R.id.widget_title, title)
            setInt(R.id.widget_title, "setMaxLines", if (compact) 1 else 4)
            setTextViewText(R.id.widget_detail, detail)
            for (view in listOf(R.id.widget_cover, R.id.widget_progress, R.id.widget_action1, R.id.widget_action2, R.id.widget_action3, R.id.widget_action4)) {
                setViewVisibility(view, View.GONE)
            }
        }

    private fun RemoteViews.button(view: Int, label: String, intent: PendingIntent) {
        setViewVisibility(view, View.VISIBLE)
        setTextViewText(view, label)
        setOnClickPendingIntent(view, intent)
    }

    fun verseView(context: Context, id: Int, verse: DailyVerse, compact: Boolean = false): RemoteViews =
        base(context, "Verse of the day", verse.text, verse.reference, compact).apply {
            setOnClickPendingIntent(R.id.widget_root, activity(context, id, 6, verse.route))
            setOnClickPendingIntent(R.id.widget_title, activity(context, id, 0, verse.route))
            button(R.id.widget_action1, "Read", activity(context, id, 1, verse.route))
            button(R.id.widget_action2, "Lesson", activity(context, id, 2, "lesson:" + verse.route))
            val send = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, verse.reference)
                .putExtra(Intent.EXTRA_TEXT, "${verse.text} — ${verse.reference}")
            // Do not set a custom data URI on an implicit ACTION_CHOOSER intent; ChooserActivity's filter has no scheme.
            val share = Intent.createChooser(send, "Share verse").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            button(R.id.widget_action3, "Share", PendingIntent.getActivity(context, id * 16 + 3, share, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            button(R.id.widget_action4, "⟳", cycle(context, id, false))
        }

    fun readingView(context: Context, id: Int, book: BookEntity?, fraction: Float, compact: Boolean = false): RemoteViews =
        base(
            context,
            "Continue reading",
            book?.title ?: "Open your shelf",
            if (book == null) "Choose a book to begin" else "${(fraction.coerceIn(0f, 1f) * 100).toInt()}% read",
            compact,
        ).apply {
            val route = book?.let { "read:${Uri.encode(it.key)}" } ?: "shelf"
            setOnClickPendingIntent(R.id.widget_root, activity(context, id, 6, route))
            setOnClickPendingIntent(R.id.widget_title, activity(context, id, 0, route))
            setOnClickPendingIntent(R.id.widget_cover, activity(context, id, 5, route))
            if (book != null) {
                setViewVisibility(R.id.widget_cover, View.VISIBLE)
                setImageViewResource(R.id.widget_cover, R.mipmap.ic_launcher)
                setViewVisibility(R.id.widget_progress, View.VISIBLE)
                setProgressBar(R.id.widget_progress, 1000, (fraction.coerceIn(0f, 1f) * 1000).toInt(), false)
                if (bookCanListen(book)) {
                    button(R.id.widget_action1, "▶ Listen", activity(context, id, 1, "listen:${Uri.encode(book.key)}"))
                }
            }
            button(R.id.widget_action2, "Next book →", cycle(context, id, true))
        }

    fun podcastView(
        context: Context,
        id: Int,
        saved: SavedPodcast?,
        pending: Int,
        played: Boolean,
        isPlaying: Boolean = saved != null && WidgetPlayback.mediaId == saved.resumeKey && WidgetPlayback.playing,
        compact: Boolean = false,
    ): RemoteViews {
        val job = saved?.job
        val detail = listOfNotNull(
            job?.durationSec?.let { "${maxOf(1, (it + 30) / 60)} min" },
            podcastWidgetStatus(job, pending, played),
        ).joinToString(" · ")
        return base(context, "Podcast Lounge", job?.title?.ifBlank { null } ?: "Your Podcast Lounge", detail, compact).apply {
            setOnClickPendingIntent(R.id.widget_root, activity(context, id, 6, MainActivity.LOUNGE))
            setOnClickPendingIntent(R.id.widget_title, activity(context, id, 0, MainActivity.LOUNGE))
            if (saved != null && job?.status == "ready") {
                button(R.id.widget_action1, if (isPlaying) "⏸ Pause" else "▶ Play", PodcastPlaybackAction.pending(context, job.id, true))
            }
            button(R.id.widget_action2, "＋ New podcast", activity(context, id, 2, "new-podcast"))
        }
    }

    suspend fun update(
        context: Context,
        todayDayOfYear: Int = LocalDate.now().dayOfYear,
        c: com.mavislibrary.app.AppContainer = (context.applicationContext as MavisApp).container,
    ) {
        val manager = AppWidgetManager.getInstance(context)
        val readingIds = manager.getAppWidgetIds(ComponentName(context, ContinueReadingWidget::class.java))
        val verseIds = manager.getAppWidgetIds(ComponentName(context, VerseOfDayWidget::class.java))
        val podcastIds = manager.getAppWidgetIds(ComponentName(context, PodcastLoungeWidget::class.java))
        if (readingIds.isEmpty() && verseIds.isEmpty() && podcastIds.isEmpty()) return
        if (context.kidsPrefs().state.first().enabled) {
            (readingIds + verseIds + podcastIds).forEach { id ->
                val compact = isCompactHeight(manager.getAppWidgetOptions(id))
                manager.updateAppWidget(
                    id,
                    base(context, "Mavis Library", "Open Mavis", "", compact).apply {
                        setOnClickPendingIntent(R.id.widget_root, activity(context, id, 6, "shelf"))
                        setOnClickPendingIntent(R.id.widget_title, activity(context, id, 0, "shelf"))
                    },
                )
            }
            return
        }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val books = if (readingIds.isEmpty()) emptyList() else recentReadingBooks(c.database.books().recent(), c.settings.lastOpenedBook.first())
        readingIds.forEach { id ->
            val compact = isCompactHeight(manager.getAppWidgetOptions(id))
            val book = books.getOrNull(prefs.getInt("$NEXT_BOOK/$id", 0) % maxOf(1, books.size))
            val fraction = book?.let { c.database.readingProgress().get(it.key)?.fraction } ?: 0f
            val view = readingView(context, id, book, fraction, compact)
            if (book?.coverUrl != null) {
                runCatching {
                    val result = coil3.SingletonImageLoader.get(context).execute(
                        coil3.request.ImageRequest.Builder(context).data(book.coverUrl).size(120, 180).build(),
                    )
                    result.image?.let { view.setImageViewBitmap(R.id.widget_cover, it.toBitmap()) }
                }
            }
            manager.updateAppWidget(id, view)
        }
        verseIds.forEach { id ->
            val compact = isCompactHeight(manager.getAppWidgetOptions(id))
            val day = (todayDayOfYear - 1 + prefs.getInt("$NEXT_VERSE/$id", 0)) % VerseOfDay.size + 1
            manager.updateAppWidget(id, verseView(context, id, VerseOfDay.forDay(day), compact))
        }
        if (podcastIds.isNotEmpty()) {
            val account = c.podcastAccountId()
            val cleanupDays = c.podcastSettings.preferences.first().cleanupDays
            val library = visiblePodcasts(c.podcastSettings.library.first(), account, BuildConfig.API_BASE, cleanupDays)
            val featured = selectPodcastWidgetEpisode(library)
            val position = featured?.let { c.resumePositionStore.load(it.resumeKey)?.positionMs } ?: 0L
            val played = featured?.job?.durationSec?.let { position >= it * 1000L - 30_000 && position >= 5_000 } ?: false
            val pending = library.count { !it.job.terminal }
            podcastIds.forEach { id ->
                val compact = isCompactHeight(manager.getAppWidgetOptions(id))
                manager.updateAppWidget(id, podcastView(context, id, featured, pending, played, compact = compact))
            }
        }
    }
}

class WidgetRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        WidgetUpdater.update(applicationContext)
        Result.success()
    } catch (_: Exception) {
        Result.retry()
    }
}
