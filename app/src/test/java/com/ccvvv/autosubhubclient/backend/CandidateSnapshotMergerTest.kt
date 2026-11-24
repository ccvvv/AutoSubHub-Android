package com.ccvvv.autosubhubclient.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

class CandidateSnapshotMergerTest {
    private val merger = CandidateSnapshotMerger()

    private fun commit(value: Int) = value.toString(16).padStart(40, '0')

    private fun proxy(name: String, server: String) = linkedMapOf<String, Any?>(
        "name" to name,
        "server" to server,
        "port" to 443,
        "type" to "ss",
        "password" to "secret",
        "udp" to true
    )

    @Test
    fun fingerprintMatchesPythonCanonicalJsonSha256() {
        val value = proxy("display name is excluded", "example.com")
        assertEquals(
            "465b374e1c17f0943fe5fb29207e1e686e4361d90e5d08391315633ab214d775",
            merger.canonicalFingerprint(value)
        )
    }

    @Test
    fun fingerprintUsesPythonFloatExponentNotation() {
        val value = linkedMapOf<String, Any?>("name" to "x", "ratio" to 0.00001)
        assertEquals(
            "a0ca8e558cfea33a589ff0b714f745221b3db1fc3f820ea7de35dd6488496dfb",
            merger.canonicalFingerprint(value)
        )
    }

    @Test
    fun mergeRetainsFirstNameAndTracksEverySource() {
        val first = proxy("node-a", "one.example")
        val sameConfigRenamed = proxy("node-b", "one.example")
        val duplicateDisplayNameDifferentNode = proxy("node-a", "two.example")
        val merged = merger.merge(
            listOf(
                CandidateSnapshotMerger.SnapshotInput(
                    commit(1), mapOf("proxies" to listOf(first)), listOf("node-a")
                ),
                CandidateSnapshotMerger.SnapshotInput(
                    commit(2),
                    mapOf("proxies" to listOf(sameConfigRenamed, duplicateDisplayNameDifferentNode)),
                    listOf("node-b", "node-a")
                )
            )
        )

        assertEquals(listOf(commit(1), commit(2)), merged.sourceCommits)
        assertEquals(2, merged.proxyCount)

        val loader = Yaml(SafeConstructor(LoaderOptions()))
        @Suppress("UNCHECKED_CAST")
        val candidate = loader.load<Map<String, Any?>>(String(merged.candidateBytes, Charsets.UTF_8))
        @Suppress("UNCHECKED_CAST")
        val proxies = candidate["proxies"] as List<Map<String, Any?>>
        assertEquals("node-a", proxies[0]["name"])
        assertEquals("node-a [${merger.canonicalFingerprint(duplicateDisplayNameDifferentNode).take(6)}]", proxies[1]["name"])

        @Suppress("UNCHECKED_CAST")
        val manifest = loader.load<Map<String, Any?>>(String(merged.manifestBytes, Charsets.UTF_8))
        assertEquals(merged.candidateSha256, manifest["candidate_sha256"])
        @Suppress("UNCHECKED_CAST")
        val nodes = manifest["nodes"] as List<Map<String, Any?>>
        assertEquals(listOf(commit(1), commit(2)), nodes[0]["source_commits"])
        assertEquals(listOf("node-a", "node-b"), nodes[0]["source_names"])
        assertTrue(merged.candidateSha256.matches(Regex("^[a-f0-9]{64}$")))
    }
}
