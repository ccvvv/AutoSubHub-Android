package com.ccvvv.autosubhubclient.backend

import org.eclipse.jgit.api.Git
import com.ccvvv.autosubhubclient.backend.NodeIdentity
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.transport.SshTransport
import org.eclipse.jgit.transport.sshd.SshdSessionFactory
import org.eclipse.jgit.transport.sshd.SshdSessionFactoryBuilder
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Uploads an already completed receipt to the single authoritative data branch.
 * A local receipt is deleted only after a fresh fetch proves all three remote blobs
 * exactly match the local bytes. This class never starts or repeats a probe.
 */
class SshReceiptUploader(
    private val config: BackendConfig,
    private val filesDir: File
) {
    data class UploadResult(val commit: String, val receiptId: String, val deletedLocalFiles: Int)

    fun upload(receiptFile: File): UploadResult {
        config.validate()
        require(receiptFile.isFile) { "本地回执文件不存在" }
        val taskDir = receiptFile.parentFile ?: error("回执目录无效")
        val candidateFile = File(taskDir, "receipt.candidate.yaml")
        val manifestFile = File(taskDir, "receipt.manifest.json")
        require(candidateFile.isFile && manifestFile.isFile) { "回执缺少候选订阅或 manifest 快照" }

        val receiptBytes = receiptFile.readBytes()
        val candidateBytes = candidateFile.readBytes()
        val manifestBytes = manifestFile.readBytes()
        require(receiptBytes.isNotEmpty() && candidateBytes.isNotEmpty() && manifestBytes.isNotEmpty())
        val receipt = org.json.JSONObject(String(receiptBytes, Charsets.UTF_8))
        val manifest = org.json.JSONObject(String(manifestBytes, Charsets.UTF_8))
        require(receipt.optInt("schema_version") == 1 && receipt.optString("status") == "completed") {
            "只有完整完成的测速回执可以上传"
        }
        val candidateHash = sha256(candidateBytes)
        require(receipt.optString("candidate_sha256").equals(candidateHash, true) &&
            manifest.optString("candidate_sha256").equals(candidateHash, true)) {
            "回执、候选订阅和 manifest 的 SHA-256 不一致"
        }
        val candidateCommit = receipt.optString("candidate_commit")
        require(candidateCommit.matches(Regex("(?i)^[a-f0-9]{40}$"))) { "候选 commit 无效" }
        val commits = receipt.optJSONArray("candidate_commits") ?: error("缺少 candidate_commits")
        require(commits.length() > 0 && commits.getString(commits.length() - 1) == candidateCommit) {
            "candidate_commits 与 candidate_commit 不匹配"
        }
        val nodes = manifest.getJSONArray("nodes")
        NodeIdentity.validateManifest(manifest)
        val results = receipt.getJSONArray("results")
        require(receipt.optInt("queue_candidate_count") == nodes.length() &&
            receipt.optInt("receipt_candidate_count") == nodes.length() &&
            results.length() == nodes.length()) { "回执没有覆盖全部候选节点，拒绝推进队列" }

        // Preserve the backend-generated full SHA-256 IDs and verify complete result coverage.
        val manifestNames = (0 until nodes.length()).map { nodes.getJSONObject(it).getString("name") }
        val nodeIds = (0 until nodes.length()).map { nodes.getJSONObject(it).getString("node_id") }
        val resultIds = (0 until results.length()).map { results.getJSONObject(it).getString("node_id") }
        require(manifestNames.all { it.isNotBlank() } &&
            manifestNames.size == manifestNames.toSet().size &&
            nodeIds.size == nodeIds.toSet().size &&
            resultIds.size == resultIds.toSet().size && resultIds.toSet() == nodeIds.toSet()) {
            "manifest 和测速结果节点映射不完整或重复"
        }

        // Upload every complete receipt, including batches where all nodes failed.
        // The backend records failures as completed probe results while leaving
        // final/latest.yaml untouched when no node passes the publication gates.

        val receiptId = receipt.optString("task_id").ifBlank { UUID.randomUUID().toString() }
        val (owner, repoName) = config.ownerAndRepository
        val sshDir = File(filesDir, "ssh").apply { check(isDirectory || mkdirs()) }
        val keyInfo = SshDeployKeyManager(filesDir).getOrCreate()
        val identity = File(sshDir, "id_rsa")
        if (!identity.isFile || !identity.readBytes().contentEquals(keyInfo.privateKeyFile.readBytes())) {
            identity.writeBytes(keyInfo.privateKeyFile.readBytes())
            identity.setReadable(false, false)
            identity.setWritable(false, false)
            check(identity.setReadable(true, true) && identity.setWritable(true, true)) {
                "无法设置 SSH 私钥权限"
            }
        }
        val knownHosts = File(sshDir, "known_hosts")
        // These are GitHub's published host public keys (ED25519, ECDSA, RSA).
        // The non-default port must be represented as [host]:port in known_hosts.
        val pins = listOf(
            "[ssh.github.com]:443 ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl",
            "[ssh.github.com]:443 ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItMjU2AAAAIbmlzdHAyNTYAAABBBEmKSENjQEezOmxkZMy7opKgwFB9nkt5YRrYMjNuG5N87uRgg6CLrbo5wAdT/y6v0mKV0U2w0WZ2YB/++Tpockg=",
            "[ssh.github.com]:443 ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABgQCj7ndNxQowgcQnjshcLrqPEiiphnt+VTTvDP6mHBL9j1aNUkY4Ue1gvwnGLVlOhGeYrnZaMgRK6+PKCUXaDbC7qtbW8gIkhL7aGCsOr/C56SJMy/BCZfxd1nWzAOxSDPgVsmerOBYfNqltV9/hWCqBywINIR+5dIg6JTJ72pcEpEjcYgXkE2YEFXV1JHnsKgbLWNlhScqb2UmyRkQyytRLtL+38TGxkxCflmO+5Z8CSSNY7GidjMIZ7Q4zMjA2n1nGrlTDkzwDCsw+wqFPGQA179cnfGWOWRVruj16z6XyvxvjJwbz0wQZ75XK5tKSb7FNyeIEs4TT4jk+S4dhPeAUC5y+bDYirYgM4GC7uEnztnZyaVWQ7B381AK4Qdrwt51ZqExKbQpTUNn+EjqoTwvqNj4kqx5QUCI0ThS/YkOxJCXmPUWZbhjpCg56i+2aB6CmK2JGhn57K5mj0MNdBXA4/WnwH6XoPWJzK5Nyu2zB3nAZp+S5hpQs+p1vN1/wsjk="
        )
        knownHosts.writeText(pins.joinToString("\n", postfix = "\n"), Charsets.US_ASCII)

        // Android has no conventional JVM user.home; configure MINA before initialization.
        val sshHome = filesDir.toPath()
        System.setProperty("user.home", filesDir.absolutePath)
        System.setProperty("user.dir", filesDir.absolutePath)
        org.apache.sshd.common.util.io.PathUtils.setUserHomeFolderResolver { sshHome }

        val factory = SshdSessionFactoryBuilder()
            .setHomeDirectory(filesDir)
            .setSshDirectory(sshDir)
            // Explicitly bind JGit to the exact app-private known_hosts file written above.
            .setDefaultKnownHostsFiles { listOf(knownHosts.toPath()) }
            .setPreferredAuthentications("publickey")
            .build(null)
        val remote = "ssh://git@ssh.github.com:443/$owner/$repoName.git"
        val tempRoot = File(filesDir, "upload-work").apply { check(isDirectory || mkdirs()) }
        val work = File(tempRoot, UUID.randomUUID().toString())
        var git: Git? = null
        var stage = "准备 Git 工作目录"
        try {
            stage = "SSH 克隆 published-subscriptions 分支"
            git = Git.cloneRepository().setURI(remote).setDirectory(work)
                .setBranch(FIXED_DATA_BRANCH).setDepth(1)
                .setTransportConfigCallback { transport ->
                    if (transport is SshTransport) transport.sshSessionFactory = factory
                }.call()
            stage = "检查远端是否已有未处理回执"
            val repository = git.repository
            val receiptPath = File(work, "receipts/latest.json")
            val candidatePath = File(work, "receipts/candidate.yaml")
            val manifestPath = File(work, "receipts/manifest.json")
            val remoteExisting = listOf(receiptPath, candidatePath, manifestPath).any { it.exists() }
            if (remoteExisting) {
                val same = receiptPath.isFile && candidatePath.isFile && manifestPath.isFile &&
                    receiptPath.readBytes().contentEquals(receiptBytes) &&
                    candidatePath.readBytes().contentEquals(candidateBytes) &&
                    manifestPath.readBytes().contentEquals(manifestBytes)
                require(same) { "数据分支已有尚未处理的回执；为防止覆盖新回执，本次上传已停止" }
            } else {
                receiptPath.parentFile?.mkdirs()
                receiptPath.writeBytes(receiptBytes)
                candidatePath.writeBytes(candidateBytes)
                manifestPath.writeBytes(manifestBytes)
                stage = "写入并暂存三份回执文件"
                git.add().addFilepattern("receipts/latest.json").addFilepattern("receipts/candidate.yaml")
                    .addFilepattern("receipts/manifest.json").call()
                stage = "创建本地回执提交"
                git.commit().setMessage("Upload Android phone probe receipt $receiptId")
                    .setAuthor("AutoSubHub Android", "ccvvv@users.noreply.github.com")
                    .setCommitter("AutoSubHub Android", "ccvvv@users.noreply.github.com").call()
                stage = "SSH 推送 published-subscriptions 分支"
                val pushResults = git.push().setRemote("origin")
                    .setRefSpecs(org.eclipse.jgit.transport.RefSpec("refs/heads/$FIXED_DATA_BRANCH:refs/heads/$FIXED_DATA_BRANCH"))
                    .setTransportConfigCallback { transport ->
                        if (transport is SshTransport) transport.sshSessionFactory = factory
                    }.call()
                stage = "解析 GitHub 推送确认结果"
                val updates = pushResults.flatMap { it.remoteUpdates }
                require(updates.isNotEmpty() && updates.all {
                    it.status == org.eclipse.jgit.transport.RemoteRefUpdate.Status.OK ||
                        it.status == org.eclipse.jgit.transport.RemoteRefUpdate.Status.UP_TO_DATE
                }) { "GitHub 未确认 published-subscriptions 分支推送成功" }
            }
            stage = "读取本地上传提交"
            val uploadedCommit = repository.resolve(Constants.HEAD) ?: error("无法读取本地上传提交")

            stage = "推送后重新连接并 fetch 远端分支"
            git.fetch().setRemote("origin")
                .setTransportConfigCallback { transport ->
                    if (transport is SshTransport) transport.sshSessionFactory = factory
                }.call()
            stage = "读取远端分支 HEAD"
            val remoteHead = repository.resolve("refs/remotes/origin/$FIXED_DATA_BRANCH")
                ?: error("推送后无法读取远端数据分支")
            stage = "核验远端提交祖先关系及三份文件内容"
            val verified = RevWalk(repository).use { walk ->
                val uploaded = walk.parseCommit(uploadedCommit)
                val remote = walk.parseCommit(remoteHead)
                check(walk.isMergedInto(uploaded, remote)) { "远端分支尚未包含本次回执提交" }
                fun blob(path: String): ByteArray {
                    val tree = TreeWalk.forPath(repository, path, uploaded.tree)
                        ?: error("远端缺少文件：$path")
                    tree.use { return repository.open(tree.getObjectId(0)).bytes }
                }
                blob("receipts/latest.json").contentEquals(receiptBytes) &&
                    blob("receipts/candidate.yaml").contentEquals(candidateBytes) &&
                    blob("receipts/manifest.json").contentEquals(manifestBytes)
            }
            check(verified) { "远端回执内容核验失败；本地文件已保留" }
            stage = "清理已确认上传成功的本地回执"
            val commitSha = uploadedCommit.name
            var deleted = 0
            listOf(receiptFile, candidateFile, manifestFile).forEach { if (it.delete()) deleted++ }
            check(deleted == 3) { "远端已核验，但本地文件未能全部删除；请检查本地回执目录" }
            taskDir.delete()
            return UploadResult(commitSha, receiptId, deleted)
        } catch (error: Exception) {
            throw IllegalStateException("SSH 上传失败阶段：$stage；原始异常：${error.javaClass.simpleName}: ${error.message ?: "无详细信息"}", error)
        } finally {
            git?.close()
            factory.close()
            work.deleteRecursively()
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
