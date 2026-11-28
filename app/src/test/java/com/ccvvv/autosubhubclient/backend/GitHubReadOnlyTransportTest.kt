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
            val manifest = """{"schema_version":1,"candidate_sha256":"${sha256(YAML)}","unique_candidates":7,"nodes":[{"node_id":"1ea8b3d1ce6c1299ccd2d95a4282694ce3e2884ef3d2fc167be362b4ff0c87b1","name":"node-one","tags":["alpha","beta"]},{"node_id":"fff91314ac74730865b22309da25737bc3d607f5f00174e60b85c8b800846049","name":"node-two"}]}""".toByteArray()
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
    fun reportsWhenIncrementalQueueIsEmpty() {
        val workspace = Files.createTempDirectory("candidate-subscription-empty").toFile()
        try {
            val commit = "e".repeat(40)
            val emptyYaml = "proxies: []\n".toByteArray()
            val manifest = """{"schema_version":1,"candidate_sha256":"${sha256(emptyYaml)}","nodes":[]}""".toByteArray()
            val transport = GitHubReadOnlyTransport(
                config = config,
                requestOverride = { url, _ ->
                    when {
                        url.contains("/commits?") -> """[{"sha":"$commit"}]""".toByteArray()
                        url.contains("manifest.json") -> manifest
                        else -> emptyYaml
                    }
                }
            )
            val error = runCatching {
                transport.fetchCandidateSubscription(workspace)
            }.exceptionOrNull()
            assertTrue(error is IllegalStateException)
            assertEquals("当前没有新增待测速节点", error?.message)
            assertTrue(workspace.listFiles().orEmpty().isEmpty())
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test
    fun rejectsCandidateSubscriptionWhenManifestHashDoesNotMatch() {
        val workspace = Files.createTempDirectory("candidate-subscription-bad-hash").toFile()
        try {
            val commit = "a".repeat(40)
            val manifest = """{"schema_version":1,"candidate_sha256":"${"c".repeat(64)}","unique_candidates":1,"nodes":[{"node_id":"1ea8b3d1ce6c1299ccd2d95a4282694ce3e2884ef3d2fc167be362b4ff0c87b1","name":"node-one"}]}""".toByteArray()
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
                    """{"node_id":"${listOf("1ea8b3d1ce6c1299ccd2d95a4282694ce3e2884ef3d2fc167be362b4ff0c87b1", "e44e23e534c0606455533e9cd2b1fc602f233eaca347efeac38e116a0a857bf4", "5c6c8d5bfdaff668b83f8b0561443f16a3718dcf59f054ce14f80efd01c627ff")[index - 1]}","name":"node-${listOf("one", "two", "three")[index - 1]}"}"""
                }
                return """{"schema_version":1,"candidate_sha256":"$hash","unique_candidates":3,"nodes":[$nodes]}""".toByteArray()
            }

            var remoteCommit = oldCommit
            var remoteYaml = YAML
            var remoteManifest = """{"schema_version":1,"candidate_sha256":"${sha256(YAML)}","unique_candidates":2,"nodes":[{"node_id":"1ea8b3d1ce6c1299ccd2d95a4282694ce3e2884ef3d2fc167be362b4ff0c87b1","name":"node-one"},{"node_id":"fff91314ac74730865b22309da25737bc3d607f5f00174e60b85c8b800846049","name":"node-two"}]}""".toByteArray()
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
