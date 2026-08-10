package com.goldenflux.goldenfluxgame.game

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.goldenflux.goldenfluxgame.audio.SoundManager
import com.goldenflux.goldenfluxgame.data.GameAssets
import com.goldenflux.goldenfluxgame.data.SaveRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LoadingState(
    val progress: Float = 0f,
    val label: String = "Igniting the Flux...",
    val finished: Boolean = false,
    val loaded: GameState? = null,
    val offline: OfflineReport? = null,
)

/**
 * Real, progress-synced loading. Each weighted stage does actual work
 * (decoding bitmaps, restoring the save, warming audio) and the bar only
 * reaches 100% once every stage has completed.
 */
class LoadingViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SaveRepository(app)
    private val _state = MutableStateFlow(LoadingState())
    val state: StateFlow<LoadingState> = _state.asStateFlow()

    private var started = false

    // budget of the progress bar allocated per stage (sums to 1.0)
    private val wSave = 0.08f
    private val wAssets = 0.72f
    private val wSound = 0.12f
    private val wOffline = 0.05f
    private val wFinalize = 0.03f

    fun start(sound: SoundManager) {
        if (started) return
        started = true
        viewModelScope.launch {
            var base = 0f

            set(base, "Restoring your factory...")
            val loaded = withContext(Dispatchers.IO) { repo.load() }
            base += wSave
            set(base, "Restoring your factory...")

            // assets: forward the decoder's fractional progress into our budget
            GameAssets.preload(getApplication()) { frac, label ->
                set(base + frac * wAssets, label)
            }
            base += wAssets

            set(base, "Tuning ancient sounds...")
            withContext(Dispatchers.IO) { sound.loadAll() }
            base += wSound
            set(base, "Tuning ancient sounds...")

            set(base, "Calculating idle production...")
            val now = System.currentTimeMillis()
            val (advanced, report) = if (loaded != null)
                GameLogic.applyOffline(loaded, now) else (loaded to null)
            base += wOffline
            set(base, "Calculating idle production...")

            // tiny finalize so 100% coincides with readiness
            set(base + wFinalize * 0.5f, "Almost ready...")
            delay(120)
            _state.value = LoadingState(
                progress = 1f,
                label = "Ready",
                finished = true,
                loaded = advanced ?: loaded,
                offline = report,
            )
        }
    }

    private fun set(progress: Float, label: String) {
        _state.value = _state.value.copy(
            progress = progress.coerceIn(0f, 1f),
            label = label,
        )
    }
}
