package com.goldenflux.goldenfluxgame.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goldenflux.goldenfluxgame.game.DeviceType
import com.goldenflux.goldenfluxgame.game.GameLogic
import com.goldenflux.goldenfluxgame.game.GameState
import com.goldenflux.goldenfluxgame.game.GameViewModel
import com.goldenflux.goldenfluxgame.ui.design.AssetIcon
import com.goldenflux.goldenfluxgame.ui.design.GfProgressBar
import com.goldenflux.goldenfluxgame.ui.design.GlassRow
import com.goldenflux.goldenfluxgame.ui.theme.GfGoldLight
import com.goldenflux.goldenfluxgame.ui.theme.GfTextDim
import com.goldenflux.goldenfluxgame.ui.theme.GfTextPrimary
import com.goldenflux.goldenfluxgame.util.formatCompact
import com.goldenflux.goldenfluxgame.util.formatDuration

private data class CodexEntry(val sprite: String, val name: String, val discovered: Boolean)

@Composable
fun CollectionScreen(gameVm: GameViewModel, onBack: () -> Unit) {
    val state by gameVm.state.collectAsState()
    val entries = buildCodex(state)
    val found = entries.count { it.discovered }

    ScreenScaffold("Codex", onBack, subtitle = "Discovered $found of ${entries.size}") {
        GfProgressBar(
            fraction = found.toFloat() / entries.size,
            height = 12.dp,
            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
        )
        LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.fillMaxWidth()) {
            items(entries) { e ->
                GlassRow(Modifier.padding(5.dp)) {
                    Column(
                        Modifier.padding(8.dp).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        AssetIcon(
                            e.sprite,
                            Modifier.aspectRatio(1f),
                            alpha = if (e.discovered) 1f else 0.18f,
                        )
                        Text(
                            if (e.discovered) e.name else "???",
                            color = if (e.discovered) GfTextPrimary else GfTextDim,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

private fun buildCodex(state: GameState): List<CodexEntry> {
    val best = HashMap<DeviceType, Int>()
    state.zones.values.forEach { z ->
        z.devices.values.forEach { d -> best[d.type] = maxOf(best[d.type] ?: 0, d.level) }
    }
    val list = ArrayList<CodexEntry>()
    DeviceType.entries.forEach { t ->
        for (lvl in 1..t.maxLevel) {
            list.add(CodexEntry(t.spriteFor(lvl), "${t.title} $lvl", (best[t] ?: 0) >= lvl))
        }
    }
    list.add(CodexEntry("game/sprites/core.png", "Flux Core", true))
    list.add(CodexEntry("game/sprites/final_reactor.png", "Final Reactor", state.coreLevel >= 5))
    list.add(CodexEntry("game/sprites/portal.png", "Portal", state.zones.values.count { it.unlocked } >= 2))
    list.add(CodexEntry("game/sprites/symbol_1.png", "Golden Symbol", state.resources.goldSymbols > 0))
    list.add(CodexEntry("game/sprites/artifact_1.png", "Star Artifact", state.resources.starArtifacts > 0))
    list.add(CodexEntry("game/sprites/chest.png", "Flux Chest", state.stats.chestsOpened > 0))
    return list
}

@Composable
fun StatsScreen(gameVm: GameViewModel, onBack: () -> Unit) {
    val state by gameVm.state.collectAsState()
    val stats by gameVm.stats.collectAsState()

    ScreenScaffold("Statistics", onBack, subtitle = state.profile.name) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            StatRow("Energy produced", formatCompact(state.stats.totalEnergyProduced))
            StatRow("Crystals produced", formatCompact(state.stats.totalCrystalsProduced))
            StatRow("Energy per second", formatCompact(stats.energyPerSec))
            StatRow("Crystals per second", formatCompact(stats.crystalsPerSec))
            StatRow("Global multiplier", "x${String.format("%.2f", stats.globalMultiplier)}")
            StatRow("Machines built", state.stats.devicesBuilt.toString())
            StatRow("Machines merged", state.stats.merges.toString())
            StatRow("Paid upgrades", state.stats.upgradesDone.toString())
            StatRow("Core taps", state.stats.taps.toString())
            StatRow("Chests opened", state.stats.chestsOpened.toString())
            StatRow("Zones unlocked", state.zones.values.count { it.unlocked }.toString())
            StatRow("Core level", state.coreLevel.toString())
            StatRow("Time played", formatDuration(state.stats.playTimeSeconds * 1000))
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    GlassRow(Modifier.padding(vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, color = GfTextDim, fontSize = 13.sp)
            Text(value, color = GfGoldLight, fontWeight = FontWeight.Black, fontSize = 13.sp)
        }
    }
}
