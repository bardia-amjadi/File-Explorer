package com.bardia.fileexplorer.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.bardia.fileexplorer.design.FileExplorerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge-to-edge on Android 12+: system bars are transparent and Scaffold/insets handle padding.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as FileExplorerApp).container
        setContent {
            val settings by container.settings.collectAsState()
            CompositionLocalProvider(LocalContainer provides container) {
                FileExplorerTheme(settings.theme) {
                    AppRoot()
                }
            }
        }
    }
}
