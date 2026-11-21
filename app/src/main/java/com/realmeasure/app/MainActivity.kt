package com.realmeasure.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import com.realmeasure.app.ui.RealMeasureApp
import com.realmeasure.app.ui.ScannerViewModel
import com.realmeasure.app.ui.ScansViewModel
import com.realmeasure.app.ui.theme.RealMeasureTheme

/**
 * Single-activity host for the planar irregular-shape scanner.
 *
 * Navigation between the scanner and the saved "My Scans" library is a bottom
 * bar driven by simple Compose state - no navigation library, no fragments,
 * one window.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RealMeasureTheme {
                val scannerViewModel: ScannerViewModel = viewModel()
                val scansViewModel: ScansViewModel = viewModel()
                RealMeasureApp(
                    scannerViewModel = scannerViewModel,
                    scansViewModel = scansViewModel,
                )
            }
        }
    }
}
