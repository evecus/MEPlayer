package com.tv.meplayer

import android.app.Application
import android.content.Context
import com.tv.meplayer.controller.MusicPlayerController
import com.tv.meplayer.data.StorageService
import com.tv.meplayer.ui.theme.TvThemeState

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        context = this
        StorageService.init(this, boxName = "me_player_tv")
        TvThemeState.init()
        MusicPlayerController.init(this)
    }

    companion object {
        lateinit var context: Context
    }
}
