package com.mantis.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController

@Composable
fun OnboardingFlow(navController: NavController) {
    val vm: OnboardingViewModel = viewModel()
    val state by vm.state.collectAsState()

    LaunchedEffect(state.step) {
        if (state.step == OnboardingStep.DONE) {
            navController.navigate("home") {
                popUpTo("onboarding") { inclusive = true }
            }
        }
    }

    when (state.step) {
        OnboardingStep.NAME -> NameScreen(state = state, vm = vm)
        OnboardingStep.LLM_SELECT -> LLMSelectScreen(state = state, vm = vm)
        OnboardingStep.CUSTOM_API_SETTINGS -> CustomAPISettingsScreen(
            state = state,
            vm = vm,
            onSave = { /* continuar después de guardar */ },
        )
        OnboardingStep.API_KEY -> APIKeyScreen(state = state, vm = vm)
        OnboardingStep.ACCESSIBILITY -> AccessibilityPermissionScreen(state = state, vm = vm)
        OnboardingStep.SCREEN_CAPTURE -> ScreenCapturePermissionScreen(state = state, vm = vm)
        OnboardingStep.BATTERY_OVERLAY -> BatteryOverlayPermissionScreen(state = state, vm = vm)
        OnboardingStep.SET_LOCK -> SetLockScreen(state = state, vm = vm)
        OnboardingStep.DONE -> Box(Modifier.fillMaxSize()) // handled in LaunchedEffect above
    }
}

// Shared scaffold used by every onboarding screen
@Composable
internal fun OnboardingScaffold(
    step: OnboardingStep,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
            .padding(top = 56.dp, bottom = 32.dp),
    ) {
        StepDots(current = step.ordinal.coerceAtMost(6))
        Spacer(Modifier.height(36.dp))
        content()
    }
}

@Composable
private fun StepDots(current: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(7) { i ->
            Box(
                modifier = Modifier
                    .size(if (i == current) 10.dp else 6.dp)
                    .clip(CircleShape)
                    .background(
                        if (i <= current) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                    )
            )
        }
    }
}
