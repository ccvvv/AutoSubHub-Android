package com.ccvvv.autosubhubclient.runtime

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * Downloads the official Android ARM64 Mihomo release, verifies the compressed
 * asset against its published SHA-256, then extracts it into app-private storage.
 */
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

    @Synchronized
    fun startVersionCheck(): String {
        check(process == null) { "Mihomo 已在运行" }
        val binary = ensureInstalled()
        val proc = ProcessBuilder(binary.absolutePath, "-v")
            .directory(coreDir)
            .redirectErrorStream(true)
            .start()
        process = proc
        val output = proc.inputStream.bufferedReader().use { it.readText() }
        val exit = proc.waitFor()
        process = null
        check(exit == 0) { "Mihomo 启动失败（退出码 $exit）：$output" }
        return output.trim().ifEmpty { "Mihomo 核心启动成功" }
    }

    @Synchronized
    fun stop() {
        process?.destroy()
        process?.let {
            if (it.isAlive) it.destroyForcibly()
        }
        process = null
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
