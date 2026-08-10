package com.goldenflux.goldenfluxgame.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.goldenflux.goldenfluxgame.game.GameViewModel
import com.goldenflux.goldenfluxgame.ui.design.GfButton
import com.goldenflux.goldenfluxgame.ui.design.GfButtonStyle
import com.goldenflux.goldenfluxgame.ui.design.glow
import com.goldenflux.goldenfluxgame.ui.theme.GfGold
import com.goldenflux.goldenfluxgame.ui.theme.GfGoldLight
import com.goldenflux.goldenfluxgame.ui.theme.GfTextDim
import com.goldenflux.goldenfluxgame.ui.theme.GfTextPrimary
import java.io.File

@Composable
fun ProfileScreen(gameVm: GameViewModel, onBack: () -> Unit) {
    val state by gameVm.state.collectAsState()
    val context = LocalContext.current
    var name by remember { mutableStateOf(state.profile.name) }

    fun avatarDir(): File = File(context.filesDir, "avatars").apply { mkdirs() }
    fun newAvatarFile(): File = File(avatarDir(), "avatar_${System.currentTimeMillis()}.jpg")

    val cameraTarget = remember { mutableStateOf<File?>(null) }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val dest = newAvatarFile()
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { input.copyTo(it) }
                }
                gameVm.setAvatar(dest.absolutePath)
            }
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success: Boolean ->
        if (success) cameraTarget.value?.let { gameVm.setAvatar(it.absolutePath) }
    }

    fun launchCamera() {
        val file = newAvatarFile()
        cameraTarget.value = file
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        cameraLauncher.launch(uri)
    }

    ScreenScaffold("Profile", onBack, subtitle = "Keeper of the Golden Flux") {
        Column(
            Modifier.fillMaxWidth().padding(top = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.glow(GfGold, 0.5f, 0.3f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Avatar(state.profile.avatarPath, 128.dp)
            }
            Spacer(Modifier.height(8.dp))
            Text("Choose a portrait", color = GfTextDim, fontSize = 12.sp)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GfButton("Gallery", compact = true) { gameVm.click(); galleryLauncher.launch("image/*") }
                GfButton("Camera", compact = true, style = GfButtonStyle.Stone) { gameVm.click(); launchCamera() }
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
