package com.mobile.meplayer

import android.app.Application
import android.content.Context
import com.mobile.meplayer.controller.MusicPlayerController
import com.mobile.meplayer.data.StorageService

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        context = this
        StorageService.init(this)
        MusicPlayerController.init(this)
    }

    companion object {
        lateinit var context: Context
    }
}
