package com.drinkcat.nekopost

import android.app.Application

class NekopostApp : Application() {
    /** Manual dependency container; created once per process. */
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
