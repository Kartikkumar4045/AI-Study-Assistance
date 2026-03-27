package com.kartik.aistudyassistant.core.utils

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs

class SmartSensorManager(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)

    private var isFaceDown = false
    private var isLowLight = false

    var onFocusModeChanged: ((Boolean) -> Unit)? = null
    var onEyeCareChanged: ((Boolean) -> Unit)? = null

    fun start() {
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        lightSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> handleAccelerometer(event.values)
            Sensor.TYPE_LIGHT -> handleLightSensor(event.values[0])
        }
    }

    private fun handleAccelerometer(values: FloatArray) {
        val z = values[2]
        // Z-axis is approx -9.8 when face down
        val faceDown = z < -8.5

        if (faceDown != isFaceDown) {
            isFaceDown = faceDown
            onFocusModeChanged?.invoke(isFaceDown)
        }
    }

    private fun handleLightSensor(lux: Float) {
        // Threshold for low light (e.g., 10 lux)
        val lowLight = lux < 10f

        if (lowLight != isLowLight) {
            isLowLight = lowLight
            onEyeCareChanged?.invoke(isLowLight)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not needed for this implementation
    }
}
