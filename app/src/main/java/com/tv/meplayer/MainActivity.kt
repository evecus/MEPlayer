package com.tv.meplayer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.tv.meplayer.controller.MusicPlayerController
import com.tv.meplayer.ui.navigation.AppNavigation
import com.tv.meplayer.ui.theme.TvTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // TV 端：app 进入后台（ON_STOP）即暂停音乐播放，
        // 与手机端不同——TV 端不允许退出 app 后在后台继续播放音乐。
        lifecycle.addObserver(LifecycleEventObserver { _: LifecycleOwner, event: Lifecycle.Event ->
            if (event == Lifecycle.Event.ON_STOP) {
                runCatching { MusicPlayerController.pause() }
            }
        })

        setContent {
            TvTheme {
                AppNavigation()
            }
        }
    }
}
