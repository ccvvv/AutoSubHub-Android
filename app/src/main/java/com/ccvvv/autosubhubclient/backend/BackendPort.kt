package com.ccvvv.autosubhubclient.backend

import com.ccvvv.autosubhubclient.core.RemotePublicationProof
import java.io.File

const val CURRENT_BACKEND_CONTRACT_VERSION = 1

data class BackendConfig(
    val repositoryUrl: String,
    val dataBranch: String,
    val contractVersion: Int = CURRENT_BACKEND_CONTRACT_VERSION
) {
    fun validate() {
        require(repositoryUrl.startsWith("https://")) { "Backend URL must use HTTPS" }
        require(repositoryUrl.endsWith(".git").not()) {
            "Use the repository web URL without the optional .git suffix"
        }
        val uri = java.net.URI(repositoryUrl)
        require(uri.host?.equals("github.com", ignoreCase = true) == true) {
            "Only GitHub repository URLs are supported by the first adapter"
        }
        val segments = uri.path.trim('/').split('/')
        require(segments.size == 2 && segments.all { it.isNotBlank() }) {
            "Expected https://github.com/owner/repository"
        }
        require(dataBranch.isNotBlank() && !dataBranch.contains(' ')) {
            "A valid data branch is required"
        }
        require(contractVersion == CURRENT_BACKEND_CONTRACT_VERSION) {
            "Unsupported backend contract version: $contractVersion"
        }
    }

    val identity: String
        get() = "${repositoryUrl.trimEnd('/')}#${dataBranch}"
}

data class BackendCapabilities(
    val contractVersion: Int,
    val backendIdentity: String,
    val supportsHistoricalSnapshots: Boolean,
    val supportsRemoteReceiptVerification: Boolean
) {
    fun requireCompatible() {
        require(contractVersion == CURRENT_BACKEND_CONTRACT_VERSION) {
            "Backend contract v$contractVersion is not supported by this client"
        }
        require(backendIdentity.isNotBlank())
    }
}

data class CandidateBatch(
    val batchId: String,
    val cursor: String?,
    val nextCursor: String?,
    val candidateFile: File,
    val manifestFile: File,
    val metadataFile: File?,
    val expectedContentSha256: String
) {
    init {
        require(batchId.isNotBlank())
        require(expectedContentSha256.matches(Regex("(?i)^[a-f0-9]{64}$")))
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
        require(sidecarFiles.isNotEmpty()) { "Receipt sidecars are required" }
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
    val latestContentSha256: String?
)

/**
 * Transport-neutral backend boundary. Implementations own all repository paths,
 * branch mapping, Git commands and remote receipt verification.
 *
 * Invoke from a background task executor; implementations must not block the UI thread.
 */
interface BackendPort {
    fun getBackendCapabilities(): BackendCapabilities
    fun fetchNextCandidateBatch(cursor: String?): CandidateBatch?
    fun fetchCandidateSnapshot(batchId: String): CandidateBatch
    fun publishReceipt(bundle: ReceiptBundle): UploadAcknowledgement
    fun verifyPublishedReceipt(receiptId: String, contentSha256: String): RemotePublicationProof?
    fun readPublishedStatus(): BackendPublishedStatus
}
