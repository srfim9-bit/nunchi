package app.nunchi

import android.app.Application

class NunchiApp : Application() {
    lateinit var runtime: CareRuntime
        private set

    override fun onCreate() {
        super.onCreate()
        runtime = CareRuntime(this)
    }
}
