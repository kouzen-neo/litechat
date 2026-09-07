package com.localgpt.app.data

/**
 * Centralized constants for role names, model sources, and backend types.
 * Replaces magic strings scattered across the codebase.
 */
object ChatConstants {
    // ── Message Roles ────────────────────────────────────────────────
    const val ROLE_USER = "user"
    const val ROLE_ASSISTANT = "assistant"
    const val ROLE_SYSTEM = "system"

    // ── Model Sources ────────────────────────────────────────────────
    const val SOURCE_LOCAL = "local"
    const val SOURCE_REMOTE = "remote"

    // ── Backend Types ────────────────────────────────────────────────
    const val BACKEND_GPU = "gpu"
    const val BACKEND_CPU = "cpu"

    // ── Theme Modes ──────────────────────────────────────────────────
    const val THEME_SYSTEM = "system"
    const val THEME_DARK = "dark"
    const val THEME_LIGHT = "light"
}
