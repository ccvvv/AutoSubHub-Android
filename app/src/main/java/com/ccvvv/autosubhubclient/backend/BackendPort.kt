package com.ccvvv.autosubhubclient.backend

import com.ccvvv.autosubhubclient.core.RemotePublicationProof
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

const val CURRENT_BACKEND_CONTRACT_VERSION = 1

data class BackendConfig(
    val repositoryUrl: String,
    val dataBranch: String,
    val contractVersion: Int = CURRENT_BACKEND_CONTRACT_VERSION
) {
    fun validate() {
        require(repositoryUrl.isNotBlank()) { "Backend URL is required" }
        val uri = java.net.URI(repositoryUrl)
        require(uri.scheme.equals("https", ignoreCase = true)) {
            "Backend URL must use HTTPS"
        }
        require(uri.host?.equals("github.com", ignoreCase = true) == true) {
            "Only GitHub repository URLs are supported by the first adapter"
        }
        require(uri.userInfo == null && uri.port == -1 && uri.query == null && uri.fragment == null) {
            "Backend URL must not contain credentials, a port, query, or fragment"
        }
        require(uri.path.endsWith(".git").not()) {
            "Use the repository web URL without the optional .git suffix"
        }
        val segments = uri.path.trim('/').split('/')
        require(segments.size == 2 && segments.all { it.matches(Regex("^[A-Za-z0-9_.-]+$")) }) {
            "Expected https://github.com/owner/repository"
        }
        require(segments.none { it == "." || it == ".." }) {
            "Invalid GitHub repository path"
        }
        require(isValidGitBranch(dataBranch)) { "Invalid Git data branch name" }
        require(contractVersion == CURRENT_BACKEND_CONTRACT_VERSION) {
            "Unsupported backend contract version: $contractVersion"
        }
    }

    val identity: String
        get() = "${repositoryUrl.trimEnd('/')}#${dataBranch}"

    val ownerAndRepository: Pair<String, String>
        get() {
            validate()
            val segments = java.net.URI(repositoryUrl).path.trim('/').split('/')
            return segments[0] to segments[1]
        }
}

/** Git's ref-name constraints, applied before a branch can enter a URL or command argument. */
internal fun isValidGitBranch(branch: String): Boolean {
    if (branch.isBlank() || branch.startsWith('/') || branch.endsWith('/') ||
        branch.startsWith('.') || branch.endsWith('.') || branch.contains("//") ||
        branch.contains("..") || branch.contains("@{") || branch == "@" ||
        branch.endsWith(".lock", ignoreCase = true)
    ) return false

    return branch.none { char ->
        char.isWhitespace() || char.code < 0x20 || char.code == 0x7f ||
            char in "~^:?*[\\\\"
    }
}

/**
 * Centralizes GitHub REST/raw URLs. Paths are encoded segment-by-segment so a slash
 * remains a path separator while branch names are encoded as one query value.
 */
object GitHubBackendEndpoints {
    fun branch(config: BackendConfig): String {
        config.validate()
        val (owner, repository) = config.ownerAndRepository
        val encodedBranch = query(config.dataBranch).replace("+", "%20")
        return "https://api.github.com/repos/$owner/$repository/branches/$encodedBranch"
    }

    fun contentsFile(config: BackendConfig, relativePath: String, ref: String): String {
        config.validate()
        require(ref.matches(Regex("(?i)^[a-f0-9]{40}$")) || isValidGitBranch(ref)) {
            "Invalid content ref"
        }
        val (owner, repository) = config.ownerAndRepository
        val path = encodePath(relativePath)
        return "https://api.github.com/repos/$owner/$repository/contents/$path?ref=${query(ref)}"
    }

    private fun encodePath(path: String): String {
        require(path.isNotBlank() && !path.startsWith('/')) { "A relative file path is required" }
        val segments = path.split('/')
        require(segments.all { it.isNotBlank() && it != "." && it != ".." }) {
            "Unsafe repository file path"
        }
        return segments.joinToString("/") { query(it).replace("+", "%20") }
    }

    private fun query(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}

/** Transport-neutral backend boundary. Implementations own repository paths and Git operations. */
data class BackendCapabilities(
    val contractVersion: Int,
    val backendIdentity: String,
    val supportsPendingQueueDownload: Boolean,
    val supportsRemoteReceiptVerification: Boolean
) {
    fun requireCompatible() {
        require(contractVersion == CURRENT_BACKEND_CONTRACT_VERSION) {
            "Backend contract v$contractVersion is not supported by this client"
        }
        require(backendIdentity.isNotBlank())
    }
}


data class CandidateSubscriptionBundle(
    val candidateCommit: String,
    val candidateFile: File,
    val manifestFile: File,
    val candidateSha256: String,
    val byteCount: Int,
    val candidateCount: Int
) {
    init {
        require(candidateCommit.matches(Regex("(?i)^[a-f0-9]{40}$")))
        require(candidateFile.isFile && manifestFile.isFile)
        require(candidateSha256.matches(Regex("(?i)^[a-f0-9]{64}$")))
        require(byteCount > 0 && candidateCount > 0)
    }
}

data class ReceiptBundle(
    val taskId: String,
    val receiptId: String,
    val receiptFile: File,
    val sidecarFiles: List<File>,
    val contentSha256: String,
    val expectedCandidateCount: Int,
    val coveredCandidateCount: Int
) {
    init {
        require(taskId.isNotBlank() && receiptId.isNotBlank())
        require(contentSha256.matches(Regex("(?i)^[a-f0-9]{64}$")))
        require(expectedCandidateCount >= 0)
        require(coveredCandidateCount == expectedCandidateCount) {
            "Receipt bundle does not cover every candidate"
        }
        require(sidecarFiles.size >= 2) { "Candidate and manifest sidecars are required" }
    }
}

/** Upload acknowledgement is not proof of publication; verification is a separate call. */
data class UploadAcknowledgement(
    val receiptId: String,
    val remoteRevision: String,
    val acceptedAtEpochMillis: Long
) {
    init {
        require(receiptId.isNotBlank())
        require(remoteRevision.isNotBlank())
        require(acceptedAtEpochMillis > 0)
    }
}

data class BackendPublishedStatus(
    val latestRevision: String?,
    val latestReceiptId: String?,
    val latestCandidateCommit: String?,
    val latestContentSha256: String?
)

/**
 * Invoke from a background task executor; implementations must not block the UI thread.
 */
interface BackendPort {
    fun getBackendCapabilities(): BackendCapabilities
    fun publishReceipt(bundle: ReceiptBundle): UploadAcknowledgement
    fun verifyPublishedReceipt(receiptId: String, contentSha256: String): RemotePublicationProof?
    fun readPublishedStatus(): BackendPublishedStatus
}

/** Prevent a valid proof from another backend or another receipt being reused. */
fun RemotePublicationProof.matches(
    config: BackendConfig,
    expectedReceiptId: String,
    expectedContentSha256: String
): Boolean =
    isValid() &&
        backendIdentity == config.identity &&
        receiptId == expectedReceiptId &&
        contentSha256.equals(expectedContentSha256, ignoreCase = true)
