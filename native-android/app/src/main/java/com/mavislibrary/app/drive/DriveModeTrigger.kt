package com.mavislibrary.app.drive

enum class BluetoothAudioKind { CAR, HEADPHONES, SPEAKER, UNKNOWN }

data class DriveModeSettings(val enabled: Boolean, val permissionGranted: Boolean)

object DriveModeTrigger {
    /** Drive mode is deliberately limited to a newly connected car-audio profile. */
    fun shouldEnter(settings: DriveModeSettings, connected: Boolean, kind: BluetoothAudioKind): Boolean =
        settings.enabled && settings.permissionGranted && connected && kind == BluetoothAudioKind.CAR
}
