package com.ccvvv.autosubhubclient.backend

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.Base64
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.transport.SshTransport
import org.eclipse.jgit.transport.sshd.SshdSessionFactoryBuilder
import org.apache.sshd.common.util.io.PathUtils
import java.security.MessageDigest

/**
 * Public-read GitHub transport for the first integration milestone.
 *
 * This class deliberately does not upload receipts or hold credentials. All methods are
 * synchronous and must be called from a background executor, never from the UI thread.
 */
class GitHubReadOnlyTransport(
    private val config: BackendConfig,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 45_000,
    private val requestOverride: ((String, String) -> ByteArray)? = null,
    private val filesDir: File? = null
) {
    init {
        config.validate()
        require(connectTimeoutMs in 1..120_000)
        require(readTimeoutMs in 1..180_000)
    }

    data class LatestReceipt(
        val receiptId: String?,
        val candidateCommit: String?,
        val candidateSha256: String?,
        val status: String?,
        val receiptCommit: String?
    )




    /**
     * Downloads the one current candidate pool and its manifest.
     * Resolve the data branch head once, then pin both file reads to that same SHA.
     * No candidate-file history is needed: the pool contains only the current test batch.
     */
    fun fetchCandidateSubscription(workspace: File): CandidateSubscriptionBundle {
        require(workspace.exists() || workspace.mkdirs()) { "Cannot create candidate workspace" }
        require(workspace.isDirectory) { "Candidate workspace is not a directory" }

        // Reuse only snapshots previously validated and tagged with their source commit.
        // Older caches lack the commit marker and are refreshed once.
        workspace.listFiles()?.filter { it.isDirectory && it.name.startsWith("candidate-") }
            ?.sortedByDescending { it.lastModified() }?.forEach { cached ->
                val yamlFile = File(cached, "subscription.yaml")
                val manifestFile = File(cached, "manifest.json")
                val commitFile = File(cached, "candidate-commit.txt")
                if (!yamlFile.isFile || !manifestFile.isFile || !commitFile.isFile) return@forEach
                val commit = commitFile.readText(Charsets.UTF_8).trim()
                if (!commit.matches(Regex("(?i)^[a-f0-9]{40}$"))) return@forEach
                val bytes = runCatching { yamlFile.readBytes() }.getOrNull() ?: return@forEach
                val hash = sha256(bytes)
                if (cached.name != "candidate-$hash") return@forEach
                val manifest = runCatching { JSONObject(manifestFile.readText(Charsets.UTF_8)) }.getOrNull() ?: return@forEach
                if (manifest.optString("candidate_sha256").lowercase() != hash) return@forEach
                val count = String(bytes, Charsets.UTF_8).lineSequence().count { it.matches(Regex("^-\\s+\\S.*$")) }
                val nodes = manifest.optJSONArray("nodes") ?: return@forEach
                if (count <= 0 || count != nodes.length()) return@forEach
                return CandidateSubscriptionBundle(commit, yamlFile, manifestFile, hash, bytes.size, count)
            }

        val branchBytes = request(GitHubBackendEndpoints.branch(config), GITHUB_JSON)
        val branchJson = String(branchBytes, Charsets.UTF_8)
        val branch = JSONObject(branchJson)
        // Prefer structured JSON parsing. The strict fallback handles Android local-test
        // JSON implementations that may return null for a nested JSONObject value.
        val candidateCommit = branch.optJSONObject("commit")?.optString("sha", "")
            ?.trim()?.takeIf { it.isNotBlank() }
            ?: Regex(""""sha"\s*:\s*"([a-fA-F0-9]{40})"""")
                .find(branchJson)?.groupValues?.getOrNull(1)
                ?: throw IllegalArgumentException("GitHub branch response is missing commit.sha")
        require(candidateCommit.matches(Regex("(?i)^[a-f0-9]{40}$"))) {
            "GitHub returned an invalid data-branch head commit"
        }

        fun readSnapshot(path: String): ByteArray = request(
            rawSnapshotUrl(path, candidateCommit),
            GITHUB_RAW
        ).also { require(it.isNotEmpty()) { "Candidate snapshot file is empty: $path" } }

        val candidateBytes = readSnapshot("candidate/subscription.yaml")
        val manifestBytes = readSnapshot("candidate/manifest.json")
        val yaml = String(candidateBytes, Charsets.UTF_8)
        require(yaml.lineSequence().any { it.trim() == "proxies:" }) {
            "Candidate subscription is not a valid proxies YAML document"
        }
        val candidateCount = yaml.lineSequence().count { it.matches(Regex("^-\\s+\\S.*$")) }
        val manifestText = String(manifestBytes, Charsets.UTF_8)
        val manifest = JSONObject(manifestText)
        // Android's lightweight local-test JSONObject may not expose opt(key) reliably;
        // read this required scalar directly from the validated JSON text.
        val schemaToken = Regex("""\"schema_version\"\s*:\s*(\"[^\"]*\"|[-+]?[0-9]+(?:\.[0-9]+)?)""")
            .find(manifestText)?.groupValues?.getOrNull(1)
        val schemaVersion = schemaToken?.trim('"')?.toBigDecimalOrNull()
            ?.let { runCatching { it.intValueExact() }.getOrNull() }
        require(schemaVersion == 1) {
            "Unsupported candidate manifest schema (token=$schemaToken)"
        }
        val hashToken = Regex("""\"candidate_sha256\"\s*:\s*\"([a-fA-F0-9]{64})\"""")
            .find(manifestText)?.groupValues?.getOrNull(1)
        val expectedHash = hashToken?.lowercase().orEmpty()
        val actualHash = sha256(candidateBytes)
        require(expectedHash.matches(Regex("^[a-f0-9]{64}$")) && expectedHash == actualHash) {
            "Candidate subscription SHA256 does not match its manifest"
        }
        val nodeArray = manifest.optJSONArray("nodes")
            ?: throw IllegalArgumentException("Candidate manifest is missing nodes")
        val names = HashSet<String>()
        val ids = HashSet<String>()
        for (index in 0 until nodeArray.length()) {
            val node = nodeArray.optJSONObject(index)
                ?: throw IllegalArgumentException("Candidate manifest contains an invalid node entry")
            val name = node.optString("name", "").trim()
            val id = node.optString("node_id", "").trim()
            require(name.isNotBlank() && id.matches(Regex("(?i)^[a-f0-9]{20}$"))) {
                "Candidate manifest contains an invalid node identity"
            }
            require(names.add(name) && ids.add(id)) {
                "Candidate manifest contains duplicate node names or IDs"
            }
        }
        require(candidateCount > 0 && candidateCount == nodeArray.length()) {
            "Candidate YAML proxy count ($candidateCount) does not match manifest node count (${nodeArray.length()})"
        }

        val target = File(workspace, "candidate-$actualHash")
        val candidateFile = File(target, "subscription.yaml")
        val manifestFile = File(target, "manifest.json")
        val existingIsValid = target.isDirectory &&
            candidateFile.isFile && manifestFile.isFile &&
            sha256(candidateFile.readBytes()) == actualHash &&
            manifestFile.readBytes().contentEquals(manifestBytes)
        if (!existingIsValid) {
            if (target.exists()) require(target.deleteRecursively()) {
                "Cannot replace incomplete local candidate snapshot"
            }
            val staging = File(workspace, ".candidate-$actualHash.tmp")
            if (staging.exists()) require(staging.deleteRecursively()) {
                "Cannot remove interrupted candidate staging directory"
            }
            require(staging.mkdirs()) { "Cannot create candidate staging directory" }
            try {
                atomicWrite(File(staging, "subscription.yaml"), candidateBytes)
                atomicWrite(File(staging, "manifest.json"), manifestBytes)
                atomicWrite(File(staging, "candidate-commit.txt"), candidateCommit.toByteArray(Charsets.UTF_8))
                try {
                    java.nio.file.Files.move(
                        staging.toPath(), target.toPath(),
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE
                    )
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    java.nio.file.Files.move(staging.toPath(), target.toPath())
                }
            } finally {
                if (staging.exists()) staging.deleteRecursively()
            }
        }
        return CandidateSubscriptionBundle(
            candidateCommit, candidateFile, manifestFile, actualHash,
            candidateBytes.size, candidateCount
        )
    }

    /** Reads the current receipt via the Contents API, so slash-containing branch names remain safe. */
    fun readLatestReceipt(): LatestReceipt = try {
        readLatestReceiptRequired()
    } catch (error: IllegalStateException) {
        if (error.message?.startsWith("GitHub read failed (HTTP 404)") == true) {
            LatestReceipt(null, null, null, null, null)
        } else throw error
    }

    private fun readLatestReceiptRequired(): LatestReceipt {
        // Verify the repository and configured branch first. A missing receipt must not
        // make a nonexistent repository or mistyped branch look like a successful connection.
        val branchUrl = GitHubBackendEndpoints.branch(config)
        request(branchUrl, GITHUB_JSON)

        val contentsUrl = GitHubBackendEndpoints.contentsFile(
            config, "receipts/latest.json", config.dataBranch
        )
        val contents = JSONObject(String(request(contentsUrl, GITHUB_JSON), Charsets.UTF_8))
        val encoded = contents.optString("content", "").replace("\n", "").replace("\r", "").trim()
        require(encoded.isNotEmpty()) { "GitHub returned an empty latest receipt file" }
        val receiptBytes = java.util.Base64.getMimeDecoder().decode(encoded)
        val receipt = JSONObject(String(receiptBytes, Charsets.UTF_8))
        val (owner, repository) = config.ownerAndRepository
        val historyUrl = "https://api.github.com/repos/$owner/$repository/commits" +
            "?sha=${encodeQuery(config.dataBranch)}&path=receipts/latest.json&per_page=1&page=1"
        val history = JSONArray(String(request(historyUrl, GITHUB_JSON), Charsets.UTF_8))
        val receiptCommit = if (history.length() > 0) {
            history.getJSONObject(0).optString("sha").takeIf { it.isNotBlank() }
        } else null
        return LatestReceipt(
            // The current Termux receipt schema has no receipt_id field; use its exact-byte SHA256.
            receiptId = sha256(receiptBytes),
            candidateCommit = receipt.optString("candidate_commit").takeIf { it.isNotBlank() },
            candidateSha256 = receipt.optString("candidate_sha256").takeIf { it.isNotBlank() },
            status = receipt.optString("status").takeIf { it.isNotBlank() },
            receiptCommit = receiptCommit
        )
    }

    private fun rawSnapshotUrl(path: String, commit: String): String {
        val (owner, repository) = config.ownerAndRepository
        val encodedPath = path.split('/').joinToString("/") {
            java.net.URLEncoder.encode(it, Charsets.UTF_8.name()).replace("+", "%20")
        }
        return "https://raw.githubusercontent.com/$owner/$repository/$commit/$encodedPath"
    }

    private data class SshSnapshot(
        val commit: String,
        val files: Map<String, ByteArray>
    )

    @Volatile
    private var sshSnapshotCache: SshSnapshot? = null

    /**
     * All production reads use the repository's SSH transport on port 443.
     * This avoids unauthenticated REST API limits and keeps reads on the same
     * authenticated path as receipt publication. Tests may inject requestOverride.
     */
    private fun request(url: String, accept: String): ByteArray {
        requestOverride?.let { return it(url, accept) }
        return readFromSsh(url)
    }

    private fun readFromSsh(url: String): ByteArray {
        val snapshot = sshSnapshotCache ?: synchronized(this) {
            sshSnapshotCache ?: loadSshSnapshot().also { sshSnapshotCache = it }
        }
        val uri = URI(url)
        val path = uri.path.trim('/')
        val repositoryPath = when {
            uri.host.equals("api.github.com", true) -> {
                val marker = "/repos/${config.ownerAndRepository.first}/${config.ownerAndRepository.second}/"
                require(uri.path.startsWith(marker)) { "Unexpected GitHub API repository URL" }
                uri.path.removePrefix(marker)
            }
            uri.host.equals("raw.githubusercontent.com", true) -> {
                val segments = path.split('/')
                require(segments.size >= 4) { "Invalid raw GitHub URL" }
                require(segments[0] == config.ownerAndRepository.first &&
                    segments[1] == config.ownerAndRepository.second) { "Raw GitHub URL repository mismatch" }
                segments.drop(3).joinToString("/")
            }
            else -> throw IllegalArgumentException("Unsupported GitHub read host: ${uri.host}")
        }

        if (uri.host.equals("api.github.com", true)) {
            if (repositoryPath.startsWith("branches/")) {
                return """{"commit":{"sha":"${snapshot.commit}"}}""".toByteArray(Charsets.UTF_8)
            }
            if (repositoryPath.startsWith("contents/")) {
                val filePath = repositoryPath.removePrefix("contents/")
                val bytes = snapshot.files[filePath]
                    ?: throw IllegalStateException("GitHub read failed (HTTP 404): remote file not found: $filePath")
                val encoded = Base64.getEncoder().encodeToString(bytes)
                return """{"content":"$encoded","encoding":"base64","path":"$filePath"}""".toByteArray(Charsets.UTF_8)
            }
            if (repositoryPath == "commits") {
                return """[{"sha":"${snapshot.commit}"}]""".toByteArray(Charsets.UTF_8)
            }
            throw IllegalArgumentException("Unsupported GitHub API read path: $repositoryPath")
        }

        return snapshot.files[repositoryPath]
            ?: throw IllegalStateException("GitHub read failed (HTTP 404): remote file not found: $repositoryPath")
    }

    private fun loadSshSnapshot(): SshSnapshot {
        val privateFilesDir = filesDir
            ?: throw IllegalStateException("请先在 App 中生成 SSH 密钥，并将公钥添加到 GitHub Deploy keys")
        val keyInfo = SshDeployKeyManager(privateFilesDir).getOrCreate()
        val sshDir = File(privateFilesDir, "ssh").apply { check(isDirectory || mkdirs()) }
        val identity = File(sshDir, "id_rsa")
        if (!identity.isFile || !identity.readBytes().contentEquals(keyInfo.privateKeyFile.readBytes())) {
            identity.writeBytes(keyInfo.privateKeyFile.readBytes())
            identity.setReadable(false, false)
            identity.setWritable(false, false)
            check(identity.setReadable(true, true) && identity.setWritable(true, true)) {
                "无法设置 SSH 私钥权限"
            }
        }
        val knownHosts = File(sshDir, "known_hosts")
        val pins = listOf(
            "[ssh.github.com]:443 ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl",
            "[ssh.github.com]:443 ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItMjU2AAAAIbmlzdHAyNTYAAABBBEmKSENjQEezOmxkZMy7opKgwFB9nkt5YRrYMjNuG5N87uRgg6CLrbo5wAdT/y6v0mKV0U2w0WZ2YB/++Tpockg=",
            "[ssh.github.com]:443 ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABgQCj7ndNxQowgcQnjshcLrqPEiiphnt+VTTvDP6mHBL9j1aNUkY4Ue1gvwnGLVlOhGeYrnZaMgRK6+PKCUXaDbC7qtbW8gIkhL7aGCsOr/C56SJMy/BCZfxd1nWzAOxSDPgVsmerOBYfNqltV9/hWCqBywINIR+5dIg6JTJ72pcEpEjcYgXkE2YEFXV1JHnsKgbLWNlhScqb2UmyRkQyytRLtL+38TGxkxCflmO+5Z8CSSNY7GidjMIZ7Q4zMjA2n1nGrlTDkzwDCsw+wqFPGQA179cnfGWOWRVruj16z6XyvxvjJwbz0wQZ75XK5tKSb7FNyeIEs4TT4jk+S4dhPeAUC5y+bDYirYgM4GC7uEnztnZyaVWQ7B381AK4Qdrwt51ZqExKbQpTUNn+EjqoTwvqNj4kqx5QUCI0ThS/YkOxJCXmPUWZbhjpCg56i+2aB6CmK2JGhn57K5mj0MNdBXA4/WnwH6XoPWJzK5Nyu2zB3nAZp+S5hpQs+p1vN1/wsjk="        )
        knownHosts.writeText(pins.joinToString("\n", postfix = "\n"), Charsets.US_ASCII)
        // Android has no conventional Linux user home. Configure Apache SSHD's
        // process-wide resolver to use this app's private files directory.
        PathUtils.setUserHomeFolderResolver { privateFilesDir.toPath() }
        val factory = SshdSessionFactoryBuilder()
            .setHomeDirectory(privateFilesDir)
            .setSshDirectory(sshDir)
            // Do not rely on SSHD's platform/home-directory discovery on Android.
            // Pin the exact app-private known_hosts file and identity file explicitly.
            .setDefaultKnownHostsFiles { listOf(knownHosts.toPath()) }
            .setDefaultIdentities { listOf(identity.toPath()) }
            .setPreferredAuthentications("publickey")
            .build(null)
        val (owner, repository) = config.ownerAndRepository
        val remote = "ssh://git@ssh.github.com:443/$owner/$repository.git"
        val tempRoot = File(privateFilesDir, "read-work").apply { check(isDirectory || mkdirs()) }
        val work = File(tempRoot, java.util.UUID.randomUUID().toString())
        var git: Git? = null
        try {
            // Probe SSH authentication and repository read access separately before cloning.
            // If this fails, the error is attributable to SSH transport/authentication,
            // not candidate parsing or local snapshot handling.
            val remoteHeads = Git.lsRemoteRepository()
                .setRemote(remote)
                .setHeads(true)
                .setTransportConfigCallback { transport ->
                    if (transport is SshTransport) transport.sshSessionFactory = factory
                }
                .call()
            check(remoteHeads.any { it.name == "refs/heads/${config.dataBranch}" }) {
                "SSH 已连接，但远端仓库中不存在数据分支：${config.dataBranch}"
            }

            git = Git.cloneRepository().setURI(remote).setDirectory(work)
                .setBranch(config.dataBranch).setDepth(1)
                .setTransportConfigCallback { transport ->
                    if (transport is SshTransport) transport.sshSessionFactory = factory
                }.call()
            val repositoryHandle = git.repository
            val head = repositoryHandle.resolve(Constants.HEAD) ?: error("无法读取 SSH 数据分支 HEAD")
            val files = linkedMapOf<String, ByteArray>()
            RevWalk(repositoryHandle).use { walk ->
                val commit = walk.parseCommit(head)
                listOf(
                    "candidate/subscription.yaml",
                    "candidate/manifest.json",
                    "receipts/latest.json"
                ).forEach { filePath ->
                    val tree = TreeWalk.forPath(repositoryHandle, filePath, commit.tree)
                    if (tree != null) tree.use { files[filePath] = repositoryHandle.open(tree.getObjectId(0)).bytes }
                }
            }
            return SshSnapshot(head.name, files)
        } catch (error: Exception) {
            val causes = generateSequence(error as Throwable?) { it.cause }
                .take(8)
                .mapIndexed { index, cause ->
                    "${index + 1}.${cause.javaClass.simpleName}: ${cause.message ?: "(无详细信息)"}"
                }
                .joinToString(" <- ")
            val detail = when {
                causes.contains("UnknownHostException", ignoreCase = true) ->
                    "DNS 解析 ssh.github.com 失败"
                causes.contains("ConnectException", ignoreCase = true) ||
                    causes.contains("SocketTimeoutException", ignoreCase = true) ->
                    "SSH 443 网络连接失败或超时"
                causes.contains("Host key", ignoreCase = true) ||
                    causes.contains("known_hosts", ignoreCase = true) ->
                    "GitHub SSH 主机密钥验证失败"
                causes.contains("auth", ignoreCase = true) ||
                    causes.contains("publickey", ignoreCase = true) ||
                    causes.contains("Permission denied", ignoreCase = true) ->
                    "SSH 公钥认证失败或 GitHub 未接受当前私钥"
                else -> causes
            }
            throw IllegalStateException(
                "GitHub SSH 443 读取失败（已先执行独立 SSH 远端分支预检）。${detail}",
                error
            )
        } finally {
            git?.close()
            factory.close()
            work.deleteRecursively()
        }
    }

    private fun readBounded(input: java.io.InputStream, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= maxBytes) { "GitHub response exceeds the $maxBytes-byte safety limit" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun atomicWrite(destination: File, bytes: ByteArray) {
        val temporary = File(destination.parentFile, destination.name + ".tmp")
        try {
            temporary.outputStream().use { stream ->
                stream.write(bytes)
                stream.flush()
            }
            java.nio.file.Files.move(
                temporary.toPath(),
                destination.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            )
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun encodeQuery(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name())


    companion object {
        private const val MAX_RESPONSE_BYTES = 32 * 1024 * 1024
        private const val REQUEST_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 500L
        private const val GITHUB_JSON = "application/vnd.github+json"
        private const val GITHUB_RAW = "application/vnd.github.raw+json"
    }
}
