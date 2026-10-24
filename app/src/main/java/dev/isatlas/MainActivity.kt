package dev.isatlas

import android.os.Bundle
import android.content.Intent
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        UpdateRepository.get(this).resume()
    }

    override fun onPause() {
        UpdateRepository.get(this).pause()
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("show_update", false)) UpdateRepository.get(this).openPrompt()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra("show_update", false)) UpdateRepository.get(this).openPrompt()
        enableEdgeToEdge()
        val bars = WindowCompat.getInsetsController(window, window.decorView)
        bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            bars.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            bars.show(WindowInsetsCompat.Type.systemBars())
        }
        setContent {
            val scheme = if (isSystemInDarkTheme()) darkColorScheme(
                primary = Color(0xFF7DDACB), secondary = Color(0xFFADCBC3),
                background = Color(0xFF101817), surface = Color(0xFF101817),
            ) else lightColorScheme(
                primary = Color(0xFF0D756B), secondary = Color(0xFF526B64),
                background = Color(0xFFF6F8F7), surface = Color(0xFFF6F8F7),
            )
            MaterialTheme(colorScheme = scheme) { AtlasApp() }
        }
    }
}
