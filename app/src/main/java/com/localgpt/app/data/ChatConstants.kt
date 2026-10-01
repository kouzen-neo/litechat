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
    // NOTE: values are uppercase "GPU"/"CPU" — this matches what is persisted
    // in DataStore and compared with equals(..., ignoreCase = true) across the
    // codebase. The old lowercase "gpu"/"cpu" values were never referenced.
    const val BACKEND_GPU = "GPU"
    const val BACKEND_CPU = "CPU"

    // ── Theme Modes ──────────────────────────────────────────────────
    const val THEME_SYSTEM = "system"
    const val THEME_DARK = "dark"
    const val THEME_LIGHT = "light"
}
