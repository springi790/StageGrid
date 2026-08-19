package dev.stagegrid.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.stagegrid.audio.ClickSubdivision
import dev.stagegrid.model.StereoRoute
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.stageGridDataStore by preferencesDataStore(name = "stagegrid_settings")

class AppSettingsRepository(private val context: Context) {
    data class Settings(
        val liveMode: Boolean = false,
        val performanceLock: Boolean = false,
        val clickSubdivision: ClickSubdivision = ClickSubdivision.QUARTER,
        val clickRoute: StereoRoute = StereoRoute.BOTH,
        val countInBars: Int = 0,
    )

    val settings: Flow<Settings> = context.stageGridDataStore.data.map { values ->
        Settings(
            liveMode = values[LIVE_MODE] ?: false,
            performanceLock = values[PERFORMANCE_LOCK] ?: false,
            clickSubdivision = ClickSubdivision.entries.firstOrNull {
                it.subdivisionsPerBeat == (values[CLICK_SUBDIVISION] ?: ClickSubdivision.QUARTER.subdivisionsPerBeat)
            } ?: ClickSubdivision.QUARTER,
            clickRoute = StereoRoute.fromStorage(values[CLICK_ROUTE] ?: StereoRoute.BOTH.name),
            countInBars = (values[COUNT_IN_BARS] ?: 0).coerceIn(0, 2),
        )
    }

    suspend fun setLiveMode(enabled: Boolean) {
        context.stageGridDataStore.edit { it[LIVE_MODE] = enabled }
    }

    suspend fun setPerformanceLock(enabled: Boolean) {
        context.stageGridDataStore.edit { it[PERFORMANCE_LOCK] = enabled }
    }

    suspend fun setClickSubdivision(subdivision: ClickSubdivision) {
        context.stageGridDataStore.edit { it[CLICK_SUBDIVISION] = subdivision.subdivisionsPerBeat }
    }

    suspend fun setClickRoute(route: StereoRoute) {
        context.stageGridDataStore.edit { it[CLICK_ROUTE] = route.name }
    }

    suspend fun setCountInBars(bars: Int) {
        context.stageGridDataStore.edit { it[COUNT_IN_BARS] = bars.coerceIn(0, 2) }
    }

    private companion object {
        val LIVE_MODE = booleanPreferencesKey("live_mode")
        val PERFORMANCE_LOCK = booleanPreferencesKey("performance_lock")
        val CLICK_SUBDIVISION = intPreferencesKey("click_subdivision")
        val CLICK_ROUTE = stringPreferencesKey("click_route")
        val COUNT_IN_BARS = intPreferencesKey("count_in_bars")
    }
}
