package com.goldenflux.goldenfluxgame.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goldenflux.goldenfluxgame.game.GameEvent
import com.goldenflux.goldenfluxgame.game.GameViewModel
import com.goldenflux.goldenfluxgame.ui.design.AssetIcon
import com.goldenflux.goldenfluxgame.ui.design.GfButton
import com.goldenflux.goldenfluxgame.ui.design.OrnatePanel
import com.goldenflux.goldenfluxgame.ui.design.glow
import com.goldenflux.goldenfluxgame.ui.screens.CollectionScreen
import com.goldenflux.goldenfluxgame.ui.screens.FactoryScreen
import com.goldenflux.goldenfluxgame.ui.screens.LegalScreen
import com.goldenflux.goldenfluxgame.ui.screens.LoadingScreen
import com.goldenflux.goldenfluxgame.ui.screens.MenuScreen
import com.goldenflux.goldenfluxgame.ui.screens.ProfileScreen
import com.goldenflux.goldenfluxgame.ui.screens.RewardsScreen
import com.goldenflux.goldenfluxgame.ui.screens.SettingsScreen
import com.goldenflux.goldenfluxgame.ui.screens.ShopScreen
import com.goldenflux.goldenfluxgame.ui.screens.StatsScreen
import com.goldenflux.goldenfluxgame.ui.screens.UpgradesScreen
import com.goldenflux.goldenfluxgame.ui.screens.ZonesScreen
import com.goldenflux.goldenfluxgame.ui.theme.GfGold
import com.goldenflux.goldenfluxgame.ui.theme.GfGoldLight
import com.goldenflux.goldenfluxgame.ui.theme.GfGreen
import com.goldenflux.goldenfluxgame.ui.theme.GfTextDim
import com.goldenflux.goldenfluxgame.ui.theme.GfTextPrimary
import com.goldenflux.goldenfluxgame.util.formatCompact
import com.goldenflux.goldenfluxgame.util.formatDuration
import kotlinx.coroutines.delay

private const val PRIVACY_URL = "https://goldenfllux.com/privacy-policy.html"
private const val SUPPORT_URL = "https://goldenfllux.com/support.html"

@Composable
fun GoldenFluxApp(gameVm: GameViewModel) {
    val stack = remember { mutableStateListOf(Route.LOADING) }
    val current = stack.last()

    fun navigate(route: Route) { if (stack.last() != route) stack.add(route) }
    fun back() { if (stack.size > 1) stack.removeAt(stack.size - 1) }
    fun goHome() { stack.clear(); stack.add(Route.MENU) }

    var dialog by remember { mutableStateOf<GameEvent?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        gameVm.events.collect { event ->
            when (event) {
                is GameEvent.Message -> toast = event.text
                is GameEvent.Merged -> toast = "Merged into ${event.type.title} tier ${event.level}"
                else -> dialog = event
            }
        }
    }
    LaunchedEffect(toast) {
        if (toast != null) { delay(1700); toast = null }
    }

    BackHandler(enabled = current != Route.MENU && current != Route.LOADING) { back() }

    Box(Modifier.fillMaxSize()) {
        Crossfade(targetState = current, animationSpec = tween(180), label = "route") { route ->
            when (route) {
                Route.LOADING -> LoadingScreen(gameVm) { goHome() }
                Route.MENU -> MenuScreen(gameVm) { navigate(it) }
                Route.FACTORY -> FactoryScreen(gameVm) { navigate(it) }
                Route.UPGRADES -> UpgradesScreen(gameVm) { back() }
                Route.ZONES -> ZonesScreen(
                    gameVm = gameVm,
                    onEnter = { goHome(); navigate(Route.FACTORY) },
                    onBack = { back() },
                )
                Route.SHOP -> ShopScreen(gameVm) { back() }
                Route.REWARDS -> RewardsScreen(gameVm) { back() }
                Route.COLLECTION -> CollectionScreen(gameVm) { back() }
                Route.STATS -> StatsScreen(gameVm) { back() }
                Route.SETTINGS -> SettingsScreen(gameVm, onOpen = { navigate(it) }, onBack = { back() })
                Route.PROFILE -> ProfileScreen(gameVm) { back() }
                Route.PRIVACY -> LegalScreen("Privacy Policy", PRIVACY_URL, "legal/privacy.html") { back() }
                Route.SUPPORT -> LegalScreen("Support", SUPPORT_URL, "legal/support.html") { back() }
            }
        }

        dialog?.let { event -> EventDialog(event) { dialog = null } }

        toast?.let { message ->
            Box(Modifier.fillMaxSize().padding(bottom = 120.dp), contentAlignment = Alignment.BottomCenter) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(22.dp))
                        .background(Color(0xF2140A2C))
                        .padding(horizontal = 20.dp, vertical = 11.dp),
                ) {
                    Text(message, color = GfTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun EventDialog(event: GameEvent, onDismiss: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xCC060312))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 26.dp)) {
            OrnatePanel(title = dialogTitle(event)) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    AssetIcon(
                        dialogIcon(event),
                        Modifier.size(110.dp).glow(GfGold, 0.55f, 0.4f),
                    )
                }
                Spacer(Modifier.height(8.dp))
                dialogLines(event).forEach { line ->
                    Text(
                        line,
                        color = if (line.startsWith("+")) GfGreen else GfTextDim,
                        fontSize = if (line.startsWith("+")) 15.sp else 13.sp,
                        fontWeight = if (line.startsWith("+")) FontWeight.Black else FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(16.dp))
                GfButton("Continue", Modifier.fillMaxWidth(), onClick = onDismiss)
            }
        }
    }
}

