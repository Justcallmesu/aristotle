package io.github.justcallmesu.aristotle

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.justcallmesu.aristotle.ui.AristotleApp
import io.github.justcallmesu.aristotle.ui.theme.AristotleTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as AristotleApplication).container
        setContent {
            AristotleTheme {
                AristotleApp(container = container, defaultDeviceName = Build.MODEL.orEmpty())
            }
        }
    }
}
