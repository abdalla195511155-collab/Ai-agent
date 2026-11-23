package com.mantis.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.mantis.R
import com.mantis.agent.AgentState
import com.mantis.permissions.HealthStatus
import com.mantis.ui.MantisTopBar

private val BannerBrokenBg     = Color(0xFFFFF0F0)
private val BannerBrokenBorder = Color(0xFFFF6B6B)
private val BannerBrokenText   = Color(0xFFD32F2F)
private val BannerAmberBg      = Color(0xFFFFF8E1)
private val BannerAmberBorder  = Color(0xFFFF9800)
private val BannerAmberText    = Color(0xFFE65100)
private val ChipBackground     = Color(0xFFFFFFFF)
private val ChipBorder         = Color(0xFFF3F3F3)
private val ChipText           = Color(0xFF666666)
private val InputBorderIdle    = Color(0xFFF3F3F3)
private val InputBorderFocused = Color(0xFF31AA50)
private val InputPlaceholder   = Color(0xFFAAAAAA)
private val TextDark           = Color(0xFF1A1A1A)

private val suggestions = listOf(
    "Set an alarm",
    "Turn off the dark mode",
    "Summarize my work emails",
)

@Composable
fun HomeScreen(
    navController: NavController,
    onMenuClick: () -> Unit = {},
    onSubmitTask: (String) -> Unit = {},
    vm: HomeViewModel = viewModel(),
) {
    val agentState by vm.agentState.collectAsStateWithLifecycle()
    val health by vm.permissionHealth.collectAsStateWithLifecycle()
    var taskInput by remember { mutableStateOf("") }
    var inputFocused by remember { mutableStateOf(false) }

    val inputEnabled = agentState is AgentState.Idle ||
            agentState is AgentState.Complete ||
            agentState is AgentState.Error ||
            agentState is AgentState.Stuck

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
    ) {

        // ── Top bar ───────────────────────────────────────────────────────────

        MantisTopBar(title = "Mantis", onMenuClick = onMenuClick)

        // ── Permission health banner ──────────────────────────────────────────

        HealthBanner(
            health = health,
            onTap = { navController.navigate("permission_repair") },
        )

        // ── Center — greeting or agent status ─────────────────────────────────

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.TopStart,
        ) {
            when (val s = agentState) {
                is AgentState.Idle -> GreetingContent(
                    name = vm.userName,
                    onSuggestionClick = { taskInput = it },
                )

                is AgentState.Running -> RunningContent(
                    task = s.task,
                    step = s.step,
                    maxSteps = s.maxSteps,
                    onTakeOver = { vm.takeOver() },
                )

                is AgentState.Paused -> PausedContent(task = s.task, step = s.step)

                is AgentState.Complete -> DoneContent(
                    summary = s.summary,
                    steps = s.steps,
                    pillColor = Color(0xFF4CAF50),
                )

                is AgentState.Stuck -> DoneContent(
                    summary = s.reason,
                    steps = s.step,
                    pillColor = Color(0xFFFF5722),
                    label = "Stuck",
                )

                is AgentState.Error -> DoneContent(
                    summary = s.message,
                    steps = null,
                    pillColor = MaterialTheme.colorScheme.error,
                    label = "Error",
                )
            }
        }

        // ── Input card ────────────────────────────────────────────────────────

        val borderColor by animateColorAsState(
            targetValue = if (inputFocused) InputBorderFocused else InputBorderIdle,
            animationSpec = tween(durationMillis = 200),
            label = "inputBorder",
        )

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            shape = RoundedCornerShape(16.dp),
            color = Color.White,
            border = BorderStroke(2.dp, borderColor),
        ) {
            Column(
                modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 8.dp),
            ) {
                BasicTextField(
                    value = taskInput,
                    onValueChange = { taskInput = it },
                    enabled = inputEnabled,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = TextDark),
                    cursorBrush = SolidColor(InputBorderFocused),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 44.dp)
                        .onFocusChanged { inputFocused = it.isFocused },
                    decorationBox = { innerTextField ->
                        if (taskInput.isEmpty()) {
                            Text(
                                text = "What Can I help you With?",
                                style = MaterialTheme.typography.bodyLarge,
                                color = InputPlaceholder,
                            )
                        }
                        innerTextField()
                    },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    IconButton(
                        onClick = {
                            val t = taskInput.trim()
                            if (t.isNotEmpty()) {
                                onSubmitTask(t)
                                taskInput = ""
                            }
                        },
                        enabled = inputEnabled && taskInput.isNotBlank(),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_send),
                            contentDescription = "Send",
                            tint = if (inputEnabled && taskInput.isNotBlank()) TextDark
                            else Color(0xFFCCCCCC),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

