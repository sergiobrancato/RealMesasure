package com.realmeasure.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.realmeasure.app.config.WorkshopPalette
import com.realmeasure.app.data.AppPreferences
import com.realmeasure.app.ui.components.OnboardingModal
import com.realmeasure.app.ui.screens.ScannerScreen
import com.realmeasure.app.ui.screens.ScansScreen
import com.realmeasure.app.ui.theme.Mono

/**
 * App shell: bottom navigation between the hands-free planar scanner and the
 * saved "My Scans" library, plus the first-run tutorial gate.
 */
@Composable
fun RealMeasureApp(
    scannerViewModel: ScannerViewModel,
    scansViewModel: ScansViewModel,
) {
    val context = LocalContext.current
    val preferences = remember { AppPreferences(context) }
    val scannerUi by scannerViewModel.ui.collectAsStateWithLifecycle()

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var showOnboarding by rememberSaveable {
        mutableStateOf(!preferences.onboardingDismissed)
    }

    LaunchedEffect(showOnboarding) {
        scannerViewModel.setPaused(showOnboarding)
    }

    Scaffold(
        containerColor = WorkshopPalette.Background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            NavigationBar(containerColor = WorkshopPalette.Surface, tonalElevation = 0.dp) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.PhotoCamera,
                            contentDescription = "Scanner",
                        )
                    },
                    label = {
                        Text(
                            text = "SCANNER",
                            fontFamily = Mono,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp,
                        )
                    },
                    colors = navigationColors(),
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = {
                        selectedTab = 1
                        scansViewModel.refresh()
                    },
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.GridView,
                            contentDescription = "My Scans",
                        )
                    },
                    label = {
                        Text(
                            text = "MY SCANS",
                            fontFamily = Mono,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp,
                        )
                    },
                    colors = navigationColors(),
                )
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (selectedTab) {
                0 -> ScannerScreen(
                    viewModel = scannerViewModel,
                    scansViewModel = scansViewModel,
                    onShowOnboarding = { showOnboarding = true },
                )
                else -> ScansScreen(
                    viewModel = scansViewModel,
                    onShowOnboarding = { showOnboarding = true },
                )
            }
        }
    }

    if (showOnboarding) {
        OnboardingModal(
            mode = scannerUi.mode,
            onDismiss = { dontShowAgain ->
                if (dontShowAgain) preferences.onboardingDismissed = true
                showOnboarding = false
            },
        )
    }
}

@Composable
private fun navigationColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = WorkshopPalette.Amber,
    selectedTextColor = WorkshopPalette.Amber,
    indicatorColor = WorkshopPalette.Amber.copy(alpha = 0.16f),
    unselectedIconColor = WorkshopPalette.TextFaint,
    unselectedTextColor = WorkshopPalette.TextFaint,
)
