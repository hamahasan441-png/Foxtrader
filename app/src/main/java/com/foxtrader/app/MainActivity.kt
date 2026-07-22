package com.foxtrader.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.foxtrader.app.ui.navigation.FoxNavHost
import com.foxtrader.app.ui.theme.FoxTraderTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Single-activity host. All screens are Composables rendered inside
 * [FoxNavHost]. [AndroidEntryPoint] enables Hilt injection into this Activity
 * and its ViewModels.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            FoxTraderApp()
        }
    }
}

@Composable
private fun FoxTraderApp() {
    FoxTraderTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            FoxNavHost()
        }
    }
}
