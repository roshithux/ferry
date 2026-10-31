package app.ferry

import android.app.Application

class FerryApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Ferry.init(this)
    }
}
