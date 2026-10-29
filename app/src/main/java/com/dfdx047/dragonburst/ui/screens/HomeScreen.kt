package com.dfdx047.dragonburst.ui.screens

import android.net.Uri
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.sp
import com.dfdx047.dragonburst.DragonBurstApp
import com.dfdx047.dragonburst.ui.components.BurstStage
import com.dfdx047.dragonburst.ui.components.gradientText
import com.dfdx047.dragonburst.ui.theme.LogoStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddToHomeScreen
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dfdx047.dragonburst.R
import com.dfdx047.dragonburst.data.FileStatus
import com.dfdx047.dragonburst.data.GameOrientation
import com.dfdx047.dragonburst.ui.LauncherTab
import com.dfdx047.dragonburst.ui.LauncherViewModel
import com.dfdx047.dragonburst.ui.components.Level
import com.dfdx047.dragonburst.ui.components.Pill
import com.dfdx047.dragonburst.ui.components.SectionCard
import com.dfdx047.dragonburst.ui.components.SettingSegmented
import com.dfdx047.dragonburst.ui.components.StatusLine
import com.dfdx047.dragonburst.ui.components.color
import com.dfdx047.dragonburst.ui.theme.DfColors

@Composable
fun HomeScreen(vm: LauncherViewModel, goTo: (LauncherTab) -> Unit) {
    val context = LocalContext.current
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) vm.importGame(uri)
    }
    val pickIso = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) vm.importIso(uri)
    }
    val install = vm.install
    var showFiles by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Palco animado: logotipo, estado e JOGAR.
        val (statusText, level) = when {
            install == null -> stringResource(R.string.status_checking) to Level.INFO
            // O import so aceita o default.xex USA: outro conteudo ou tamanho depois disso e um ficheiro danificado.
            install.xex?.status == FileStatus.DIFFERENT || install.xex?.status == FileStatus.WRONG_SIZE ->
                stringResource(R.string.status_xex_damaged) to Level.ERROR
            install.playable -> stringResource(R.string.status_ready) to Level.OK
            install.installed -> stringResource(R.string.status_incomplete) to Level.WARN
            else -> stringResource(R.string.status_not_installed) to Level.WARN
        }
        val heroShape = RoundedCornerShape(28.dp)
        BurstStage(
            Modifier
                .fillMaxWidth()
                .height(300.dp)
                .clip(heroShape)
                .border(1.5.dp, DfColors.Cream.copy(alpha = 0.35f), heroShape),
        ) {
            Column(Modifier.align(Alignment.TopStart).padding(20.dp)) {
                Text(
                    "DRAGON BURST",
                    style = LogoStyle.copy(
                        fontSize = 38.sp,
                        shadow = Shadow(DfColors.Night, Offset(0f, 4f), 10f),
                    ),
                    color = Color.White,
                    modifier = Modifier.gradientText(Brush.verticalGradient(listOf(DfColors.Gold, DfColors.Ember))),
                )
                Text(
                    stringResource(R.string.game_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = DfColors.Cream,
                )
            }
            Row(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, DfColors.Night.copy(alpha = 0.92f))))
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.weight(1f)) {
                    Pill(statusText, if (level == Level.OK) DfColors.Gold else level.color())
                }
                Button(
                    onClick = { vm.play(context) },
                    enabled = install?.playable == true && vm.busy == null,
                    modifier = Modifier.height(56.dp).widthIn(min = 180.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = DfColors.Gold, contentColor = DfColors.Panel),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(Icons.Filled.PlayArrow, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.play), style = MaterialTheme.typography.titleMedium)
                }
            }
        }

        SectionCard(
            stringResource(R.string.game_files_title),
            Icons.Filled.FolderOpen,
            subtitle = stringResource(R.string.game_files_subtitle),
        ) {
            Text(stringResource(R.string.game_files_help), style = MaterialTheme.typography.bodySmall)
            vm.importIssue?.let { (res, detail) ->
                StatusLine(stringResource(res, detail), Level.ERROR)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = { pickIso.launch(arrayOf("application/x-iso9660-image", "application/octet-stream", "*/*")) },
                    enabled = vm.busy == null,
                ) {
                    Icon(Icons.Filled.Album, null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.import_iso))
                }
                OutlinedButton(onClick = { pickFolder.launch(null) }, enabled = vm.busy == null) {
                    Icon(Icons.Filled.FolderOpen, null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.import_folder))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.refreshInstall(true) }, enabled = install?.installed == true && vm.busy == null) {
                    Icon(Icons.Filled.VerifiedUser, null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.verify))
                }
            }
            if (install != null && install.installed) {
                TextButton(onClick = { showFiles = !showFiles }) {
                    Text(stringResource(if (showFiles) R.string.hide_files else R.string.show_files))
                }
                if (showFiles) {
                    install.checks.forEach { c ->
                        val (label, lvl) = when (c.status) {
                            FileStatus.OK -> stringResource(R.string.file_ok) to Level.OK
                            FileStatus.MISSING -> stringResource(if (c.required) R.string.file_missing_required else R.string.file_missing_optional) to
                                (if (c.required) Level.ERROR else Level.INFO)
                            FileStatus.DIFFERENT -> stringResource(R.string.file_damaged) to Level.ERROR
                            FileStatus.WRONG_SIZE -> stringResource(R.string.file_wrong_size) to Level.ERROR
                        }
                        StatusLine("${c.name} — $label", lvl)
                    }
                }
            }
        }

        SectionCard(stringResource(R.string.launch_options_title), Icons.Filled.ScreenRotation) {
            SettingSegmented(
                title = stringResource(R.string.orientation),
                options = listOf(
                    GameOrientation.LANDSCAPE to stringResource(R.string.orientation_landscape),
                    GameOrientation.REVERSE to stringResource(R.string.orientation_reverse),
                    GameOrientation.AUTO to stringResource(R.string.orientation_auto),
                ),
                selected = vm.gameOrientation,
                onSelect = vm::setOrientation,
            )
            OutlinedButton(onClick = { vm.createShortcut(context) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.AddToHomeScreen, null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.create_shortcut))
            }
            Text(stringResource(R.string.create_shortcut_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SectionCard(
            stringResource(R.string.community_title),
            Icons.Filled.Groups,
            subtitle = stringResource(R.string.community_sub),
        ) {
            fun open(url: String) = context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                LinkButton("Discord", Icons.Filled.Forum, Color(0xFF5865F2), Modifier.weight(1f)) { open(DragonBurstApp.DISCORD_URL) }
                LinkButton("YouTube", Icons.Filled.SmartDisplay, Color(0xFFE62117), Modifier.weight(1f)) { open(DragonBurstApp.YOUTUBE_URL) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                LinkButton("GitHub", Icons.Filled.Code, Color(0xFF6E5494), Modifier.weight(1f)) { open(DragonBurstApp.GITHUB_URL) }
                LinkButton("Ko-fi", Icons.Filled.LocalCafe, Color(0xFF13A8D8), Modifier.weight(1f)) { open(DragonBurstApp.KOFI_URL) }
            }
            Text(stringResource(R.string.community_kofi), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SectionCard(stringResource(R.string.quick_links_title), Icons.Filled.SportsEsports) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                FilledTonalButton(onClick = { goTo(LauncherTab.PERFORMANCE) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.tab_performance))
                }
                FilledTonalButton(onClick = { goTo(LauncherTab.CONTROLS) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.tab_controls))
                }
                FilledTonalButton(onClick = { goTo(LauncherTab.DRIVERS) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.tab_drivers))
                }
            }
            val active = vm.activeDriver
            Text(
                if (active == null) stringResource(R.string.driver_current_system) else stringResource(R.string.driver_current, active),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Botao de um link da comunidade: a cor da marca so na faixa e no icone. */
@Composable
private fun LinkButton(label: String, icon: ImageVector, accent: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.7f)),
    ) {
        Icon(icon, null, tint = accent)
        Spacer(Modifier.width(8.dp))
        Text(label, maxLines = 1)
    }
}
