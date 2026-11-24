package com.ccvvv.autosubhubclient.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class CommandSpecTest {
    @Test
    fun acceptsArgumentVectorWithoutShellParsing() {
        val spec = CommandSpec(
            executable = File("/system/bin/sh"),
            arguments = listOf("-c", "echo safe"),
            workingDirectory = File(".").absoluteFile
        )
        assertEquals(listOf("-c", "echo safe"), spec.arguments)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsRelativeExecutablePath() {
        CommandSpec(
            executable = File("git"),
            arguments = emptyList(),
            workingDirectory = File(".").absoluteFile
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNulInArguments() {
        CommandSpec(
            executable = File("/system/bin/git"),
            arguments = listOf("bad\u0000argument"),
            workingDirectory = File(".").absoluteFile
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonPositiveTimeout() {
        CommandSpec(
            executable = File("/system/bin/git"),
            arguments = emptyList(),
            workingDirectory = File(".").absoluteFile,
            timeoutMillis = 0
        )
    }
}
