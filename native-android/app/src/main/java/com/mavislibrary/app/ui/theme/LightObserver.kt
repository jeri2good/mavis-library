package com.mavislibrary.app.ui.theme

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEventListener
import android.hardware.SensorEvent
import android.hardware.SensorManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

enum class LightLevel { DAY, EVENING, DARK }

class LightClassifier(private val clock: () -> Long = System::currentTimeMillis) {
    var level: LightLevel? = null
        private set
    private var pending: LightLevel? = null
    private var since = 0L

    fun classify(lux: Float): LightLevel? {
        if (!lux.isFinite() || lux < 0f) return level
        val next = when (level) {
            null -> when { lux < 8f -> LightLevel.DARK; lux > 120f -> LightLevel.DAY; else -> LightLevel.EVENING }
            LightLevel.DARK -> if (lux > 20f) if (lux > 120f) LightLevel.DAY else LightLevel.EVENING else LightLevel.DARK
            LightLevel.DAY -> if (lux < 8f) LightLevel.DARK else if (lux < 100f) LightLevel.EVENING else LightLevel.DAY
            LightLevel.EVENING -> when { lux < 8f -> LightLevel.DARK; lux > 120f -> LightLevel.DAY; else -> LightLevel.EVENING }
        }
        if (level == null) level = next
        else if (next == level) pending = null
        else if (pending != next) { pending = next; since = clock() }
        else if (clock() - since >= 5_000) { level = next; pending = null }
        return level
    }
}

class LightObserver(context: Context) {
    private val sensors = context.getSystemService(SensorManager::class.java)
    val available: Boolean get() = sensors?.getDefaultSensor(Sensor.TYPE_LIGHT) != null

    fun readings(): Flow<Float> = callbackFlow {
        val sensor = sensors?.getDefaultSensor(Sensor.TYPE_LIGHT)
        if (sensor == null) { close(); return@callbackFlow }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) { trySend(event.values[0]) }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        if (!sensors.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)) {
            close(); return@callbackFlow
        }
        awaitClose { sensors.unregisterListener(listener) }
    }
}

fun lightLook(chosen: Look, kidsMode: Boolean, enabled: Boolean, sensorAvailable: Boolean, level: LightLevel?): Look =
    if (kidsMode) Look.KIDS else if (enabled && sensorAvailable && level != null && level != LightLevel.DAY) Look.MIDNIGHT else chosen
