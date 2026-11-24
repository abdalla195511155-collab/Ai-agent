package com.mantis.onboarding

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardCapitalization
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/**
 * شاشة إعدادات API مخصص (Custom API).
 *
 * تسمح للمستخدم بإدخال بيانات مزود AI متوافق مع OpenAI Chat Completions API:
 * - API Key
 * - Model ID
 * - Base URL (القيمة الافتراضية: NVIDIA NIM)
 *
 * عند الضغط على "Save Key & Continue" يتم حفظ الإعدادات والمتابعة في عملية التسجيل (onboarding).
 */
@Composable
fun CustomAPISettingsScreen(
    state: OnboardingState,
    vm: OnboardingViewModel,
    onSave: () -> Unit,
) {
    // حالة محلية لحقول النموذج (تُمرر من ViewModel في النسخة النهائية، هنا تُدار محلياً للبساطة)
    val apiKey by remember { mutableStateOf(state.customApiKey) }
    val modelId by remember { mutableStateOf(state.customModelId) }
    val baseUrl by remember { mutableStateOf(state.customBaseUrl) }

    // تحديث قيم ViewModel عند التغيير
    if (apiKey != state.customApiKey) vm.onCustomApiKeyChange(apiKey)
    if (modelId != state.customModelId) vm.onCustomModelIdChange(modelId)
    if (baseUrl != state.customBaseUrl) vm.onCustomBaseUrlChange(baseUrl)

    val isFormValid = apiKey.isNotBlank() && modelId.isNotBlank() && baseUrl.isNotBlank()
    val connectionStatus = state.customConnectionStatus

    OnboardingScaffold(step = OnboardingStep.CUSTOM_API_SETTINGS) {
        // رأس الشاشة مع زر رجوع
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            TextButton(onClick = { /*arája→ الشاشة السابقة */ }) {
                Text(
                    text = "< Custom API",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        Text(
            text = "Custom API",
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Configure your OpenAI-compatible AI provider. API key is stored only on this device.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(32.dp))

        // —— API Key ——
        Text(
            text = "API Key",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = apiKey,
            onValueChange = vm::onCustomApiKeyChange,
            label = { Text("Your API key...") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Next,
            ),
            keyboardActions = KeyboardActions(onNext = { /* الانتقال للحقل التالي */ }),
        )
        Spacer(Modifier.height(20.dp))

        // —— Model ID ——
        Text(
            text = "Model ID",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = modelId,
            onValueChange = vm::onCustomModelIdChange,
            label = { Text("e.g., meta/llama-3-70b-instruct") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                capitalization = KeyboardCapitalization.None,
                imeAction = ImeAction.Next,
            ),
            keyboardActions = KeyboardActions(onNext = { /* الانتقال للحقل التالي */ }),
        )
        Spacer(Modifier.height(20.dp))

        // —— Base URL ——
        Text(
            text = "Base URL",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = baseUrl,
            onValueChange = vm::onCustomBaseUrlChange,
            label = { Text("https://integrate.api.nvidia.com/v1") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                capitalization = KeyboardCapitalization.None,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = {
                if (isFormValid) vm.saveCustomApiSettings(onSave)
            }),
        )
        Spacer(Modifier.height(24.dp))

        // —— زر اختبار الاتصال ——
        Button(
            onClick = { vm.testCustomConnection() },
            enabled = isFormValid,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            Text(
                text = if (connectionStatus == "testing") "Testing..." else "Test Connection",
                style = MaterialTheme.typography.labelLarge,
            )
        }

        // عرض نتيجة الاختبار إذا وجدت
        state.customConnectionResult?.let { result ->
            Spacer(Modifier.height(12.dp))
            Text(
                text = result.message,
                style = MaterialTheme.typography.bodySmall,
                color = if (result.success) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.weight(1f))

        // —— زر الحفظ والمتابعة ——
        Button(
            onClick = { vm.saveCustomApiSettings(onSave) },
            enabled = isFormValid && connectionStatus != "testing",
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            Text("Save Key & Continue")
        }
    }
}

/**
 * نتيجة اختبار الاتصال
 */
data class ConnectionTestResult(
    val success: Boolean,
    val message: String,
)
