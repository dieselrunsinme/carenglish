package app.bureung.english.car

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context

/**
 * Standard AAOS vehicle properties: GEAR_SELECTION (Pleos recommends it over its own gear getter) and
 * ENV_OUTSIDE_TEMPERATURE for the weather greeting. Every call is guarded: a missing property or permission only
 * means "unknown". Only constructed when [hasCarApi] says the android.car library is there.
 */
class AaosCar(
    private val context: Context,
    private val log: (String) -> Unit,
    private val onGear: (Gear) -> Unit,
) {
    private var car: Car? = null
    private var props: CarPropertyManager? = null
    private var callback: CarPropertyManager.CarPropertyEventCallback? = null
    var gearWorks = false
        private set

    fun start() {
        if (car != null) return
        val connected = runCatching { Car.createCar(context.applicationContext) }
            .onFailure { log("AAOS Car 서비스 없음: ${it.message}") }
            .getOrNull() ?: return
        car = connected
        val manager = runCatching { connected.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager }.getOrNull()
        props = manager
        if (manager == null) return
        val cb = object : CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(value: CarPropertyValue<*>) {
                if (value.propertyId == VehiclePropertyIds.GEAR_SELECTION) {
                    (value.value as? Int)?.let { onGear(Gear.fromAaos(it)) }
                }
            }

            override fun onErrorEvent(propId: Int, zone: Int) = Unit
        }
        callback = cb
        gearWorks = runCatching {
            manager.registerCallback(cb, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE)
        }.onFailure { log("AAOS 기어 신호 없음: ${it.message}") }.getOrDefault(false)
        if (gearWorks) {
            runCatching { manager.getProperty<Any>(VehiclePropertyIds.GEAR_SELECTION, 0)?.value as? Int }
                .getOrNull()?.let { onGear(Gear.fromAaos(it)) }
            log("AAOS 기어 신호 연결됨")
        }
    }

    /** Outside temperature in °C, or null when the car does not say. Read when a lesson is planned. */
    fun outsideCelsius(): Float? = runCatching {
        props?.getProperty<Any>(VehiclePropertyIds.ENV_OUTSIDE_TEMPERATURE, 0)?.value as? Float
    }.getOrNull()

    fun stop() {
        callback?.let { cb -> runCatching { props?.unregisterCallback(cb) } }
        runCatching { car?.disconnect() }
        car = null
        props = null
        callback = null
        gearWorks = false
    }
}
