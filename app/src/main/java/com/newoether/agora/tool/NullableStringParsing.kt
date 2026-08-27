package com.newoether.agora.tool

/** Strict boolean parsing for optional tool-output fields. */
internal fun String?.toBooleanStrictOrNull(): Boolean? = when (this) {
    "true" -> true
    "false" -> false
    else -> null
}
