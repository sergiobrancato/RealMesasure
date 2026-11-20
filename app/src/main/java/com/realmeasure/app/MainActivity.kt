package com.realmeasure.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.realmeasure.app.ui.ScannerViewModel
import com.realmeasure.app.ui.Screen
import com.realmeasure.app.ui.screens.ResultScreen
import com.realmeasure.app.ui.screens.ScannerScreen
import com.realmeasure.app.ui.theme.RealMeasureTheme

/**
 * Single-activity host. Navigation between the scanner and the result screen
 * is a pure state transition driven by [ScannerViewModel] - no navigation
 * library, no fragments, one window.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RealMeasureTheme {
                val viewModel: ScannerViewModel = viewModel()
                val ui by viewModel.ui.collectAsStateWithLifecycle()
                when (ui.screen) {
                    Screen.SCANNER -> ScannerScreen(viewModel)
                    Screen.RESULT -> ResultScreen(viewModel)
                }
            }
        }
    }
}
