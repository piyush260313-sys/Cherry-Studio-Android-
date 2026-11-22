package com.cherry.studio

import android.app.Application

class CherryApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Engine.init(this)
    }
}
