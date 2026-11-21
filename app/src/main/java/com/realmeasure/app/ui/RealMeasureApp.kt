package com.realmeasure.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
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
import com.realmeasure.app.config.WorkshopPalette
import com.realmeasure.app.data.AppPreferences
import com.realmeasure.app.ui.components.OnboardingModal
import com.realmeasure.app.ui.screens.HistoryScreen
import com.realmeasure.app.ui.screens.ScannerScreen
import com.realmeasure.app.ui.theme.Mono

/**
 * App shell: bottom navigation between the hands-free scanner and the saved
 * hardware history, plus the first-run onboarding modal.
 */
@Composable
fun RealMeasureApp(
    scannerViewModel: ScannerViewModel,
    historyViewModel: HistoryViewModel,
) {
    val context = LocalContext.current
    val preferences = remember { AppPreferences(context) }

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var showOnboarding by rememberSaveable {
        mutableStateOf(!preferences.onboardingDismissed)
    }

    // The tutorial covers the viewfinder, so stop consuming frames while it is
    // open; dismissing it resumes live scanning immediately.
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
                        historyViewModel.refresh()
                    },
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.Build,
                            contentDescription = "My Hardware",
                        )
                    },
                    label = {
                        Text(
                            text = "MY HARDWARE",
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
                    historyViewModel = historyViewModel,
                    onShowOnboarding = { showOnboarding = true },
                )
                else -> HistoryScreen(
                    viewModel = historyViewModel,
                    onShowOnboarding = { showOnboarding = true },
                )
            }
        }
    }

    if (showOnboarding) {
        OnboardingModal(
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
