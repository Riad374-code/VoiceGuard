package com.voiceguard

import android.app.Application
import timber.log.Timber
import com.voiceguard.di.ServiceLocator
import com.voiceguard.util.LogBuffer

class VoiceGuardApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Timber.plant(object : Timber.DebugTree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                super.log(priority, tag, message, t)
                LogBuffer.add(tag ?: "app", message)
            }
        })
        ServiceLocator.init(this)
    }
}

