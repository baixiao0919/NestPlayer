package com.cloudnest.player

import android.app.Application
import com.cloudnest.player.data.Store
import com.cloudnest.player.net.StreamProxy

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        StreamProxy.start()
    }
}
