package com.seanproctor.potassium.updater.internal

/**
 * Renders [value] as a PowerShell single-quoted string literal, for the install scripts
 * [PlatformInstaller] generates.
 *
 * Paths reaching those scripts are not fully trusted: the temp directory contains the user's
 * account name (an apostrophe there is ordinary — `C:\Users\O'Brien\...`), and the downloaded
 * artifact's file name comes from the remote update manifest.
 *
 * Inside single quotes PowerShell treats every character literally except `'`, which is escaped
 * by doubling it. Doubling is therefore complete: no path content can terminate the literal and
 * be parsed as script.
 */
internal fun psLiteral(value: String): String = "'" + value.replace("'", "''") + "'"

/**
 * Renders [value] as a POSIX shell single-quoted string literal.
 *
 * Single quotes suppress every expansion, including `$(…)` command substitution — which a
 * double-quoted literal would still run. An embedded quote is emitted as `'\''` (close, escaped
 * quote, reopen), the only way to represent one, so no path content can escape the literal.
 */
internal fun shLiteral(value: String): String = "'" + value.replace("'", "'\\''") + "'"
