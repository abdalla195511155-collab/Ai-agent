package com.mantis.lock

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController

@Composable
fun LockScreen(navController: NavController) {
    val vm: LockViewModel = viewModel()
    val state by vm.state.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(state.isUnlocked) {
        if (state.isUnlocked) {
            navController.navigate("home") {
                popUpTo("lock") { inclusive = true }
            }
        }
    }

    when (state.mode) {
        LockMode.BIOMETRIC -> {
            LaunchedEffect(Unit) {
                val activity = context as? FragmentActivity ?: run {
                    vm.switchToPinMode(); return@LaunchedEffect
                }
                triggerBiometric(activity, vm)
            }
            BiometricWaitScreen(onUsePin = vm::switchToPinMode)
        }
        LockMode.PIN -> PinEntryScreen(state = state, vm = vm)
    }
}

@Composable
private fun BiometricWaitScreen(onUsePin: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "MANTIS",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Authenticating...",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(48.dp))
        TextButton(onClick = onUsePin) {
            Text("Use PIN instead")
        }
    }
}

@Composable
private fun PinEntryScreen(state: LockState, vm: LockViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "MANTIS",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Enter your PIN",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(32.dp))
        OutlinedTextField(
            value = state.pin,
            onValueChange = vm::onPinChange,
            label = { Text("6-digit PIN") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            isError = state.error.isNotEmpty(),
            supportingText = if (state.error.isNotEmpty()) {
                { Text(state.error) }
            } else null,
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = vm::submitPin,
            enabled = state.pin.length == 6,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            Text("Unlock")
        }
    }
}

private fun triggerBiometric(activity: FragmentActivity, vm: LockViewModel) {
    val canAuth = BiometricManager.from(activity)
        .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
    if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
        vm.switchToPinMode()
        return
    }

    val promptInfo = BiometricPrompt.PromptInfo.Builder()
        .setTitle("Unlock MANTIS")
        .setSubtitle("Use biometric to continue")
        .setNegativeButtonText("Use PIN")
        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        .build()

    BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                vm.onBiometricSuccess()
            }
            override fun onAuthenticationFailed() {
                vm.onBiometricFailed()
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                // Negative button ("Use PIN") or cancellation both drop to PIN mode
                vm.switchToPinMode()
            }
        }
    ).authenticate(promptInfo)
}
