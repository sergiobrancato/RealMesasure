package com.realmeasure.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import com.realmeasure.app.ui.HistoryViewModel
import com.realmeasure.app.ui.RealMeasureApp
import com.realmeasure.app.ui.ScannerViewModel
import com.realmeasure.app.ui.theme.RealMeasureTheme

/**
 * Single-activity host.
 *
 * Navigation between the hands-free scanner and the saved "My Hardware" list
 * is a bottom bar driven by simple Compose state - no navigation library, no
 * fragments, one window. Camera analysis is paused whenever the result, error
 * or onboarding modal is open.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RealMeasureTheme {
                val scannerViewModel: ScannerViewModel = viewModel()
                val historyViewModel: HistoryViewModel = viewModel()
                RealMeasureApp(
                    scannerViewModel = scannerViewModel,
                    historyViewModel = historyViewModel,
                )
            }
        }
    }
}
