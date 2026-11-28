package com.ccvvv.autosubhubclient.backend

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test

class NodeIdentityTest {
    private val fingerprint = "b5d71b09b494ebb876ce6bc9d7ec0552ffad6a0e1b2a097fb55a48367d9fcec7"

    @Test
    fun acceptsCanonicalFullSha256FromManifestWithoutParsingYaml() {
        val manifest = JSONObject()
            .put("unique_candidates", 1)
            .put("nodes", JSONArray().put(
                JSONObject().put("name", "display-name")
                    .put("node_id", fingerprint).put("fingerprint", fingerprint)
            ))
        assertEquals(mapOf("display-name" to fingerprint),
            NodeIdentity.validateManifest(manifest))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsLegacyTwentyCharacterNodeId() {
        val manifest = JSONObject().put("nodes", JSONArray().put(
            JSONObject().put("name", "display-name").put("node_id", fingerprint.take(20))
        ))
        NodeIdentity.validateManifest(manifest)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsFingerprintFieldThatDiffersFromNodeId() {
        val manifest = JSONObject().put("nodes", JSONArray().put(
            JSONObject().put("name", "display-name")
                .put("node_id", fingerprint)
                .put("fingerprint", "a".repeat(64))
        ))
        NodeIdentity.validateManifest(manifest)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDuplicateNodeNamesOrIds() {
        val nodes = JSONArray()
            .put(JSONObject().put("name", "same").put("node_id", fingerprint))
            .put(JSONObject().put("name", "same").put("node_id", "a".repeat(64)))
        NodeIdentity.validateManifest(JSONObject().put("nodes", nodes))
    }
}
