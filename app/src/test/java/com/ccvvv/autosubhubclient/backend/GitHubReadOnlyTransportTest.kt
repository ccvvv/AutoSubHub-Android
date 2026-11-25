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
    fun downloadsOriginalCandidateAndManifestFromOneImmutableCommit() {
        val workspace = Files.createTempDirectory("candidate-subscription-test").toFile()
        try {
            val commit = "a".repeat(40)
            val manifest = """{"schema_version":1,"candidate_sha256":"${sha256(YAML)}","unique_candidates":1,"nodes":[{"node_id":"${"b".repeat(20)}","name":"node-one"}]}""".toByteArray()
            val requests = mutableListOf<String>()
            val transport = GitHubReadOnlyTransport(
                config = config,
                requestOverride = { url, accept ->
                    requests += url
                    when {
                        url.substringBefore("?").endsWith("/branches/published-subscriptions") -> {
                            assertEquals("application/vnd.github+json", accept)
                            "{}".toByteArray()
                        }
                        url.contains("/commits?") -> {
                            assertEquals("application/vnd.github+json", accept)
                            """[{"sha":"$commit"}]""".toByteArray()
                        }
                        else -> {
                            assertTrue("Unexpected GitHub request URL: $url", url.contains("/contents/"))
                            assertEquals("application/vnd.github.raw+json", accept)
                            assertTrue(url.contains("ref=$commit"))
                            if (url.contains("manifest.json")) manifest else YAML
                        }
                    }
                }
            )
            val bundle = try {
                transport.fetchCandidateSubscription(workspace)
            } catch (error: IllegalArgumentException) {
                println("CANDIDATE_TEST_DIAGNOSTIC: ${error.message}; requests=${requests.joinToString(" || ")}")
                throw AssertionError("Candidate snapshot test failed: ${error.message}", error)
            }
            assertEquals(commit, bundle.candidateCommit)
            assertEquals(1, bundle.candidateCount)
            assertEquals(YAML.size, bundle.byteCount)
            assertEquals(sha256(YAML), bundle.candidateSha256)
            assertTrue(bundle.candidateFile.readBytes().contentEquals(YAML))
            assertTrue(bundle.manifestFile.readBytes().contentEquals(manifest))
            assertEquals(4, requests.size)
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test
    fun rejectsCandidateSubscriptionWhenManifestHashDoesNotMatch() {
        val workspace = Files.createTempDirectory("candidate-subscription-bad-hash").toFile()
        try {
            val commit = "a".repeat(40)
            val manifest = """{"schema_version":1,"candidate_sha256":"${"c".repeat(64)}","unique_candidates":1,"nodes":[{"node_id":"${"b".repeat(20)}","name":"node-one"}]}""".toByteArray()
            val transport = GitHubReadOnlyTransport(
                config = config,
                requestOverride = { url, _ ->
                    when {
                        url.contains("/branches/published-subscriptions") -> "{}".toByteArray()
                        url.contains("/commits?") -> """[{"sha":"$commit"}]""".toByteArray()
                        url.contains("manifest.json") -> manifest
                        else -> {
                            assertTrue("Unexpected GitHub request URL: $url", url.contains("/contents/"))
                            YAML
                        }
                    }
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
