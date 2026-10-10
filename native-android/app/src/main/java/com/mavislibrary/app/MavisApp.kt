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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

private val Context.settingsDataStore by preferencesDataStore(name = "settings")
private val Context.secureDataStore by preferencesDataStore(name = "secure_store")

/** Hand-wired dependencies (small app; no DI framework needed yet). */
class AppContainer(private val app: Application) {
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
        okHttp.newBuilder()
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("x-mavis-source", "android-native").build())
            }
            .build()
    }

    val database: MavisDatabase by lazy { MavisDatabase.create(app) }
    val connectivity: ConnectivityMonitor by lazy { ConnectivityMonitor(app, scope) }
    val settings: SettingsStore by lazy { SettingsStore(app.settingsDataStore) }
    val secureStore: SecureStore by lazy { SecureStore(app.secureDataStore, KeystoreCipher()) }
    val api: MavisApi by lazy { MavisApi(apiHttp, BuildConfig.API_BASE) }
    val searchRepository: SearchRepository by lazy {
        SearchRepository(CachedFetcher(api, RoomResponseCache(database.cachedResponses()), connectivity::isOnline))
    }
    val shelfRepository: ShelfRepository by lazy { ShelfRepository(database.shelf()) }
}

class MavisApp : Application(), Configuration.Provider, SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
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
