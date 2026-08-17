package pl.nightvox.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import pl.nightvox.NightVoxApp
import pl.nightvox.ui.theme.NightVoxTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val container = (application as NightVoxApp).container

        // „Nie gaś ekranu” jest opcją debugową — do podglądania metera przy strojeniu progów.
        lifecycleScope.launch {
            container.settingsStore.settings.collectLatest { settings ->
                if (settings.keepScreenOn) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }

        setContent {
            NightVoxTheme {
                NightVoxRoot()
            }
        }
    }
}
