package com.ccvvv.autosubhubclient.backend

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
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
    private val readTimeoutMs: Int = 45_000
) {
    init {
        config.validate()
        require(connectTimeoutMs in 1..120_000)
        require(readTimeoutMs in 1..180_000)
    }

    data class CandidateSnapshot(
        val commit: String,
        val candidateFile: File,
        val manifestFile: File,
        val candidateSha256: String,
        val proxyCount: Int
    )

    data class LatestReceipt(
        val receiptId: String?,
        val candidateCommit: String?,
        val candidateSha256: String?,
        val status: String?,
        val receiptCommit: String?
    )

    /** Downloads an immutable candidate snapshot and verifies its SHA256 against manifest.json. */
    fun fetchCandidateSnapshot(commit: String, workspace: File): CandidateSnapshot {
        require(commit.matches(Regex("(?i)^[a-f0-9]{40}$"))) { "Invalid candidate commit" }
        require(workspace.exists() || workspace.mkdirs()) { "Cannot create candidate workspace" }
        require(workspace.isDirectory) { "Candidate workspace is not a directory" }

        val candidateBytes = request(
            GitHubBackendEndpoints.rawSnapshotFile(config, commit, "candidate/subscription.yaml"),
            GITHUB_RAW
        )
        val manifestBytes = request(
            GitHubBackendEndpoints.rawSnapshotFile(config, commit, "candidate/manifest.json"),
            GITHUB_RAW
        )
        val manifest = JSONObject(String(manifestBytes, Charsets.UTF_8))
        val expectedHash = manifest.optString("candidate_sha256", "").lowercase()
        val actualHash = sha256(candidateBytes)
        require(expectedHash.matches(Regex("^[a-f0-9]{64}$")) && expectedHash == actualHash) {
            "Candidate SHA256 does not match the immutable manifest at $commit"
        }
        val nodes = manifest.optJSONArray("nodes")
            ?: throw IllegalArgumentException("Candidate manifest is missing nodes")
        val names = HashSet<String>()
        val ids = HashSet<String>()
        for (index in 0 until nodes.length()) {
            val node = nodes.getJSONObject(index)
            val name = node.optString("name", "")
            val id = node.optString("node_id", "")
            require(name.isNotBlank() && id.matches(Regex("(?i)^[a-f0-9]{64}$"))) {
                "Candidate manifest contains an invalid node entry"
            }
            require(names.add(name) && ids.add(id)) {
                "Candidate manifest contains duplicate names or node IDs"
            }
        }

        val batchDir = File(workspace, commit)
        require(batchDir.exists() || batchDir.mkdirs()) { "Cannot create snapshot directory" }
        val candidateFile = File(batchDir, "subscription.yaml")
        val manifestFile = File(batchDir, "manifest.json")
        atomicWrite(candidateFile, candidateBytes)
        atomicWrite(manifestFile, manifestBytes)
        return CandidateSnapshot(commit, candidateFile, manifestFile, actualHash, nodes.length())
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

    private fun request(url: String, accept: String): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", accept)
            connection.setRequestProperty("User-Agent", "AutoSubHub-Android/0.1")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { readBounded(it, MAX_RESPONSE_BYTES) } ?: ByteArray(0)
            if (status !in 200..299) {
                val detail = String(body, Charsets.UTF_8).take(500)
                throw IllegalStateException("GitHub read failed (HTTP $status): $detail")
            }
            return body
        } finally {
            connection.disconnect()
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
        private const val GITHUB_JSON = "application/vnd.github+json"
        private const val GITHUB_RAW = "application/vnd.github.raw"
    }
}
