package com.ccvvv.autosubhubclient.backend

import org.json.JSONObject

/**
 * Validates the manifest-side node identities without parsing candidate YAML.
 *
 * The candidate bytes are pinned by candidate_sha256. Mihomo is the sole YAML
 * parser for the actual proxy configuration. The backend-generated manifest
 * supplies the canonical full SHA-256 identity for each node.
 */
object NodeIdentity {
    private val fullId = Regex("^[a-f0-9]{64}$")

    fun validateManifest(manifest: JSONObject): Map<String, String> {
        val nodes = manifest.optJSONArray("nodes")
            ?: error("manifest 缺少 nodes 列表")
        val idsByName = LinkedHashMap<String, String>()
        val usedIds = HashSet<String>()

        for (index in 0 until nodes.length()) {
            val node = nodes.getJSONObject(index)
            val name = node.optString("name", "").trim()
            val declared = node.optString("node_id", "").trim()
            require(name.isNotBlank()) { "manifest 节点名称为空" }
            require(fullId.matches(declared)) {
                "manifest 节点身份必须是完整 64 位 SHA-256：$name"
            }
            require(!idsByName.containsKey(name)) { "manifest 节点名称重复：$name" }
            require(usedIds.add(declared)) { "manifest 包含重复 SHA-256 指纹：$name" }
            if (node.has("fingerprint")) {
                require(node.optString("fingerprint", "") == declared) {
                    "manifest fingerprint 与 node_id 不一致：$name"
                }
            }
            idsByName[name] = declared
        }

        if (manifest.has("unique_candidates")) {
            val declaredCount = manifest.optInt("unique_candidates", -1)
            require(declaredCount == nodes.length()) {
                "manifest unique_candidates 与 nodes 数量不一致"
            }
        }
        return idsByName
    }
}
