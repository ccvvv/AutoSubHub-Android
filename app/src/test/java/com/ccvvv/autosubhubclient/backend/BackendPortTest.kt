package com.ccvvv.autosubhubclient.backend

import com.ccvvv.autosubhubclient.core.RemotePublicationProof
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendPortTest {
    private fun config(branch: String = "published-subscriptions") = BackendConfig(
        repositoryUrl = "https://github.com/owner/repository",
        dataBranch = branch
    )

    @Test
    fun acceptsRepositoryUrlWithTheFixedDataBranch() {
        config().validate()
        assertEquals("https://github.com/owner/repository#published-subscriptions", config().identity)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUserSelectedAlternativeBranch() {
        config("release/2026-11").validate()
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsupportedContractVersion() {
        config().copy(contractVersion = 2).validate()
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsRepositoryUrlWithQueryParameters() {
        config().copy(repositoryUrl = "https://github.com/owner/repository?redirect=evil").validate()
    }

    @Test
    fun endpointBuilderUsesTheFixedDataBranchAndPreservesPathSeparators() {
        val branchUrl = GitHubBackendEndpoints.branch(config())
        assertTrue(branchUrl.endsWith("/branches/published-subscriptions"))

        val contentUrl = GitHubBackendEndpoints.contentsFile(
            config(), "candidate/pending-manifest.json", "published-subscriptions"
        )
        assertTrue(contentUrl.contains("/contents/candidate/pending-manifest.json?ref=published-subscriptions"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun endpointBuilderRejectsPathTraversal() {
        GitHubBackendEndpoints.contentsFile(config(), "../secrets.txt", "a".repeat(40))
    }

    @Test
    fun remoteProofMustMatchConfiguredBackendReceiptAndHash() {
        val backend = config()
        val hash = "d".repeat(64)
        val proof = RemotePublicationProof(
            backendIdentity = backend.identity,
            receiptId = "receipt-7",
            contentSha256 = hash,
            remoteRevision = "commit-abc",
            verifiedAtEpochMillis = 123L,
            completeBundleVerified = true
        )

        assertTrue(proof.matches(backend, "receipt-7", hash))
        assertFalse(proof.matches(backend, "receipt-other", hash))
        assertFalse(proof.matches(backend, "receipt-7", "e".repeat(64)))
        assertFalse(proof.matches(
            backend.copy(dataBranch = "another-branch"),
            "receipt-7",
            hash
        ))
    }
}
