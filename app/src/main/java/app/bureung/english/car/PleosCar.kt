package app.bureung.english.car

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.WiperStateListener
import android.content.Context
import android.os.Handler

/**
 * Pleos Vehicle SDK: the windshield wipers (a real-time cue that it is raining, for the surprise word) and the gear
 * as a fallback when the AAOS gear property is not readable.
 */
class PleosCar(
    private val context: Context,
    private val main: Handler,
    private val log: (String) -> Unit,
    private val onGear: (Gear) -> Unit,
    private val onWipers: (Boolean) -> Unit,
) {
    private var vehicle: Vehicle? = null
    private val windshield = WindowArea(WindowValue.FRONT_WINDSHIELD)
    private var gearListening = false
    private var wiperListening = false

    private val gearListener = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) {
            main.post { onGear(Gear.fromPleosName(gear?.name)) }
        }

        override fun onFailed(e: Exception) {
            main.post { log("Pleos 기어 신호 실패: ${e.message}") }
        }
    }

    private val wiperListener = object : WiperStateListener {
        override fun onWiperStateUpdated(state: WiperState?) {
            main.post { onWipers(wiping(state)) }
        }

        override fun onFailed(e: Exception) {
            main.post { log("Pleos 와이퍼 신호 실패: ${e.message}") }
        }
    }

    fun start(wantGear: Boolean) {
        val v = vehicle ?: runCatching { Vehicle(context.applicationContext).apply { initialize() } }
            .onFailure { log("Pleos Vehicle 연결 실패: ${it.message}") }
            .getOrNull()
            ?.also { vehicle = it }
            ?: return
        if (!wiperListening) {
            wiperListening = runCatching {
                v.getWiper().registerWiperState(windshield, wiperListener)
                v.getWiper().getCurrentWiperState(
                    windshield,
                    { state -> main.post { onWipers(wiping(state)) } },
                    { e -> main.post { log("와이퍼 읽기 실패: ${e.message}") } },
                )
            }.onFailure { log("Pleos 와이퍼 신호 없음: ${it.message}") }.isSuccess
            if (wiperListening) log("Pleos 와이퍼 신호 연결됨")
        }
        if (wantGear && !gearListening) {
            gearListening = runCatching {
                v.getGear().registerGearSelection(gearListener)
                v.getGear().getCurrentGearSelectionState(
                    { gear -> main.post { onGear(Gear.fromPleosName(gear?.name)) } },
                    { e -> main.post { log("기어 읽기 실패: ${e.message}") } },
                )
            }.onFailure { log("Pleos 기어 신호 없음: ${it.message}") }.isSuccess
            if (gearListening) log("Pleos 기어 신호 연결됨")
        }
    }

    /** OFF (and unknown) means dry; any wiping state means rain. */
    private fun wiping(state: WiperState?): Boolean {
        val name = state?.name ?: return false
        return name != "OFF" && name != "UNKNOWN" && name != "NONE"
    }

    fun stop() {
        val v = vehicle ?: return
        if (gearListening) runCatching { v.getGear().unregisterGearSelection(gearListener) }
        if (wiperListening) runCatching { v.getWiper().unregisterWiperState(windshield, wiperListener) }
        gearListening = false
        wiperListening = false
        runCatching { v.release() }
        vehicle = null
    }
}
