package com.mavislibrary.app

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.mavislibrary.app.data.api.MavisApi
import com.mavislibrary.app.data.db.MavisDatabase
import com.mavislibrary.app.data.net.ConnectivityMonitor
import com.mavislibrary.app.data.prefs.SettingsStore
import com.mavislibrary.app.data.repo.CachedFetcher
import com.mavislibrary.app.data.repo.RoomResponseCache
import com.mavislibrary.app.data.repo.SearchRepository
import com.mavislibrary.app.data.repo.ShelfRepository
import com.mavislibrary.app.data.secure.KeystoreCipher
import com.mavislibrary.app.data.secure.SecureStore
import com.mavislibrary.app.audio.ResumePositionStore
import com.mavislibrary.app.audio.AudiobookRepository
import com.mavislibrary.app.audio.AudioFileStore
import com.mavislibrary.app.data.kids.kidsPrefs
import com.mavislibrary.app.work.WidgetUpdater
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

private val Context.settingsDataStore by preferencesDataStore(name = "settings")
private val Context.secureDataStore by preferencesDataStore(name = "secure_store")

/** Hand-wired dependencies (small app; no DI framework needed yet). */
class AppContainer internal constructor(
    private val app: Application,
    customFeedFetcher: com.mavislibrary.app.data.feeds.FeedFetcher? = null,
    private val onlineOverride: (() -> Boolean)? = null,
    private val podcastTransport: (suspend (okhttp3.Request) -> okhttp3.Response)? = null,
    customApiHttp: OkHttpClient? = null,
    customCipher: com.mavislibrary.app.data.secure.SecretCipher? = null,
    private val customSettingsDataStore: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>? = null,
    private val customSecureDataStore: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>? = null,
) {
    constructor(app: Application) : this(app, null, null, null, null, null, null, null)

    private val settingsPrefs get() = customSettingsDataStore ?: app.settingsDataStore
    private val securePrefs get() = customSecureDataStore ?: app.secureDataStore

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val okHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cache(Cache(app.cacheDir.resolve("http"), 20L * 1024 * 1024))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /** Only requests to our own backend carry the app's source header. */
    private val apiHttp: OkHttpClient by lazy {
        customApiHttp ?: okHttp.newBuilder()
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("x-mavis-source", "android-native").build())
            }
            .build()
    }

    val database: MavisDatabase by lazy { MavisDatabase.create(app) }
    val connectivity: ConnectivityMonitor by lazy { ConnectivityMonitor(app, scope) }
    val settings: SettingsStore by lazy { SettingsStore(settingsPrefs) }
    val feedStore by lazy { com.mavislibrary.app.data.feeds.FeedStore(settingsPrefs) }
    private val feedFetcher by lazy { customFeedFetcher ?: com.mavislibrary.app.data.feeds.DeviceFeedFetcher() }
    val feedDiscovery by lazy { com.mavislibrary.app.data.feeds.FeedDiscovery(feedFetcher) }
    val feedRepository by lazy { com.mavislibrary.app.data.feeds.FeedRepository(feedFetcher, onlineOverride ?: connectivity::isOnline, reader = readerApi()) }
    val secureStore: SecureStore by lazy { SecureStore(securePrefs, customCipher ?: KeystoreCipher()) }
    val accountsApi by lazy { com.mavislibrary.app.data.api.AccountsApi(BuildConfig.API_BASE,
        token = { secureStore.read(com.mavislibrary.app.data.secure.Secret.ACCOUNT_TOKEN) },
        owner = { secureStore.read(com.mavislibrary.app.data.secure.Secret.OWNER_ACCESS_CODE) }) }
    val accounts by lazy { com.mavislibrary.app.data.api.AccountRepository(accountsApi, secureStore) { !app.kidsPrefs().state.first().enabled } }
    val api: MavisApi by lazy { MavisApi(apiHttp, BuildConfig.API_BASE) }
    val searchRepository: SearchRepository by lazy {
        SearchRepository(CachedFetcher(api, RoomResponseCache(database.cachedResponses()), onlineOverride ?: connectivity::isOnline))
    }
    val shelfRepository: ShelfRepository by lazy { ShelfRepository(database.shelf()) }
    val resumePositionStore: ResumePositionStore by lazy { ResumePositionStore(database.audioResume()) }
    val audiobookRepository: AudiobookRepository by lazy { AudiobookRepository(api) }
    val audioFiles: AudioFileStore by lazy { AudioFileStore(app.filesDir, okHttp) }
    val podcastSettings by lazy { com.mavislibrary.app.data.podcast.PodcastSettings(settingsPrefs, secureStore) }
    suspend fun podcastAccountId(): String? = if (secureStore.read(com.mavislibrary.app.data.secure.Secret.OWNER_ACCESS_CODE).isNullOrBlank())
        secureStore.read(com.mavislibrary.app.data.secure.Secret.ACCOUNT_USER_ID) else "owner"
    fun podcastApi() = com.mavislibrary.app.data.podcast.PodcastApi(BuildConfig.API_BASE, execute = podcastTransport,
        owner = { secureStore.read(com.mavislibrary.app.data.secure.Secret.OWNER_ACCESS_CODE) },
        adult = { !app.kidsPrefs().state.first().enabled },
        token = { secureStore.read(com.mavislibrary.app.data.secure.Secret.ACCOUNT_TOKEN) })
    fun readerApi() = com.mavislibrary.app.data.reader.ReaderApi(podcastApi())
    val readerPrefs by lazy { com.mavislibrary.app.data.reader.ReaderPrefs(settingsPrefs) }
    val readState by lazy { com.mavislibrary.app.data.reader.ReadStateStore(settingsPrefs) }
    fun podcastRepository() = com.mavislibrary.app.data.podcast.PodcastRepository(podcastApi(), podcastSettings, BuildConfig.API_BASE, ::podcastAccountId)
    val briefingStore by lazy { com.mavislibrary.app.data.briefing.BriefingStore(settingsPrefs) }
    val podsubRepository by lazy { com.mavislibrary.app.data.podsub.PodsubRepository(
        com.mavislibrary.app.data.podsub.PodsubApi(podcastApi()),
        com.mavislibrary.app.data.podsub.PodsubStore(settingsPrefs), ::podcastAccountId,
        { feedStore.sources.first() }, { briefingStore.settings.first() }) }
    /** No HTTP cache: update checks must see the newest release, and APKs are too big to cache. */
    private val updateHttp by lazy { okHttp.newBuilder().cache(null).readTimeout(60, TimeUnit.SECONDS).build() }
    val updateChecker by lazy { com.mavislibrary.app.update.UpdateChecker(updateHttp, BuildConfig.UPDATE_MANIFEST_URL, BuildConfig.VERSION_CODE) }
    val updateNotices by lazy { com.mavislibrary.app.update.UpdateNotices(settingsPrefs) }
    val appUpdater by lazy {
        com.mavislibrary.app.update.AppUpdater(
            updateChecker, com.mavislibrary.app.update.UpdateDownloader(updateHttp, app.cacheDir.resolve("updates")), scope,
            canInstall = { com.mavislibrary.app.update.UpdateInstaller.canInstall(app) },
            installApk = { com.mavislibrary.app.update.UpdateInstaller.install(app, it) },
        )
    }
}

