package com.mavislibrary.app.work

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver.PendingResult
import android.widget.RemoteViews
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mavislibrary.app.MainActivity
import com.mavislibrary.app.MavisApp
import com.mavislibrary.app.R
import com.mavislibrary.app.data.db.BookEntity
import com.mavislibrary.app.data.kids.kidsBook
import com.mavislibrary.app.data.kids.kidsPrefs
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

class ContinueReadingWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) { WidgetUpdater.refresh(context, goAsync()) }
}

class VerseOfDayWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) { WidgetUpdater.refresh(context, goAsync()) }
}

object WidgetUpdater {
    fun schedule(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "mavis-widgets", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<WidgetRefreshWorker>(1, TimeUnit.DAYS).build(),
        )
    }

    fun refresh(context: Context, result: PendingResult? = null) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { update(context.applicationContext) } catch (_: Exception) { } finally { result?.finish() }
        }
    }

    suspend fun update(context: Context) {
        val app = context.applicationContext as MavisApp
        val kids = context.kidsPrefs().state.first().enabled
        val manager = AppWidgetManager.getInstance(context)
        val readingIds = manager.getAppWidgetIds(ComponentName(context, ContinueReadingWidget::class.java))
        val verseIds = manager.getAppWidgetIds(ComponentName(context, VerseOfDayWidget::class.java))
        if (readingIds.isEmpty() && verseIds.isEmpty()) return
        val book = if (kids || readingIds.isEmpty()) null else {
            val key = app.container.settings.lastOpenedBook.first()
            continueReading(listOfNotNull(key?.let { app.container.database.books().get(it) }), key)
        }
        val verse = VerseOfDay.forDay(LocalDate.now().dayOfYear)
        readingIds.forEach { id ->
            val title = if (kids) "Open Mavis" else book?.title ?: "Open Mavis"
            manager.updateAppWidget(id, view(context, id, if (kids) "Mavis Library" else "Continue reading", title, if (kids) "" else if (book == null) "Visit your shelf" else "Tap to open your shelf", "shelf"))
        }
        verseIds.forEach { id ->
            manager.updateAppWidget(id, view(context, id, if (kids) "Mavis Library" else "Verse of the day", if (kids) "Open Mavis" else verse.text,
                if (kids) "" else verse.reference, if (kids) "shelf" else verse.route))
        }
    }

    private fun view(context: Context, id: Int, heading: String, title: String, detail: String, destination: String): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_paper).apply {
            setTextViewText(R.id.widget_heading, heading)
            setTextViewText(R.id.widget_title, title)
            setTextViewText(R.id.widget_detail, detail)
            val intent = Intent(context, MainActivity::class.java).putExtra(MainActivity.WIDGET_DESTINATION, destination)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(context, id + if (destination == "shelf") 10000 else 20000, intent, flags))
        }
}

class WidgetRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        WidgetUpdater.update(applicationContext)
        Result.success()
    } catch (e: Exception) { Result.retry() }
}
