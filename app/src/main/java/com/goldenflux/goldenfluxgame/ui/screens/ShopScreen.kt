package com.goldenflux.goldenfluxgame.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.goldenflux.goldenfluxgame.game.Grant
import com.goldenflux.goldenfluxgame.game.ShopItem
import com.goldenflux.goldenfluxgame.ui.design.AssetIcon
import com.goldenflux.goldenfluxgame.ui.design.GfButton
import com.goldenflux.goldenfluxgame.ui.design.GlassRow
import com.goldenflux.goldenfluxgame.ui.theme.GfGreen
import com.goldenflux.goldenfluxgame.ui.theme.GfTextDim
import com.goldenflux.goldenfluxgame.ui.theme.GfTextPrimary
import com.goldenflux.goldenfluxgame.util.formatCompact

@Composable
fun ShopScreen(gameVm: GameViewModel, onBack: () -> Unit) {
    val state by gameVm.state.collectAsState()

    ScreenScaffold("Exchange", onBack, subtitle = "Trade resources — no real money involved") {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            ShopItem.catalog().forEach { item ->
                val afford = GameLogic.canAfford(state, item.cost)
                GlassRow(Modifier.padding(vertical = 5.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        AssetIcon(item.icon, Modifier.size(48.dp), alpha = if (afford) 1f else 0.45f)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.title, color = GfTextPrimary, fontWeight = FontWeight.Black, fontSize = 14.sp)
                            Text(item.description, color = GfTextDim, fontSize = 11.sp)
                            Text(grantText(item.grant), color = GfGreen, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        GfButton(costLabel(item), enabled = afford, compact = true) { gameVm.buy(item) }
                    }
                }
            }
        }
    }
}

private fun grantText(g: Grant): String {
    val parts = buildList {
        if (g.energy > 0) add("+${formatCompact(g.energy)} energy")
        if (g.crystals > 0) add("+${formatCompact(g.crystals)} crystals")
        if (g.goldSymbols > 0) add("+${g.goldSymbols} symbol")
        if (g.starArtifacts > 0) add("+${g.starArtifacts} artifact")
    }
    return parts.joinToString(", ")
}

private fun costLabel(item: ShopItem): String {
    val parts = buildList {
        if (item.cost.energy > 0) add("${formatCompact(item.cost.energy)}E")
        if (item.cost.crystals > 0) add("${formatCompact(item.cost.crystals)}C")
        if (item.cost.goldSymbols > 0) add("${item.cost.goldSymbols}G")
    }
    return parts.joinToString(" ")
}
