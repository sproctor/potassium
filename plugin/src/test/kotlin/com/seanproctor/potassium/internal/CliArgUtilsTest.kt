/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package com.seanproctor.potassium.internal

import org.junit.Assert.assertEquals
import org.junit.Test

class CliArgUtilsTest {
    /**
     * Values are quoted on purpose: cliArg output is written to a jlink/jpackage `@argfile`, whose
     * parser tokenizes each line on whitespace, so multi-word values must stay quoted. Tools invoked
     * directly via ExecOperations.exec (e.g. `java -cp`) must build args without cliArg — see
     * AbstractProguardTask.
     */
    @Test
    fun `cliArg quotes string values for the argfile tokenizer`() {
        val args = mutableListOf<String>()
        args.cliArg("--name", "My App")
        assertEquals(listOf("--name", "\"My App\""), args)
    }

    @Test
    fun `cliArg emits only the flag for a true boolean and nothing for false`() {
        val whenTrue = mutableListOf<String>()
        whenTrue.cliArg("--verbose", true)
        assertEquals(listOf("--verbose"), whenTrue)

        val whenFalse = mutableListOf<String>()
        whenFalse.cliArg("--verbose", false)
        assertEquals(emptyList<String>(), whenFalse)
    }

    @Test
    fun `cliArg skips null values`() {
        val args = mutableListOf<String>()
        args.cliArg("--missing", null as String?)
        assertEquals(emptyList<String>(), args)
    }

    @Test
    fun `javaOption wraps the value in single quotes for the cfg, then cliArg quotes for the argfile`() {
        val args = mutableListOf<String>()
        args.javaOption("-Dfoo=bar baz")
        assertEquals(listOf("--java-options", "\"'-Dfoo=bar baz'\""), args)
    }
}
