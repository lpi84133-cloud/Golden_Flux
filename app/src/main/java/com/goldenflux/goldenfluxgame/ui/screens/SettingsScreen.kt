package com.goldenflux.goldenfluxgame.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import com.goldenflux.goldenfluxgame.game.GameViewModel
import com.goldenflux.goldenfluxgame.ui.Route
import com.goldenflux.goldenfluxgame.ui.design.AssetIcon
import com.goldenflux.goldenfluxgame.ui.design.GfToggle
import com.goldenflux.goldenfluxgame.ui.design.GlassRow
import com.goldenflux.goldenfluxgame.ui.design.SectionHeader
import com.goldenflux.goldenfluxgame.ui.theme.GfGoldLight
import com.goldenflux.goldenfluxgame.ui.theme.GfTextDim
import com.goldenflux.goldenfluxgame.ui.theme.GfTextPrimary

@Composable
fun SettingsScreen(gameVm: GameViewModel, onOpen: (Route) -> Unit, onBack: () -> Unit) {
    val state by gameVm.state.collectAsState()

    ScreenScaffold("Settings", onBack) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            SectionHeader("Audio")
            Spacer(Modifier.height(8.dp))
            ToggleRow("Music", "game/sprites/crystal_1.png", state.settings.music) { gameVm.toggleMusic() }
            ToggleRow("Sound effects", "game/sprites/symbol_1.png", state.settings.sfx) { gameVm.toggleSfx() }
            ToggleRow("Vibration", "game/sprites/mechanism_1.png", state.settings.vibration) { gameVm.toggleVibration() }

            Spacer(Modifier.height(18.dp))
            SectionHeader("Account")
            Spacer(Modifier.height(8.dp))
            LinkRow("Edit profile") { gameVm.click(); onOpen(Route.PROFILE) }
            LinkRow("Privacy Policy") { gameVm.click(); onOpen(Route.PRIVACY) }
            LinkRow("Support") { gameVm.click(); onOpen(Route.SUPPORT) }

            Spacer(Modifier.height(20.dp))
            Text("Golden Flux  ·  v1.0", color = GfTextDim, fontSize = 12.sp)
            Text(
                "Plays fully offline. Progress is stored only on this device.",
                color = GfTextDim, fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun ToggleRow(label: String, icon: String, checked: Boolean, onToggle: () -> Unit) {
    GlassRow(Modifier.padding(vertical = 5.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssetIcon(icon, Modifier.size(26.dp))
                Spacer(Modifier.width(10.dp))
                Text(label, color = GfTextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
            GfToggle(checked = checked, onToggle = onToggle)
        }
    }
}

@Composable
private fun LinkRow(label: String, onClick: () -> Unit) {
    GlassRow(Modifier.padding(vertical = 5.dp), onClick = onClick) {
        Text(
            label,
            color = GfGoldLight,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 15.dp),
        )
    }
}
