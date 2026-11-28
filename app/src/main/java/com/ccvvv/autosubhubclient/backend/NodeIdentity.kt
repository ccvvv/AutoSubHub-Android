package com.ccvvv.autosubhubclient.backend

import org.json.JSONObject
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.security.MessageDigest

/**
 * Enforces the backend's canonical node identity contract:
 * SHA-256(JSON(proxy without name, sorted keys, compact separators, UTF-8)).
 */
object NodeIdentity {
    private val fullId = Regex("^[a-f0-9]{64}$")

    fun validateCandidate(candidateBytes: ByteArray, manifest: JSONObject): Map<String, String> {
        val root = try {
            Yaml(SafeConstructor(LoaderOptions())).load<Any>(candidateBytes.toString(Charsets.UTF_8))
        } catch (error: Exception) {
            throw IllegalArgumentException(
                "候选订阅 YAML 解析失败（${candidateBytes.size} bytes，SHA-256=${sha256(candidateBytes)}）",
                error
            )
        } as? Map<*, *> ?: error("候选订阅 YAML 根节点无效")
        val proxies = root["proxies"] as? List<*> ?: error("候选订阅缺少 proxies 列表")
        val nodes = manifest.optJSONArray("nodes") ?: error("manifest 缺少 nodes 列表")
        require(proxies.size == nodes.length()) { "候选节点数量与 manifest 不一致" }

        val proxiesByName = LinkedHashMap<String, Map<String, Any?>>()
        for (entry in proxies) {
            val proxy = entry as? Map<*, *> ?: error("候选订阅包含无效节点")
            val name = proxy["name"] as? String ?: error("候选节点缺少名称")
            require(name.isNotBlank() && !proxiesByName.containsKey(name)) {
                "候选节点名称为空或重复：$name"
            }
            val normalized = LinkedHashMap<String, Any?>()
            for ((key, value) in proxy) {
                require(key is String) { "候选节点包含非字符串配置键" }
                normalized[key] = value
            }
            proxiesByName[name] = normalized
        }

        val idsByName = LinkedHashMap<String, String>()
        val usedIds = HashSet<String>()
        for (index in 0 until nodes.length()) {
            val node = nodes.getJSONObject(index)
            val name = node.optString("name", "")
            val declared = node.optString("node_id", "")
            require(name.isNotBlank() && fullId.matches(declared)) {
                "manifest 节点身份必须是完整 64 位 SHA-256：$name"
            }
            val proxy = proxiesByName[name] ?: error("manifest 节点不在候选订阅中：$name")
            val identity = proxy.filterKeys { it != "name" }
            val actual = sha256(canonicalJson(identity).toByteArray(Charsets.UTF_8))
            require(declared == actual) { "节点完整 SHA-256 指纹不匹配：$name" }
            val declaredFingerprint = if (node.has("fingerprint")) {
                node.optString("fingerprint", "")
            } else actual
            require(declaredFingerprint == actual) { "节点 fingerprint 字段不匹配：$name" }
            require(usedIds.add(actual)) { "候选订阅包含重复 SHA-256 指纹：$name" }
            idsByName[name] = actual
        }
        require(idsByName.keys == proxiesByName.keys) { "manifest 与候选订阅节点集合不一致" }
        return idsByName
    }

    private fun canonicalJson(value: Any?): String = when (value) {
        null -> "null"
        is String -> JSONObject.quote(value)
        is Boolean -> if (value) "true" else "false"
        is Number -> value.toString()
        is Map<*, *> -> value.entries
            .sortedBy { it.key as? String ?: error("JSON 对象键必须是字符串") }
            .joinToString(",", "{", "}") { (key, item) ->
                JSONObject.quote(key as String) + ":" + canonicalJson(item)
            }
        is Iterable<*> -> value.joinToString(",", "[", "]") { canonicalJson(it) }
        is Array<*> -> value.joinToString(",", "[", "]") { canonicalJson(it) }
        else -> error("不支持的候选配置值类型：${value.javaClass.name}")
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
