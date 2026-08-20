package dev.stagegrid.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.stagegrid.R
import dev.stagegrid.audio.AudioDeviceManager
import dev.stagegrid.audio.NativeAudioEngine
import dev.stagegrid.settings.AppSettingsRepository
import dev.stagegrid.ui.StageGridViewModel
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun SettingsScreen(
    settings: AppSettingsRepository.Settings,
    guidePackState: StageGridViewModel.GuidePackUiState,
    outputs: List<AudioDeviceManager.OutputDevice>,
    selectedOutputId: Int?,
    diagnosticsProvider: () -> NativeAudioEngine.Diagnostics,
    onLiveMode: (Boolean) -> Unit,
    onPerformanceLock: (Boolean) -> Unit,
    onOutput: (Int) -> Unit,
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

    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(stringResource(R.string.settings), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }
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
            )
        }
        item {
            Text(stringResource(R.string.native_guide_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.native_guide_description), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (guidePackState.installing) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CircularProgressIndicator()
                            Text(stringResource(R.string.guide_pack_installing))
                        }
                    } else if (guidePackState.status.installed) {
                        val languageSummary = guidePackState.status.languages.joinToString(", ") { it.uppercase(Locale.ROOT) }
                        Text(
                            stringResource(
                                R.string.guide_pack_installed,
                                guidePackState.status.sampleCount,
                                languageSummary,
                            ),
                            fontWeight = FontWeight.SemiBold,
                        )
                        guidePackState.status.sourceName?.let {
                            Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(stringResource(R.string.native_guide_language), fontWeight = FontWeight.SemiBold)
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            val options = listOf("auto") + guidePackState.status.languages
                            options.distinct().forEach { language ->
                                FilterChip(
                                    selected = settings.nativeGuideLanguage == language,
                                    onClick = { onNativeGuideLanguage(language) },
                                    label = {
                                        Text(
                                            if (language == "auto") stringResource(R.string.guide_language_auto)
                                            else guideLanguageLabel(language),
                                        )
                                    },
                                )
                            }
                        }
                    } else {
                        Text(stringResource(R.string.guide_pack_not_installed), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    guidePackState.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    OutlinedButton(onClick = onInstallGuidePack, enabled = !guidePackState.installing) {
                        Text(
                            if (guidePackState.status.installed) stringResource(R.string.guide_pack_replace)
                            else stringResource(R.string.guide_pack_install),
                        )
                    }
                    Text(stringResource(R.string.native_guide_import_note), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Text(stringResource(R.string.backup_restore_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.backup_restore_description), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.backup_restore_contents), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = onCreateBackup,
                            enabled = !backupBusy,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.backup_create_button))
                        }
                        OutlinedButton(
                            onClick = onRestoreBackup,
                            enabled = !backupBusy,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.backup_restore_button))
                        }
                    }
                    Text(stringResource(R.string.backup_restore_provider_note), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Text(stringResource(R.string.audio_outputs), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.bluetooth_latency_warning), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(outputs, key = { it.id }) { device ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(device.productName, fontWeight = FontWeight.SemiBold)
                        val type = when {
                            device.isUsb -> stringResource(R.string.usb)
                            device.isBluetooth -> stringResource(R.string.bluetooth)
                            else -> stringResource(R.string.android_audio)
                        }
                        Text(
                            stringResource(
                                R.string.device_details,
                                type,
                                device.channelCounts.maxOrNull() ?: 0,
                                device.sampleRates.maxOrNull() ?: 0,
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(onClick = { onOutput(device.id) }, enabled = selectedOutputId != device.id) {
                        Text(if (selectedOutputId == device.id) stringResource(R.string.selected) else stringResource(R.string.select))
                    }
                }
            }
        }
        item {
            Text(stringResource(R.string.diagnostics), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.sample_rate_value, diagnostics.sampleRate))
                    Text(stringResource(R.string.buffer_burst_value, diagnostics.framesPerBurst))
                    Text(stringResource(R.string.underruns_value, diagnostics.underruns))
                    Text(stringResource(R.string.cpu_load_value, diagnostics.cpuLoad * 100f))
                    Text(stringResource(R.string.loaded_tracks_value, diagnostics.loadedTracks))
                    if (diagnostics.lastError.isNotBlank()) Text(stringResource(R.string.last_error_value, diagnostics.lastError), color = MaterialTheme.colorScheme.error)
                }
            }
        }
        item {
            Text(stringResource(R.string.pcm_wav_note), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
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
) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}
