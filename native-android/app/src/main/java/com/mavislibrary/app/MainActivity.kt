package com.mavislibrary.app

import android.graphics.Color
import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import com.mavislibrary.app.lock.LockPolicy
import com.mavislibrary.app.lock.LockScreen
import com.mavislibrary.app.lock.lockEnrollmentAvailable
import com.mavislibrary.app.lock.ownerCodeAllowed
import com.mavislibrary.app.lock.secureWindowRequired
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import com.mavislibrary.app.ui.theme.LightClassifier
import com.mavislibrary.app.ui.theme.LightLevel
import com.mavislibrary.app.ui.theme.LightObserver
import com.mavislibrary.app.ui.theme.lightLook
import com.mavislibrary.app.work.ReminderScheduler
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mavislibrary.app.data.kids.kidsPrefs
import com.mavislibrary.app.ui.MavisRoot
import com.mavislibrary.app.ui.theme.MavisTheme
import com.mavislibrary.app.ui.theme.tokensFor
import com.mavislibrary.app.web.WebHost
import com.mavislibrary.app.drive.DriveModeMonitor
import kotlinx.coroutines.delay
import java.time.LocalTime

class MainActivity : FragmentActivity() {
    companion object {
        const val WIDGET_DESTINATION = "com.mavislibrary.app.WIDGET_DESTINATION"
        private const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL
    }
    private val lockPolicy = LockPolicy()
    private var lockSetting by mutableStateOf<Boolean?>(null)
    private var lockState by mutableStateOf(LockPolicy.State.NEEDS_AUTH)
    private var lockMessage by mutableStateOf<String?>(null)
    private val prompt by lazy {
        BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                lockPolicy.authenticated()
                lockState = lockPolicy.state
                lockMessage = null
                webHost?.onResume()
                lifecycleScope.launch { refreshOwnerCode() }
            }
            override fun onAuthenticationFailed() { lockMessage = "Not recognized. Try again or use your device PIN." }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                lockMessage = when (errorCode) {
                    BiometricPrompt.ERROR_LOCKOUT, BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> "Too many attempts. Use your device PIN or try again later."
                    BiometricPrompt.ERROR_NEGATIVE_BUTTON, BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_CANCELED -> "Unlock cancelled. Tap Unlock to try again."
                    else -> "Couldn't unlock: $errString. Try again."
                }
            }
        })
    }
    private var widgetDestination by mutableStateOf<String?>(null)
    private var widgetRequest by mutableIntStateOf(0)

    private fun acceptWidgetDestination(intent: android.content.Intent?) {
        val requested = intent?.getStringExtra(WIDGET_DESTINATION)
        widgetDestination = requested?.takeIf { it == "shelf" || it in (1..366).map { day -> com.mavislibrary.app.work.VerseOfDay.forDay(day).route } }
        if (widgetDestination != null) widgetRequest++
    }
    private var sharedIntent by mutableStateOf<android.content.Intent?>(null)
    private var webHost: WebHost? = null
    private var enablingReminder = false
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (enablingReminder && granted) turnReminderOn()
        enablingReminder = false
    }
    private var driveEnabled = false
    private var readerKeys: com.mavislibrary.app.web.ReaderMediaSession? = null
    private var driveMonitor: DriveModeMonitor? = null
    private var driveModeActive by mutableStateOf(false)
    private val lightObserver by lazy { LightObserver(applicationContext) }
    private var lightLevel by mutableStateOf<LightLevel?>(null)
    private var kidsModeEnabled = true
    private var kidsModeLoaded = false
    private var pendingDebugDrive = false
    private val askBluetooth = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && driveEnabled) startDriveMonitor()
    }

    /** Robolectric has no real Chromium, so the JVM shell tests run without the WebView. */
    private val useWebHost: Boolean get() = android.os.Build.FINGERPRINT != "robolectric"

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        acceptWidgetDestination(intent)
        if (savedInstanceState == null) sharedIntent = intent?.takeIf { it.action in setOf(android.content.Intent.ACTION_SEND, android.content.Intent.ACTION_VIEW) }
        pendingDebugDrive = BuildConfig.DEBUG && savedInstanceState == null && intent?.action == "com.mavislibrary.app.DEBUG_DRIVE"
        lifecycleScope.launch {
            kidsPrefs().state.collect { state ->
                kidsModeEnabled = state.enabled
                kidsModeLoaded = true
                refreshOwnerCode()
                if (state.enabled) {
                    pendingDebugDrive = false
                    configureDriveMode(false)
                } else if (pendingDebugDrive) {
                    pendingDebugDrive = false
                    window.decorView.post { setDriveScreen(true) }
                }
            }
        }
        val container = (application as MavisApp).container
        webHost = if (useWebHost) WebHost(this) else null
        lifecycleScope.launch {
            container.settings.appLockEnabled.collect { enabled ->
                if (enabled && !lockEnrollmentAvailable(BiometricManager.from(this@MainActivity).canAuthenticate(AUTHENTICATORS))) {
                    lockMessage = "Set up a device PIN, face, or fingerprint before using app lock. Lock has been turned off."
                    container.settings.setAppLockEnabled(false)
                    return@collect
                }
                if (secureWindowRequired(enabled)) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                lockPolicy.setEnabled(enabled)
                lockState = lockPolicy.state
                lockSetting = enabled
                refreshOwnerCode()
            }
        }
        lifecycleScope.launch {
            container.secureStore.has(com.mavislibrary.app.data.secure.Secret.OWNER_ACCESS_CODE).collect {
                refreshOwnerCode()
            }
        }

        setContent {
            val choice by container.settings.lookChoice.collectAsStateWithLifecycle(initialValue = null)
            val matchRoomLight by container.settings.matchRoomLight.collectAsStateWithLifecycle(initialValue = false)
            LaunchedEffect(matchRoomLight) {
                lightLevel = null
                if (matchRoomLight && lightObserver.available) {
                    repeatOnLifecycle(Lifecycle.State.STARTED) {
                        val classifier = LightClassifier()
                        try {
                            lightObserver.readings().collect { lightLevel = classifier.classify(it) }
                        } finally { lightLevel = null }
                    }
                }
            }
            val online by container.connectivity.online.collectAsStateWithLifecycle()
            val playbackSettings by container.settings.playback.collectAsStateWithLifecycle(initialValue = com.mavislibrary.app.data.prefs.PlaybackSettings())
            val kidsPrefs = remember { kidsPrefs() }
            val kidsState by kidsPrefs.state.collectAsStateWithLifecycle(initialValue = null)
            val now by produceState(LocalTime.now()) {
                while (true) {
                    delay(60_000)
                    value = LocalTime.now()
                }
            }
            val resolved = choice?.resolve(now)?.let { lightLook(it, kidsState?.enabled == true, matchRoomLight, lightObserver.available, lightLevel) }
            if (resolved == null) {
                Box(Modifier.fillMaxSize().background(colorResource(R.color.window_background)))
                return@setContent
            }
            val dark = tokensFor(resolved).isDark
            LaunchedEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            MavisTheme(resolved) {
                if (lockSetting == null || kidsState == null) {
                    Box(Modifier.fillMaxSize().background(colorResource(R.color.window_background)))
                    return@MavisTheme
                }
                if (lockState != LockPolicy.State.UNLOCKED && kidsState?.enabled == false) {
                    LockScreen(lockMessage, ::unlock)
                    return@MavisTheme
                }
                LaunchedEffect(playbackSettings.driveModeEnabled, kidsState?.enabled) {
                    configureDriveMode(playbackSettings.driveModeEnabled && kidsState?.enabled == false)
                }
                MavisRoot(
                    container, webHost, online,
                    sharedIntent = sharedIntent,
                    onShareConsumed = { consumed ->
                        if (sharedIntent === consumed) {
                            sharedIntent = null
                            if (intent?.action in setOf(android.content.Intent.ACTION_SEND, android.content.Intent.ACTION_VIEW)) {
                                setIntent(android.content.Intent(android.content.Intent.ACTION_MAIN).setClass(this@MainActivity, MainActivity::class.java).addCategory(android.content.Intent.CATEGORY_LAUNCHER))
                            }
                        }
                    },
                    onFirstAudio = ::requestNotificationPermission,
                    onEnableReminder = ::enableReadingReminder,
                    onAppLockChange = ::changeAppLock,
                    driveModeActive = driveModeActive,
                    onDriveModeChange = ::configureDriveMode,
                    onCloseDriveMode = { setDriveScreen(false) },
                    widgetDestination = widgetDestination,
                    widgetRequest = widgetRequest,
                )
            }
        }
    }

    private suspend fun refreshOwnerCode() {
        if (!ownerCodeAllowed(lockSetting, lockState, kidsModeLoaded, kidsModeEnabled)) {
            webHost?.setOwnerCode(null)
            return
        }
        val code = (application as MavisApp).container.secureStore.read(com.mavislibrary.app.data.secure.Secret.OWNER_ACCESS_CODE)
        if (ownerCodeAllowed(lockSetting, lockState, kidsModeLoaded, kidsModeEnabled)) webHost?.setOwnerCode(code)
    }

    private fun changeAppLock(enabled: Boolean) {
        if (enabled && !lockEnrollmentAvailable(BiometricManager.from(this).canAuthenticate(AUTHENTICATORS))) {
            Toast.makeText(this, "Set up a device PIN, face, or fingerprint first. Lock is still off.", Toast.LENGTH_LONG).show()
            return
        }
        if (secureWindowRequired(enabled)) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        lockPolicy.setEnabled(enabled)
        lockState = lockPolicy.state
        if (enabled) webHost?.setOwnerCode(null)
        lifecycleScope.launch {
            refreshOwnerCode()
            (application as MavisApp).container.settings.setAppLockEnabled(enabled)
        }
    }

    private fun unlock() {
        if (!lockEnrollmentAvailable(BiometricManager.from(this).canAuthenticate(AUTHENTICATORS))) {
            lockMessage = "No device PIN, face, or fingerprint is available. App lock has been turned off so you aren't locked out."
            changeAppLock(false)
            return
        }
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Mavis")
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build())
    }

    private fun enableReadingReminder() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            enablingReminder = true
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else turnReminderOn()
    }

    private fun turnReminderOn() {
        lifecycleScope.launch {
            (application as MavisApp).container.settings.setReminderEnabled(true)
            ReminderScheduler.sync(this@MainActivity)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun configureDriveMode(enabled: Boolean) {
        driveEnabled = enabled && !kidsModeEnabled
        if (!driveEnabled) {
            driveMonitor?.close(); driveMonitor = null; driveModeActive = false
            window.attributes = window.attributes.apply { screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
            return
        }
        if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            askBluetooth.launch(Manifest.permission.BLUETOOTH_CONNECT)
        } else startDriveMonitor()
    }

    private fun startDriveMonitor() {
        if (kidsModeEnabled || !driveEnabled || driveMonitor != null) return
        driveMonitor = DriveModeMonitor(this, { driveEnabled }) { active ->
            setDriveScreen(active)
        }.also { it.start() }
    }

    private fun setDriveScreen(active: Boolean) {
        if (active && kidsModeEnabled) return
        driveModeActive = active
        window.attributes = window.attributes.apply {
            screenBrightness = if (active) .12f else android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
    }

    /** Debug builds only: `am start -a com.mavislibrary.app.DEBUG_DRIVE` shows the drive screen without a car. */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        acceptWidgetDestination(intent)
        if (intent.action in setOf(android.content.Intent.ACTION_SEND, android.content.Intent.ACTION_VIEW)) {
            sharedIntent = android.content.Intent(intent)
        }
        if (BuildConfig.DEBUG && intent.action == "com.mavislibrary.app.DEBUG_DRIVE") {
            if (!kidsModeLoaded) pendingDebugDrive = true
            else if (!kidsModeEnabled) setDriveScreen(true)
        }
    }

    override fun onStart() {
        lockPolicy.foreground()
        lockState = lockPolicy.state
        if (lockState != LockPolicy.State.UNLOCKED) webHost?.setOwnerCode(null)
        super.onStart()
    }

    override fun onResume() {
        super.onResume()
        if (lockSetting == true && lockState != LockPolicy.State.UNLOCKED) return
        webHost?.onResume()
        readerKeys?.close()
        readerKeys = com.mavislibrary.app.web.ReaderMediaSession(this) { code ->
            webHost?.handlePageTurnKey(KeyEvent(KeyEvent.ACTION_DOWN, code)) == true
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        if (lockState == LockPolicy.State.UNLOCKED && webHost?.handlePageTurnKey(event) == true) true else super.onKeyDown(keyCode, event)

    override fun onStop() {
        lockPolicy.background()
        super.onStop()
    }

    override fun onPause() {
        readerKeys?.close(); readerKeys = null
        webHost?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        driveMonitor?.close()
        driveMonitor = null
        webHost?.destroy()
        webHost = null
        super.onDestroy()
    }
}
