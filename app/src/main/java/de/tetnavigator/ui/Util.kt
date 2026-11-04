package de.tetnavigator.ui

import java.util.Locale

fun bytes(b: Long): String = when {
    b >= 1_000_000_000 -> String.format(Locale.GERMANY, "%.1f GB", b / 1e9)
    b >= 1_000_000 -> String.format(Locale.GERMANY, "%.0f MB", b / 1e6)
    b >= 1_000 -> String.format(Locale.GERMANY, "%.0f kB", b / 1e3)
    else -> "$b B"
}