class MavisApp : Application(), Configuration.Provider, SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        WidgetUpdater.schedule(this)
        container.scope.launch { com.mavislibrary.app.work.BriefingScheduler.sync(this@MavisApp, restore = true) }
        container.scope.launch {
            kotlinx.coroutines.flow.combine(container.feedStore.sources,
                container.secureStore.observe(com.mavislibrary.app.data.secure.Secret.ACCOUNT_USER_ID),
                container.secureStore.has(com.mavislibrary.app.data.secure.Secret.OWNER_ACCESS_CODE),
                kidsPrefs().state) { sites, uid, owner, kids ->
                // Reading/viewed timestamps do not change subscription rules.
                listOf(sites.map { listOf(it.id, it.name, it.feedUrl, it.siteUrl, it.source) }, uid, owner, kids.enabled)
            }.distinctUntilChanged().collect { com.mavislibrary.app.work.BriefingScheduler.syncSoon(this@MavisApp) }
        }
        com.mavislibrary.app.update.UpdateScheduler.schedule(this)
        container.scope.launch { kidsPrefs().state.map { it.enabled }.distinctUntilChanged().collect { WidgetUpdater.refresh(this@MavisApp) } }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setMinimumLoggingLevel(android.util.Log.WARN).build()

    /** Cover images: memory cache plus a 100 MB disk cache so covers show offline. */
    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { container.okHttp })) }
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.2).build() }
        .diskCache { DiskCache.Builder().directory(context.cacheDir.resolve("covers")).maxSizeBytes(100L * 1024 * 1024).build() }
        .crossfade(true)
        .build()
}
