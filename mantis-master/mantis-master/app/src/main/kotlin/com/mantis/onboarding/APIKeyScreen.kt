package com.mantis.onboarding

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun APIKeyScreen(state: OnboardingState, vm: OnboardingViewModel) {
    val providerName = when (state.llmChoice) {
        "claude" -> "Anthropic"
        "openai" -> "OpenAI"
        "gemini" -> "Google AI"
        else -> "your provider"
    }

    OnboardingScaffold(step = OnboardingStep.API_KEY) {
        Text(
            text = "Enter your API key",
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Get it from the $providerName developer console. It never leaves your device.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(32.dp))
        OutlinedTextField(
            value = state.apiKey,
            onValueChange = vm::onApiKeyChange,
            label = { Text("API key") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (state.apiKeyVisible) VisualTransformation.None
                                   else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { vm.submitApiKey() }),
            trailingIcon = {
                TextButton(onClick = vm::toggleApiKeyVisible) {
                    Text(
                        text = if (state.apiKeyVisible) "Hide" else "Show",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            },
        )
        Spacer(Modifier.weight(1f))
        Button(
            onClick = vm::submitApiKey,
            enabled = state.apiKey.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            Text("Continue")
        }
    }
}
