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

    private fun fixture(candidateHash: String = sha256(YAML)): Map<String, ByteArray> {
        val commit = "a".repeat(40)
        val manifest = """{"candidate_sha256":"$candidateHash","nodes":[{"name":"node-one","node_id":"${"b".repeat(64)}"}]}"""
        val metadata = """{"schema_version":1,"candidate_commit":"$commit","candidate_commits":["$commit"],"candidate_batch_count":1,"candidate_sha256":"$candidateHash","candidate_count":1}"""
        return mapOf(
            "branch" to "{}".toByteArray(),
            "candidate/pending.yaml" to YAML,
            "candidate/pending-manifest.json" to manifest.toByteArray(),
            "candidate/pending-meta.json" to metadata.toByteArray()
        )
    }

    private fun transport(files: Map<String, ByteArray>) = GitHubReadOnlyTransport(
        config = config,
        requestOverride = { url, accept ->
            if (url.contains("/branches/")) {
                assertEquals("application/vnd.github+json", accept)
                files.getValue("branch")
            } else {
                assertEquals("application/vnd.github.raw+json", accept)
                val path = when {
                    url.contains("pending-manifest.json") -> "candidate/pending-manifest.json"
                    url.contains("pending-meta.json") -> "candidate/pending-meta.json"
                    url.contains("pending.yaml") -> "candidate/pending.yaml"
                    else -> error("Unexpected URL: $url")
                }
                files.getValue(path)
            }
        }
    )


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

    @Test
    fun downloadsAndStoresVerifiedCloudQueue() {
        val workspace = Files.createTempDirectory("pending-queue-test").toFile()
        try {
            val queue = transport(fixture()).fetchPendingQueue(workspace)
            assertEquals(1, queue.candidateCount)
            assertEquals("a".repeat(40), queue.candidateCommit)
            assertEquals(sha256(YAML), queue.candidateSha256)
            assertTrue(queue.candidateFile.readBytes().contentEquals(YAML))
            assertTrue(queue.manifestFile.isFile)
            assertTrue(queue.metadataFile.isFile)
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test
    fun rejectsQueueWhenManifestHashDoesNotMatchYaml() {
        val workspace = Files.createTempDirectory("pending-queue-bad-hash").toFile()
        try {
            val error = runCatching {
                transport(fixture("c".repeat(64))).fetchPendingQueue(workspace)
            }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            assertTrue(workspace.listFiles().orEmpty().isEmpty())
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test
    fun rejectsDuplicateNodeIdsInManifest() {
        val files = fixture().toMutableMap()
        val commit = "a".repeat(40)
        val id = "b".repeat(64)
        files["candidate/pending-manifest.json"] =
            """{"candidate_sha256":"${sha256(YAML)}","nodes":[{"name":"one","node_id":"$id"},{"name":"two","node_id":"$id"}]}""".toByteArray()
        files["candidate/pending-meta.json"] =
            """{"schema_version":1,"candidate_commit":"$commit","candidate_commits":["$commit"],"candidate_batch_count":1,"candidate_sha256":"${sha256(YAML)}","candidate_count":2}""".toByteArray()
        val workspace = Files.createTempDirectory("pending-queue-duplicate").toFile()
        try {
            val error = runCatching {
                transport(files).fetchPendingQueue(workspace)
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
