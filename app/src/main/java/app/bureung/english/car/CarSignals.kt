package app.bureung.english.car

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.SystemClock

/** True when the android.car library is on this device (any AAOS head unit, including the Pleos emulator). */
fun hasCarApi(context: Context): Boolean =
    context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE) &&
        runCatching { Class.forName("android.car.Car") }.isSuccess

/**
 * Everything the lesson reads from the car, in one place: gear (driving or parked), wipers (rain), outside
 * temperature, and the guided destination. Demo overrides exist for recording the video without a Virtual Console;
 * they are labelled as such on screen.
 */
class CarSignals(private val context: Context, private val main: Handler, private val log: (String) -> Unit) {
    private val wipersKeptOn = Runnable { setRain(true) }
    interface Listener {
        fun onGearChanged(old: Gear, new: Gear)
        fun onRainStarted()
        fun onDestinationChanged(destination: Destination?)
        fun onArrived()
    }

    var listener: Listener? = null

    private val aaos: AaosCar? = if (hasCarApi(context)) {
        runCatching { AaosCar(context, log) { g -> setGear(g) } }.getOrNull()
    } else {
        null
    }
    private val pleos = PleosCar(context, main, log, onGear = { g -> if (aaos?.gearWorks != true) setGear(g) }, onWipers = ::setWipers)
    val navi = PleosNavi(context, main, log, onDestination = ::setDestination, onArrived = { listener?.onArrived() })

    private var realGear = Gear.UNKNOWN
    private var realRain = false
    private var realDestination: Destination? = null

    /** demo overrides */
    var demoGear: Gear? = null
        private set
    var demoRain = false
        private set
    var demoDestination: Destination? = null
        private set
    var demoTemp: Float? = null
        private set

    val gear: Gear get() = demoGear ?: realGear
    /** P, or no gear signal at all (a phone-size emulator): show the full screen */
    val parked: Boolean get() = gear == Gear.PARK || gear == Gear.UNKNOWN
    val driving: Boolean get() = gear == Gear.DRIVE || gear == Gear.REVERSE || gear == Gear.NEUTRAL
    val raining: Boolean get() = demoRain || realRain
    val destination: Destination? get() = demoDestination ?: realDestination
    val home: Pair<Double, Double>? get() = navi.home
    val usingDemo: Boolean get() = demoGear != null || demoRain || demoDestination != null || demoTemp != null

    private var outside: Float? = null
    private var outsideAt = 0L

    /** outside temperature, read from the car at most once a minute */
    fun outsideCelsius(): Float? {
        demoTemp?.let { return it }
        val now = SystemClock.elapsedRealtime()
        if (outsideAt == 0L || now - outsideAt > 60_000L) {
            outside = aaos?.outsideCelsius()
            outsideAt = now
        }
        return outside
    }

    fun start() {
        aaos?.start()
        pleos.start(wantGear = aaos?.gearWorks != true)
        navi.start()
    }

    fun stop() {
        aaos?.stop()
        pleos.stop()
        navi.stop()
    }

    private fun setGear(g: Gear) {
        val old = gear
        realGear = g
        if (gear != old) listener?.onGearChanged(old, gear)
    }

    /**
     * The wipers alone do not mean rain: a squirt of washer fluid or one mist wipe is over in a few seconds. Rain is
     * the wipers kept going for about five wipes ([RAIN_AFTER_MS]); it is over as soon as they are switched off.
     */
    private fun setWipers(on: Boolean) {
        main.removeCallbacks(wipersKeptOn)
        if (on) {
            if (!realRain) main.postDelayed(wipersKeptOn, RAIN_AFTER_MS)
        } else {
            setRain(false)
        }
    }

    private fun setRain(on: Boolean) {
        val was = raining
        realRain = on
        if (raining && !was) listener?.onRainStarted()
        if (raining != was) listener?.onDestinationChanged(destination)
    }

    private fun setDestination(d: Destination?) {
        realDestination = d
        if (demoDestination == null) listener?.onDestinationChanged(d)
    }

    // --- demo tools (settings ▸ 시연 도구) ------------------------------------------------------------------------

    fun demoSetDriving(drive: Boolean) {
        val old = gear
        demoGear = if (drive) Gear.DRIVE else Gear.PARK
        if (gear != old) listener?.onGearChanged(old, gear)
    }

    fun demoSetRain(on: Boolean) {
        val was = raining
        demoRain = on
        if (raining && !was) listener?.onRainStarted()
        listener?.onDestinationChanged(destination)
    }

    /** A pretend route: [name] in [minutes], counting down in real time. */
    fun demoRoute(name: String, seconds: Int) {
        demoDestination = Destination(name, seconds, seconds * 8, 0.0, 0.0, SystemClock.elapsedRealtime())
        listener?.onDestinationChanged(destination)
    }

    /** A pretend outside temperature (null: the car's own reading again). */
    fun demoSetTemp(celsius: Float?) {
        demoTemp = celsius
        listener?.onDestinationChanged(destination)
    }

    /** No route any more (without arriving). */
    fun demoClearRoute() {
        demoDestination = null
        listener?.onDestinationChanged(destination)
    }

    fun demoArrive() {
        demoDestination = null
        listener?.onArrived()
        listener?.onDestinationChanged(destination)
    }

    fun clearDemo() {
        val old = gear
        demoGear = null
        demoRain = false
        demoDestination = null
        demoTemp = null
        if (gear != old) listener?.onGearChanged(old, gear)
        listener?.onDestinationChanged(destination)
    }

    private companion object {
        /** the wipers kept going this long (about five wipes at the slow setting) count as rain */
        const val RAIN_AFTER_MS = 7_000L
    }
}
