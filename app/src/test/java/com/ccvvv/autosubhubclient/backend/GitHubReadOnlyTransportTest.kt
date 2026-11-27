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
    fun downloadsCandidateAndManifestFromTheLatestCandidateFileCommit() {
        val workspace = Files.createTempDirectory("candidate-subscription-test").toFile()
        try {
            val commit = "a".repeat(40)
            val manifest = """{"schema_version":1,"candidate_sha256":"${sha256(YAML)}","unique_candidates":7,"nodes":[{"node_id":"${"b".repeat(20)}","name":"node-one","tags":["alpha","beta"]},{"node_id":"${"c".repeat(20)}","name":"node-two"}]}""".toByteArray()
            val requests = mutableListOf<String>()
            val transport = GitHubReadOnlyTransport(
                config = config,
                requestOverride = { url, accept ->
                    requests += url
                    when {
                        url.contains("/commits?") -> {
                            assertEquals("application/vnd.github+json", accept)
                            """[{"sha":"$commit"}]""".toByteArray()
                        }
                        else -> {
                            assertTrue("Unexpected raw snapshot URL: $url", url.startsWith("https://raw.githubusercontent.com/owner/repository/$commit/"))
                            assertEquals("application/vnd.github.raw+json", accept)
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
            assertEquals(2, bundle.candidateCount)
            assertEquals(YAML.size, bundle.byteCount)
            assertEquals(sha256(YAML), bundle.candidateSha256)
            assertTrue(bundle.candidateFile.readBytes().contentEquals(YAML))
            assertTrue(bundle.manifestFile.readBytes().contentEquals(manifest))
            assertEquals(3, requests.size)
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
                        url.contains("/commits?") ->
                            """[{"sha":"$commit"}]""".toByteArray()
                        url.contains("manifest.json") -> manifest
                        else -> {
                            assertTrue("Unexpected raw snapshot URL: $url", url.startsWith("https://raw.githubusercontent.com/owner/repository/$commit/"))
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

    @Test
    fun refreshesCachedCandidatesWhenRemoteBranchHeadChanges() {
        val workspace = Files.createTempDirectory("candidate-subscription-refresh").toFile()
        try {
            val oldCommit = "a".repeat(40)
            val newCommit = "d".repeat(40)
            val refreshedYaml = """proxies:
- name: node-one
  type: socks5
  server: 127.0.0.1
  port: 1080
- name: node-two
  type: ss
  server: 127.0.0.2
  port: 8388
- name: node-three
  type: ss
  server: 127.0.0.3
  port: 8389
""".toByteArray()
            fun manifestFor(bytes: ByteArray): ByteArray {
                val hash = sha256(bytes)
                val nodes = (1..3).joinToString(",") { index ->
                    """{"node_id":"${index.toString(16).padStart(20, '0')}","name":"node-${listOf("one", "two", "three")[index - 1]}"}"""
                }
                return """{"schema_version":1,"candidate_sha256":"$hash","unique_candidates":3,"nodes":[$nodes]}""".toByteArray()
            }

            var remoteCommit = oldCommit
            var remoteYaml = YAML
            var remoteManifest = """{"schema_version":1,"candidate_sha256":"${sha256(YAML)}","unique_candidates":2,"nodes":[{"node_id":"${"b".repeat(20)}","name":"node-one"},{"node_id":"${"c".repeat(20)}","name":"node-two"}]}""".toByteArray()
            val requests = mutableListOf<String>()
            val transport = GitHubReadOnlyTransport(
                config = config,
                requestOverride = { url, _ ->
                    requests += url
                    when {
                        url.contains("/commits?") ->
                            """[{"sha":"$remoteCommit"}]""".toByteArray()
                        url.contains("manifest.json") -> remoteManifest
                        else -> remoteYaml
                    }
                }
            )

            val initial = transport.fetchCandidateSubscription(workspace)
            assertEquals(oldCommit, initial.candidateCommit)
            assertEquals(2, initial.candidateCount)
            assertEquals(3, requests.size)

            // An unchanged candidate-file commit reuses the verified local snapshot.
            val cached = transport.fetchCandidateSubscription(workspace)
            assertEquals(oldCommit, cached.candidateCommit)
            assertEquals(2, cached.candidateCount)
            assertEquals(4, requests.size)

            // A new branch head must bypass the old, otherwise-valid local cache.
            remoteCommit = newCommit
            remoteYaml = refreshedYaml
            remoteManifest = manifestFor(refreshedYaml)
            val refreshed = transport.fetchCandidateSubscription(workspace)
            assertEquals(newCommit, refreshed.candidateCommit)
            assertEquals(3, refreshed.candidateCount)
            assertTrue(refreshed.candidateFile.readBytes().contentEquals(refreshedYaml))
            assertEquals(7, requests.size)
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
- cipher: aes-256-gcm
  name: node-two
  type: ss
  server: 127.0.0.2
  port: 8388
""".toByteArray()
    }
}
