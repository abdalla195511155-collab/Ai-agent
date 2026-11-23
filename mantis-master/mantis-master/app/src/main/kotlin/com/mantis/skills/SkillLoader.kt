package com.mantis.skills

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Loads MANTIS skill files from the device's files directory.
 *
 * Three skill directories are scanned in priority order:
 *  1. `/files/skills/bundled/`   — shipped with the APK (copied from assets on first run)
 *  2. `/files/skills/installed/` — downloaded from the Skill Store (Phase 10)
 *  3. `/files/skills/workspace/` — user-authored private skills (Phase 10)
 *
 * On first run (bundled dir is empty), all `.md` files under `assets/skills/`
 * are copied into the bundled directory so they survive APK updates gracefully.
 *
 * Each `.md` file must follow the SKILL.md schema:
 * ```
 * # Skill: {name}
 * ## Trigger
 * ...keywords...
 * ## Steps
 * ...numbered steps...
 * ## Notes
 * ...edge cases...
 * ```
 *
 * Files that cannot be parsed are skipped with a warning log — a bad skill
 * never crashes the app or blocks other skills from loading.
 */
object SkillLoader {

    private const val TAG = "SkillLoader"
    private const val ASSETS_DIR = "skills"

    /**
     * Loads all valid skills from bundled, installed, and workspace directories.
     * Copies bundled assets on first run.
     *
     * @return List of parsed [Skill]s, potentially empty if no files exist yet.
     */
    fun loadAll(context: Context): List<Skill> {
        ensureBundledSkillsCopied(context)

        val dirs = listOf(
            File(context.filesDir, "skills/bundled"),
            File(context.filesDir, "skills/installed"),
            File(context.filesDir, "skills/workspace"),
        )

        return dirs
            .filter { it.exists() && it.isDirectory }
            .flatMap { dir ->
                dir.listFiles { f -> f.extension == "md" }
                    ?.mapNotNull { file -> parseSkillFile(file) }
                    ?: emptyList()
            }
            .also { Log.d(TAG, "Loaded ${it.size} skill(s)") }
    }

    // ── Asset copy ────────────────────────────────────────────────────────────

    /**
     * Copies all `.md` files from `assets/skills/` into the bundled directory
     * if the directory does not exist or is empty. Runs at most once per install.
     */
    private fun ensureBundledSkillsCopied(context: Context) {
        val bundledDir = File(context.filesDir, "skills/bundled")
        if (bundledDir.exists() && (bundledDir.listFiles()?.isNotEmpty() == true)) return

        bundledDir.mkdirs()
        try {
            val assetFiles = context.assets.list(ASSETS_DIR) ?: return
            var copied = 0
            for (fileName in assetFiles) {
                if (!fileName.endsWith(".md")) continue
                val dest = File(bundledDir, fileName)
                context.assets.open("$ASSETS_DIR/$fileName").use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                copied++
            }
            Log.d(TAG, "Copied $copied bundled skill(s) to ${bundledDir.path}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy bundled skills from assets", e)
        }
    }

    // ── Parsing ───────────────────────────────────────────────────────────────

    /**
     * Parses a SKILL.md file into a [Skill].
     * Returns null (and logs a warning) if any required section is missing.
     */
    private fun parseSkillFile(file: File): Skill? {
        return try {
            val text = file.readText()
            val name = parseH1Name(text) ?: run {
                Log.w(TAG, "${file.name}: missing '# Skill:' header — skipped")
                return null
            }
            val trigger = parseSection(text, "Trigger") ?: run {
                Log.w(TAG, "${file.name}: missing '## Trigger' section — skipped")
                return null
            }
            val steps = parseSection(text, "Steps") ?: run {
                Log.w(TAG, "${file.name}: missing '## Steps' section — skipped")
                return null
            }
            val notes = parseSection(text, "Notes") ?: ""  // Notes are optional

            Skill(name = name, trigger = trigger, steps = steps, notes = notes)
                .also { Log.d(TAG, "Loaded skill: '$name' from ${file.name}") }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading ${file.name} — skipped", e)
            null
        }
    }

    /**
     * Extracts the skill name from the `# Skill: {name}` header.
     */
    private fun parseH1Name(text: String): String? {
        return text.lines()
            .firstOrNull { it.startsWith("# Skill:") }
            ?.removePrefix("# Skill:")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * Extracts the trimmed body of a `## {sectionName}` section.
     * The section ends at the next `##` heading or end of file.
     */
    private fun parseSection(text: String, sectionName: String): String? {
        val lines = text.lines()
        val startIndex = lines.indexOfFirst {
            it.trim().equals("## $sectionName", ignoreCase = true)
        }
        if (startIndex == -1) return null

        val body = lines
            .drop(startIndex + 1)
            .takeWhile { !it.startsWith("## ") }
            .joinToString("\n")
            .trim()

        return body.takeIf { it.isNotBlank() }
    }
}
