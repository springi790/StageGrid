package dev.stagegrid.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.stagegrid.R
import dev.stagegrid.audio.AudioDeviceManager
import dev.stagegrid.audio.NativeAudioEngine
import dev.stagegrid.settings.AppSettingsRepository
import dev.stagegrid.storage.StorageCacheManager
import dev.stagegrid.ui.StageGridViewModel
import dev.stagegrid.ui.components.StageGridMetric
import dev.stagegrid.ui.components.StageGridPanel
import dev.stagegrid.ui.components.StageGridPill
import dev.stagegrid.ui.components.StageGridScreenHeader
import dev.stagegrid.ui.theme.StageGridColors
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(
    settings: AppSettingsRepository.Settings,
    guidePackState: StageGridViewModel.GuidePackUiState,
    outputs: List<AudioDeviceManager.OutputDevice>,
    selectedOutputId: Int?,
    diagnosticsProvider: () -> NativeAudioEngine.Diagnostics,
    onLiveMode: (Boolean) -> Unit,
    onPerformanceLock: (Boolean) -> Unit,
    onOutput: (Int, Int) -> Unit,
    onTestOutput: (Int) -> Unit,
    onInstallGuidePack: () -> Unit,
    onNativeGuideLanguage: (String) -> Unit,
    onCreateBackup: () -> Unit,
    onRestoreBackup: () -> Unit,
    backupBusy: Boolean,
    modifier: Modifier = Modifier,
) {
    var diagnostics by remember { mutableStateOf(diagnosticsProvider()) }
    LaunchedEffect(Unit) {
        while (true) {
            diagnostics = diagnosticsProvider()
            delay(1_000)
        }
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsRepository = remember(context.applicationContext) { AppSettingsRepository(context.applicationContext) }
    val storageManager = remember(context.filesDir) { StorageCacheManager(context.filesDir) }
    var storageSnapshot by remember { mutableStateOf<StorageCacheManager.Snapshot?>(null) }
    var storageBusy by remember { mutableStateOf(false) }
    var reclaimedBytes by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(context.filesDir) {
        storageSnapshot = withContext(Dispatchers.IO) { storageManager.snapshot() }
    }

    LazyColumn(
        modifier.fillMaxSize().padding(horizontal = 14.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 14.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            StageGridScreenHeader(
                kicker = stringResource(R.string.settings_kicker),
                title = stringResource(R.string.settings),
                subtitle = "StageGrid  ·  ${diagnostics.sampleRate / 1000f} kHz  ·  ${diagnostics.outputChannelCount} OUT",
                badge = stringResource(R.string.brand_ready),
            )
        }

        item { SettingsSectionTitle(stringResource(R.string.settings_stage_title), stringResource(R.string.settings_stage_desc)) }
        item {
            SettingSwitch(
                title = stringResource(R.string.live_mode),
                description = stringResource(R.string.live_mode_desc),
                checked = settings.liveMode,
                onCheckedChange = onLiveMode,
            )
        }
        item {
            SettingSwitch(
                title = stringResource(R.string.performance_lock),
                description = stringResource(R.string.performance_lock_desc),
                checked = settings.performanceLock,
                onCheckedChange = onPerformanceLock,
                accent = MaterialTheme.colorScheme.tertiary,
            )
        }

        item {
            StageGridPanel(accent = MaterialTheme.colorScheme.tertiary) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.labs_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                            Text(stringResource(R.string.labs_description), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        StageGridPill(stringResource(R.string.labs_badge), MaterialTheme.colorScheme.tertiary)
                    }

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.native_guide_experimental_toggle), fontWeight = FontWeight.Bold)
                            Text(stringResource(R.string.native_guide_experimental_desc), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = settings.nativeGuideExperimentalEnabled,
                            onCheckedChange = { enabled ->
                                scope.launch { settingsRepository.setNativeGuideExperimentalEnabled(enabled) }
                            },
                        )
                    }

                    Text(
                        if (settings.nativeGuideExperimentalEnabled) stringResource(R.string.native_guide_experimental_on)
                        else stringResource(R.string.native_guide_experimental_off),
                        color = if (settings.nativeGuideExperimentalEnabled) StageGridColors.Amber else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                    )

                    if (settings.nativeGuideExperimentalEnabled) {
                        NativeGuideLabControls(
                            settings = settings,
                            guidePackState = guidePackState,
                            onInstallGuidePack = onInstallGuidePack,
                            onNativeGuideLanguage = onNativeGuideLanguage,
                        )
                    }
                }
            }
        }

        item { MidiMonitorPanelHost() }

        item { SettingsSectionTitle(stringResource(R.string.settings_audio_title), stringResource(R.string.settings_audio_desc)) }
        item {
            StageGridPanel(accent = MaterialTheme.colorScheme.primary) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        StageGridMetric(stringResource(R.string.status_engine), if (diagnostics.lastError.isBlank()) "OK" else "ERR", Modifier.weight(1f), if (diagnostics.lastError.isBlank()) StageGridColors.Mint else MaterialTheme.colorScheme.error)
                        StageGridMetric(stringResource(R.string.status_output), "${diagnostics.outputChannelCount} CH", Modifier.weight(1f))
                        StageGridMetric(stringResource(R.string.status_tracks), diagnostics.loadedTracks.toString(), Modifier.weight(1f), MaterialTheme.colorScheme.tertiary)
                        StageGridMetric(stringResource(R.string.status_cpu), String.format(Locale.ROOT, "%.0f%%", diagnostics.cpuLoad * 100f), Modifier.weight(1f), StageGridColors.Amber)
                    }
                    Text(stringResource(R.string.negotiated_output, diagnostics.outputChannelCount, diagnostics.sampleRate), fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.requested_output_channels, diagnostics.requestedOutputChannelCount), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (diagnostics.multichannelFallback) {
                        StageGridPill(stringResource(R.string.output_fallback_active), StageGridColors.Amber)
                    }
                }
            }
        }

        items(outputs, key = { it.id }) { device ->
            StageGridPanel(accent = if (selectedOutputId == device.id) MaterialTheme.colorScheme.primary else null) {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(device.productName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            val type = when {
                                device.isUsb -> stringResource(R.string.usb)
                                device.isBluetooth -> stringResource(R.string.bluetooth)
                                else -> stringResource(R.string.android_audio)
                            }
                            Text(
                                stringResource(R.string.device_details, type, device.maxChannelCount, device.sampleRates.maxOrNull() ?: 0),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (selectedOutputId == device.id) StageGridPill(stringResource(R.string.selected), StageGridColors.Mint)
                    }
                    Text(
                        stringResource(R.string.device_channel_capability, device.maxChannelCount, device.preferredStageGridChannels),
                        color = if (device.supportsMultichannel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = { onOutput(device.id, device.preferredStageGridChannels) },
                        enabled = selectedOutputId != device.id,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (selectedOutputId == device.id) stringResource(R.string.selected) else stringResource(R.string.select))
                    }
                }
            }
        }

        item {
            StageGridPanel {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text(stringResource(R.string.output_test_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.output_test_help), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        repeat(diagnostics.outputChannelCount.coerceIn(2, 8)) { index ->
                            OutlinedButton(onClick = { onTestOutput(index) }) {
                                Text(stringResource(R.string.test_output_channel, index + 1))
                            }
                        }
                    }
                }
            }
        }

        item { SettingsSectionTitle(stringResource(R.string.settings_data_title), stringResource(R.string.settings_data_desc)) }
        item {
            StageGridPanel {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.backup_restore_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.backup_restore_description), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.backup_restore_contents), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onCreateBackup, enabled = !backupBusy, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.backup_create_button))
                        }
                        OutlinedButton(onClick = onRestoreBackup, enabled = !backupBusy, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.backup_restore_button))
                        }
                    }
                }
            }
        }

        item {
            StageGridPanel {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(stringResource(R.string.storage_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.storage_description), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val snapshot = storageSnapshot
                    if (snapshot == null) {
                        Text(stringResource(R.string.storage_calculating))
                    } else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            StageGridMetric("TOTAL", formatStorageBytes(snapshot.totalBytes), Modifier.weight(1f))
                            StageGridMetric("CACHE", formatStorageBytes(snapshot.regenerableCacheBytes), Modifier.weight(1f), MaterialTheme.colorScheme.tertiary)
                            StageGridMetric("SONGS", snapshot.songCount.toString(), Modifier.weight(1f), MaterialTheme.colorScheme.secondary)
                        }
                        Text(stringResource(R.string.storage_audio, formatStorageBytes(snapshot.playbackAudioBytes)), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(R.string.storage_guide, formatStorageBytes(snapshot.guideBytes)), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        reclaimedBytes?.let { Text(stringResource(R.string.storage_reclaimed, formatStorageBytes(it)), color = MaterialTheme.colorScheme.primary) }
                        OutlinedButton(
                            onClick = {
                                storageBusy = true
                                reclaimedBytes = null
                                scope.launch {
                                    val reclaimed = withContext(Dispatchers.IO) { storageManager.clearRegenerableCaches() }
                                    storageSnapshot = withContext(Dispatchers.IO) { storageManager.snapshot() }
                                    reclaimedBytes = reclaimed
                                    storageBusy = false
                                }
                            },
                            enabled = !storageBusy && snapshot.regenerableCacheBytes > 0L,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (storageBusy) stringResource(R.string.storage_clearing) else stringResource(R.string.storage_clear_cache))
                        }
                    }
                }
            }
        }

        item {
            StageGridPanel {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.diagnostics), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.sample_rate_value, diagnostics.sampleRate))
                    Text(stringResource(R.string.buffer_burst_value, diagnostics.framesPerBurst))
                    Text(stringResource(R.string.underruns_value, diagnostics.underruns))
                    Text(stringResource(R.string.cpu_load_value, diagnostics.cpuLoad * 100f))
                    Text(stringResource(R.string.loaded_tracks_value, diagnostics.loadedTracks))
                    if (diagnostics.lastError.isNotBlank()) {
                        Text(stringResource(R.string.last_error_value, diagnostics.lastError), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun NativeGuideLabControls(
    settings: AppSettingsRepository.Settings,
    guidePackState: StageGridViewModel.GuidePackUiState,
    onInstallGuidePack: () -> Unit,
    onNativeGuideLanguage: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        if (guidePackState.installing) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator()
                Text(stringResource(R.string.guide_pack_installing))
            }
        } else if (guidePackState.status.installed) {
            val languageSummary = guidePackState.status.languages.joinToString(", ") { it.uppercase(Locale.ROOT) }
            Text(stringResource(R.string.guide_pack_installed, guidePackState.status.sampleCount, languageSummary), fontWeight = FontWeight.SemiBold)
            guidePackState.status.sourceName?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(stringResource(R.string.native_guide_language), fontWeight = FontWeight.SemiBold)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                (listOf("auto") + guidePackState.status.languages).distinct().forEach { language ->
                    FilterChip(
                        selected = settings.nativeGuideLanguage == language,
                        onClick = { onNativeGuideLanguage(language) },
                        label = {
                            Text(if (language == "auto") stringResource(R.string.guide_language_auto) else guideLanguageLabel(language))
                        },
                    )
                }
            }
        } else {
            Text(stringResource(R.string.guide_pack_not_installed), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        guidePackState.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        OutlinedButton(onClick = onInstallGuidePack, enabled = !guidePackState.installing, modifier = Modifier.fillMaxWidth()) {
            Text(if (guidePackState.status.installed) stringResource(R.string.guide_pack_replace) else stringResource(R.string.guide_pack_install))
        }
        Text(stringResource(R.string.native_guide_import_note), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SettingsSectionTitle(title: String, description: String) {
    Column(Modifier.padding(top = 6.dp, bottom = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
        Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun guideLanguageLabel(language: String): String = when (language) {
    "es" -> stringResource(R.string.guide_language_spanish)
    "en" -> stringResource(R.string.guide_language_english)
    "fr" -> stringResource(R.string.guide_language_french)
    "pt" -> stringResource(R.string.guide_language_portuguese)
    else -> language.uppercase(Locale.ROOT)
}

@Composable
private fun SettingSwitch(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    accent: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
) {
    StageGridPanel(accent = if (checked) accent else null) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold)
                Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

private fun formatStorageBytes(bytes: Long): String {
    val value = bytes.coerceAtLeast(0L).toDouble()
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var scaled = value
    var unit = 0
    while (scaled >= 1024.0 && unit < units.lastIndex) {
        scaled /= 1024.0
        unit++
    }
    return if (unit == 0) "${scaled.toLong()} ${units[unit]}"
    else String.format(Locale.ROOT, "%.1f %s", scaled, units[unit])
}
