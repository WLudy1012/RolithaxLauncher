package com.wludy.rolithax.launcher.shared

fun recommendedJavaMajor(gameVersion: String): Int? {
    val version = gameVersion.trim().removePrefix("v")
    val match = Regex("^(\\d+)\\.(\\d+)(?:\\.(\\d+))?").find(version) ?: return null
    val major = match.groupValues[1].toIntOrNull() ?: return null
    val minor = match.groupValues[2].toIntOrNull() ?: return null
    val patch = match.groupValues.getOrNull(3)?.toIntOrNull() ?: 0
    if (major >= 26) return 25
    if (major != 1) return null
    return when {
        minor <= 16 -> 8
        minor <= 20 && (minor < 20 || patch <= 4) -> 17
        else -> 21
    }
}
