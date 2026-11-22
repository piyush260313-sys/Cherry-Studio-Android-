package com.cherry.studio

import android.app.Application
import android.os.Build
import java.io.PrintWriter
import java.io.StringWriter

class CherryApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                val report = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) ${Build.MODEL}\n" +
                    "Thread: ${thread.name}\n\n$sw"
                getSharedPreferences("cherry", MODE_PRIVATE).edit().putString("crash", report).commit()
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
        Engine.init(this)
    }
}
