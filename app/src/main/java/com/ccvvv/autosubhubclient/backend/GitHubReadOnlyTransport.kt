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
    private val readTimeoutMs: Int = 45_000,
    private val requestOverride: ((String, String) -> ByteArray)? = null
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
        val branch = JSONObject(
            String(request(GitHubBackendEndpoints.branch(config), GITHUB_JSON), Charsets.UTF_8)
        )
        // GitHub's branch endpoint returns the commit object at the top level under "commit".
        // Use required accessors so malformed or unexpected payloads fail with a precise cause.
        val candidateCommit = runCatching {
            branch.getJSONObject("commit").getString("sha").trim()
        }.getOrElse {
            throw IllegalArgumentException("GitHub branch response is missing commit.sha", it)
        }
        require(candidateCommit.matches(Regex("(?i)^[a-f0-9]{40}$"))) {
            "GitHub returned an invalid data-branch head commit"
        }

        fun readSnapshot(path: String): ByteArray = request(
            GitHubBackendEndpoints.contentsFile(config, path, candidateCommit),
            GITHUB_RAW
        ).also { require(it.isNotEmpty()) { "Candidate snapshot file is empty: $path" } }

        val candidateBytes = readSnapshot("candidate/subscription.yaml")
        val manifestBytes = readSnapshot("candidate/manifest.json")
        val yaml = String(candidateBytes, Charsets.UTF_8)
        require(yaml.lineSequence().any { it.trim() == "proxies:" }) {
            "Candidate subscription is not a valid proxies YAML document"
        }
        val candidateCount = yaml.lineSequence().count { it.matches(Regex("^-\\s+.+$")) }
        val manifest = JSONObject(String(manifestBytes, Charsets.UTF_8))
        require(manifest.optInt("schema_version", -1) == 1) {
            "Unsupported candidate manifest schema"
        }
        val expectedHash = manifest.optString("candidate_sha256", "").lowercase()
        val actualHash = sha256(candidateBytes)
        require(expectedHash.matches(Regex("^[a-f0-9]{64}$")) && expectedHash == actualHash) {
            "Candidate subscription SHA256 does not match its manifest"
        }
        val nodes = manifest.optJSONArray("nodes")
            ?: throw IllegalArgumentException("Candidate manifest is missing nodes")
        val names = HashSet<String>()
        val ids = HashSet<String>()
        for (index in 0 until nodes.length()) {
            val node = nodes.getJSONObject(index)
            val name = node.optString("name", "")
            val id = node.optString("node_id", "")
            require(name.isNotBlank() && id.matches(Regex("(?i)^[a-f0-9]{20}$"))) {
                "Candidate manifest contains an invalid node identity"
            }
            require(names.add(name) && ids.add(id)) {
                "Candidate manifest contains duplicate node names or IDs"
            }
        }
        require(candidateCount == nodes.length() &&
            manifest.optInt("unique_candidates", -1) == nodes.length()) {
            "Candidate YAML and manifest node counts do not match"
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

    private fun request(url: String, accept: String): ByteArray {
        requestOverride?.let { return it(url, accept) }
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
        private const val GITHUB_RAW = "application/vnd.github.raw+json"
    }
}
