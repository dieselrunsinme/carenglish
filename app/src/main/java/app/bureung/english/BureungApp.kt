package app.bureung.english

import android.app.Application

class BureungApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Hub.init(this)
    }
}
