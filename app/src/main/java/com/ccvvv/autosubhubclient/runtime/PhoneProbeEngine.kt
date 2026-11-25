package com.ccvvv.autosubhubclient.runtime

import com.ccvvv.autosubhubclient.backend.CandidateSubscriptionBundle
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.URL
import android.util.Log
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Native port of the Termux probe: five-site gate, six streaming checks, 5 MiB download. */
class PhoneProbeEngine(private val core: MihomoCoreManager, private val filesDir: File) {
    data class Outcome(val taskId: String, val receiptFile: File, val tested: Int,
        val total: Int, val passed: Int, val high: Int, val normal: Int, val status: String)

    fun run(bundle: CandidateSubscriptionBundle, workers: Int, onProgress: (String) -> Unit = {}): Outcome {
        require(workers in 1..300)
        val candidateBytes = bundle.candidateFile.readBytes()
        val hash = sha256(candidateBytes)
        require(hash.equals(bundle.candidateSha256, true)) { "候选订阅 SHA-256 不匹配" }
        val manifest = JSONObject(bundle.manifestFile.readText())
        val nodeArray = manifest.getJSONArray("nodes")
        require(nodeArray.length() == bundle.candidateCount)
        val names = ArrayList<String>()
        val ids = HashMap<String, String>()
        for (i in 0 until nodeArray.length()) {
            val node = nodeArray.getJSONObject(i)
            val name = node.getString("name")
            val id = node.getString("node_id")
            require(name.isNotBlank() && id.matches(Regex("(?i)^[a-f0-9]{20}$")))
            require(ids.put(name, id) == null) { "节点名称重复：$name" }
            names.add(name)
        }
        require(names.isNotEmpty() && names.toSet().size == names.size)
        val task = "phone-" + UUID.randomUUID().toString()
        val dir = File(filesDir, "mihomo").apply { mkdirs() }
        val apiPort = ServerSocket(0).use { it.localPort }
        val basePort = reservePorts(workers, apiPort)
        val secret = UUID.randomUUID().toString().replace("-", "")
        val config = File(dir, "probe-$task.yaml")
        val log = File(dir, "probe-$task.log")
        config.writeText(makeConfig(String(candidateBytes, Charsets.UTF_8), names, workers, apiPort, basePort, secret))
        val pool = Executors.newFixedThreadPool(workers)
        val streamPool = Executors.newFixedThreadPool(minOf(24, maxOf(4, workers)))
        val results = java.util.Collections.synchronizedList(mutableListOf<JSONObject>())
        var process: Process? = null
        try {
            core.startVersionCheck()
            val binary = core.executable
            val validateLog = File(dir, "probe-$task-validate.log")
            val validationProcess = ProcessBuilder("/system/bin/linker64", binary.absolutePath, "-d", dir.absolutePath, "-t", "-f", config.absolutePath)
                .directory(dir).redirectErrorStream(true).redirectOutput(validateLog).start()
            check(validationProcess.waitFor(45, TimeUnit.SECONDS)) { "Mihomo 配置校验超时" }
            val validationOutput = validateLog.readText()
            if (validationProcess.exitValue() != 0) {
                val lineNumber = Regex("""yaml: line (\d+):""").find(validationOutput)
                    ?.groupValues?.getOrNull(1)?.toIntOrNull()
                val configLines = config.readLines()
                val context = if (lineNumber != null && lineNumber in 1..configLines.size) {
                    val from = maxOf(1, lineNumber - 3)
                    val to = minOf(configLines.size, lineNumber + 3)
                    (from..to).joinToString("\n") { n ->
                        val marker = if (n == lineNumber) ">>" else "  "
                        val value = configLines[n - 1]
                        "$marker %d: %s".format(n, value.take(240))
                    }
                } else "无法从 Mihomo 输出中提取 YAML 行号"
                val codepoints = if (lineNumber != null && lineNumber in 1..configLines.size) {
                    configLines[lineNumber - 1].take(240).map { "U+%04X".format(it.code) }.joinToString(" ")
                } else ""
                throw IllegalStateException(
                    "Mihomo 配置校验失败：$validationOutput\n配置行上下文：\n$context\n目标行字符编码：$codepoints"
                )
            }
            process = ProcessBuilder("/system/bin/linker64", binary.absolutePath, "-d", dir.absolutePath, "-f", config.absolutePath)
                .directory(dir).redirectErrorStream(true).redirectOutput(log).start()
            val api = "http://127.0.0.1:$apiPort"
            waitApi(api, secret, process)
            val futures = (0 until workers).mapNotNull { worker ->
                val batch = names.filterIndexed { index, _ -> index % workers == worker }
                if (batch.isEmpty()) null else pool.submit(Callable {
                    val selector = "PHONE-%02d".format(worker)
                    val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", basePort + worker))
                    batch.forEach { name ->
                        val row = JSONObject().put("node_id", ids[name]).put("name", name)
                            .put("status", "failed").put("download_speed_bps", 0)
                        var stage = "mihomo_select"
                        val stageTimes = JSONObject()
                        try {
                            var stageStart = System.nanoTime()
                            select(api, secret, selector, name)
                            stageTimes.put("mihomo_select_ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - stageStart))
                            stage = "application_checks"
                            stageStart = System.nanoTime()
                            val appTargets = linkedMapOf(
                                "youtube" to "https://www.youtube.com/",
                                "telegram" to "https://web.telegram.org/",
                                "google" to "https://www.google.com/generate_204",
                                "chatgpt" to "https://chatgpt.com/",
                                "github" to "https://github.com/"
                            )
                            val apps = JSONObject()
                            appTargets.forEach { (key, url) -> apps.put(key, checkApp(key, url, proxy)) }
                            stageTimes.put("application_checks_ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - stageStart))
                            val appPassed = appTargets.keys.filter { apps.optJSONObject(it)?.optString("status") == "passed" }
                            row.put("application_checks", apps).put("application_gate",
                                JSONObject().put("required_passes", 5).put("passed_count", appPassed.size)
                                    .put("passed_services", JSONArray(appPassed))
                                    .put("status", if (appPassed.size == 5) "passed" else "failed"))

                            stage = "streaming_checks"
                            stageStart = System.nanoTime()
                            val targets = linkedMapOf(
                                "netflix" to "https://www.netflix.com/title/80018499",
                                "youtube_premium" to "https://www.youtube.com/premium",
                                "disney_plus" to "https://www.disneyplus.com/",
                                "bilibili" to "https://api.bilibili.com/x/web-interface/nav",
                                "spotify" to "https://open.spotify.com/",
                                "tiktok" to "https://www.tiktok.com/"
                            )
                            val pending = targets.map { (key, url) -> key to streamPool.submit(Callable { checkStreaming(key, url, proxy) }) }
                            val streams = JSONObject()
                            pending.forEach { (key, future) ->
                                streams.put(key, try { future.get() } catch (e: Exception) {
                                    JSONObject().put("status", "error").put("confidence", "low")
                                        .put("detail", e.javaClass.simpleName + ": " + (e.message ?: "").take(160))
                                })
                            }
                            stageTimes.put("streaming_checks_ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - stageStart))
                            val yes = targets.keys.count { streams.optJSONObject(it)?.optString("status") == "yes" }
                            val streamVerdict = if (yes > 0) "passed" else "failed"
                            row.put("streaming_checks", streams).put("streaming_verdict", streamVerdict)
                                .put("streaming_gate", JSONObject().put("required_yes", 1).put("passed_count", yes).put("status", streamVerdict))
                            if (appPassed.size != 5) row.put("failure_stage", "application_checks")
                            else if (streamVerdict != "passed") row.put("failure_stage", "streaming_checks")
                            else {
                                stage = "download"
                                stageStart = System.nanoTime()
                                val (bytes, seconds) = download(proxy)
                                stageTimes.put("download_total_ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - stageStart))
                                val speed = (bytes / seconds).toLong()
                                val category = if (speed >= 500000) "high" else if (speed >= 200000) "normal" else "rejected"
                                row.put("downloaded_bytes", bytes).put("expected_download_bytes", 5242880)
                                    .put("elapsed_seconds", seconds).put("download_speed_bps", speed)
                                    .put("speed_category", category).put("status", if (category == "rejected") "failed" else "passed")
                                if (category == "rejected") row.put("failure_stage", "download_threshold")
                            }
                        } catch (e: Exception) {
                            row.put("error", e.javaClass.simpleName).put("message", (e.message ?: "").take(500))
                                .put("failure_stage", stage)
                        }
                        row.put("stage_timings_ms", stageTimes)
                        Log.i("AutoSubHubProbe", "node=" + name + " stage=" + stage + " timings=" + stageTimes + " result=" + row.optString("status"))
                        synchronized(results) {
                            results.add(row)
                            onProgress("测速进度：" + results.size + "/" + names.size + "，当前节点：" + name)
                        }
                    }
                })
            }
            futures.forEach { it.get() }
            val ordered = synchronized(results) { results.sortedBy { names.indexOf(it.optString("name")) } }
            val high = ordered.count { it.optString("status") == "passed" && it.optString("speed_category") == "high" }
            val normal = ordered.count { it.optString("status") == "passed" && it.optString("speed_category") == "normal" }
            val now = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date())
            val receipt = JSONObject().put("schema_version", 1).put("status", if (ordered.size == names.size) "completed" else "partial")
                .put("task_id", task).put("created_at", now).put("completed_at", now)
                .put("candidate_sha256", hash).put("candidate_commit", bundle.candidateCommit)
                .put("candidate_commits", JSONArray().put(bundle.candidateCommit)).put("candidate_batch_count", 1)
                .put("queue_candidate_count", names.size).put("receipt_candidate_count", ordered.size)
                .put("candidate_source", "published-subscriptions candidate snapshot").put("device", "android")
                .put("workers", workers).put("min_speed_bps", 200000).put("high_speed_bps", 500000)
                .put("service_timeout_seconds", 3).put("streaming_timeout_seconds", 8)
                .put("service_urls", JSONObject().put("youtube", "https://www.youtube.com/").put("telegram", "https://web.telegram.org/")
                    .put("google", "https://www.google.com/generate_204").put("chatgpt", "https://chatgpt.com/").put("github", "https://github.com/"))
                .put("download_url", "https://speed.cloudflare.com/__down?bytes=5242880").put("max_download_bytes", 5242880)
                .put("streaming_urls", JSONObject().put("netflix", "https://www.netflix.com/title/80018499")
                    .put("youtube_premium", "https://www.youtube.com/premium").put("disney_plus", "https://www.disneyplus.com/")
                    .put("bilibili", "https://api.bilibili.com/x/web-interface/nav").put("spotify", "https://open.spotify.com/").put("tiktok", "https://www.tiktok.com/"))
                .put("summary", JSONObject().put("tested", ordered.size).put("total", names.size)
                    .put("passed", high + normal).put("failed", ordered.size - high - normal).put("high", high).put("normal", normal))
                .put("results", JSONArray(ordered))
            val taskDir = File(filesDir, "receipts/$task")
            check(taskDir.mkdirs()) { "无法创建回执目录" }
            val receiptFile = File(taskDir, "receipt.json")
            atomicWrite(receiptFile, (receipt.toString(2) + "\n").toByteArray(Charsets.UTF_8))
            atomicWrite(File(taskDir, "receipt.candidate.yaml"), candidateBytes)
            atomicWrite(File(taskDir, "receipt.manifest.json"), bundle.manifestFile.readBytes())
            onProgress("本地回执已生成：" + receiptFile.absolutePath)
            return Outcome(task, receiptFile, ordered.size, names.size, high + normal, high, normal,
                if (ordered.size == names.size) "completed" else "partial")
        } finally {
            pool.shutdownNow()
            streamPool.shutdownNow()
            process?.destroy()
            if (process?.isAlive == true) process?.destroyForcibly()
            config.delete()
        }
    }

    private fun makeConfig(candidate: String, names: List<String>, workers: Int, apiPort: Int, base: Int, secret: String): String {
        // Each worker only tests its own modulo-partitioned batch. Keep its
        // selector limited to that batch instead of repeating all 2,285 names
        // on one enormous YAML flow line for every worker.
        val groups = (0 until workers).joinToString("\n") { i ->
            val batch = names.filterIndexed { index, _ -> index % workers == i }
            val proxyEntries = batch.joinToString("\n") { "      - ${yamlSingleQuoted(it)}" }
            "  - name: PHONE-%02d\n    type: select\n    proxies:\n%s".format(i, proxyEntries)
        }
        val listeners = (0 until workers).joinToString("\n") { i ->
            "  - name: phone-%02d\n    type: mixed\n    listen: 127.0.0.1\n    port: %d\n    proxy: PHONE-%02d".format(i, base + i, i)
        }
        return candidate.trimEnd() + "\n" + "allow-lan: false\nmode: rule\nlog-level: error\nipv6: false\n" +
            "external-controller: 127.0.0.1:$apiPort\nsecret: \"$secret\"\nproxy-groups:\n$groups\nlisteners:\n$listeners\nrules:\n  - MATCH,DIRECT\n"
    }

    /** YAML single-quoted scalars do not interpret backslashes as escape sequences. */
    private fun yamlSingleQuoted(value: String): String {
        require(value.none { it.code < 0x20 || it.code == 0x7f }) { "节点名称包含 YAML 不允许的控制字符" }
        return "'" + value.replace("'", "''") + "'"
    }

    private fun waitApi(api: String, secret: String, process: Process) {
        var last: Exception? = null
        repeat(60) {
            check(process.isAlive) { "Mihomo 正式测速进程提前退出" }
            try { apiCall("$api/version", secret, "GET"); return }
            catch (e: Exception) { last = e; Thread.sleep(500) }
        }
        throw IllegalStateException("Mihomo 控制器启动超时", last)
    }

    private fun select(api: String, secret: String, group: String, name: String) {
        apiCall("$api/proxies/$group", secret, "PUT", JSONObject().put("name", name).toString().toByteArray())
        val selected = JSONObject(String(apiCall("$api/proxies/$group", secret, "GET"), Charsets.UTF_8))
        check(selected.optString("now") == name) { "Mihomo 选择器未切换到节点：" + name }
    }

    private fun apiCall(url: String, secret: String, method: String, body: ByteArray? = null): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method; c.connectTimeout = 8000; c.readTimeout = 8000
            c.setRequestProperty("Authorization", "Bearer $secret")
            c.setRequestProperty("Connection", "keep-alive")
            if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.outputStream.use { it.write(body) } }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val bytes = stream?.use { bounded(it, 65536) } ?: ByteArray(0)
            check(code in 200..299) { "Mihomo API HTTP $code: " + String(bytes, Charsets.UTF_8).take(300) }
            return bytes
        } finally {
            // Response body is consumed; allow HttpURLConnection keep-alive pooling.
        }
    }

