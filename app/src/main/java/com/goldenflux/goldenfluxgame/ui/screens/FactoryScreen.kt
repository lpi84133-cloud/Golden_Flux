package com.goldenflux.goldenfluxgame.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goldenflux.goldenfluxgame.data.GameAssets
import com.goldenflux.goldenfluxgame.game.DeviceInstance
import com.goldenflux.goldenfluxgame.game.DeviceType
import com.goldenflux.goldenfluxgame.game.GameLogic
import com.goldenflux.goldenfluxgame.game.GameViewModel
import com.goldenflux.goldenfluxgame.ui.Route
import com.goldenflux.goldenfluxgame.ui.design.AssetIcon
import com.goldenflux.goldenfluxgame.ui.design.GfButton
import com.goldenflux.goldenfluxgame.ui.design.GfButtonStyle
import com.goldenflux.goldenfluxgame.ui.design.GfIconButton
import com.goldenflux.goldenfluxgame.ui.design.GfProgressBar
import com.goldenflux.goldenfluxgame.ui.design.GlassRow
import com.goldenflux.goldenfluxgame.ui.design.NotifyBadge
import com.goldenflux.goldenfluxgame.ui.design.OrnatePanel
import com.goldenflux.goldenfluxgame.ui.design.ResourcePill
import com.goldenflux.goldenfluxgame.ui.design.glow
import com.goldenflux.goldenfluxgame.ui.theme.GfCrystalBlue
import com.goldenflux.goldenfluxgame.ui.theme.GfGold
import com.goldenflux.goldenfluxgame.ui.theme.GfGoldLight
import com.goldenflux.goldenfluxgame.ui.theme.GfGreen
import com.goldenflux.goldenfluxgame.ui.theme.GfPurpleBright
import com.goldenflux.goldenfluxgame.ui.theme.GfTextDim
import com.goldenflux.goldenfluxgame.ui.theme.GfTextPrimary
import com.goldenflux.goldenfluxgame.util.formatCompact
import com.goldenflux.goldenfluxgame.util.formatDuration
import kotlinx.coroutines.delay

private class GainFx(val id: Long, val amount: Double, val xBias: Float)

@Composable
fun FactoryScreen(gameVm: GameViewModel, onOpen: (Route) -> Unit) {
    val hud by gameVm.hud.collectAsState()
    val board by gameVm.board.collectAsState()
    val hint by gameVm.hint.collectAsState()
    val claimable by gameVm.claimableQuests.collectAsState()

    var selectedTile by remember { mutableStateOf<Int?>(null) }
    var showQuests by remember { mutableStateOf(false) }
    val gains = remember { mutableStateListOf<GainFx>() }
    var gainSeq by remember { mutableIntStateOf(0) }

    Box(Modifier.fillMaxSize()) {
        GameAssets[board.zone.background]?.let {
            Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xE60E0720), Color(0x660E0720), Color(0xF20E0720))
                    )
                )
        )

        Column(Modifier.fillMaxSize().statusBarsPadding()) {

            TopHud(
                energy = hud.energy,
                capacity = hud.capacity,
                crystals = hud.crystals,
                gold = hud.goldSymbols,
                energyPerSec = hud.energyPerSec,
                crystalsPerSec = hud.crystalsPerSec,
                onSettings = { gameVm.click(); onOpen(Route.SETTINGS) },
                onHome = { gameVm.click(); onOpen(Route.MENU) },
            )

            ZoneStrip(
                zoneTitle = board.zone.title,
                coreLevel = hud.coreLevel,
                converterLoad = hud.converterLoad,
                hasConverters = hud.crystalsPerSec > 0.0,
            )

            AnimatedVisibility(visible = hint != null, enter = fadeIn(), exit = fadeOut()) {
                HintBanner(hint.orEmpty())
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                FactoryGrid(
                    zone = board.zone,
                    devices = board.devices,
                    tileBoost = board.tileBoost,
                    onTileTap = { selectedTile = it },
                    onMoveOrMerge = { from, to -> gameVm.moveOrMerge(from, to) },
                    modifier = Modifier.fillMaxSize(),
                )
                gains.forEach { fx ->
                    key(fx.id) {
                        FloatingGain(fx) { gains.remove(fx) }
                    }
                }
            }

            CoreDock(
                gameVm = gameVm,
                claimable = claimable,
                onOpenRewards = { gameVm.click(); onOpen(Route.REWARDS) },
                onTapCore = {
                    val gained = gameVm.tapCore()
                    gainSeq += 1
                    val bias = kotlin.random.Random.nextFloat() * 0.5f - 0.25f
                    gains.add(GainFx(gainSeq.toLong(), gained, bias))
                },
                onQuests = { gameVm.click(); showQuests = true },
            )

            Dock(onOpen = onOpen, click = gameVm::click, questBadge = claimable) { showQuests = true }
        }

        selectedTile?.let { tile ->
            val device = board.devices[tile]
            if (device == null) {
                BuildSheet(gameVm, onDismiss = { selectedTile = null }) { type ->
                    if (gameVm.build(tile, type)) selectedTile = null
                }
            } else {
                DeviceSheet(
                    gameVm = gameVm,
                    device = device,
                    onDismiss = { selectedTile = null },
                    onSold = { selectedTile = null },
                )
            }
        }

        if (showQuests) {
            QuestSheet(gameVm) { showQuests = false }
        }
    }
}

