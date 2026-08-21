package dev.stagegrid

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dev.stagegrid.debug.StageGridDebugLog
import dev.stagegrid.ui.StageGridApp
import dev.stagegrid.ui.StageGridViewModel
import dev.stagegrid.ui.startup.QuickSetupScreen
import dev.stagegrid.ui.startup.StageGridSplashScreen
import dev.stagegrid.ui.theme.StageGridTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: StageGridViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as StageGridApplication
        val startupPrefs = getSharedPreferences(STARTUP_PREFS, MODE_PRIVATE)

        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            var splashFinished by rememberSaveable { mutableStateOf(false) }
            var onboardingComplete by rememberSaveable {
                mutableStateOf(startupPrefs.getBoolean(KEY_ONBOARDING_COMPLETE, false))
            }

            LaunchedEffect(Unit) {
                delay(SPLASH_MINIMUM_MS)
                splashFinished = true
            }

            DisposableEffect(settings.liveMode) {
                if (settings.liveMode) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                onDispose { }
            }

            StageGridTheme {
                Box(Modifier.fillMaxSize()) {
                    when {
                        !splashFinished -> StageGridSplashScreen()

                        !onboardingComplete -> QuickSetupScreen(
                            initialLiveMode = settings.liveMode,
                            initialPerformanceLock = settings.performanceLock,
                            initialClickSubdivision = settings.clickSubdivision,
                            initialCountInBars = settings.countInBars,
                            initialGuideLanguage = settings.nativeGuideLanguage,
                            onSkip = {
                                StageGridDebugLog.action("STARTUP", "QUICK_SETUP_SKIPPED")
                                startupPrefs.edit().putBoolean(KEY_ONBOARDING_COMPLETE, true).apply()
                                onboardingComplete = true
                            },
                            onFinish = { choices ->
                                lifecycleScope.launch {
                                    StageGridDebugLog.action(
                                        "STARTUP",
                                        "QUICK_SETUP_COMPLETE liveMode=${choices.liveMode} performanceLock=${choices.performanceLock} click=${choices.clickSubdivision.name} countIn=${choices.countInBars} guideLanguage=${choices.guideLanguage}",
                                    )
                                    app.settings.setLiveMode(choices.liveMode)
                                    app.settings.setPerformanceLock(choices.performanceLock)
                                    app.settings.setClickSubdivision(choices.clickSubdivision)
                                    app.settings.setCountInBars(choices.countInBars)
                                    app.settings.setNativeGuideLanguage(choices.guideLanguage)
                                    startupPrefs.edit().putBoolean(KEY_ONBOARDING_COMPLETE, true).apply()
                                    onboardingComplete = true
                                }
                            },
                        )

                        else -> StageGridApp(viewModel)
                    }

                    if (BuildConfig.DEBUG && splashFinished && onboardingComplete) {
                        Text(
                            text = "StageGrid ${BuildConfig.VERSION_NAME} • DEBUG",
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .windowInsetsPadding(WindowInsets.safeDrawing)
                                .padding(top = 4.dp, end = 8.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        )
                    }
                }
            }
        }
    }

    private companion object {
        const val STARTUP_PREFS = "stagegrid_startup"
        const val KEY_ONBOARDING_COMPLETE = "onboarding_complete_v1"
        const val SPLASH_MINIMUM_MS = 1_050L
    }
}
