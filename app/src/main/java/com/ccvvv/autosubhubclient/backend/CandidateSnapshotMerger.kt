package com.ccvvv.autosubhubclient.backend

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.representer.Representer
import java.io.File
import java.math.BigDecimal
import java.math.BigInteger
import java.security.MessageDigest

/**
 * Reproduces AutoSubHub's canonical proxy fingerprint and ordered snapshot merge.
 * Input snapshots must already have passed immutable SHA256 verification.
 */
class CandidateSnapshotMerger {
    data class SnapshotInput(
        val commit: String,
        val candidate: Map<String, Any?>,
        val manifestNodeNames: List<String>
    )

    data class MergedBundle(
        val candidateBytes: ByteArray,
        val manifestBytes: ByteArray,
        val candidateSha256: String,
        val sourceCommits: List<String>,
        val proxyCount: Int
    )

    private data class Entry(
        val proxy: LinkedHashMap<String, Any?>,
        val sourceCommits: MutableList<String>,
        val sourceNames: MutableList<String>
    )

    fun mergeFiles(snapshots: List<GitHubReadOnlyTransport.CandidateSnapshot>): MergedBundle {
        require(snapshots.isNotEmpty()) { "At least one verified candidate snapshot is required" }
        val inputs = snapshots.map { snapshot ->
            val candidate = parseYamlMap(snapshot.candidateFile)
            val manifest = parseYamlMap(snapshot.manifestFile)
            val nodes = manifest["nodes"] as? List<*>
                ?: throw IllegalArgumentException("Manifest nodes are missing at ${snapshot.commit}")
            val names = nodes.map { node ->
                val item = node as? Map<*, *>
                    ?: throw IllegalArgumentException("Invalid manifest node at ${snapshot.commit}")
                item["name"] as? String
                    ?: throw IllegalArgumentException("Manifest node name is invalid at ${snapshot.commit}")
            }
            SnapshotInput(snapshot.commit, candidate, names)
        }
        return merge(inputs)
    }

    fun merge(snapshots: List<SnapshotInput>): MergedBundle {
        require(snapshots.isNotEmpty()) { "At least one candidate snapshot is required" }
        val merged = LinkedHashMap<String, Entry>()
        val commits = ArrayList<String>()

        for (snapshot in snapshots) {
            require(snapshot.commit.matches(Regex("(?i)^[a-f0-9]{40}$"))) {
                "Invalid candidate commit in merge input"
            }
            if (snapshot.commit !in commits) commits.add(snapshot.commit)

            val rawProxies = snapshot.candidate["proxies"] as? List<*>
                ?: throw IllegalArgumentException("Invalid candidate proxies at ${snapshot.commit}")
            val byName = LinkedHashMap<String, LinkedHashMap<String, Any?>>()
            for (rawProxy in rawProxies) {
                val proxy = toStringKeyedMap(rawProxy, snapshot.commit)
                val name = proxy["name"] as? String
                    ?: throw IllegalArgumentException("Invalid proxy name at ${snapshot.commit}")
                require(name.isNotBlank()) { "Blank proxy name at ${snapshot.commit}" }
                require(byName.put(name, proxy) == null) {
                    "Invalid or duplicate proxy names at ${snapshot.commit}"
                }
            }
            require(snapshot.manifestNodeNames.size == snapshot.manifestNodeNames.toSet().size &&
                snapshot.manifestNodeNames.toSet() == byName.keys) {
                "Manifest does not match candidate at ${snapshot.commit}"
            }

            for ((name, proxy) in byName) {
                val fingerprint = canonicalFingerprint(proxy)
                val entry = merged.getOrPut(fingerprint) {
                    Entry(LinkedHashMap(proxy), mutableListOf(), mutableListOf())
                }
                if (snapshot.commit !in entry.sourceCommits) entry.sourceCommits.add(snapshot.commit)
                if (name !in entry.sourceNames) entry.sourceNames.add(name)
            }
        }

        val outputProxies = ArrayList<Map<String, Any?>>()
        val outputNodes = ArrayList<Map<String, Any?>>()
        val usedNames = HashSet<String>()
        for ((fingerprint, entry) in merged) {
            val proxy = LinkedHashMap(entry.proxy)
            val originalName = proxy["name"] as String
            if (originalName in usedNames) {
                proxy["name"] = "$originalName [${fingerprint.take(6)}]"
            }
            val finalName = proxy["name"] as String
            usedNames.add(finalName)
            outputProxies.add(proxy)
            outputNodes.add(
                linkedMapOf(
                    "node_id" to fingerprint,
                    "name" to finalName,
                    "source_commits" to entry.sourceCommits.toList(),
                    "source_names" to entry.sourceNames.toList()
                )
            )
        }

        val candidateRoot = linkedMapOf<String, Any?>("proxies" to outputProxies)
        val candidateBytes = yamlDumper().dump(candidateRoot).toByteArray(Charsets.UTF_8)
        val candidateHash = sha256(candidateBytes)
        val manifestRoot = linkedMapOf<String, Any?>(
            "nodes" to outputNodes,
            "candidate_sha256" to candidateHash
        )
        val manifestBytes = (canonicalJson(manifestRoot) + "\n").toByteArray(Charsets.UTF_8)

        return MergedBundle(
            candidateBytes = candidateBytes,
            manifestBytes = manifestBytes,
            candidateSha256 = candidateHash,
            sourceCommits = commits,
            proxyCount = outputProxies.size
        )
    }