// ------------------------------------------------------------------ HUD

@Composable
private fun TopHud(
    energy: Double,
    capacity: Double,
    crystals: Double,
    gold: Long,
    energyPerSec: Double,
    crystalsPerSec: Double,
    onSettings: () -> Unit,
    onHome: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ResourcePill(
                icon = "game/sprites/crystal_1.png",
                value = formatCompact(energy),
                sub = "+${formatCompact(energyPerSec)}/s",
                accent = GfGold,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            ResourcePill(
                icon = "game/sprites/upgrade_crystal.png",
                value = formatCompact(crystals),
                sub = if (crystalsPerSec > 0) "+${formatCompact(crystalsPerSec)}/s" else "idle",
                accent = GfPurpleBright,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            ResourcePill(
                icon = "game/sprites/symbol_1.png",
                value = gold.toString(),
                accent = GfCrystalBlue,
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            GfProgressBar(
                fraction = if (capacity > 0) (energy / capacity).toFloat() else 0f,
                height = 10.dp,
                modifier = Modifier.weight(1f),
                shimmer = false,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "cap ${formatCompact(capacity)}",
                color = GfTextDim,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(8.dp))
            GfIconButton(diameter = 34.dp, onClick = onHome) {
                Icon(Icons.Filled.Home, null, tint = GfGoldLight, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.width(6.dp))
            GfIconButton(diameter = 34.dp, onClick = onSettings) {
                Icon(Icons.Filled.Settings, null, tint = GfGoldLight, modifier = Modifier.size(17.dp))
            }
        }
    }
}

@Composable
private fun ZoneStrip(zoneTitle: String, coreLevel: Int, converterLoad: Float, hasConverters: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(zoneTitle, color = GfGoldLight, fontWeight = FontWeight.Black, fontSize = 15.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (hasConverters && converterLoad < 0.99f) {
                Text(
                    "converters ${(converterLoad * 100).toInt()}%",
                    color = if (converterLoad < 0.6f) Color(0xFFEF6B6B) else GfTextDim,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(10.dp))
            }
            Text("CORE Lv $coreLevel", color = GfGold, fontWeight = FontWeight.Black, fontSize = 13.sp)
        }
    }
}

@Composable
private fun HintBanner(text: String) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)) {
        GlassRow {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AssetIcon("game/sprites/symbol_1.png", Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(text, color = GfTextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// ------------------------------------------------------------------ core dock

@Composable
private fun CoreDock(
    gameVm: GameViewModel,
    claimable: Int,
    onOpenRewards: () -> Unit,
    onTapCore: () -> Unit,
    onQuests: () -> Unit,
) {
    val stats by gameVm.stats.collectAsState()
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, tween(90), label = "coreScale")

    LaunchedEffect(pressed) {
        if (pressed) { delay(90); pressed = false }
    }

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        ChestButton(gameVm, onOpenRewards)

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(112.dp)
                    .glow(GfGold, radiusScale = 0.6f, alpha = 0.5f)
                    .scale(scale)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { pressed = true; onTapCore() },
                contentAlignment = Alignment.Center,
            ) {
                AssetIcon("game/sprites/core.png", Modifier.fillMaxSize())
            }
            Text(
                "TAP  +${formatCompact(stats.tapValue)}",
                color = GfGoldLight,
                fontWeight = FontWeight.Black,
                fontSize = 12.sp,
            )
        }

        Box(contentAlignment = Alignment.TopEnd) {
            GfIconButton(diameter = 54.dp, onClick = onQuests) {
                AssetIcon("game/sprites/artifact_1.png", Modifier.size(32.dp))
            }
            NotifyBadge(claimable)
        }
    }
}

