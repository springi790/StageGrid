package dev.stagegrid.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.stagegrid.audio.ClickSubdivision
import dev.stagegrid.debug.StageGridDebugLog
import dev.stagegrid.model.OutputBus
import dev.stagegrid.model.StereoRoute
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.stageGridDataStore by preferencesDataStore(name = "stagegrid_settings")

/**
 * Process-wide read-only gate for lower-level Native Guide helpers that do not own the settings
 * repository. The authoritative value remains DataStore; this mirror is updated whenever the
 * settings flow is collected by the app.
 */
object NativeGuideFeatureGate {
    private val enabledFlag = AtomicBoolean(false)
    val enabled: Boolean get() = enabledFlag.get()
    internal fun update(enabled: Boolean) = enabledFlag.set(enabled)
}

class AppSettingsRepository(private val context: Context) {
    data class Settings(
        val liveMode: Boolean = false,
        val performanceLock: Boolean = false,
        val clickSubdivision: ClickSubdivision = ClickSubdivision.QUARTER,
        val clickRoute: StereoRoute = StereoRoute.BOTH,
        val clickBus: OutputBus = OutputBus.OUT_1_2,
        val countInBars: Int = 0,
        /** Experimental/Beta feature. Off by default and opt-in only. */
        val nativeGuideExperimentalEnabled: Boolean = false,
        /** auto follows the detected Guide language; es/en/fr/pt force an installed language. */
        val nativeGuideLanguage: String = "auto",
        /** Try documented online catalog providers after import. Network failure never blocks import. */
        val metadataOnlineLookupEnabled: Boolean = true,
        /** Cache cover art inside the song folder so Library stays visual offline. */
        val metadataArtworkEnabled: Boolean = true,
        /** Analyze imported audio locally when BPM/key are absent. */
        val metadataLocalAnalysisEnabled: Boolean = true,
    )

    val settings: Flow<Settings> = context.stageGridDataStore.data.map { values ->
        val nativeGuideEnabled = values[NATIVE_GUIDE_EXPERIMENTAL] ?: false
        NativeGuideFeatureGate.update(nativeGuideEnabled)
        Settings(
            liveMode = values[LIVE_MODE] ?: false,
            performanceLock = values[PERFORMANCE_LOCK] ?: false,
            clickSubdivision = ClickSubdivision.entries.firstOrNull {
                it.subdivisionsPerBeat == (values[CLICK_SUBDIVISION] ?: ClickSubdivision.QUARTER.subdivisionsPerBeat)
            } ?: ClickSubdivision.QUARTER,
            clickRoute = StereoRoute.fromStorage(values[CLICK_ROUTE] ?: StereoRoute.BOTH.name),
            clickBus = OutputBus.fromStorage(values[CLICK_BUS] ?: OutputBus.OUT_1_2.nativeCode),
            countInBars = (values[COUNT_IN_BARS] ?: 0).coerceIn(0, 2),
            nativeGuideExperimentalEnabled = nativeGuideEnabled,
            nativeGuideLanguage = (values[NATIVE_GUIDE_LANGUAGE] ?: "auto")
                .takeIf { it in setOf("auto", "es", "en", "fr", "pt") } ?: "auto",
            metadataOnlineLookupEnabled = values[METADATA_ONLINE_LOOKUP] ?: true,
            metadataArtworkEnabled = values[METADATA_ARTWORK] ?: true,
            metadataLocalAnalysisEnabled = values[METADATA_LOCAL_ANALYSIS] ?: true,
        )
    }

    suspend fun setLiveMode(enabled: Boolean) {
        StageGridDebugLog.action("SETTINGS", "LIVE_MODE enabled=$enabled")
        context.stageGridDataStore.edit { it[LIVE_MODE] = enabled }
    }

