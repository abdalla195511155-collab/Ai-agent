package com.mantis.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.mantis.R

private data class LlmOption(
    val id: String,
    val name: String,
    val subtitle: String,
    val available: Boolean,
    val iconRes: Int?,
)

private val LLM_OPTIONS = listOf(
    LlmOption("claude", "Claude",      "by Anthropic", true,  R.drawable.ic_claude),
    LlmOption("openai", "OpenAI",      "GPT-4o",       true,  R.drawable.ic_openai),
    LlmOption("gemini", "Gemini",      "by Google",    true,  R.drawable.ic_gemini),
    LlmOption("custom_api", "Custom API", "Generic or Self-hosted\nIntegrate any compatible model", true, R.drawable.ic_settings),
)

@Composable
fun LLMSelectScreen(state: OnboardingState, vm: OnboardingViewModel) {
    OnboardingScaffold(step = OnboardingStep.LLM_SELECT) {
        Text(
            text = "Choose your AI",
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Your API key is stored only on this device.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(24.dp))
        LLM_OPTIONS.forEach { option ->
            LlmOptionCard(
                option = option,
                selected = state.llmChoice == option.id,
                onSelect = { if (option.available) vm.onLlmChoiceChange(option.id) },
            )
            Spacer(Modifier.height(12.dp))
        }
        Spacer(Modifier.weight(1f))
        Button(
            onClick = vm::submitLlmChoice,
            enabled = state.llmChoice.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            Text("Continue")
        }
    }
}

@Composable
private fun LlmOptionCard(
    option: LlmOption,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                shape = MaterialTheme.shapes.medium,
            )
            .clickable(enabled = option.available, onClick = onSelect),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (option.iconRes != null) {
                    Image(
                        painter = painterResource(option.iconRes),
                        contentDescription = "${option.name} logo",
                        modifier = Modifier.size(32.dp),
                        alpha = if (option.available) 1f else 0.4f,
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Column {
                    Text(
                        text = option.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (option.available) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    )
                    Text(
                        text = option.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                }
            }
            if (!option.available) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
                ) {
                    Text(
                        text = "Coming Soon",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    )
                }
            }
        }
    }
}
