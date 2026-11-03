package io.github.justcallmesu.aristotle

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.justcallmesu.aristotle.data.pairingStateOf
import io.github.justcallmesu.aristotle.ui.home.HomeScreen
import io.github.justcallmesu.aristotle.ui.theme.AristotleTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AristotleTheme {
                // No credential storage until pairing exists (later ticket).
                HomeScreen(pairingState = pairingStateOf(credential = null))
            }
        }
    }
}
