package com.ccvvv.autosubhubclient.backend

import com.ccvvv.autosubhubclient.core.RemotePublicationProof

data class VerifiedCursorAdvance(
    val backendIdentity: String,
    val candidateCommit: String,
    val receiptCommit: String,
    val receiptId: String,
    val contentSha256: String,
    val verifiedAtEpochMillis: Long
)

object BackendCursorPolicy {
    fun validate(
        backendIdentity: String,
        candidateCommit: String,
        receiptCommit: String,
        proof: RemotePublicationProof
    ): VerifiedCursorAdvance {
        val commitPattern = Regex("(?i)^[a-f0-9]{40}$")
        require(backendIdentity.isNotBlank())
        require(candidateCommit.matches(commitPattern)) { "Invalid candidate commit" }
        require(receiptCommit.matches(commitPattern)) { "Invalid receipt commit" }
        require(proof.isValid()) { "Remote publication proof is invalid" }
        require(proof.backendIdentity == backendIdentity) { "Proof belongs to another backend" }
        require(proof.remoteRevision.equals(receiptCommit, ignoreCase = true)) {
            "Proof revision does not match the accepted receipt commit"
        }
        return VerifiedCursorAdvance(
            backendIdentity = backendIdentity,
            candidateCommit = candidateCommit.lowercase(),
            receiptCommit = receiptCommit.lowercase(),
            receiptId = proof.receiptId,
            contentSha256 = proof.contentSha256.lowercase(),
            verifiedAtEpochMillis = proof.verifiedAtEpochMillis
        )
    }
}
