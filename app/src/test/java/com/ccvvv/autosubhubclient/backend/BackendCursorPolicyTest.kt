package com.ccvvv.autosubhubclient.backend

import com.ccvvv.autosubhubclient.core.RemotePublicationProof
import org.junit.Assert.assertEquals
import org.junit.Test

class BackendCursorPolicyTest {
    private val backend = "https://github.com/owner/repository#published-subscriptions"
    private val candidateCommit = "a".repeat(40)
    private val receiptCommit = "b".repeat(40)
    private val proof = RemotePublicationProof(
        backendIdentity = backend,
        receiptId = "receipt-1",
        contentSha256 = "c".repeat(64),
        remoteRevision = receiptCommit,
        verifiedAtEpochMillis = 123L,
        completeBundleVerified = true
    )

    @Test
    fun validProofAllowsCursorAdvance() {
        val result = BackendCursorPolicy.validate(
            backend, candidateCommit.uppercase(), receiptCommit.uppercase(), proof
        )
        assertEquals(candidateCommit, result.candidateCommit)
        assertEquals(receiptCommit, result.receiptCommit)
        assertEquals("receipt-1", result.receiptId)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsProofFromDifferentBackend() {
        BackendCursorPolicy.validate(
            "https://github.com/other/repo#published-subscriptions",
            candidateCommit,
            receiptCommit,
            proof
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsProofForDifferentRemoteRevision() {
        BackendCursorPolicy.validate(
            backend,
            candidateCommit,
            "d".repeat(40),
            proof
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMalformedCandidateCommit() {
        BackendCursorPolicy.validate(backend, "bad", receiptCommit, proof)
    }
}
