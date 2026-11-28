package com.ccvvv.autosubhubclient.backend

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class NodeIdentityTest {
    private val yaml = """
        proxies:
          - name: display-name
            type: ss
            server: example.com
            port: 443
            cipher: aes-128-gcm
            password: secret
    """.trimIndent().toByteArray(Charsets.UTF_8)

    private val fingerprint = "b5d71b09b494ebb876ce6bc9d7ec0552ffad6a0e1b2a097fb55a48367d9fcec7"

    @Test
    fun acceptsCanonicalFullSha256AndIgnoresDisplayName() {
        val manifest = JSONObject()
            .put("nodes", org.json.JSONArray().put(
                JSONObject().put("name", "display-name")
                    .put("node_id", fingerprint).put("fingerprint", fingerprint)
            ))
        assertEquals(mapOf("display-name" to fingerprint),
            NodeIdentity.validateCandidate(yaml, manifest))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsLegacyTwentyCharacterNodeId() {
        val manifest = JSONObject().put("nodes", org.json.JSONArray().put(
            JSONObject().put("name", "display-name").put("node_id", fingerprint.take(20))
        ))
        NodeIdentity.validateCandidate(yaml, manifest)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsFingerprintThatDoesNotMatchProxyConfiguration() {
        val changed = String(yaml, Charsets.UTF_8).replace("example.com", "other.example")
            .toByteArray(Charsets.UTF_8)
        val manifest = JSONObject().put("nodes", org.json.JSONArray().put(
            JSONObject().put("name", "display-name").put("node_id", fingerprint)
        ))
        NodeIdentity.validateCandidate(changed, manifest)
    }
}