    suspend fun setPerformanceLock(enabled: Boolean) {
        StageGridDebugLog.action("SETTINGS", "PERFORMANCE_LOCK enabled=$enabled")
        context.stageGridDataStore.edit { it[PERFORMANCE_LOCK] = enabled }
    }

    suspend fun setClickSubdivision(subdivision: ClickSubdivision) {
        StageGridDebugLog.action("SETTINGS", "CLICK_SUBDIVISION value=${subdivision.name}")
        context.stageGridDataStore.edit { it[CLICK_SUBDIVISION] = subdivision.subdivisionsPerBeat }
    }

    suspend fun setClickRoute(route: StereoRoute) {
        StageGridDebugLog.action("SETTINGS", "CLICK_ROUTE value=$route")
        context.stageGridDataStore.edit { it[CLICK_ROUTE] = route.name }
    }

    suspend fun setClickBus(bus: OutputBus) {
        StageGridDebugLog.action("SETTINGS", "CLICK_BUS value=$bus")
        context.stageGridDataStore.edit { it[CLICK_BUS] = bus.nativeCode }
    }

    suspend fun setCountInBars(bars: Int) {
        val normalized = bars.coerceIn(0, 2)
        StageGridDebugLog.action("SETTINGS", "COUNT_IN bars=$normalized")
        context.stageGridDataStore.edit { it[COUNT_IN_BARS] = normalized }
    }

    suspend fun setNativeGuideExperimentalEnabled(enabled: Boolean) {
        StageGridDebugLog.action("LABS", "NATIVE_GUIDE enabled=$enabled")
        NativeGuideFeatureGate.update(enabled)
        context.stageGridDataStore.edit { it[NATIVE_GUIDE_EXPERIMENTAL] = enabled }
    }

    suspend fun setNativeGuideLanguage(language: String) {
        val normalized = language.takeIf { it in setOf("auto", "es", "en", "fr", "pt") } ?: "auto"
        StageGridDebugLog.action("LABS", "NATIVE_GUIDE_LANGUAGE value=$normalized")
        context.stageGridDataStore.edit { it[NATIVE_GUIDE_LANGUAGE] = normalized }
    }

    suspend fun setMetadataOnlineLookupEnabled(enabled: Boolean) {
        StageGridDebugLog.action("METADATA", "ONLINE_LOOKUP enabled=$enabled")
        context.stageGridDataStore.edit { it[METADATA_ONLINE_LOOKUP] = enabled }
    }

    suspend fun setMetadataArtworkEnabled(enabled: Boolean) {
        StageGridDebugLog.action("METADATA", "ARTWORK enabled=$enabled")
        context.stageGridDataStore.edit { it[METADATA_ARTWORK] = enabled }
    }

    suspend fun setMetadataLocalAnalysisEnabled(enabled: Boolean) {
        StageGridDebugLog.action("METADATA", "LOCAL_ANALYSIS enabled=$enabled")
        context.stageGridDataStore.edit { it[METADATA_LOCAL_ANALYSIS] = enabled }
    }

    private companion object {
        val LIVE_MODE = booleanPreferencesKey("live_mode")
        val PERFORMANCE_LOCK = booleanPreferencesKey("performance_lock")
        val CLICK_SUBDIVISION = intPreferencesKey("click_subdivision")
        val CLICK_ROUTE = stringPreferencesKey("click_route")
        val CLICK_BUS = intPreferencesKey("click_bus")
        val COUNT_IN_BARS = intPreferencesKey("count_in_bars")
        val NATIVE_GUIDE_EXPERIMENTAL = booleanPreferencesKey("native_guide_experimental")
        val NATIVE_GUIDE_LANGUAGE = stringPreferencesKey("native_guide_language")
        val METADATA_ONLINE_LOOKUP = booleanPreferencesKey("metadata_online_lookup")
        val METADATA_ARTWORK = booleanPreferencesKey("metadata_artwork")
        val METADATA_LOCAL_ANALYSIS = booleanPreferencesKey("metadata_local_analysis")
    }
}
