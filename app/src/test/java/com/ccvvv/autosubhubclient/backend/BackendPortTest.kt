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
    fun acceptsSupportedGitHubBackendConfiguration() {
        config().validate()
        config("release/2026-11").validate()
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsupportedContractVersion() {
        config().copy(contractVersion = 2).validate()
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsafeGitBranchName() {
        config("feature/bad?branch").validate()
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsRepositoryUrlWithQueryParameters() {
        config().copy(repositoryUrl = "https://github.com/owner/repository?redirect=evil").validate()
    }

    @Test
    fun endpointBuilderEncodesBranchAndPathWithoutChangingPathSeparators() {
        val branch = "release/2026-11"
        val candidateCommits = GitHubBackendEndpoints.listCandidateCommits(config(branch), 2)
        assertTrue(candidateCommits.contains("sha=release%2F2026-11"))
        assertTrue(candidateCommits.contains("path=candidate%2Fsubscription.yaml"))
        assertTrue(candidateCommits.endsWith("per_page=100&page=2"))

        val commit = "a".repeat(40)
        val contentUrl = GitHubBackendEndpoints.contentsFile(
            config(branch), "candidate/manifest file.json", commit
        )
        assertTrue(contentUrl.contains("/contents/candidate/manifest%20file.json?ref=$commit"))

        val rawUrl = GitHubBackendEndpoints.rawSnapshotFile(
            config(), commit, "candidate/subscription.yaml"
        )
        assertTrue(rawUrl.endsWith("/$commit/candidate/subscription.yaml"))
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
