package app.bureung.english.car

import ai.pleos.playground.navi.constants.BookmarkType
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NaviMode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.data.BookmarkInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.os.Handler
import android.os.SystemClock

/** The destination being guided: where the lesson is going and how long it has. */
data class Destination(
    val name: String,
    val remainSec: Int,
    val remainMeters: Int,
    val latitude: Double,
    val longitude: Double,
    val atMs: Long,
) {
    /** remaining seconds now, counting down from the last report */
    fun remainNow(nowMs: Long = SystemClock.elapsedRealtime()): Int =
        (remainSec - ((nowMs - atMs) / 1000).toInt()).coerceAtLeast(0)

    /** remaining metres now: the last report, shrinking with the time that has passed since */
    fun metersNow(nowMs: Long = SystemClock.elapsedRealtime()): Int =
        if (remainSec <= 0) remainMeters else (remainMeters.toLong() * remainNow(nowMs) / remainSec).toInt()
}

/**
 * Pleos NaviHelper SDK: destination name, remaining time and distance (polled every 10 s while guiding), arrival,
 * and the HOME bookmark, which tells the way home (하원) from the way to kindergarten (등원).
 */
class PleosNavi(
    private val context: Context,
    private val main: Handler,
    private val log: (String) -> Unit,
    private val onDestination: (Destination?) -> Unit,
    private val onArrived: () -> Unit,
) {
    private var helper: NaviHelper? = null
    var connected = false
        private set
    private var mode: NaviMode? = null
    private var status: NavigationStatus? = null
    var home: Pair<Double, Double>? = null
        private set
    private var polls = 0

    private val guiding: Boolean
        get() = mode == NaviMode.ROUTE_GUIDING || mode == NaviMode.SIMULATION_GUIDING ||
            status == NavigationStatus.ROUTE_DRIVE

    private val poll = object : Runnable {
        override fun run() {
            val h = helper ?: return
            runCatching {
                h.getRouteStateInfo()
                h.getDestinationInfo()
                if (polls++ % 30 == 0) h.getBookmarkInfo()
            }
            main.postDelayed(this, POLL_MS)
        }
    }

    private val listener = object : NaviHelperEventListener {
        override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) {
            main.post {
                mode = routeStateInfo.naviMode
                if (!guiding) onDestination(null)
            }
        }

        override fun onNavigationStatus(navigationStatus: NavigationStatus) {
            main.post { status = navigationStatus }
        }

        override fun onDestinationInfo(destinationInfo: DestinationInfo) {
            val name = destinationInfo.name?.trim().orEmpty().ifBlank { destinationInfo.address?.trim().orEmpty() }
            val sec = destinationInfo.remainTime
            val meters = destinationInfo.remainDistance
            val lat = destinationInfo.latitude
            val lon = destinationInfo.longitude
            main.post {
                // trust a report while guiding, or before the first route state arrives
                val believable = name.isNotBlank() && sec > 0 && (guiding || mode == null)
                onDestination(
                    if (believable) Destination(name.take(40), sec, meters.coerceAtLeast(0), lat, lon, SystemClock.elapsedRealtime())
                    else null,
                )
            }
        }

        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) {
            main.post {
                log("내비: 목적지 도착")
                onArrived()
                onDestination(null)
            }
        }

        override fun onRouteCancelled() {
            main.post { onDestination(null) }
        }

        override fun onBookmarkInfo(bookmarkInfoList: List<BookmarkInfo>) {
            val h = bookmarkInfoList.firstOrNull {
                it.category == BookmarkType.HOME && it.name.isNotBlank() && !(it.latitude == 0.0 && it.longitude == 0.0)
            }
            main.post { home = h?.let { it.latitude to it.longitude } }
        }

        override fun onError(errorCode: NaviErrorCode) {
            main.post { log("내비 오류: $errorCode") }
        }
    }

    fun start() {
        if (helper != null) return
        val h = runCatching { NaviHelper(context.applicationContext) }
            .onFailure { log("NaviHelper 없음: ${it.message}") }
            .getOrNull() ?: return
        helper = h
        runCatching {
            h.initialize(
                {
                    main.post {
                        connected = true
                        runCatching { h.addListener(listener) }
                        log("NaviHelper 연결됨")
                        main.removeCallbacks(poll)
                        main.post(poll)
                    }
                },
                { e ->
                    main.post {
                        connected = false
                        helper = null
                        log("NaviHelper 연결 실패: ${e.message}")
                    }
                },
            )
        }.onFailure {
            helper = null
            log("NaviHelper 초기화 불가: ${it.message}")
        }
    }

    /** Asks for a fresh destination report now (when a lesson is being planned). */
    fun refresh() {
        runCatching { helper?.getRouteStateInfo(); helper?.getDestinationInfo() }
    }

    fun stop() {
        main.removeCallbacks(poll)
        val h = helper ?: return
        runCatching { h.removeListener(listener) }
        runCatching { h.release() }
        helper = null
        connected = false
    }

    private companion object {
        const val POLL_MS = 10_000L
    }
}
