package com.mantis.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.item
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    onMenuClick: () -> Unit = {},
    vm: SettingsViewModel = viewModel(),
) {
    val name by vm.name.collectAsStateWithLifecycle()
    val maxSteps by vm.maxSteps.collectAsStateWithLifecycle()
    val lockEnabled by vm.isLockEnabled.collectAsStateWithLifecycle()
    val biometricEnabled by vm.isBiometricEnabled.collectAsStateWithLifecycle()
    val memory by vm.memoryContent.collectAsStateWithLifecycle()
    val memoryWords by vm.memoryWordCount.collectAsStateWithLifecycle()

    var nameInput by rememberSaveable(name) { mutableStateOf(name) }
    var apiKey by rememberSaveable { mutableStateOf("") }
    var memoryInput by rememberSaveable(memory) { mutableStateOf(memory) }
    var sliderValue by remember(maxSteps) { mutableStateOf(maxSteps.toFloat()) }

    Column(modifier = Modifier.fillMaxSize()) {
        androidx.compose.material3.TopAppBar(
            title = { Text("Settings") },
            navigationIcon = {
                TextButton(onClick = onMenuClick) { Text("Menu") }
            }
        )
        HorizontalDivider()
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("Profile", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        label = { Text("Your name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { vm.saveName(nameInput.trim()) },
                        modifier = Modifier.align(Alignment.End),
                    ) { Text("Save name") }
                }
            }
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("AI provider", style = MaterialTheme.typography.titleMedium)
                    Text("Active provider: ${vm.llmChoice.ifBlank { "Not selected" }}")
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text("API key") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { vm.saveApiKey(apiKey); apiKey = "" },
                        enabled = apiKey.isNotBlank(),
                        modifier = Modifier.align(Alignment.End),
                    ) { Text("Save API key") }
                    Text(
                        if (vm.hasApiKey) "An API key is configured." else "No API key configured.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("Agent", style = MaterialTheme.typography.titleMedium)
                    Text("Maximum steps: ${sliderValue.roundToInt()}")
                    Slider(
                        value = sliderValue,
                        onValueChange = { sliderValue = it },
                        onValueChangeFinished = { vm.saveMaxSteps(sliderValue.roundToInt()) },
                        valueRange = 10f..100f,
                        steps = 17,
                    )
                }
            }
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("Security", style = MaterialTheme.typography.titleMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("App lock")
                        Switch(checked = lockEnabled, onCheckedChange = vm::setLockEnabled)
                    }
                    if (lockEnabled) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("Biometric unlock")
                            Switch(
                                checked = biometricEnabled,
                                onCheckedChange = vm::setBiometricEnabled,
                            )
                        }
                    }
                }
            }
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("Memory ($memoryWords words)", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = memoryInput,
                        onValueChange = { memoryInput = it },
                        label = { Text("User memory") },
                        minLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = { vm.clearMemory(); memoryInput = "" }) {
                            Text("Clear")
                        }
                        Button(
                            onClick = { vm.saveMemory(memoryInput) },
                            enabled = memoryInput != memory,
                        ) { Text("Save memory") }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
