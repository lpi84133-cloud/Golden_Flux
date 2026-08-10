package com.goldenflux.goldenfluxgame.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goldenflux.goldenfluxgame.game.GameViewModel
import com.goldenflux.goldenfluxgame.ui.design.AssetIcon
import com.goldenflux.goldenfluxgame.ui.design.GfButton
import com.goldenflux.goldenfluxgame.ui.design.GfProgressBar
import com.goldenflux.goldenfluxgame.ui.design.glow
import com.goldenflux.goldenfluxgame.ui.theme.GfGold
import com.goldenflux.goldenfluxgame.ui.theme.GfGoldLight
import com.goldenflux.goldenfluxgame.ui.theme.GfTextDim
import com.goldenflux.goldenfluxgame.util.formatDuration

@Composable
fun RewardsScreen(gameVm: GameViewModel, onBack: () -> Unit) {
    val hud by gameVm.hud.collectAsState()
    val remaining = remember(hud) { gameVm.rewardRemainingMs() }
    val ready = remaining <= 0L
    val total = com.goldenflux.goldenfluxgame.game.GameLogic.REWARD_COOLDOWN_MS.toFloat()

    ScreenScaffold("Flux Chest", onBack) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            AssetIcon(
                "game/sprites/chest.png",
                Modifier.size(190.dp).glow(GfGold, 0.55f, if (ready) 0.55f else 0.15f),
                alpha = if (ready) 1f else 0.6f,
            )
            Spacer(Modifier.height(18.dp))
            Text(
                if (ready) "Your chest is ready" else "Recharging",
                color = GfGoldLight, fontWeight = FontWeight.Black, fontSize = 20.sp,
            )
            Spacer(Modifier.height(10.dp))
            GfProgressBar(
                fraction = if (ready) 1f else 1f - (remaining / total),
                modifier = Modifier.fillMaxWidth(0.8f),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (ready) "Open it to collect 15 minutes of production" else formatDuration(remaining),
                color = GfTextDim, fontSize = 13.sp, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(22.dp))
            GfButton("Open Chest", enabled = ready, icon = "game/sprites/symbol_1.png") {
                gameVm.claimReward()
            }
        }
    }
}