    fun canonicalFingerprint(proxy: Map<String, Any?>): String {
        val config = LinkedHashMap<String, Any?>()
        proxy.forEach { (key, value) -> if (key != "name") config[key] = value }
        return sha256(canonicalJson(config).toByteArray(Charsets.UTF_8))
    }

    private fun parseYamlMap(file: File): Map<String, Any?> {
        require(file.isFile) { "Candidate snapshot file is missing: ${file.name}" }
        val options = LoaderOptions().apply {
            setAllowDuplicateKeys(false)
            setMaxAliasesForCollections(50)
            setCodePointLimit(MAX_YAML_CODE_POINTS)
        }
        val loaded = Yaml(SafeConstructor(options)).load<Any?>(file.readText(Charsets.UTF_8))
        return toStringKeyedMap(loaded, file.name)
    }

    private fun toStringKeyedMap(value: Any?, context: String): LinkedHashMap<String, Any?> {
        val source = value as? Map<*, *>
            ?: throw IllegalArgumentException("Expected a YAML mapping at $context")
        val result = LinkedHashMap<String, Any?>()
        for ((rawKey, rawValue) in source) {
            val key = rawKey as? String
                ?: throw IllegalArgumentException("Non-string YAML key at $context")
            require(result.put(key, rawValue) == null) { "Duplicate YAML key '$key' at $context" }
        }
        return result
    }

    private fun yamlDumper(): Yaml {
        val options = DumperOptions().apply {
            setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK)
            setIndent(2)
            setIndicatorIndent(0)
            setWidth(1000)
            setAllowUnicode(true)
            setSplitLines(false)
        }
        return Yaml(SafeConstructor(LoaderOptions()), Representer(options), options)
    }

    private fun canonicalJson(value: Any?): String = when (value) {
        null -> "null"
        is String -> quoteJson(value)
        is Boolean -> if (value) "true" else "false"
        is Byte, is Short, is Int, is Long, is BigInteger -> value.toString()
        is Float -> floatJson(value.toDouble())
        is Double -> floatJson(value)
        is BigDecimal -> value.toPlainString()
        is Number -> value.toString()
        is Map<*, *> -> {
            val entries = value.entries.map { entry ->
                val key = entry.key as? String
                    ?: throw IllegalArgumentException("Proxy JSON object keys must be strings")
                key to entry.value
            }.sortedBy { it.first }
            entries.joinToString(prefix = "{", postfix = "}", separator = ",") {
                quoteJson(it.first) + ":" + canonicalJson(it.second)
            }
        }
        is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]", separator = ",") {
            canonicalJson(it)
        }
        is Array<*> -> value.joinToString(prefix = "[", postfix = "]", separator = ",") {
            canonicalJson(it)
        }
        else -> throw IllegalArgumentException("Unsupported proxy value type: ${value.javaClass.name}")
    }

    private fun floatJson(value: Double): String {
        require(value.isFinite()) { "Non-finite proxy numbers cannot be fingerprinted" }
        val decimal = BigDecimal.valueOf(value).stripTrailingZeros()
        val exponent = decimal.precision() - decimal.scale() - 1
        if (exponent < -4 || exponent >= 16) {
            val mantissa = decimal.movePointLeft(exponent).stripTrailingZeros().toPlainString()
            val signedExponent = if (exponent >= 0) "+$exponent" else exponent.toString()
            return mantissa + "e" + signedExponent.padStart(if (exponent < 0) 3 else 2, '0')
        }
        val plain = decimal.toPlainString()
        return if (plain.contains('.')) plain else "$plain.0"
    }

    private fun quoteJson(value: String): String = buildString {
        append('"')
        for (char in value) {
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) {
                    append("\\u")
                    append(char.code.toString(16).padStart(4, '0'))
                } else append(char)
            }
        }
        append('"')
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    companion object {
        private const val MAX_YAML_CODE_POINTS = 32 * 1024 * 1024
    }
}
