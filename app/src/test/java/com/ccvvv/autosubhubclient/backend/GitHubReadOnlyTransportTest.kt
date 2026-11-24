package com.ccvvv.autosubhubclient.backend

import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReadOnlyTransportTest {
    private val config = BackendConfig(
        repositoryUrl = "https://github.com/owner/repository",
        dataBranch = "published-subscriptions"
    )

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    @Test
    fun downloadsOriginalCandidateSubscriptionFromPublishedDataBranch() {
        val workspace = Files.createTempDirectory("candidate-subscription-test").toFile()
        try {
            val requests = mutableListOf<String>()
            val transport = GitHubReadOnlyTransport(
                config = config,
                requestOverride = { url, accept ->
                    requests += url
                    if (url.contains("/branches/")) {
                        assertEquals("application/vnd.github+json", accept)
                        "{}".toByteArray()
                    } else {
                        assertEquals("application/vnd.github.raw+json", accept)
                        assertTrue(url.contains("candidate/subscription.yaml"))
                        assertTrue(url.contains("ref=published-subscriptions"))
                        YAML
                    }
                }
            )
            val bundle = transport.fetchCandidateSubscription(workspace)
            assertEquals(1, bundle.candidateCount)
            assertEquals(YAML.size, bundle.byteCount)
            assertEquals(sha256(YAML), bundle.candidateSha256)
            assertTrue(bundle.candidateFile.readBytes().contentEquals(YAML))
            assertEquals(2, requests.size)
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test
    fun rejectsCandidateSubscriptionWithoutProxyEntries() {
        val workspace = Files.createTempDirectory("candidate-subscription-empty").toFile()
        try {
            val transport = GitHubReadOnlyTransport(
                config = config,
                requestOverride = { url, _ ->
                    if (url.contains("/branches/")) "{}".toByteArray()
                    else "proxies: []\\n".toByteArray()
                }
            )
            val error = runCatching {
                transport.fetchCandidateSubscription(workspace)
            }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            assertTrue(workspace.listFiles().orEmpty().isEmpty())
        } finally {
            workspace.deleteRecursively()
        }
    }

    companion object {
        private val YAML = """proxies:
- name: node-one
  type: socks5
  server: 127.0.0.1
  port: 1080
""".toByteArray()
    }
}
