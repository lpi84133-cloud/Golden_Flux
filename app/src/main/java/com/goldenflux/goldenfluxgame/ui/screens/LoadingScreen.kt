package com.goldenflux.goldenfluxgame.ui.screens

import android.content.res.Configuration
import android.graphics.BitmapFactory
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.goldenflux.goldenfluxgame.game.GameViewModel
import com.goldenflux.goldenfluxgame.game.LoadingViewModel
import com.goldenflux.goldenfluxgame.ui.theme.GfBackgroundDeep
import com.goldenflux.goldenfluxgame.ui.theme.GfGold
import com.goldenflux.goldenfluxgame.ui.theme.GfGoldLight
import com.goldenflux.goldenfluxgame.ui.theme.GfPanelBorder
import com.goldenflux.goldenfluxgame.ui.theme.GfTextPrimary

@Composable
fun LoadingScreen(
    gameVm: GameViewModel,
    onFinished: () -> Unit,
) {
    val loadingVm: LoadingViewModel = viewModel()
    val ui by loadingVm.state.collectAsState()
    val context = LocalContext.current
    val portrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT

    LaunchedEffect(Unit) { loadingVm.start(gameVm.sound) }

    LaunchedEffect(ui.finished) {
        if (ui.finished) {
            gameVm.initFrom(ui.loaded, ui.offline)
            onFinished()
        }
    }

    val bgAsset = if (portrait) "game/loading_vertical.webp" else "game/loading_horizontal.webp"
    val bg by produceState<ImageBitmap?>(initialValue = null, bgAsset) {
        value = runCatching {
            context.assets.open(bgAsset).use { BitmapFactory.decodeStream(it).asImageBitmap() }
        }.getOrNull()
    }

    val animated by animateFloatAsState(
        targetValue = ui.progress,
        animationSpec = tween(300),
        label = "progress",
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF2A1650), GfBackgroundDeep))),
    ) {
        bg?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Bottom,
        ) {
            androidx.compose.material3.Text(
                text = ui.label,
                color = GfTextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 10.dp),
            )
            ProgressBar(fraction = animated)
            androidx.compose.material3.Text(
                text = "${(ui.progress * 100).toInt()}%",
                color = GfGoldLight,
                fontSize = 14.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun ProgressBar(fraction: Float) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(22.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xAA0E0720))
            .border(1.5.dp, GfPanelBorder, RoundedCornerShape(12.dp)),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction)
                .clip(RoundedCornerShape(12.dp))
                .background(Brush.horizontalGradient(listOf(GfGoldLight, GfGold, Color(0xFFFFA726)))),
        )
    }
}
