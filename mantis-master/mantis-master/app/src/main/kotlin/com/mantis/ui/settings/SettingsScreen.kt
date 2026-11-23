package com.mantis.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Chip
import androidx.compose.material3.FilledButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TonalIcon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mantis.model.ProviderConfig
import com.mantis.storage.SecureConfig
import com.mantis.ui.settings.SettingsViewModel
import kotlin.text.isNotBlank
import kotlin.text.isEmpty
import com.mantis.ui.MantisApp

@Composable
fun SettingsScreen(onMenuClick: () -> Unit = {}, vm: SettingsViewModel = viewModel()) {
    val name by vm.name.collectAsStateWithLifecycle()
    val maxSteps by vm.maxSteps.collectAsStateWithLifecycle()
    val isLockEnabled by vm.isLockEnabled.collectAsStateWithLifecycle()
    val isBiometricEnabled by vm.isBiometricEnabled.collectAsStateWithLifecycle()
    val memoryContent by vm.memoryContent.collectAsStateWithLifecycle()
    val memoryWordCount by vm.memoryWordCount.collectAsStateWithLifecycle()

    // Dialog visibility states.
    var showApiKeyDialog by rememberSaveable { mutableStateOf(false) }
    var showChangePinDialog by rememberSaveable { mutableStateOf(false) }
    var showClearMemoryDialog by rememberSaveable { mutableStateOf(false) }
    var showAddProviderDialog by rememberSaveable { mutableStateOf(false) }
    var showEditProviderDialog by rememberSaveable { mutableStateOf(false) }

    // Access SecureConfig through the ViewModel
    val secureConfig = vm.secureConfig
    val providerIds = secureConfig.getProviderIds()
    val activeProviderId = secureConfig.llmChoice

    Column(
        modifier = Modifier.fillMaxSize(),
    ) {
        // Simplified top bar
        androidx.compose.material3.Surface(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            elevation = androidx.compose.ui.elevation.DefaultElevation.surface
        ) {
            androidx.compose.material3.Text(
                text = "Settings",
                style = androidx.compose.ui.platform.LocalContext.current materialTheme typography headlineSmall
            )
        }
        androidx.compose.foundation.Separator(modifier = Modifier.height(1.dp).padding(16.dp))

        LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {

            // ── Profile ───────────────────────────────────────────────────────

            androidx.compose.foundation.lazy.LazyItem {
                SectionHeader("Profile")
            }
            androidx.compose.foundation.lazy.LazyItem {
                var nameField by rememberSaveable(name) { mutableStateOf(name) }
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    OutlinedTextField(
                        value = nameField,
                        onValueChange = { nameField = it },
                        label = { Text("Your name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    androidx.compose.material3.TextButton(
                        onClick = { vm.saveName(nameField.trim()) },
                        modifier = androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.End),
                    ) { Text("Save") }
                }
            }

            // ── AI Provider ───────────────────────────────────────────────────

            androidx.compose.foundation.lazy.LazyItem {
                SectionHeader("AI Provider")
            }
            androidx.compose.foundation.lazy.LazyItem {
                SettingsRow(
                    title = "Current Provider",
                    subtitle = when {
                        vm.llmChoice.isNotBlank() -> vm.llmChoice.replaceFirstChar { it.uppercase() }
                        else -> "Not set"
                    },
                )
            }
            androidx.compose.foundation.lazy.LazyItem {
                SettingsRow(
                    title = "API Key",
                    subtitle = if (vm.hasApiKey) "Configured  ✓" else "Not set",
                    action = {
                        androidx.compose.material3.TextButton(onClick = { showApiKeyDialog = true }) {
                            Text("Change")
                        }
                    },
                )
            }

            // ── Custom AI Providers ────────────────────────────────────────────

            androidx.compose.foundation.lazy.LazyItem {
                SectionHeader("Custom AI Providers")
            }
            if (providerIds.isEmpty()) {
                androidx.compose.foundation.lazy.LazyItem {
                    androidx.compose.material3.OutlinedTextField(
                        value = "",
                        label = { Text("No custom providers configured") },
                        modifier = Modifier.padding(16.dp),
                        singleLine = true,
                        enabled = false,
                    )
                }
                androidx.compose.foundation.lazy.LazyItem {
                    FilledButton(
                        onClick = { showAddProviderDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("+ Add Custom Provider")
                    }
                }
            } else {
                // Display existing custom providers as chips
                androidx.compose.foundation.lazy.LazyItem {
                    ChipsRow(
                        secureConfig = secureConfig,
                        providerIds = providerIds,
                        onDelete = { providerId ->
                            androidx.compose.material3.AlertDialog(
                                onDismissRequest = { },
                                title = { Text("Delete provider") },
                                text = { Text("Are you sure you want to delete provider '$providerId'?") },
                                confirmButton = {
                                    androidx.compose.material3.TextButton(
                                        onClick = {
                                            secureConfig.setProviderConfig(providerId, ProviderConfig(
                                                id = providerId,
                                                name = providerId,
                                                baseUrl = "",
                                                modelId = "",
                                                protocol = com.mantis.storage.ProviderConfig.ProviderProtocol.OPENAI_COMPATIBLE,
                                                authType = com.mantis.storage.ProviderConfig.ProviderAuthType.BEARER_TOKEN
                                            ))
                                        },
                                        Text("Delete")
                                    )
                                },
                                dismissButton = {
                                    androidx.compose.material3.TextButton(onClick = { }) { Text("Cancel") }
                                }
                            )
                        },
                        onSelect = { providerId ->
                            secureConfig.setActiveProvider(providerId)
                            vm.llmChoice = providerId
                        }
                    )
                }
            }

            // ── Agent ─────────────────────────────────────────────────────────

            androidx.compose.foundation.lazy.LazyItem {
                SectionHeader("Agent")
            }
            androidx.compose.foundation.lazy.LazyItem {
                var sliderValue by remember(maxSteps.toFloat()) { mutableFloatStateOf(maxSteps.toFloat()) }
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "Max steps per task",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = sliderValue.roundToInt().toString(),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    androidx.compose.material3.Slider(
                        value = sliderValue,
                        onValueChange = { sliderValue = it },
                        onValueChangeFinished = { vm.saveMaxSteps(sliderValue.roundToInt()) },
                        valueRange = 10f..100f,
                        steps = 17,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("10", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("100", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // ── Security ────────────────────────────────────────────────────────

            androidx.compose.foundation.lazy.LazyItem {
                SectionHeader("Security")
            }
            androidx.compose.foundation.lazy.LazyItem {
                SettingsRow(
                    title = "App lock",
                    subtitle = if (isLockEnabled) "PIN required on open" else "Disabled",
                    action = {
                        androidx.compose.material3.Switch(
                            checked = isLockEnabled,
                            onCheckedChange = { vm.setLockEnabled(it) },
                        )
                    },
                )
            }
            if (isLockEnabled) {
                androidx.compose.foundation.lazy.LazyItem {
                    SettingsRow(
                        title = "Change PIN",
                        subtitle = "Update your unlock PIN",
                        action = {
                            androidx.compose.material3.TextButton(onClick = { showChangePinDialog = true }) {
                                Text("Change")
                            }
                        },
                    )
                }
                androidx.compose.foundation.lazy.LazyItem {
                    SettingsRow(
                        title = "Biometric unlock",
                        subtitle = if (isBiometricEnabled) "Fingerprint / face enabled" else "Disabled",
                        action = {
                            androidx.compose.material3.Switch(
                                checked = isBiometricEnabled,
                                onCheckedChange = { vm.setBiometricEnabled(it) },
                            )
                        },
                    )
                }
            }

            // ── Memory ──────────────────────────────────────────────────────────

            androidx.compose.foundation.lazy.LazyItem {
                SectionHeader("Memory")
            }
            androidx.compose.foundation.lazy.LazyItem {
                var memoryField by rememberSaveable(memoryContent) { mutableStateOf(memoryContent) }
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "$memoryWordCount / ${com.mantis.memory.MemoryManager.MAX_WORDS} words",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        androidx.compose.material3.TextButton(onClick = { showClearMemoryDialog = true }) {
                            Text("Clear", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    OutlinedTextField(
                        value = memoryField,
                        onValueChange = { memoryField = it },
                        label = { Text("User memory") },
                        minLines = 5,
                        maxLines = 12,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (memoryField != memoryContent) {
                        androidx.compose.material3.TextButton(
                            onClick = { vm.saveMemory(memoryField) },
                            modifier = androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.End),
                        ) { Text("Save") }
                    }
                }
            }

            // ── About ─────────────────────────────────────────────────────────

            androidx.compose.foundation.lazy.LazyItem {
                SectionHeader("About")
            }
            androidx.compose.foundation.lazy.LazyItem {
                androidx.compose.material3.SettingsRow(title = "Version", subtitle = "0.1.0")
            }
            androidx.compose.foundation.lazy.LazyItem {
                androidx.compose.material3.SettingsRow(
                    title = "Accessibility layer",
                    subtitle = "Adapted from DroidRun (MIT)\ngithub.com/droidrun/droidrun-portal",
                )
            }
            androidx.compose.foundation.lazy.LazyItem {
                androidx.compose.material3.SettingsRow(
                    title = "License",
                    subtitle = "MANTIS is open-source — MIT License",
                )
            }
            androidx.compose.foundation.lazy.LazyItem {
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    // ── Dialogs ───────────────────────────────────────────────────────────────

    if (showApiKeyDialog) {
        ApiKeyDialog(
            onDismiss = { showApiKeyDialog = false },
            onSave = { key ->
                vm.saveApiKey(key)
                showApiKeyDialog = false
            },
        )
    }

    if (showChangePinDialog) {
        ChangePinDialog(
            requireOldPin = vm.hasPinSet,
            onDismiss = { showChangePinDialog = false },
            onVerifyOldPin = { vm.verifyPin(it) },
            onSave = { newPin ->
                vm.changePin(newPin)
                showChangePinDialog = false
            },
        )
    }

    // ── Add Custom Provider Dialog ──────────────────────────────────────────

    if (showAddProviderDialog) {
        AddProviderDialog(
            onDismiss = { showAddProviderDialog = false },
            onSave = { providerConfig ->
                val providerId = providerConfig.id
                secureConfig.setProviderConfig(providerId, providerConfig)
                secureConfig.setApiKey(providerId, providerConfig.customHeaderValue ?: providerConfig.name)
                secureConfig.setActiveProvider(providerId)
                vm.llmChoice = providerId
                showAddProviderDialog = false
            },
        )
    }

    // ── Edit Custom Provider Dialog ─────────────────────────────────────────

    if (showEditProviderDialog) {
        EditProviderDialog(
            onDismiss = { showEditProviderDialog = false },
            onSave = { providerConfig ->
                val providerId = providerConfig.id
                secureConfig.setProviderConfig(providerId, providerConfig)
                showEditProviderDialog = false
            },
        )
    }
}

// ── Section header ────────────────────────────────────────────────────────────

@Composable
private fun SectionHeader(title: String) {
    androidx.compose.material3.Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 2.dp),
    )
}

// ── Generic settings row ──────────────────────────────────────────────────────

@Composable
private fun SettingsRow(
    title: String,
    subtitle: String,
    action: @Composable (() -> Unit)? = null,
) {
    androidx.compose.material3.Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        elevation = androidx.compose.ui.elevation.ElevationLevel.Minimum,
        onClick = action?.invoke(),
    ) {
        androidx.compose.material3.Row(
            modifier = androidx.compose.ui.Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            androidx.compose.material3.Column(modifier = androidx.compose.ui.Modifier.weight(1f).padding(end = 12.dp)) {
                androidx.compose.material3.Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                androidx.compose.material3.Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            androidx.compose.material3.Icon(
                imageVector = androidx.compose.material3.Icons.Filled.MoreVert,
                tint = androidx.compose.ui.Color.Unspecified,
                modifier = androidx.compose.ui.Modifier.size(24.dp)
            )
        }
    }
}

// ── Chips row for displaying providers ───────────────────────────────────────

@Composable
private fun ChipsRow(
    secureConfig: SecureConfig,
    providerIds: List<String>,
    onDelete: (String) -> Unit,
    onSelect: (String) -> Unit,
) {
    androidx.compose.material3.Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp),  // Fixed height for chip row
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        providerIds.mapIndexed { index, providerId ->
            val isActive = providerId == secureConfig.llmChoice
            androidx.compose.material3.Chip(
                text = androidx.compose.material3.Text(providerId),
                selected = isActive,
                onClick = { onSelect(providerId) },
                removable = true,
                onRemove = { onDelete(providerId) },
                modifier = androidx.compose.ui.Modifier.padding(4.dp),
            )
        }
    }
}

// ── Add Custom Provider Dialog ──────────────────────────────────────────────

@Composable
private fun AddProviderDialog(
    onDismiss: () -> Unit,
    onSave: (ProviderConfig) -> Unit,
) {
    var name by rememberSaveable { "" }
    var baseUrl by rememberSaveable { "https://api." }
    var modelId by rememberSaveable { "gpt-4o" }
    var authType by rememberSaveable { com.mantis.storage.ProviderConfig.ProviderAuthType.BEARER_TOKEN }
    var customHeaderName by rememberSaveable { "" }
    var customHeaderValue by rememberSaveable { "" }
    var timeoutMs by rememberSaveable { 30000 }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Custom AI Provider") },
        text = {
            androidx.compose.material3.Column(verticalArrangement = androidx.compose.material3.Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Provider Name") },
                    modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                )
                androidx.compose.material3.OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL") },
                    modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.WebDomainName),
                )
                androidx.compose.material3.OutlinedTextField(
                    value = modelId,
                    onValueChange = { modelId = it },
                    label = { Text("Model ID") },
                    modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                )
                androidx.compose.material3.OutlinedTextField(
                    value = customHeaderName,
                    onValueChange = { customHeaderName = it },
                    label = { Text("Custom Header Name (optional)") },
                    modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                    isVisible = authType == com.mantis.storage.ProviderConfig.ProviderAuthType.CUSTOM_API_KEY_HEADER,
                )
                androidx.compose.material3.OutlinedTextField(
                    value = customHeaderValue,
                    onValueChange = { customHeaderValue = it },
                    label = { Text("Custom Header Value (optional)") },
                    modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                    isVisible = authType == com.mantis.storage.ProviderConfig.ProviderAuthType.CUSTOM_API_KEY_HEADER,
                )
                androidx.compose.material3.TextField(
                    value = timeoutMs.toString(),
                    onValueChange = { timeoutMs = it.toIntOrElse(30000) },
                    label = { Text("Timeout (ms)") },
                    modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                androidx.compose.material3.Text(
                    text = "Auth: ${authType}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                val providerConfig = ProviderConfig(
                    id = name.toLowerCase().replace(" ", "_"),
                    name = name,
                    baseUrl = baseUrl,
                    modelId = modelId,
                    protocol = com.mantis.storage.ProviderConfig.ProviderProtocol.OPENAI_COMPATIBLE,
                    authType = authType,
                    customHeaderName = if (customHeaderName.isNotBlank()) customHeaderName else null,
                    customHeaderValue = if (customHeaderValue.isNotBlank()) customHeaderValue else null,
                    timeoutMs = timeoutMs
                )
                onSave(providerConfig)
            }) { Text("Save") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

// ── Edit Custom Provider Dialog ─────────────────────────────────────────────

@Composable
private fun EditProviderDialog(
    onDismiss: () -> Unit,
    onSave: (ProviderConfig) -> Unit,
    initialId: String = "",
) {
    var name by rememberSaveable { if (initialId.isNotBlank()) initialId else "" }
    var baseUrl by rememberSaveable { "https://api." }
    var modelId by rememberSaveable { "gpt-4o" }
    var authType by rememberSaveable { com.mantis.storage.ProviderConfig.ProviderAuthType.BEARER_TOKEN }
    var customHeaderName by rememberSaveable { "" }
    var customHeaderValue by rememberSaveable { "" }
    var timeoutMs by rememberSaveable { 30000 }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Custom AI Provider") },
        text = {
            androidx.compose.material3.Column(verticalArrangement = androidx.compose.material3.Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Provider Name") },
                    modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                )
                androidx.compose.material3.OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL") },
                    modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.WebDomainName),
                )
                androidx.compose.material3.OutlinedTextField(
                    value = modelId,
                    onValueChange = { modelId = it },
                    label = { Text("Model ID") },
                    modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                )
                androidx.compose.material3.OutlinedTextField(
                    value = customHeaderName,
                    onValueChange = { customHeaderName = it },
                    label = { Text("Custom Header Name (optional)") },
                    modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                    isVisible = authType == com.mantis.storage.ProviderConfig.ProviderAuthType.CUSTOM_API_KEY_HEADER,
                )
                androidx.compose.material3.OutlinedTextField(
                    value = customHeaderValue,
                    onValueChange = { customHeaderValue = it },
                    label = { Text("Custom Header Value (optional)") },
                    modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                    isVisible = authType == com.mantis.storage.ProviderConfig.ProviderAuthType.CUSTOM_API_KEY_HEADER,
                )
                androidx.compose.material3.TextField(
                    value = timeoutMs.toString(),
                    onValueChange = { timeoutMs = it.toIntOrElse(30000) },
                    label = { Text("Timeout (ms)") },
                    modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                androidx.compose.material3.Text(
                    text = "Auth: ${authType}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                val providerConfig = ProviderConfig(
                    id = name.toLowerCase().replace(" ", "_"),
                    name = name,
                    baseUrl = baseUrl,
                    modelId = modelId,
                    protocol = com.mantis.storage.ProviderConfig.ProviderProtocol.OPENAI_COMPATIBLE,
                    authType = authType,
                    customHeaderName = if (customHeaderName.isNotBlank()) customHeaderName else null,
                    customHeaderValue = if (customHeaderValue.isNotBlank()) customHeaderValue else null,
                    timeoutMs = timeoutMs
                )
                onSave(providerConfig)
            }) { Text("Save") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
