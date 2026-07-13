/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package com.seanproctor.potassium.internal

import com.seanproctor.potassium.internal.files.normalizedPath
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.Provider
import java.io.File

internal fun <T : Any> MutableCollection<String>.cliArg(
    name: String,
    value: T?,
    fn: (T) -> String = defaultToString(),
) {
    if (value is Boolean) {
        if (value) add(name)
    } else if (value != null) {
        add(name)
        add(fn(value))
    }
}

internal fun <T : Any> MutableCollection<String>.cliArg(
    name: String,
    value: Provider<T>,
    fn: (T) -> String = defaultToString(),
) {
    cliArg(name, value.orNull, fn)
}

internal fun MutableCollection<String>.javaOption(value: String) {
    cliArg("--java-options", "'$value'")
}

// Values are wrapped in double quotes because these args are written to a jpackage/jlink `@argfile`
// (see AbstractJvmToolOperationTask), whose parser tokenizes each line on whitespace — quoting keeps
// values with spaces (paths, descriptions) as a single token. Do NOT use cliArg for tools invoked
// directly via ExecOperations.exec (e.g. a raw `java -cp` call): there each list element is passed to
// the process verbatim, so the surrounding quotes become part of the argument and corrupt it. Such
// callers should build their args without quoting.
private fun <T : Any> defaultToString(): (T) -> String =
    {
        val asString =
            when (it) {
                is FileSystemLocation -> it.asFile.normalizedPath()
                is File -> it.normalizedPath()
                else -> it.toString()
            }
        "\"$asString\""
    }
