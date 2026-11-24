package com.ccvvv.autosubhubclient.runtime

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class CommandSpec(
    val executable: File,
    val arguments: List<String>,
    val workingDirectory: File,
    val environment: Map<String, String> = emptyMap(),
    val timeoutMillis: Long = 30_000,
    val maxCapturedBytesPerStream: Int = 256 * 1024
) {
    init {
        require(executable.isAbsolute) { "Executable path must be absolute" }
        require(workingDirectory.isAbsolute && workingDirectory.isDirectory) {
            "Working directory must be an existing absolute directory"
        }
        require(timeoutMillis > 0)
        require(maxCapturedBytesPerStream > 0)
        require(arguments.none { it.indexOf('\u0000') >= 0 }) {
            "Command arguments must not contain NUL characters"
        }
        require(environment.keys.none { it.isBlank() || it.contains('=') || it.indexOf('\u0000') >= 0 })
        require(environment.values.none { it.indexOf('\u0000') >= 0 })
    }
}

data class CommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val elapsedMillis: Long,
    val stdoutTruncated: Boolean,
    val stderrTruncated: Boolean
)

class CommandTimeoutException(message: String, val partialResult: CommandResult) :
    RuntimeException(message)

interface CommandRunner {
    fun run(spec: CommandSpec): CommandResult
}

/**
 * Executes an argument vector directly; it never concatenates commands into a shell string.
 * Output is drained concurrently and bounded so a noisy child cannot exhaust app memory.
 */
class ProcessCommandRunner : CommandRunner {
    override fun run(spec: CommandSpec): CommandResult {
        require(spec.executable.isFile && spec.executable.canExecute()) {
            "Executable is missing or not executable: ${spec.executable.name}"
        }

        val command = ArrayList<String>(spec.arguments.size + 1).apply {
            add(spec.executable.absolutePath)
            addAll(spec.arguments)
        }
        val builder = ProcessBuilder(command)
            .directory(spec.workingDirectory)
            .redirectErrorStream(false)
        builder.environment().putAll(spec.environment)

        val startedAt = System.nanoTime()
        val process = builder.start()
        val drains = Executors.newFixedThreadPool(2)
        try {
            val stdoutFuture = drains.submit<CapturedOutput> {
                capture(process.inputStream, spec.maxCapturedBytesPerStream)
            }
            val stderrFuture = drains.submit<CapturedOutput> {
                capture(process.errorStream, spec.maxCapturedBytesPerStream)
            }

            val finished = process.waitFor(spec.timeoutMillis, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroy()
                if (!process.waitFor(750, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                    process.waitFor(750, TimeUnit.MILLISECONDS)
                }
            }

            val stdout = stdoutFuture.get(2, TimeUnit.SECONDS)
            val stderr = stderrFuture.get(2, TimeUnit.SECONDS)
            val result = CommandResult(
                exitCode = if (finished) process.exitValue() else -1,
                stdout = String(stdout.bytes, Charsets.UTF_8),
                stderr = String(stderr.bytes, Charsets.UTF_8),
                elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt),
                stdoutTruncated = stdout.truncated,
                stderrTruncated = stderr.truncated
            )
            if (!finished) {
                throw CommandTimeoutException(
                    "Command timed out after ${spec.timeoutMillis} ms",
                    result
                )
            }
            return result
        } catch (interrupted: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
            throw IllegalStateException("Command execution was interrupted", interrupted)
        } finally {
            drains.shutdownNow()
        }
    }

    private fun capture(input: InputStream, maxBytes: Int): CapturedOutput {
        val output = ByteArrayOutputStream(minOf(maxBytes, 8192))
        val buffer = ByteArray(8192)
        var truncated = false
        input.use { stream ->
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                val remaining = maxBytes - output.size()
                if (remaining > 0) output.write(buffer, 0, minOf(count, remaining))
                if (count > remaining) truncated = true
            }
        }
        return CapturedOutput(output.toByteArray(), truncated)
    }

    private data class CapturedOutput(val bytes: ByteArray, val truncated: Boolean)
}
