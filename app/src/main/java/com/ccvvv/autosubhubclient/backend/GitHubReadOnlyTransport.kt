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

    data class LatestReceipt(
        val receiptId: String?,
        val candidateCommit: String?,
        val candidateSha256: String?,
        val status: String?,
        val receiptCommit: String?
    )


    /**
     * Downloads the cloud-prepared cumulative pending queue. The phone does not enumerate
     * candidate history or merge snapshots; it only verifies and stores the cloud output.
     */
    fun fetchPendingQueue(workspace: File): PendingQueueBundle {
        require(workspace.exists() || workspace.mkdirs()) { "Cannot create pending queue workspace" }
        require(workspace.isDirectory) { "Pending queue workspace is not a directory" }
        // Distinguish an absent queue from a mistyped repository or data branch.
        request(GitHubBackendEndpoints.branch(config), GITHUB_JSON)

        fun readContents(path: String): ByteArray {
            val url = GitHubBackendEndpoints.contentsFile(config, path, config.dataBranch)
            // The raw media type avoids the Contents API's base64/JSON size overhead and
            // supports larger candidate YAML files. The ref remains a query parameter.
            val bytes = try {
                request(url, GITHUB_RAW)
            } catch (error: IllegalStateException) {
                if (error.message?.startsWith("GitHub read failed (HTTP 404)") == true) {
                    throw IllegalStateException(
                        "Cloud pending queue is not published or is incomplete (missing $path)",
                        error
                    )
                }
                throw error
            }
            require(bytes.isNotEmpty()) { "Cloud queue file is empty: $path" }
            return bytes
        }

        val candidateBytes = readContents("candidate/pending.yaml")
        val manifestBytes = readContents("candidate/pending-manifest.json")
        val metadataBytes = readContents("candidate/pending-meta.json")
        val manifest = JSONObject(String(manifestBytes, Charsets.UTF_8))
        val metadata = JSONObject(String(metadataBytes, Charsets.UTF_8))
        val actualHash = sha256(candidateBytes)
        val expectedHash = manifest.optString("candidate_sha256", "").lowercase()
        val metadataHash = metadata.optString("candidate_sha256", "").lowercase()
        require(expectedHash.matches(Regex("^[a-f0-9]{64}$")) &&
            expectedHash == actualHash && metadataHash == actualHash) {
            "Cloud pending queue SHA256 verification failed"
        }

        val nodes = manifest.optJSONArray("nodes")
            ?: throw IllegalArgumentException("Cloud pending manifest is missing nodes")
        val names = HashSet<String>()
        val ids = HashSet<String>()
        for (index in 0 until nodes.length()) {
            val node = nodes.getJSONObject(index)
            val name = node.optString("name", "")
            val id = node.optString("node_id", "")
            require(name.isNotBlank() && id.matches(Regex("(?i)^[a-f0-9]{64}$"))) {
                "Cloud pending manifest contains an invalid node"
            }
            require(names.add(name) && ids.add(id)) {
                "Cloud pending manifest contains duplicate node names or IDs"
            }
        }

        val candidateCommit = metadata.optString("candidate_commit", "")
        require(candidateCommit.matches(Regex("(?i)^[a-f0-9]{40}$"))) {
            "Cloud pending metadata has an invalid candidate commit"
        }
        val commitArray = metadata.optJSONArray("candidate_commits")
            ?: throw IllegalArgumentException("Cloud pending metadata is missing candidate commits")
        val commits = (0 until commitArray.length()).map { commitArray.getString(it) }
        require(commits.isNotEmpty() && commits.all { it.matches(Regex("(?i)^[a-f0-9]{40}$")) } &&
            commits.last() == candidateCommit) {
            "Cloud pending metadata has an invalid candidate commit list"
        }
        require(metadata.optInt("candidate_count", -1) == nodes.length()) {
            "Cloud pending node count does not match its manifest"
        }

        // Keep each queue generation immutable. Stage all files together and publish the
        // directory only after every byte has been written, so a failed update cannot mix
        // a new YAML file with an older manifest or metadata file.
        val target = File(workspace, "pending-$actualHash")
        val candidateFile = File(target, "pending.yaml")
        val manifestFile = File(target, "pending-manifest.json")
        val metadataFile = File(target, "pending-meta.json")
        val existingIsValid = target.isDirectory &&
            candidateFile.isFile && manifestFile.isFile && metadataFile.isFile &&
            sha256(candidateFile.readBytes()) == actualHash &&
            manifestFile.readBytes().contentEquals(manifestBytes) &&
            metadataFile.readBytes().contentEquals(metadataBytes)
        if (!existingIsValid) {
            if (target.exists()) require(target.deleteRecursively()) {
                "Cannot replace an incomplete local pending queue generation"
            }
            val staging = File(workspace, ".pending-$actualHash.tmp")
            if (staging.exists()) require(staging.deleteRecursively()) {
                "Cannot remove an interrupted pending queue staging directory"
            }
            require(staging.mkdirs()) { "Cannot create pending queue staging directory" }
            try {
                atomicWrite(File(staging, "pending.yaml"), candidateBytes)
                atomicWrite(File(staging, "pending-manifest.json"), manifestBytes)
                atomicWrite(File(staging, "pending-meta.json"), metadataBytes)
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
        return PendingQueueBundle(candidateCommit, commits, candidateFile, manifestFile, metadataFile, actualHash, nodes.length())
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
        private const val GITHUB_RAW = "application/vnd.github.raw+json"
    }
}
