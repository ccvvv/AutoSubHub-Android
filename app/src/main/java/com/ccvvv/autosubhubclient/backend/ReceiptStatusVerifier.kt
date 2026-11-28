package com.ccvvv.autosubhubclient.backend

import org.json.JSONObject
import java.security.MessageDigest

data class RemoteReceiptStatus(
    val status: String,
    val verified: Boolean,
    val receiptSha256: String,
    val candidateCommit: String?,
    val detail: String
)

/** The local receipt is only a claim; only exact remote bytes or a matching durable record verify it. */
object ReceiptStatusVerifier {
    fun verify(
        localReceiptBytes: ByteArray,
        remotePendingBytes: ByteArray?,
        remoteProcessedBytes: ByteArray?
    ): RemoteReceiptStatus {
        require(localReceiptBytes.isNotEmpty()) { "Local receipt is empty" }
        val local = JSONObject(String(localReceiptBytes, Charsets.UTF_8))
        require(local.optInt("schema_version") == 1 && local.optString("status") == "completed") {
            "Local receipt is not a completed schema-v1 receipt"
        }
        val digest = sha256(localReceiptBytes)
        val candidate = local.optString("candidate_commit").takeIf { it.isNotBlank() }

        if (remoteProcessedBytes != null) {
            val record = JSONObject(String(remoteProcessedBytes, Charsets.UTF_8))
            val matches = record.optInt("schema_version") == 1 &&
                record.optString("status") == "processed" &&
                record.optString("receipt_sha256").equals(digest, true) &&
                record.optString("candidate_commit") == candidate
            if (!matches) {
                return RemoteReceiptStatus(
                    "unverified", false, digest, candidate,
                    "Remote processed record does not match local receipt SHA/candidate commit"
                )
            }
            return RemoteReceiptStatus(
                "processed", true, digest, candidate,
                "GitHub durable processed record matches exact local receipt SHA"
            )
        }

        if (remotePendingBytes != null && sha256(remotePendingBytes) == digest) {
            val pending = JSONObject(String(remotePendingBytes, Charsets.UTF_8))
            if (pending.optString("candidate_commit") != candidate) {
                return RemoteReceiptStatus(
                    "unverified", false, digest, candidate,
                    "Remote pending receipt candidate commit mismatch"
                )
            }
            return RemoteReceiptStatus(
                "uploaded_pending", true, digest, candidate,
                "GitHub latest receipt matches exact local receipt bytes"
            )
        }

        return RemoteReceiptStatus(
            "unverified", false, digest, candidate,
            "No matching pending receipt or durable processed record exists on GitHub"
        )
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
