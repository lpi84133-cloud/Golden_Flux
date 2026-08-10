package com.goldenflux.goldenfluxgame

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.goldenflux.goldenfluxgame.game.GameViewModel
import com.goldenflux.goldenfluxgame.ui.GoldenFluxApp
import com.goldenflux.goldenfluxgame.ui.theme.GoldenFluxTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GoldenFluxTheme {
                val gameVm: GameViewModel = viewModel()

                // pause/resume ambient audio and persist with the lifecycle
                val owner = LocalLifecycleOwner.current
                DisposableEffect(owner) {
                    val observer = LifecycleEventObserver { _, event ->
                        when (event) {
                            Lifecycle.Event.ON_RESUME -> gameVm.sound.applyMusicSetting()
                            Lifecycle.Event.ON_PAUSE -> { gameVm.sound.pauseAmbient(); gameVm.persist() }
                            else -> {}
                        }
                    }
                    owner.lifecycle.addObserver(observer)
                    onDispose { owner.lifecycle.removeObserver(observer) }
                }

                GoldenFluxApp(gameVm)
            }
        }
    }
}
