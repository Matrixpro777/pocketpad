package dev.roque.pocketpad

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Giroscopio como stick derecho: la velocidad angular (rad/s) se convierte en un
 * desplazamiento proporcional, con zona muerta y saturación. Al soltar el sensor
 * el stick vuelve al centro, así que no pelea con el dedo cuando se toca.
 */
class Gyro(context: Context, private val input: PadInput) : SensorEventListener {

    var available by mutableStateOf(false)
        private set

    var enabled by mutableStateOf(false)
        private set

    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    init {
        available = sensor != null
    }

    fun toggle() {
        if (enabled) stop() else start()
    }

    fun start() {
        val sensor = sensor ?: return
        enabled = true
        manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        enabled = false
        manager.unregisterListener(this)
        if (!input.stickRightHeld) input.right.reset()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!enabled || input.stickRightHeld) return
        // En horizontal: eje Y del teléfono = giro izquierda/derecha (yaw),
        // eje X = arriba/abajo (pitch). Signos ajustados a la pantalla girada.
        val yaw = -event.values[1]
        val pitch = event.values[0]
        input.right.set(shape(yaw), shape(pitch))
    }

    private fun shape(rate: Float): Float {
        if (kotlin.math.abs(rate) < DEADZONE) return 0f
        val value = (kotlin.math.abs(rate) - DEADZONE) / (SATURATION - DEADZONE)
        return (value.coerceIn(0f, 1f) * (if (rate < 0) -1f else 1f)) * 0.9f
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val DEADZONE = 0.06f   // rad/s
        const val SATURATION = 1.6f  // rad/s ≈ 90 °/s
    }
}
