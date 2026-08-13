package com.goldenflux.goldenfluxgame.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goldenflux.goldenfluxgame.game.GameViewModel
import com.goldenflux.goldenfluxgame.ui.design.GfButton
import com.goldenflux.goldenfluxgame.ui.design.glow
import com.goldenflux.goldenfluxgame.ui.theme.GfGold
import com.goldenflux.goldenfluxgame.ui.theme.GfGoldLight
import com.goldenflux.goldenfluxgame.ui.theme.GfTextPrimary

@Composable
fun ProfileScreen(gameVm: GameViewModel, onBack: () -> Unit) {
    val state by gameVm.state.collectAsState()
    var name by remember { mutableStateOf(state.profile.name) }

    ScreenScaffold("Profile", onBack, subtitle = "Keeper of the Golden Flux") {
        Column(
            Modifier.fillMaxWidth().padding(top = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.glow(GfGold, 0.5f, 0.3f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Avatar(128.dp)
            }
            Spacer(Modifier.height(26.dp))
            Text(
                "Keeper name",
                color = GfGoldLight,
                fontWeight = FontWeight.Black,
                fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = name,
                onValueChange = { if (it.length <= 18) name = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = GfGold,
                    unfocusedBorderColor = GfGoldLight,
                    focusedTextColor = GfTextPrimary,
                    unfocusedTextColor = GfTextPrimary,
                    cursorColor = GfGold,
                ),
            )
            Spacer(Modifier.height(18.dp))
            GfButton("Save", Modifier.fillMaxWidth(0.6f)) {
                gameVm.setProfileName(name)
                gameVm.click()
                onBack()
            }
        }
    }
}
