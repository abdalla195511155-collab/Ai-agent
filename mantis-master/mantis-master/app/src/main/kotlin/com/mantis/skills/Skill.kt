package com.mantis.skills

/**
 * A parsed MANTIS skill loaded from a SKILL.md file.
 *
 * @property name    Human-readable skill name (from `# Skill: {name}` header).
 * @property trigger Keyword/pattern list used by [SkillSelector] to match user tasks.
 * @property steps   Numbered step guidance injected into the AgentLoop system prompt.
 * @property notes   Optional edge cases and device-specific tips (may be empty).
 */
data class Skill(
    val name: String,
    val trigger: String,
    val steps: String,
    val notes: String,
)
