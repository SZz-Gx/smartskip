package io.github.szzgx.smartskip.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.szzgx.smartskip.ui.theme.SmartSkipTheme

/**
 * The single Activity.
 *
 * Compose-only navigation lives in [SmartSkipApp]; this class exists to host it, apply the
 * theme and opt into edge-to-edge drawing.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SmartSkipTheme {
                SmartSkipApp()
            }
        }
    }
}
