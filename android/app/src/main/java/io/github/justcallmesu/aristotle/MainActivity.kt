package io.github.justcallmesu.aristotle

import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.justcallmesu.aristotle.data.DeviceName
import io.github.justcallmesu.aristotle.ui.AristotleApp
import io.github.justcallmesu.aristotle.ui.theme.AristotleTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Transparent bars whose icons follow the system light/dark mode, as AristotleTheme does (D8); the
        // screens pad their content by the window insets. The launch theme paints the same colours before this.
        enableEdgeToEdge()
        val container = (application as AristotleApplication).container
        // The name the user gave the phone ("POCO X5 Pro 5G"), not the model code ("22101320G").
        val deviceName = DeviceName.of(
            Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME),
            Build.MANUFACTURER,
            Build.MODEL,
        )
        setContent {
            AristotleTheme {
                AristotleApp(container = container, defaultDeviceName = deviceName)
            }
        }
    }
}
