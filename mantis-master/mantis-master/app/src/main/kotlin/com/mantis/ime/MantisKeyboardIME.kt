/*
 * Adapted from DroidRun Portal (github.com/droidrun/droidrun-portal)
 * Original work Copyright (c) DroidRun contributors
 * Licensed under MIT License
 * Modifications: Removed ADB/ContentProvider integration. Registered on MantisApp
 *   so AgentBridge can dispatch text without ADB. Keyboard UI is fully hidden —
 *   this IME exists solely for programmatic text injection via InputConnection.
 */
package com.mantis.ime

import android.inputmethodservice.InputMethodService
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import com.mantis.MantisApp

/**
 * Programmatic keyboard IME for MANTIS.
 *
 * This IME has no visible keyboard. It must be set as the device's active input method
 * so MANTIS can inject text into any focused field via [android.view.inputmethod.InputConnection].
 *
 * Setup required by user (one-time, prompted during onboarding):
 *   Settings → General Management → Keyboard list and default → Default keyboard → MANTIS Keyboard
 *
 * Registered on [MantisApp][com.mantis.MantisApp] in [onCreate], cleared in [onDestroy].
 * [AgentBridge][com.mantis.bridge.AgentBridge] calls [typeText] via the MantisApp reference.
 *
 * Public API:
 *  - [typeText]: commits a text string into the focused field (handles '\n' → [pressEnter])
 *  - [clearField]: selects all + replaces with empty string
 *  - [pressEnter]: performs the field's configured IME action, or falls back to Enter key
 */
class MantisKeyboardIME : InputMethodService() {

    companion object {
        private const val TAG = "MantisIME"
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        (applicationContext as MantisApp).keyboardIme = this
        Log.d(TAG, "MantisKeyboardIME created and registered on MantisApp")
    }

    override fun onDestroy() {
        (applicationContext as MantisApp).keyboardIme = null
        Log.d(TAG, "MantisKeyboardIME destroyed and cleared from MantisApp")
        super.onDestroy()
    }

    // ── Hidden keyboard — no visible UI ──────────────────────────────────────

    /**
     * Returns a zero-size placeholder view. This IME never renders a keyboard —
     * all text injection happens programmatically via [typeText].
     */
    override fun onCreateInputView(): View = View(this)

    /** Prevents the IME from ever entering full-screen mode. */
    override fun onEvaluateFullscreenMode(): Boolean = false

    /** Prevents the keyboard view from ever being shown to the user. */
    override fun onEvaluateInputViewShown(): Boolean = false

    /**
     * When the user taps a field inside the MANTIS app (e.g. the task input),
     * hand control back to whatever keyboard they had before so they can type normally.
     * The agent types in other apps via ACTION_SET_TEXT — no active IME needed there.
     */
    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (info.packageName == packageName) {
            val token = window?.window?.attributes?.token ?: return
            @Suppress("DEPRECATION")
            getSystemService(InputMethodManager::class.java).switchToLastInputMethod(token)
            Log.d(TAG, "onStartInputView: MANTIS field — switched to last IME")
        }
    }

    // ── Text injection API ────────────────────────────────────────────────────

    /**
     * Commits [text] into the currently focused input field.
     *
     * Uses [android.view.inputmethod.InputConnection.commitText] which handles all
     * Unicode characters natively — no per-character key-event overhead.
     *
     * If [text] ends with '\n', the trailing newline is stripped and [pressEnter]
     * is called after committing the body, triggering the field's IME action.
     *
     * Does nothing if there is no active InputConnection (field not focused,
     * or MANTIS Keyboard is not selected as the device input method).
     */
    fun typeText(text: String) {
        val ic = currentInputConnection ?: run {
            Log.w(TAG, "typeText: no active InputConnection — is MANTIS Keyboard selected?")
            return
        }
        if (text.endsWith("\n")) {
            val body = text.dropLast(1)
            if (body.isNotEmpty()) ic.commitText(body, 1)
            pressEnter()
        } else {
            ic.commitText(text, 1)
        }
        Log.d(TAG, "typeText: committed ${text.length} char(s)")
    }

    /**
     * Clears the currently focused input field by selecting all content and
     * replacing it with an empty string.
     */
    fun clearField() {
        val ic = currentInputConnection ?: run {
            Log.w(TAG, "clearField: no active InputConnection")
            return
        }
        ic.performContextMenuAction(android.R.id.selectAll)
        ic.commitText("", 1)
        Log.d(TAG, "clearField: field cleared")
    }

    /**
     * Performs the IME action configured for the focused field (Done, Send,
     * Search, Go, Next, etc.). Falls back to an Enter key event if the field
     * declares [EditorInfo.IME_ACTION_NONE] or [EditorInfo.IME_ACTION_UNSPECIFIED].
     */
    fun pressEnter() {
        val ic = currentInputConnection ?: run {
            Log.w(TAG, "pressEnter: no active InputConnection")
            return
        }
        val imeAction = currentInputEditorInfo
            ?.imeOptions
            ?.and(EditorInfo.IME_MASK_ACTION)
            ?: EditorInfo.IME_ACTION_NONE

        if (imeAction != EditorInfo.IME_ACTION_NONE &&
            imeAction != EditorInfo.IME_ACTION_UNSPECIFIED
        ) {
            ic.performEditorAction(imeAction)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
        Log.d(TAG, "pressEnter: imeAction=$imeAction")
    }
}