    private fun checkApp(name: String, target: String, proxy: Proxy): JSONObject {
        val start = System.nanoTime()
        var active: HttpURLConnection? = null
        return try {
            var url = URL(target); var redirects = 0
            var c: HttpURLConnection
            while (true) {
                c = url.openConnection(proxy) as HttpURLConnection
                active = c
                c.instanceFollowRedirects = false; c.connectTimeout = 3000; c.readTimeout = 3000
                c.setRequestProperty("User-Agent", "AutoSubHub-Android/0.1")
                val code = c.responseCode
                if (code in REDIRECTS && redirects < 8 && c.getHeaderField("Location") != null) {
                    val next = URL(url, c.getHeaderField("Location")); c.disconnect(); active = null; url = next; redirects++
                } else break
            }
            val code = c.responseCode
            check(code == if (name == "google") 204 else 200) { "$name returned HTTP $code" }
            val host = url.host.removePrefix("www.").lowercase()
            val original = URL(target).host.removePrefix("www.").lowercase()
            val allowed = mapOf("youtube" to setOf("youtube.com", "youtube-nocookie.com"),
                "telegram" to setOf("telegram.org"), "google" to setOf("google.com"),
                "chatgpt" to setOf("chatgpt.com", "openai.com"), "github" to setOf("github.com"))[name].orEmpty()
            check(host == original || host.endsWith(".$original") || allowed.any { host == it || host.endsWith(".$it") }) {
                "$name redirected to unexpected host: $host"
            }
            val type = c.getHeaderField("Content-Type").orEmpty().lowercase()
            val sample = if (name == "google") 0 else c.inputStream.use { it.read(ByteArray(1024)).coerceAtLeast(0) }
            if (name != "google") {
                check(sample > 0) { "$name returned empty body" }
                check(type.contains("text/html") || type.contains("application/xhtml+xml")) { "$name content type invalid: $type" }
            }
            val result = JSONObject().put("status", "passed").put("status_code", code).put("final_url", url.toString())
                .put("redirect_count", redirects).put("content_type", type).put("sample_bytes", sample)
                .put("elapsed_ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start))
            active?.disconnect()
            result
        } catch (e: Exception) {
            active?.disconnect()
            JSONObject().put("status", "failed").put("error", e.javaClass.simpleName)
                .put("message", (e.message ?: "").take(500))
                .put("elapsed_ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start))
        }
    }

    private fun checkStreaming(name: String, target: String, proxy: Proxy): JSONObject {
        var active: HttpURLConnection? = null
        return try {
            val c = URL(target).openConnection(proxy) as HttpURLConnection
            active = c
            c.instanceFollowRedirects = name != "disney_plus"; c.connectTimeout = 8000; c.readTimeout = 8000
            c.setRequestProperty("User-Agent", "AutoSubHub-Streaming-Check/1.0")
            if (name == "bilibili") c.setRequestProperty("Referer", "https://www.bilibili.com/")
            val code = c.responseCode
            val limit = when (name) { "youtube_premium", "disney_plus" -> 200000; "bilibili" -> 65536; else -> 0 }
            val body = if (limit > 0) {
                val stream = if (code in 200..399) c.inputStream else c.errorStream
                stream?.use { bounded(it, limit) } ?: ByteArray(0)
            } else ByteArray(0)
            c.disconnect()
            active = null
            classify(name, code, body)
        } catch (e: Exception) {
            active?.disconnect()
            JSONObject().put("status", "error").put("confidence", "low")
                .put("detail", e.javaClass.simpleName + ": " + (e.message ?: "").take(160))
        }
    }

    private fun classify(name: String, code: Int, body: ByteArray): JSONObject {
        val text = String(body, Charsets.UTF_8).lowercase()
        var status = "error"; var confidence = "low"; var detail = "HTTP $code"
        when (name) {
            "netflix" -> if (code == 200 || code in REDIRECTS) { status = "yes"; confidence = "medium"; detail = "页面可访问，不代表所有内容可播放" } else if (code == 403) { status = "no"; confidence = "high" }
            "youtube_premium" -> if (code == 200 && text.contains("premium")) { status = "yes"; confidence = "medium"; detail = "Premium 页面包含标记" } else if (code == 200) { status = "partial"; detail = "页面可访问但未发现 Premium 标记" } else if (code == 403) { status = "no"; confidence = "medium" }
            "disney_plus" -> if (code in REDIRECTS) { status = "yes"; confidence = "low"; detail = "服务重定向，地区判断置信度较低" } else if (code == 200 && text.contains("disney")) { status = "yes"; confidence = "medium" } else if (code == 200) { status = "partial" } else if (code == 403) { status = "no"; confidence = "medium" }
            "bilibili" -> if (code == 403) { status = "no"; confidence = "medium" } else if (code == 200) {
                val apiCode = try { JSONObject(text).optInt("code", Int.MIN_VALUE) } catch (_: Exception) { Int.MIN_VALUE }
                if (apiCode == 0) { status = "yes"; confidence = "medium"; detail = "Bilibili API code=0" } else { status = "partial"; detail = "API 非零或无法解析" }
            }
            "spotify", "tiktok" -> if (code == 200) { status = "yes"; confidence = "medium"; detail = "页面可访问，不代表实际播放" } else if (code == 403) { status = "no"; confidence = "medium" }
        }
        return JSONObject().put("status", status).put("confidence", confidence).put("detail", detail).put("http_status", code)
    }

    private fun download(proxy: Proxy): Pair<Long, Double> {
        val c = URL("https://speed.cloudflare.com/__down?bytes=5242880").openConnection(proxy) as HttpURLConnection
        c.connectTimeout = 5000; c.readTimeout = 5000
        c.setRequestProperty("User-Agent", "AutoSubHub-Android/0.1"); c.setRequestProperty("Accept-Encoding", "identity")
        val start = System.nanoTime()
        try {
            check(c.responseCode == 200) { "下载测速 HTTP " + c.responseCode }
            val length = c.getHeaderField("Content-Length")?.toLongOrNull()
            if (length != null) check(length >= 5242880)
            var bytes = 0L
            c.inputStream.use { input ->
                val buffer = ByteArray(65536)
                while (bytes < 5242880) {
                    val n = input.read(buffer, 0, minOf(buffer.size.toLong(), 5242880 - bytes).toInt())
                    if (n < 0) break
                    bytes += n
                }
            }
            check(bytes == 5242880L) { "下载不完整：$bytes/5242880" }
            return bytes to ((System.nanoTime() - start) / 1_000_000_000.0)
        } finally { c.disconnect() }
    }

    private fun reservePorts(count: Int, excluded: Int): Int {
        repeat(40) {
            val p = ServerSocket(0).use { it.localPort }
            if (p in 1024..(65535 - count) && excluded !in p..(p + count)) return p
        }
        error("无法分配本地测速端口")
    }

    private fun bounded(input: java.io.InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream(); val buf = ByteArray(8192)
        while (true) { val n = input.read(buf); if (n < 0) break
            val take = minOf(n, max - out.size()); if (take > 0) out.write(buf, 0, take)
            if (out.size() >= max) break
        }
        return out.toByteArray()
    }

    private fun atomicWrite(file: File, bytes: ByteArray) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            tmp.outputStream().use { it.write(bytes); it.flush() }
            java.nio.file.Files.move(tmp.toPath(), file.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally { tmp.delete() }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
    }
}
