package com.ccvvv.autosubhubclient.storage

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

data class CleanupResult(
    val deletedCount: Int,
    val preservedCount: Int,
    val deletedBytes: Long,
    val failures: List<String>
)

/**
 * Deletes only registered task payload files beneath filesDir/workspace/tasks/<taskId>.
 * A file becomes eligible only after TaskStore has persisted a complete remote proof.
 */
class LocalArtifactCleaner(
    context: Context,
    private val taskStore: TaskStore
) {
    private val workspace = File(context.applicationContext.filesDir, "workspace").canonicalFile

    fun cleanConfirmedArtifacts(): CleanupResult {
        var deleted = 0
        var preserved = 0
        var bytes = 0L
        val failures = mutableListOf<String>()

        taskStore.listCleanupEligible().forEach { artifact ->
            try {
                val target = resolveSafeTaskFile(artifact.taskId, artifact.relativePath)
                if (target == null) {
                    preserved++
                    failures += "${artifact.artifactId}: unsafe path"
                    taskStore.recordCleanupAudit(
                        artifact.taskId, artifact.artifactId, "PRESERVED",
                        "Rejected path outside task workspace or symbolic link"
                    )
                    return@forEach
                }

                if (!target.exists()) {
                    // Idempotent recovery: a prior run may have deleted the file before process death.
                    taskStore.recordCleanupAudit(
                        artifact.taskId, artifact.artifactId, "RECOVERED_MISSING",
                        "Eligible file already absent; finalizing cleanup journal"
                    )
                    taskStore.markArtifactCleaned(artifact.artifactId)
                    deleted++
                    return@forEach
                }

                if (!target.isFile) {
                    preserved++
                    failures += "${artifact.artifactId}: not a regular file"
                    taskStore.recordCleanupAudit(
                        artifact.taskId, artifact.artifactId, "PRESERVED",
                        "Target is not a regular file"
                    )
                    return@forEach
                }

                if (artifact.sha256 != null && sha256(target) != artifact.sha256.lowercase()) {
                    preserved++
                    failures += "${artifact.artifactId}: hash mismatch"
                    taskStore.recordCleanupAudit(
                        artifact.taskId, artifact.artifactId, "PRESERVED",
                        "Local file hash differs from registered hash"
                    )
                    return@forEach
                }

                taskStore.recordCleanupAudit(
                    artifact.taskId, artifact.artifactId, "DELETE_PREPARED",
                    "Remote publication was verified; local payload deletion starting"
                )
                val fileBytes = target.length()
                if (!target.delete() || target.exists()) {
                    preserved++
                    failures += "${artifact.artifactId}: delete failed"
                    taskStore.recordCleanupAudit(
                        artifact.taskId, artifact.artifactId, "DELETE_FAILED",
                        "File deletion could not be verified"
                    )
                    return@forEach
                }

                taskStore.markArtifactCleaned(artifact.artifactId)
                taskStore.recordCleanupAudit(
                    artifact.taskId, artifact.artifactId, "DELETED",
                    "File deletion verified"
                )
                deleted++
                bytes += fileBytes
            } catch (error: Exception) {
                preserved++
                failures += "${artifact.artifactId}: ${error.javaClass.simpleName}"
                runCatching {
                    taskStore.recordCleanupAudit(
                        artifact.taskId, artifact.artifactId, "ERROR",
                        error.message ?: error.javaClass.simpleName
                    )
                }
            }
        }

        return CleanupResult(deleted, preserved, bytes, failures)
    }

    private fun resolveSafeTaskFile(taskId: String, relativePath: String): File? {
        if (taskId.isBlank() || taskId.contains('/') || taskId.contains('\\')) return null
        if (!relativePath.startsWith("tasks/$taskId/")) return null
        if (relativePath.startsWith('/') || relativePath.split('/').any { it == ".." || it.isBlank() }) return null

        val lexical = File(workspace, relativePath)
        val canonical = lexical.canonicalFile
        val taskRoot = File(workspace, "tasks/$taskId").canonicalFile
        val workspacePrefix = workspace.path + File.separator
        val taskPrefix = taskRoot.path + File.separator

        // Canonical mismatch detects symlinks in any existing path component.
        if (lexical.absoluteFile.toPath().normalize().toString() != canonical.path) return null
        if (!canonical.path.startsWith(workspacePrefix)) return null
        if (!canonical.path.startsWith(taskPrefix)) return null
        if (canonical == workspace || canonical == taskRoot) return null
        return canonical
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
