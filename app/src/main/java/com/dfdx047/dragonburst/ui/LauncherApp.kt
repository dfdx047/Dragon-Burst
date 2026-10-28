@file:OptIn(ExperimentalMaterial3Api::class)

package com.dfdx047.dragonburst.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.SportsMartialArts
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Texture
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dfdx047.dragonburst.R
import com.dfdx047.dragonburst.data.GpuAdvice
import com.dfdx047.dragonburst.data.GraphicsPreset
import com.dfdx047.dragonburst.data.recommendedPreset
import com.dfdx047.dragonburst.data.withPreset
import com.dfdx047.dragonburst.ui.screens.AboutScreen
import com.dfdx047.dragonburst.ui.screens.ControlsScreen
import com.dfdx047.dragonburst.ui.screens.DebugScreen
import com.dfdx047.dragonburst.ui.screens.GameScreen
import com.dfdx047.dragonburst.ui.screens.DriversScreen
import com.dfdx047.dragonburst.ui.screens.HomeScreen
import com.dfdx047.dragonburst.ui.screens.ModsScreen
import com.dfdx047.dragonburst.ui.screens.PerformanceScreen
import com.dfdx047.dragonburst.ui.screens.SettingsScreen
import com.dfdx047.dragonburst.ui.screens.TexturesScreen
import com.dfdx047.dragonburst.ui.theme.DfColors
import com.dfdx047.dragonburst.ui.theme.LogoStyle
import com.dfdx047.dragonburst.ui.components.gradientText

enum class LauncherTab(@StringRes val title: Int, val icon: ImageVector) {
    HOME(R.string.tab_home, Icons.Filled.Home),
    PERFORMANCE(R.string.tab_performance, Icons.Filled.Speed),
    CONTROLS(R.string.tab_controls, Icons.Filled.Gamepad),
    GAME(R.string.tab_game, Icons.Filled.SportsMartialArts),
    MODS(R.string.tab_mods, Icons.Filled.Extension),
    TEXTURES(R.string.tab_textures, Icons.Filled.Texture),
    DRIVERS(R.string.tab_drivers, Icons.Filled.Memory),
    DEBUG(R.string.tab_debug, Icons.Filled.BugReport),
    SETTINGS(R.string.tab_settings, Icons.Filled.Settings),
    ABOUT(R.string.tab_about, Icons.Filled.Info),
}

@Composable
fun LauncherApp(vm: LauncherViewModel) {
    var tab by rememberSaveable { mutableStateOfTab() }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(vm.message) {
        val m = vm.message ?: return@LaunchedEffect
        snackbar.showSnackbar(context.getString(m.res, *m.args.toTypedArray()))
        vm.message = null
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets(0),
    ) { inner ->
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .background(MaterialTheme.colorScheme.background)
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            val wide = maxWidth > maxHeight && maxWidth >= 600.dp
            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    SideRail(tab) { tab = it }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        BusyBar(vm.busy)
                        TabContent(tab, vm) { tab = it }
                    }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    Header()
                    ScrollableTabRow(
                        selectedTabIndex = tab.ordinal,
                        edgePadding = 12.dp,
                        containerColor = MaterialTheme.colorScheme.background,
                    ) {
                        LauncherTab.entries.forEach { t ->
                            Tab(
                                selected = t == tab,
                                onClick = { tab = t },
                                text = { Text(stringResource(t.title), maxLines = 1) },
                                icon = { Icon(t.icon, null) },
                            )
                        }
                    }
                    BusyBar(vm.busy)
                    TabContent(tab, vm) { tab = it }
                }
            }
        }
    }

    if (vm.showGpuNotice) GpuNotice(vm, openTab = { tab = it })
    else if (vm.showScreenResNotice) ScreenResNotice(vm)
    if (vm.showGpuLost) GpuLostDialog(vm, openTab = { tab = it })
    if (vm.showUpdateDialog) UpdateDialog(vm)
}

private fun mutableStateOfTab() = androidx.compose.runtime.mutableStateOf(LauncherTab.HOME)