// ── Idle greeting ─────────────────────────────────────────────────────────────

@Composable
private fun GreetingContent(name: String, onSuggestionClick: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = "Hey $name",
            style = MaterialTheme.typography.headlineLarge,
            color = TextDark,
        )
        Text(
            text = "How can I help you?",
            style = MaterialTheme.typography.headlineLarge,
            color = TextDark,
        )
        Spacer(modifier = Modifier.height(32.dp))
        suggestions.forEach { suggestion ->
            SuggestionChip(
                text = suggestion,
                onClick = { onSuggestionClick(suggestion) },
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SuggestionChip(text: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(50.dp),
        color = ChipBackground,
        border = BorderStroke(2.dp, ChipBorder),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = ChipText,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        )
    }
}

// ── Running ───────────────────────────────────────────────────────────────────

@Composable
private fun RunningContent(task: String, step: Int, maxSteps: Int, onTakeOver: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 40.dp),
    ) {
        Surface(
            color = Color(0xFF2196F3),
            shape = RoundedCornerShape(50.dp),
        ) {
            Text(
                text = "Step $step / $maxSteps",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        Text(
            text = task,
            style = MaterialTheme.typography.bodyLarge,
            color = TextDark,
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.Medium,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = onTakeOver,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFFF5722),
                contentColor = Color.White,
            ),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text("TAKE OVER", fontWeight = FontWeight.Bold)
        }
    }
}

// ── Paused ────────────────────────────────────────────────────────────────────

@Composable
private fun PausedContent(task: String, step: Int) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 40.dp),
    ) {
        Surface(
            color = Color(0xFFFF9800),
            shape = RoundedCornerShape(50.dp),
        ) {
            Text(
                text = "PAUSED  ·  Step $step",
                color = Color.Black,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        Text(
            text = task,
            style = MaterialTheme.typography.bodyLarge,
            color = TextDark,
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = "You're in control — tap the MANTIS notification to resume",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

// ── Complete / Stuck / Error ──────────────────────────────────────────────────

@Composable
private fun DoneContent(
    summary: String,
    steps: Int?,
    pillColor: Color,
    label: String = "Done",
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 40.dp),
    ) {
        Surface(
            color = pillColor,
            shape = RoundedCornerShape(50.dp),
        ) {
            Text(
                text = if (steps != null) "$label  ·  $steps steps" else label,
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        Text(
            text = summary,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

// ── Health banner ─────────────────────────────────────────────────────────────

@Composable
private fun HealthBanner(health: HealthStatus, onTap: () -> Unit) {
    val (bg, border, textColor, message) = when (health) {
        is HealthStatus.Healthy  -> return
        is HealthStatus.Degraded -> BannerStyle(
            bg = BannerAmberBg,
            border = BannerAmberBorder,
            text = BannerAmberText,
            message = "Some permissions are missing — tap to fix.",
        )
        is HealthStatus.Broken   -> BannerStyle(
            bg = BannerBrokenBg,
            border = BannerBrokenBorder,
            text = BannerBrokenText,
            message = "Critical permission missing — Mantis cannot run. Tap to fix.",
        )
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable(onClick = onTap),
        shape = RoundedCornerShape(50.dp),
        color = bg,
        border = BorderStroke(1.dp, border),
    ) {
        Text(
            text = message,
            color = textColor,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

private data class BannerStyle(
    val bg: Color,
    val border: Color,
    val text: Color,
    val message: String,
)
