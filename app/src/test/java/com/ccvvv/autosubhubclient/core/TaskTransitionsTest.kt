package com.ccvvv.autosubhubclient.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskTransitionsTest {
    @Test
    fun completedProbeIsNotEnoughToDeleteArtifacts() {
        val artifact = LocalArtifact("batch-1", "receipts/receipt.json", 100, sha256 = null)
        assertFalse(artifact.isCleanupEligible(TaskState.PROBE_COMPLETED_LOCAL, null))
        assertFalse(artifact.isCleanupEligible(TaskState.UPLOADING, null))
    }

    @Test
    fun onlyVerifiedPublicationMakesArtifactEligible() {
        val proof = RemotePublicationProof(
            backendIdentity = "github.com/owner/repo#published-subscriptions",
            receiptId = "receipt-123",
            contentSha256 = "a".repeat(64),
            remoteRevision = "abc123",
            verifiedAtEpochMillis = 1L,
            completeBundleVerified = true
        )
        val artifact = LocalArtifact("batch-1", "receipts/receipt.json", 100)
        assertTrue(artifact.isCleanupEligible(TaskState.CLEANUP_ELIGIBLE, proof))
    }

    @Test
    fun keepLocalCopyOverridesCleanupEligibility() {
        val proof = RemotePublicationProof("backend", "receipt", "b".repeat(64), "rev", 1L, true)
        val artifact = LocalArtifact("batch-1", "receipts/receipt.json", 100, sha256 = null, keepLocalCopy = true)
        assertFalse(artifact.isCleanupEligible(TaskState.CLEANUP_ELIGIBLE, proof))
    }

    @Test
    fun rejectsIllegalTransitionFromProbeCompletedDirectlyToCleaned() {
        assertFalse(TaskTransitions.canTransition(TaskState.PROBE_COMPLETED_LOCAL, TaskState.CLEANED))
        assertTrue(TaskTransitions.canTransition(TaskState.PUBLISHED_CONFIRMED, TaskState.CLEANUP_ELIGIBLE))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsWorkspaceTraversalPath() {
        LocalArtifact("batch-1", "receipts/../../private-key", 1, sha256 = null)
    }
}