@Composable
private fun Header() {
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.horizontalGradient(listOf(DfColors.Night, DfColors.Royal, DfColors.Panel)),
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painterResource(R.drawable.ic_launcher_foreground),
            null,
            tint = androidx.compose.ui.graphics.Color.Unspecified,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                "DRAGON BURST",
                style = LogoStyle,
                color = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier.gradientText(Brush.verticalGradient(listOf(DfColors.Gold, DfColors.Ember))),
            )
            Text(
                stringResource(R.string.header_subtitle),
                style = MaterialTheme.typography.labelMedium,
                color = DfColors.Cream,
            )
        }
    }
}

@Composable
private fun SideRail(selected: LauncherTab, onSelect: (LauncherTab) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxHeight().width(96.dp)) {
        Column(
            Modifier
                .fillMaxHeight()
                .background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.background)))
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                painterResource(R.drawable.ic_launcher_foreground),
                null,
                tint = androidx.compose.ui.graphics.Color.Unspecified,
                modifier = Modifier.size(56.dp),
            )
            LauncherTab.entries.forEach { t ->
                NavigationRailItem(
                    selected = t == selected,
                    onClick = { onSelect(t) },
                    icon = { Icon(t.icon, null) },
                    label = { Text(stringResource(t.title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
        }
    }
}

@Composable
private fun BusyBar(busy: Busy?) {
    if (busy == null) return
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            stringResource(busy.label) + if (busy.detail.isNotEmpty()) " · ${busy.detail}" else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.heightIn(min = 4.dp))
        if (busy.progress != null) {
            LinearProgressIndicator(progress = { busy.progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun TabContent(tab: LauncherTab, vm: LauncherViewModel, goTo: (LauncherTab) -> Unit) {
    Box(Modifier.fillMaxSize()) {
        when (tab) {
            LauncherTab.HOME -> HomeScreen(vm, goTo)
            LauncherTab.PERFORMANCE -> PerformanceScreen(vm)
            LauncherTab.CONTROLS -> ControlsScreen(vm)
            LauncherTab.MODS -> ModsScreen(vm)
            LauncherTab.TEXTURES -> TexturesScreen(vm)
            LauncherTab.DRIVERS -> DriversScreen(vm)
            LauncherTab.GAME -> GameScreen(vm)
            LauncherTab.DEBUG -> DebugScreen(vm)
            LauncherTab.SETTINGS -> SettingsScreen(vm)
            LauncherTab.ABOUT -> AboutScreen(vm)
        }
    }
}

/**
 * Primeiro arranque (e pelo Sobre): a GPU deste aparelho, a predefinicao sugerida e o driver
 * pelo que o do sistema tem (GpuAdvice). Aceitar aplica a predefinicao; Escolher eu mesmo abre
 * a aba Desempenho. Fechar marca-o como visto.
 */
@Composable
private fun GpuNotice(vm: LauncherViewModel, openTab: (LauncherTab) -> Unit) {
    val context = LocalContext.current
    val preset = remember { recommendedPreset() }
    val device = remember { GpuAdvice.deviceLabel() }
    val driver = remember { GpuAdvice.driver(context) }
    val vulkan = remember { GpuAdvice.vulkanLabel(GpuAdvice.systemVulkanVersion(context)) }
    val presetName = stringResource(
        when (preset) {
            GraphicsPreset.MINIMUM -> R.string.preset_minimum
            GraphicsPreset.LIGHT -> R.string.preset_light
            GraphicsPreset.BALANCED -> R.string.preset_balanced
            GraphicsPreset.HIGH -> R.string.preset_high
            GraphicsPreset.ULTRA -> R.string.preset_ultra
        },
    )
    val presetDesc = stringResource(
        when (preset) {
            GraphicsPreset.MINIMUM -> R.string.preset_minimum_desc
            GraphicsPreset.LIGHT -> R.string.preset_light_desc
            GraphicsPreset.BALANCED -> R.string.preset_balanced_desc
            GraphicsPreset.HIGH -> R.string.preset_high_desc
            GraphicsPreset.ULTRA -> R.string.preset_ultra_desc
        },
    )
    val driverText = when (driver) {
        GpuAdvice.Driver.SYSTEM -> stringResource(R.string.gpu_first_driver_system, vulkan)
        GpuAdvice.Driver.TURNIP_NEEDED -> stringResource(R.string.gpu_first_driver_turnip, vulkan)
        GpuAdvice.Driver.NOT_ADRENO -> stringResource(R.string.gpu_first_driver_other)
    }
    AlertDialog(
        onDismissRequest = { vm.dismissGpuNotice() },
        icon = { Icon(Icons.Filled.Memory, null) },
        title = { Text(stringResource(R.string.gpu_notice_title)) },
        text = {
            Text(
                listOf(
                    stringResource(R.string.gpu_first_device, device),
                    stringResource(R.string.gpu_first_suggest, presetName, presetDesc),
                    driverText,
                    stringResource(R.string.gpu_first_footer),
                ).joinToString("\n\n"),
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            TextButton(onClick = {
                vm.updateConfig { it.withPreset(preset) }
                vm.dismissGpuNotice()
                if (driver == GpuAdvice.Driver.TURNIP_NEEDED) openTab(LauncherTab.DRIVERS)
            }) { Text(stringResource(R.string.gpu_first_accept)) }
        },
        dismissButton = {
            TextButton(onClick = {
                vm.dismissGpuNotice()
                openTab(LauncherTab.PERFORMANCE)
            }) { Text(stringResource(R.string.gpu_first_choose)) }
        },
    )
}

/** Tela pequena: desenhar na altura que a imagem ocupa (LauncherViewModel.showScreenResNotice). */
@Composable
private fun ScreenResNotice(vm: LauncherViewModel) {
    val fit = com.dfdx047.dragonburst.data.ScreenFit
    val h = fit.height(vm.config.letterbox)
    // A altura em que fica desenhado: a do Renut e a mais perto das suas (405 -> 360), o Xenia faz a exata.
    val drawn = if (vm.config.useRenut) fit.renutHeight(vm.config.letterbox) else h
    AlertDialog(
        onDismissRequest = { vm.answerScreenResNotice(false) },
        icon = { Icon(Icons.Filled.Memory, null) },
        title = { Text(stringResource(R.string.screen_res_title)) },
        text = { Text(stringResource(R.string.screen_res_text, h, drawn)) },
        confirmButton = {
            TextButton(onClick = { vm.answerScreenResNotice(true) }) { Text(stringResource(R.string.screen_res_use, drawn)) }
        },
        dismissButton = {
            TextButton(onClick = { vm.answerScreenResNotice(false) }) { Text(stringResource(R.string.screen_res_keep)) }
        },
    )
}

/** O jogo perdeu a GPU (SDK 0188) e voltou aqui em vez de fechar. */
@Composable
private fun GpuLostDialog(vm: LauncherViewModel, openTab: (LauncherTab) -> Unit) {
    // Trocar de driver so e conselho nas Adreno (Turnip); nas outras GPUs o que ha para mexer sao os graficos.
    val adreno = vm.isAdreno
    AlertDialog(
        onDismissRequest = { vm.dismissGpuLost() },
        icon = { Icon(Icons.Filled.Memory, null) },
        title = { Text(stringResource(R.string.gpu_lost_title)) },
        text = { Text(stringResource(if (adreno) R.string.gpu_lost_text else R.string.gpu_lost_text_other)) },
        confirmButton = {
            TextButton(onClick = { vm.dismissGpuLost() }) { Text(stringResource(R.string.gpu_lost_ok)) }
        },
        dismissButton = {
            TextButton(onClick = {
                vm.dismissGpuLost()
                openTab(if (adreno) LauncherTab.DRIVERS else LauncherTab.PERFORMANCE)
            }) { Text(stringResource(if (adreno) R.string.tab_drivers else R.string.tab_performance)) }
        },
    )
}

@Composable
private fun UpdateDialog(vm: LauncherViewModel) {
    val info = vm.updateInfo ?: return
    val context = LocalContext.current
    val language = context.resources.configuration.locales[0].language
    AlertDialog(
        onDismissRequest = { vm.showUpdateDialog = false },
        title = { Text(stringResource(R.string.update_title, info.version)) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.update_current, com.dfdx047.dragonburst.BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(stringResource(R.string.update_changelog), style = MaterialTheme.typography.titleSmall)
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Text(
                        info.changelog(language).ifBlank { stringResource(R.string.update_no_changelog) },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.downloadUpdate(context) }) {
                Text(stringResource(if (info.apkUrl != null) R.string.update_install else R.string.update_open_page))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { vm.skipUpdate() }) { Text(stringResource(R.string.update_skip)) }
                TextButton(onClick = { vm.showUpdateDialog = false }) { Text(stringResource(R.string.later)) }
            }
        },
    )
}
