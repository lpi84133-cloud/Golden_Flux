package com.goldenflux.goldenfluxgame.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goldenflux.goldenfluxgame.data.GameAssets
import com.goldenflux.goldenfluxgame.game.GameLogic
import com.goldenflux.goldenfluxgame.game.GameViewModel
import com.goldenflux.goldenfluxgame.game.ZoneId
import com.goldenflux.goldenfluxgame.ui.design.AssetIcon
import com.goldenflux.goldenfluxgame.ui.design.GfButton
import com.goldenflux.goldenfluxgame.ui.design.GfButtonStyle
import com.goldenflux.goldenfluxgame.ui.theme.GfGoldDeep
import com.goldenflux.goldenfluxgame.ui.theme.GfGoldLight
import com.goldenflux.goldenfluxgame.ui.theme.GfTextDim
import com.goldenflux.goldenfluxgame.util.formatCompact

@Composable
fun ZonesScreen(gameVm: GameViewModel, onEnter: () -> Unit, onBack: () -> Unit) {
    val state by gameVm.state.collectAsState()
    val current by gameVm.selectedZone.collectAsState()

    ScreenScaffold("Zones", onBack, subtitle = "Every zone adds more space to build") {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            ZoneId.entries.forEach { zone ->
                val z = state.zones[zone]
                val unlocked = z?.unlocked == true
                val active = unlocked && zone == current

                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 7.dp)
                        .height(140.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .drawBehind {
                            drawRoundRect(
                                brush = Brush.verticalGradient(
                                    if (active) listOf(GfGoldLight, GfGoldDeep)
                                    else listOf(Color(0x88B68A2E), Color(0x55B68A2E))
                                ),
                                cornerRadius = CornerRadius(20.dp.toPx()),
                                style = Stroke(width = if (active) 4f else 2f),
                            )
                        },
                ) {
                    GameAssets[zone.background]?.let {
                        Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(
                                Brush.horizontalGradient(listOf(Color(0xF00E0720), Color(0x660E0720)))
                            )
                    )
                    Row(Modifier.fillMaxSize().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                zone.title,
                                color = GfGoldLight, fontWeight = FontWeight.Black, fontSize = 18.sp,
                            )
                            Text(zone.subtitle, color = GfTextDim, fontSize = 11.sp)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                if (unlocked)
                                    "${z?.devices?.size ?: 0} / ${zone.tiles} platforms used"
                                else
                                    "Unlock for ${formatCompact(zone.unlockEnergy)}E  ${formatCompact(zone.unlockCrystals)}C",
                                color = GfTextDim, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            )
                            Spacer(Modifier.height(8.dp))
                            if (unlocked) {
                                GfButton(
                                    if (active) "Current zone" else "Enter",
                                    style = if (active) GfButtonStyle.Stone else GfButtonStyle.Gold,
                                    compact = true,
                                ) { gameVm.selectZone(zone); onEnter() }
                            } else {
                                val cost = GameLogic.zoneUnlockCost(zone)
                                GfButton(
                                    "Unlock",
                                    enabled = GameLogic.canAfford(state, cost),
                                    compact = true,
                                    icon = "game/sprites/portal.png",
                                ) { if (gameVm.unlockZone(zone)) onEnter() }
                            }
                        }
                        if (!unlocked) {
                            AssetIcon("game/sprites/portal.png", Modifier.size(64.dp), alpha = 0.4f)
                        }
                    }
                }
            }
        }
    }
}
