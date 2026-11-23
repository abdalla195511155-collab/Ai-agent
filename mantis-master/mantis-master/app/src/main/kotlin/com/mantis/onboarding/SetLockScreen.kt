package com.mantis.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun SetLockScreen(state: OnboardingState, vm: OnboardingViewModel) {
    OnboardingScaffold(step = OnboardingStep.SET_LOCK) {
        Text(
            text = "Set a lock",
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Protect MANTIS with a 6-digit PIN and biometric unlock.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(28.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Enable lock screen", style = MaterialTheme.typography.titleSmall)
            Switch(
                checked = state.lockEnabled,
                onCheckedChange = vm::onLockEnabledChange,
            )
        }

        if (state.lockEnabled) {
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(
                value = state.pin,
                onValueChange = vm::onPinChange,
                label = { Text("6-digit PIN") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = state.pinConfirm,
                onValueChange = vm::onPinConfirmChange,
                label = { Text("Confirm PIN") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                isError = state.pinError.isNotEmpty(),
                supportingText = if (state.pinError.isNotEmpty()) {
                    { Text(state.pinError) }
                } else null,
            )
        }

        Spacer(Modifier.weight(1f))
        Button(
            onClick = vm::submitPin,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            Text("Finish Setup")
        }
    }
}