@Composable
private fun ChestButton(gameVm: GameViewModel, onOpenRewards: () -> Unit) {
    val hud by gameVm.hud.collectAsState()   // ticks, so the countdown refreshes
    val remaining = remember(hud) { gameVm.rewardRemainingMs() }
    val ready = remaining <= 0L

    Box(contentAlignment = Alignment.Center) {
        GfIconButton(diameter = 54.dp, onClick = { if (ready) gameVm.claimReward() else onOpenRewards() }) {
            AssetIcon("game/sprites/chest.png", Modifier.size(34.dp), alpha = if (ready) 1f else 0.45f)
        }
        if (!ready) {
            Text(
                formatDuration(remaining),
                color = GfTextPrimary,
                fontSize = 9.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xCC0E0720))
                    .padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun FloatingGain(fx: GainFx, onDone: () -> Unit) {
    var run by remember { mutableStateOf(false) }
    val t by animateFloatAsState(if (run) 1f else 0f, tween(850), label = "gain")
    LaunchedEffect(Unit) { run = true; delay(900); onDone() }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Text(
            "+${formatCompact(fx.amount)}",
            color = GfGoldLight,
            fontWeight = FontWeight.Black,
            fontSize = (16 + 6 * (1f - t)).sp,
            modifier = Modifier
                .padding(bottom = 12.dp)
                .offset(
                    x = (fx.xBias * 120).dp,
                    y = (-90 * t).dp,
                )
                .alpha(1f - t),
        )
    }
}

// ------------------------------------------------------------------ dock

@Composable
private fun Dock(
    onOpen: (Route) -> Unit,
    click: () -> Unit,
    questBadge: Int,
    onQuests: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color(0x00000000), Color(0xE60E0720))))
            .navigationBarsPadding()
            .padding(top = 8.dp, bottom = 8.dp, start = 6.dp, end = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DockItem("Upgrade", "game/sprites/core.png") { click(); onOpen(Route.UPGRADES) }
        DockItem("Zones", "game/sprites/portal.png") { click(); onOpen(Route.ZONES) }
        DockItem("Shop", "game/sprites/symbol_1.png") { click(); onOpen(Route.SHOP) }
        DockItem("Goals", "game/sprites/artifact_1.png", badge = questBadge) { click(); onQuests() }
        DockItem("Codex", "game/sprites/mechanism_1.png") { click(); onOpen(Route.COLLECTION) }
    }
}

@Composable
private fun DockItem(label: String, icon: String, badge: Int = 0, onClick: () -> Unit) {
    Box(contentAlignment = Alignment.TopEnd) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .clickable { onClick() }
                .padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            AssetIcon(icon, Modifier.size(30.dp))
            Text(label, color = GfTextDim, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        NotifyBadge(badge, Modifier.padding(end = 2.dp))
    }
}

// ------------------------------------------------------------------ sheets

@Composable
private fun Sheet(onDismiss: () -> Unit, title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
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
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { },
        ) {
            OrnatePanel(title = title, content = content)
        }
    }
}

@Composable
private fun BuildSheet(
    gameVm: GameViewModel,
    onDismiss: () -> Unit,
    onBuild: (DeviceType) -> Unit,
) {
    val state by gameVm.state.collectAsState()
    Sheet(onDismiss, "Build") {
        DeviceType.entries.forEach { type ->
            val cost = GameLogic.buildCost(type, state.countOf(type))
            val afford = GameLogic.canAfford(state, cost)
            GlassRow(Modifier.padding(vertical = 4.dp), onClick = { if (afford) onBuild(type) }) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    AssetIcon(type.spriteFor(1), Modifier.size(52.dp), alpha = if (afford) 1f else 0.4f)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(type.title, color = GfTextPrimary, fontWeight = FontWeight.Black, fontSize = 15.sp)
                        Text(GameLogic.deviceRole(type), color = GfTextDim, fontSize = 11.sp)
                        Text(
                            GameLogic.deviceEffect(type, 1),
                            color = GfGreen, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        )
                    }
                    CostLabel(cost.energy, cost.crystals, afford)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Tip: drag a machine onto an identical one to merge them into a stronger tier.",
            color = GfTextDim, fontSize = 11.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        GfButton("Close", Modifier.fillMaxWidth(), style = GfButtonStyle.Stone, onClick = onDismiss)
    }
}

