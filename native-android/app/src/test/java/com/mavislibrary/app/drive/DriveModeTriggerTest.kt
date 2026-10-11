package com.mavislibrary.app.drive

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveModeTriggerTest {
    @Test fun `only opted-in permitted connected car enters drive mode`() {
        assertTrue(DriveModeTrigger.shouldEnter(DriveModeSettings(true, true), true, BluetoothAudioKind.CAR))
        assertFalse(DriveModeTrigger.shouldEnter(DriveModeSettings(false, true), true, BluetoothAudioKind.CAR))
        assertFalse(DriveModeTrigger.shouldEnter(DriveModeSettings(true, false), true, BluetoothAudioKind.CAR))
        assertFalse(DriveModeTrigger.shouldEnter(DriveModeSettings(true, true), false, BluetoothAudioKind.CAR))
        assertFalse(DriveModeTrigger.shouldEnter(DriveModeSettings(true, true), true, BluetoothAudioKind.HEADPHONES))
    }
}
