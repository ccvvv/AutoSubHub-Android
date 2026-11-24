package com.ccvvv.autosubhubclient.backend

import com.ccvvv.autosubhubclient.core.RemotePublicationProof
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendPortTest {
    @Test
    fun acceptsSupportedGitHubBackendConfiguration() {
        BackendConfig(
            repositoryUrl = "https://github.com/owner/repository",
            dataBranch = "published-subscriptions"
        ).validate()
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsupportedContractVersion() {
        BackendConfig(
            repositoryUrl = "https://github.com/owner/repository",
            dataBranch = "published-subscriptions",
            contractVersion = 2
        ).validate()
    }

    @Test
    fun remoteProofMustMatchConfiguredBackendReceiptAndHash() {
        val config = BackendConfig(
            repositoryUrl = "https://github.com/owner/repository",
            dataBranch = "published-subscriptions"
        )
        val hash = "d".repeat(64)
        val proof = RemotePublicationProof(
            backendIdentity = config.identity,
            receiptId = "receipt-7",
            contentSha256 = hash,
            remoteRevision = "commit-abc",
            verifiedAtEpochMillis = 123L,
            completeBundleVerified = true
        )

        assertTrue(proof.matches(config, "receipt-7", hash))
        assertFalse(proof.matches(config, "receipt-other", hash))
        assertFalse(proof.matches(config, "receipt-7", "e".repeat(64)))
        assertFalse(proof.matches(
            config.copy(dataBranch = "another-branch"),
            "receipt-7",
            hash
        ))
    }
}
