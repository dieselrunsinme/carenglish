package app.bureung.english.voice

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * What the car says about its microphones. An app does not choose a seat: it asks for a "voice recognition" input
 * and the car's audio system decides which microphone (or mix of microphones) that is. This only reports it, so
 * the answer can be read on the car itself (설정 ▸ 상태).
 */
object Mics {
    fun describe(d: AudioDeviceInfo): String {
        val kind = when (d.type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "내장 마이크"
            AudioDeviceInfo.TYPE_BUS -> "차량 오디오 버스"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "블루투스"
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "유선 헤드셋"
            AudioDeviceInfo.TYPE_TELEPHONY -> "전화"
            AudioDeviceInfo.TYPE_FM_TUNER -> "라디오"
            AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "내부 믹스"
            else -> "종류 ${d.type}"
        }
        val address = d.address?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""
        val channels = d.channelCounts?.maxOrNull()?.takeIf { it > 0 }?.let { " · ${it}채널" } ?: ""
        return "$kind$address$channels"
    }

    /** every input the car exposes to apps */
    fun all(context: Context): List<String> = runCatching {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.getDevices(AudioManager.GET_DEVICES_INPUTS).map(::describe)
    }.getOrDefault(emptyList())
}