@Composable
private fun DeviceSheet(
    gameVm: GameViewModel,
    device: DeviceInstance,
    onDismiss: () -> Unit,
    onSold: () -> Unit,
) {
    val state by gameVm.state.collectAsState()
    Sheet(onDismiss, "${device.type.title} · Tier ${device.level}") {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            AssetIcon(
                device.type.spriteFor(device.level),
                Modifier.size(130.dp).glow(GfGold, 0.55f, 0.35f),
            )
        }
        Text(
            GameLogic.deviceEffect(device.type, device.level),
            color = GfGreen, fontWeight = FontWeight.Bold, fontSize = 13.sp,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
        Text(
            GameLogic.deviceRole(device.type),
            color = GfTextDim, fontSize = 11.sp,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))

        if (device.level < device.type.maxLevel) {
            val cost = GameLogic.upgradeCost(device.type, device.level)
            val afford = GameLogic.canAfford(state, cost)
            GlassRow {
                Column(Modifier.padding(10.dp)) {
                    Text("Next tier", color = GfGoldLight, fontWeight = FontWeight.Black, fontSize = 12.sp)
                    Text(
                        GameLogic.deviceEffect(device.type, device.level + 1),
                        color = GfGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            GfButton(
                text = "Upgrade  ·  ${costText(cost.energy, cost.crystals)}",
                modifier = Modifier.fillMaxWidth(),
                enabled = afford,
                icon = "game/sprites/upgrade_crystal.png",
            ) { gameVm.upgradeDevice(device.tile) }
        } else {
            Text(
                "Maximum tier reached",
                color = GfGold, fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "Or drag it onto an identical machine to merge for free.",
            color = GfTextDim, fontSize = 11.sp,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GfButton(
                "Sell +${formatCompact(GameLogic.sellValue(device.type, device.level, state.countOf(device.type)))}",
                Modifier.weight(1f),
                style = GfButtonStyle.Danger,
                compact = true,
            ) { gameVm.sellDevice(device.tile); onSold() }
            GfButton("Close", Modifier.weight(1f), style = GfButtonStyle.Stone, compact = true, onClick = onDismiss)
        }
    }
}

@Composable
private fun QuestSheet(gameVm: GameViewModel, onDismiss: () -> Unit) {
    val state by gameVm.state.collectAsState()
    val quests = com.goldenflux.goldenfluxgame.game.Quests.active(state, 4)
    Sheet(onDismiss, "Goals") {
        if (quests.isEmpty()) {
            Text(
                "Every goal complete. The Golden Flux is yours to master.",
                color = GfTextDim, fontSize = 12.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        quests.forEach { quest ->
            val done = quest.isComplete(state)
            GlassRow(Modifier.padding(vertical = 4.dp)) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    AssetIcon(quest.icon, Modifier.size(44.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(quest.title, color = GfTextPrimary, fontWeight = FontWeight.Black, fontSize = 14.sp)
                        Text(quest.detail, color = GfTextDim, fontSize = 11.sp)
                        Spacer(Modifier.height(4.dp))
                        GfProgressBar(
                            fraction = quest.progress(state),
                            height = 8.dp,
                            shimmer = false,
                            colors = if (done) listOf(GfGreen, Color(0xFF2E9E4A)) else listOf(GfGoldLight, GfGold),
                        )
                        Text(rewardText(quest.reward), color = GfGoldLight, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(8.dp))
                    if (done) {
                        GfButton("Claim", compact = true) { gameVm.claimQuest(quest.id) }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        GfButton("Close", Modifier.fillMaxWidth(), style = GfButtonStyle.Stone, onClick = onDismiss)
    }
}

private fun rewardText(g: com.goldenflux.goldenfluxgame.game.Grant): String {
    val parts = buildList {
        if (g.energy > 0) add("${formatCompact(g.energy)} energy")
        if (g.crystals > 0) add("${formatCompact(g.crystals)} crystals")
        if (g.goldSymbols > 0) add("${g.goldSymbols} symbol")
        if (g.starArtifacts > 0) add("${g.starArtifacts} artifact")
    }
    return "Reward: " + parts.joinToString(", ")
}

private fun costText(energy: Double, crystals: Double): String {
    val parts = buildList {
        if (energy > 0) add("${formatCompact(energy)}E")
        if (crystals > 0) add("${formatCompact(crystals)}C")
    }
    return parts.joinToString(" ")
}

@Composable
private fun CostLabel(energy: Double, crystals: Double, afford: Boolean) {
    val color = if (afford) GfGoldLight else Color(0xFFEF6B6B)
    Column(horizontalAlignment = Alignment.End) {
        if (energy > 0) {
            Text("${formatCompact(energy)} E", color = color, fontWeight = FontWeight.Black, fontSize = 13.sp)
        }
        if (crystals > 0) {
            Text("${formatCompact(crystals)} C", color = color, fontWeight = FontWeight.Black, fontSize = 13.sp)
        }
    }
}
