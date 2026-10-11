package com.mavislibrary.app.drive

import android.Manifest
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Watches newly connected Bluetooth devices while the opted-in activity is alive. */
class DriveModeMonitor(
    private val context: Context,
    private val enabled: () -> Boolean,
    private val onDriveMode: (Boolean) -> Unit,
) : AutoCloseable {
    private var registered = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val connected = intent.action == BluetoothDevice.ACTION_ACL_CONNECTED
            val device = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            val kind = if (device?.bluetoothClass?.deviceClass == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO) BluetoothAudioKind.CAR
                else BluetoothAudioKind.UNKNOWN
            val permission = Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            if (DriveModeTrigger.shouldEnter(DriveModeSettings(enabled(), permission), connected, kind)) onDriveMode(true)
            if (!connected) onDriveMode(false)
        }
    }

    fun start() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        registered = true
    }

    override fun close() {
        if (registered) context.unregisterReceiver(receiver)
        registered = false
    }
}
