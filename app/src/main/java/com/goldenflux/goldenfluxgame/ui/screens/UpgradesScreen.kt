package com.goldenflux.goldenfluxgame.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goldenflux.goldenfluxgame.game.GameLogic
import com.goldenflux.goldenfluxgame.game.GameViewModel
import com.goldenflux.goldenfluxgame.game.UpgradeTrack
import com.goldenflux.goldenfluxgame.ui.design.AssetIcon
import com.goldenflux.goldenfluxgame.ui.design.GfButton
import com.goldenflux.goldenfluxgame.ui.design.GlassRow
import com.goldenflux.goldenfluxgame.ui.design.OrnatePanel
import com.goldenflux.goldenfluxgame.ui.design.SectionHeader
import com.goldenflux.goldenfluxgame.ui.design.glow
import com.goldenflux.goldenfluxgame.ui.theme.GfGold
import com.goldenflux.goldenfluxgame.ui.theme.GfGoldLight
import com.goldenflux.goldenfluxgame.ui.theme.GfGreen
import com.goldenflux.goldenfluxgame.ui.theme.GfTextDim
import com.goldenflux.goldenfluxgame.ui.theme.GfTextPrimary
import com.goldenflux.goldenfluxgame.util.formatCompact

@Composable
fun UpgradesScreen(gameVm: GameViewModel, onBack: () -> Unit) {
    val state by gameVm.state.collectAsState()
    val stats by gameVm.stats.collectAsState()

    ScreenScaffold("Upgrades", onBack, subtitle = "Multiply the whole factory") {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {

            OrnatePanel(title = "Golden Flux Core") {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    AssetIcon(
                        "game/sprites/core.png",
                        Modifier.size(130.dp).glow(GfGold, 0.55f, 0.45f),
                    )
                }
                Text(
                    "Level ${state.coreLevel}",
                    color = GfGoldLight, fontWeight = FontWeight.Black, fontSize = 18.sp,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "All production x${String.format("%.2f", GameLogic.coreMultiplier(state.coreLevel))}   ·   capacity +${formatCompact(250.0 * state.coreLevel)}",
                    color = GfTextDim, fontSize = 12.sp,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Next level: x${String.format("%.2f", GameLogic.coreMultiplier(state.coreLevel + 1))} and stronger taps",
                    color = GfGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(12.dp))
                val cost = GameLogic.coreUpgradeCost(state.coreLevel)
                GfButton(
                    text = "Upgrade  ·  ${formatCompact(cost.energy)}E  ${formatCompact(cost.crystals)}C",
                    modifier = Modifier.fillMaxWidth(),
                    enabled = GameLogic.canAfford(state, cost),
                    icon = "game/sprites/upgrade_crystal.png",
                ) { gameVm.upgradeCore() }
            }

            Spacer(Modifier.height(18.dp))
            SectionHeader("Research")
            Spacer(Modifier.height(8.dp))

            UpgradeTrack.entries.forEach { track ->
                val level = gameVm.levelOf(state, track)
                val cost = GameLogic.trackUpgradeCost(track, level)
                val afford = GameLogic.canAfford(state, cost)
                GlassRow(Modifier.padding(vertical = 5.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        AssetIcon(track.icon, Modifier.size(40.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${track.title}  ·  Lv $level",
                                color = GfTextPrimary, fontWeight = FontWeight.Black, fontSize = 14.sp,
                            )
                            Text(track.description, color = GfTextDim, fontSize = 11.sp)
                        }
                        GfButton(
                            "${formatCompact(cost.crystals)} C",
                            enabled = afford,
                            compact = true,
                        ) { gameVm.upgradeTrack(track) }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            GlassRow {
                Column(Modifier.padding(12.dp)) {
                    Text("Current output", color = GfGoldLight, fontWeight = FontWeight.Black, fontSize = 13.sp)
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${formatCompact(stats.energyPerSec)} energy/s", color = GfGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text("${formatCompact(stats.crystalsPerSec)} crystals/s", color = GfGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