private fun dialogTitle(event: GameEvent): String = when (event) {
    is GameEvent.Offline -> "Welcome back"
    is GameEvent.Reward -> "Flux Chest"
    is GameEvent.ZoneUnlocked -> "Zone unlocked"
    is GameEvent.CoreUpgraded -> "Core upgraded"
    is GameEvent.QuestDone -> "Goal complete"
    else -> "Golden Flux"
}

private fun dialogIcon(event: GameEvent): String = when (event) {
    is GameEvent.Offline -> "game/sprites/chest.png"
    is GameEvent.Reward -> "game/sprites/chest.png"
    is GameEvent.ZoneUnlocked -> "game/sprites/portal.png"
    is GameEvent.CoreUpgraded -> "game/sprites/core.png"
    is GameEvent.QuestDone -> event.quest.icon
    else -> "game/sprites/symbol_1.png"
}

private fun dialogLines(event: GameEvent): List<String> = when (event) {
    is GameEvent.Offline -> buildList {
        add("Your factory ran for ${formatDuration(event.report.durationMs)} while you were away")
        add("+${formatCompact(event.report.energy)} energy")
        if (event.report.crystals > 0.05) add("+${formatCompact(event.report.crystals)} crystals")
    }
    is GameEvent.Reward -> buildList {
        add("+${formatCompact(event.energy)} energy")
        add("+${formatCompact(event.crystals)} crystals")
        if (event.gold > 0) add("+${event.gold} golden symbol")
    }
    is GameEvent.ZoneUnlocked -> listOf(event.zone.title, event.zone.subtitle)
    is GameEvent.CoreUpgraded -> listOf(
        "The Golden Flux Core reached level ${event.level}",
        "Everything you build is now stronger",
    )
    is GameEvent.QuestDone -> buildList {
        add(event.quest.title)
        val r = event.quest.reward
        if (r.energy > 0) add("+${formatCompact(r.energy)} energy")
        if (r.crystals > 0) add("+${formatCompact(r.crystals)} crystals")
        if (r.goldSymbols > 0) add("+${r.goldSymbols} golden symbol")
        if (r.starArtifacts > 0) add("+${r.starArtifacts} star artifact")
    }
    is GameEvent.Message -> listOf(event.text)
    is GameEvent.Merged -> listOf("Merged into tier ${event.level}")
}
