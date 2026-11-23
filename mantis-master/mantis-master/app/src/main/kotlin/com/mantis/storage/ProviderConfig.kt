/**
 * Configuration for a single AI provider.
 *
 * Each provider has independent configuration that can be
 * added, edited, or deleted without affecting other providers.
 *
 * The API key is stored in the Android Keystore with a unique alias
 * per provider, never in plaintext on disk.
 *
 * Implements @Serializable for Kotlin serialization compatibility
 * with EncryptedSharedPreferences.
 */
@kotlinx.serialization.Serializable
data class ProviderConfig(

    /** Unique identifier for this provider (lowercase, no spaces). */
    val id: String,

    /** Display name shown in the UI. */
    val name: String,

    /** Base URL for the API endpoint (e.g. https://api.openai.com/v1 or custom endpoint). */
    val baseUrl: String,

    /** Model ID to use for agent execution (e.g. gpt-4o, claude-sonnet-4-6). */
    val modelId: String,

    /** API protocol type. */
    @SerializedName("protocol")
    val protocol: ProviderProtocol,

    /** Authentication type. */
    @SerializedName("auth_type")
    val authType: ProviderAuthType,

    /** Custom header name for API key (used with CUSTOM_API_KEY_HEADER auth type). */
    @SerializedName("custom_header_name")
    val customHeaderName: String? = null,

    /** Custom header value for API key (used with CUSTOM_API_KEY_HEADER auth type). */
    @SerializedName("custom_header_value")
    val customHeaderValue: String? = null,

    /** Request timeout in milliseconds. */
    @SerializedName("timeout_ms")
    val timeoutMs: Int = 30_000,

    /** Whether this provider is currently active. */
    @SerializedName("is_active")
    val isActive: Boolean = false
)

/** API protocol types. */
enum class ProviderProtocol {
    @SerializedName("openai_compatible")
    OPENAI_COMPATIBLE,
    @SerializedName("native")
    NATIVE
}

/** Authentication types. */
enum class ProviderAuthType {
    @SerializedName("bearer_token")
    BEARER_TOKEN,
    @SerializedName("custom_api_key_header")
    CUSTOM_API_KEY_HEADER
}
