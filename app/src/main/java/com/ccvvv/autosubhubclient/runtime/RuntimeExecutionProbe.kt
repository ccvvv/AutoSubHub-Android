package com.ccvvv.autosubhubclient.runtime

import android.content.Context
import java.io.File

data class RuntimeExecutionResult(
    val success: Boolean,
    val executablePath: String,
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val detail: String
)

class RuntimeExecutionProbe(
    context: Context,
    private val commandRunner: CommandRunner = ProcessCommandRunner()
) {
    private val appContext = context.applicationContext

    fun runSelfTest(): RuntimeExecutionResult {
        val executable = File(
            appContext.applicationInfo.nativeLibraryDir,
            PROBE_FILE_NAME
        )
        if (!executable.isFile) {
            return RuntimeExecutionResult(
                success = false,
                executablePath = executable.absolutePath,
                exitCode = null,
                stdout = "",
                stderr = "",
                detail = "Packaged ARM64 execution probe was not extracted"
            )
        }

        return try {
            val result = commandRunner.run(
                CommandSpec(
                    executable = executable,
                    arguments = listOf("--self-test"),
                    workingDirectory = appContext.filesDir,
                    timeoutMillis = 5_000,
                    maxCapturedBytesPerStream = 4 * 1024
                )
            )
            val passed = result.exitCode == 0 &&
                result.stdout.trim() == EXPECTED_OUTPUT
            RuntimeExecutionResult(
                success = passed,
                executablePath = executable.absolutePath,
                exitCode = result.exitCode,
                stdout = result.stdout,
                stderr = result.stderr,
                detail = if (passed) {
                    "Native ELF child process executed successfully"
                } else {
                    "ELF process returned an unexpected result"
                }
            )
        } catch (error: Exception) {
            RuntimeExecutionResult(
                success = false,
                executablePath = executable.absolutePath,
                exitCode = null,
                stdout = "",
                stderr = error.message.orEmpty(),
                detail = "${error.javaClass.simpleName}: ${error.message.orEmpty()}"
            )
        }
    }

    companion object {
        private const val PROBE_FILE_NAME = "libruntime_probe.so"
        private const val EXPECTED_OUTPUT = "AUTOSUBHUB_RUNTIME_PROBE_OK"
    }
}
