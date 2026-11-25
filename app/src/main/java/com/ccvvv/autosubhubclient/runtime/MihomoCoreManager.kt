package com.ccvvv.autosubhubclient.runtime

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/** Downloads and runs the official Android ARM64 Mihomo in app-private storage. */
class MihomoCoreManager(context: Context) {
    private val coreDir = File(context.filesDir, "mihomo").apply { mkdirs() }
    private val archive = File(coreDir, ASSET_NAME)
    val executable = File(coreDir, "mihomo")

    @Volatile private var process: Process? = null

    @Synchronized
    fun ensureInstalled(): File {
        if (executable.isFile && executable.canExecute()) return executable
        if (!archive.isFile || sha256(archive) != SHA256) {
            archive.delete()
            val connection = (URL(ASSET_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = true
            }
            try {
                connection.inputStream.use { input ->
                    archive.outputStream().use { output -> input.copyTo(output) }
                }
            } finally {
                connection.disconnect()
            }
        }
        require(sha256(archive) == SHA256) { "Mihomo 下载文件 SHA-256 校验失败" }
        val temp = File(coreDir, "mihomo.tmp")
        GZIPInputStream(archive.inputStream()).use { input ->
            temp.outputStream().use { output -> input.copyTo(output) }
        }
        require(temp.length() > 1_000_000L) { "Mihomo 解压文件异常" }
        if (executable.exists()) executable.delete()
        check(temp.renameTo(executable)) { "无法安装 Mihomo 可执行文件" }
        check(executable.setExecutable(true, true)) { "无法设置 Mihomo 执行权限" }
        return executable
    }

    /** Validate the downloaded candidate proxy list with a minimal complete Mihomo config. */
    @Synchronized
    fun validateCandidateSubscription(candidateFile: File): String {
        require(candidateFile.isFile) { "找不到已下载的候选订阅文件" }
        val candidateYaml = candidateFile.readText()
        require(candidateYaml.lineSequence().any { it.trim() == "proxies:" }) {
            "候选文件缺少 proxies 节点列表"
        }
        val config = File(coreDir, "candidate-smoke-test.yaml")
        config.writeText(
            candidateYaml.trimEnd() + "\n" + """
                proxy-groups:
                  - name: AutoSubHub-Validation
                    type: select
                    proxies:
                      - DIRECT
                rules:
                  - MATCH,DIRECT
            """.trimIndent() + "\n"
        )
        val binary = ensureInstalled()
        val validation = linkerProcess(binary, "-d", coreDir.absolutePath, "-t", "-f", config.absolutePath)
        val output = validation.inputStream.bufferedReader().use { it.readText() }
        val exit = validation.waitFor()
        check(exit == 0) { "候选配置校验失败（退出码 $exit）：$output" }
        return "候选配置校验通过；文件 ${candidateFile.name}"
    }

    @Synchronized
    fun startVersionCheck(): String {
        check(process == null) { "Mihomo 已在运行" }
        val binary = ensureInstalled()
        val proc = linkerProcess(binary, "-v")
        val output = proc.inputStream.bufferedReader().use { it.readText() }
        val exit = proc.waitFor()
        check(exit == 0) { "Mihomo 启动失败（退出码 $exit）：$output" }
        return output.trim().ifEmpty { "Mihomo 核心启动成功" }
    }

    /** Validate a minimal local config, launch the core, confirm it stays alive, then stop it. */
    @Synchronized
    fun testMinimalConfig(): String {
        check(process == null) { "Mihomo 已在运行" }
        val binary = ensureInstalled()
        val config = File(coreDir, "smoke-test.yaml").apply {
            writeText(
                """
                mixed-port: 17890
                allow-lan: false
                mode: rule
                log-level: warning
                ipv6: false
                """.trimIndent() + "\n"
            )
        }
        val validation = linkerProcess(binary, "-d", coreDir.absolutePath, "-t", "-f", config.absolutePath)
        val validationOutput = validation.inputStream.bufferedReader().use { it.readText() }
        val validationExit = validation.waitFor()
        check(validationExit == 0) {
            "最小配置校验失败（退出码 $validationExit）：$validationOutput"
        }

        val running = ProcessBuilder(
            "/system/bin/linker64", binary.absolutePath,
            "-d", coreDir.absolutePath, "-f", config.absolutePath
        ).directory(coreDir).redirectErrorStream(true).start()
        process = running
        try {
            if (!running.waitFor(2, TimeUnit.SECONDS)) {
                check(running.isAlive) { "Mihomo 启动后立即退出" }
                return "最小配置校验通过；核心启动并持续运行 2 秒，已正常停止"
            }
            val output = running.inputStream.bufferedReader().use { it.readText() }
            check(running.exitValue() == 0) {
                "Mihomo 启动失败（退出码 ${running.exitValue()}）：$output"
            }
            return "核心启动并自行退出：$output"
        } finally {
            running.destroy()
            if (running.isAlive) running.destroyForcibly()
            process = null
        }
    }

    @Synchronized
    fun stop() {
        process?.destroy()
        process?.let { if (it.isAlive) it.destroyForcibly() }
        process = null
    }

    private fun linkerProcess(binary: File, vararg args: String): Process {
        val linker = File("/system/bin/linker64")
        check(linker.isFile) { "找不到 Android ARM64 linker64" }
        return ProcessBuilder(listOf(linker.absolutePath, binary.absolutePath) + args)
            .directory(coreDir)
            .redirectErrorStream(true)
            .start()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val ASSET_NAME = "mihomo-android-arm64-v8-v1.19.34.gz"
        private const val ASSET_URL =
            "https://github.com/MetaCubeX/mihomo/releases/download/v1.19.34/mihomo-android-arm64-v8-v1.19.34.gz"
        private const val SHA256 =
            "48a71fc6a68e2909587176e0ab7185d8f2fac298fa96ccdacf30ea86ab2359b5"
    }
}
