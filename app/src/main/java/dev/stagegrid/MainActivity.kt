package dev.stagegrid

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.stagegrid.ui.StageGridApp
import dev.stagegrid.ui.StageGridViewModel
import dev.stagegrid.ui.theme.StageGridTheme

class MainActivity : ComponentActivity() {
    private val viewModel: StageGridViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            DisposableEffect(settings.liveMode) {
                if (settings.liveMode) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                onDispose { }
            }
            StageGridTheme {
                StageGridApp(viewModel)
            }
        }
    }
}
