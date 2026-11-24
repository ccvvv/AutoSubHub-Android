package com.ccvvv.autosubhubclient.core

/**
 * Durable task lifecycle vocabulary shared by the UI, backend adapter and probe engine.
 * This is deliberately transport-agnostic: no GitHub branch names or file paths live here.
 */
enum class TaskState {
    QUEUED,
    DOWNLOADED,
    INPUT_VERIFIED,
    PROBING,
    PROBE_COMPLETED_LOCAL,
    UPLOAD_PENDING,
    UPLOADING,
    REMOTE_VERIFYING,
    PUBLISHED_CONFIRMED,
    CLEANUP_ELIGIBLE,
    CLEANED,
    PROBE_FAILED,
    UPLOAD_FAILED,
    REMOTE_VERIFY_FAILED,
    CANCELLED_WITH_DATA
}

object TaskTransitions {
    private val allowed = mapOf(
        TaskState.QUEUED to setOf(TaskState.DOWNLOADED, TaskState.CANCELLED_WITH_DATA),
        TaskState.DOWNLOADED to setOf(TaskState.INPUT_VERIFIED, TaskState.CANCELLED_WITH_DATA),
        TaskState.INPUT_VERIFIED to setOf(TaskState.PROBING, TaskState.CANCELLED_WITH_DATA),
        TaskState.PROBING to setOf(TaskState.PROBE_COMPLETED_LOCAL, TaskState.PROBE_FAILED, TaskState.CANCELLED_WITH_DATA),
        TaskState.PROBE_FAILED to setOf(TaskState.PROBING, TaskState.CANCELLED_WITH_DATA),
        TaskState.PROBE_COMPLETED_LOCAL to setOf(TaskState.UPLOAD_PENDING, TaskState.CANCELLED_WITH_DATA),
        TaskState.UPLOAD_PENDING to setOf(TaskState.UPLOADING, TaskState.CANCELLED_WITH_DATA),
        TaskState.UPLOADING to setOf(TaskState.REMOTE_VERIFYING, TaskState.UPLOAD_FAILED),
        TaskState.UPLOAD_FAILED to setOf(TaskState.UPLOAD_PENDING, TaskState.CANCELLED_WITH_DATA),
        TaskState.REMOTE_VERIFYING to setOf(TaskState.PUBLISHED_CONFIRMED, TaskState.REMOTE_VERIFY_FAILED),
        TaskState.REMOTE_VERIFY_FAILED to setOf(TaskState.UPLOAD_PENDING, TaskState.CANCELLED_WITH_DATA),
        TaskState.PUBLISHED_CONFIRMED to setOf(TaskState.CLEANUP_ELIGIBLE),
        TaskState.CLEANUP_ELIGIBLE to setOf(TaskState.CLEANED)
    )

    fun canTransition(from: TaskState, to: TaskState): Boolean =
        allowed[from]?.contains(to) == true

    fun requireTransition(from: TaskState, to: TaskState) {
        require(canTransition(from, to)) {
            "Illegal task transition: $from -> $to"
        }
    }
}

/** Remote proof must come from the backend adapter after checking the complete bundle. */
data class RemotePublicationProof(
    val backendIdentity: String,
    val receiptId: String,
    val contentSha256: String,
    val remoteRevision: String,
    val verifiedAtEpochMillis: Long,
    val completeBundleVerified: Boolean
) {
    fun isValid(): Boolean =
        backendIdentity.isNotBlank() &&
            receiptId.isNotBlank() &&
            contentSha256.matches(Regex("(?i)^[a-f0-9]{64}$")) &&
            remoteRevision.isNotBlank() &&
            verifiedAtEpochMillis > 0 &&
            completeBundleVerified
}

/** A local artifact is deletable only when tied to a task with verified remote publication. */
data class LocalArtifact(
    val taskId: String,
    val relativePath: String,
    val byteCount: Long,
    val sha256: String?,
    val keepLocalCopy: Boolean = false
) {
    init {
        require(taskId.isNotBlank())
        require(relativePath.isNotBlank())
        require(!relativePath.startsWith("/")) { "Artifact path must be app-workspace relative" }
        require(!relativePath.split('/').contains("..")) { "Artifact path must not escape workspace" }
        require(byteCount >= 0)
    }

    fun isCleanupEligible(state: TaskState, proof: RemotePublicationProof?): Boolean =
        state == TaskState.CLEANUP_ELIGIBLE &&
            !keepLocalCopy &&
            proof?.isValid() == true
}
