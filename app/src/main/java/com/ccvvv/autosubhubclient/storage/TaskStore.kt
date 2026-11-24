package com.ccvvv.autosubhubclient.storage

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.ccvvv.autosubhubclient.core.RemotePublicationProof
import com.ccvvv.autosubhubclient.backend.BackendCursorPolicy
import com.ccvvv.autosubhubclient.core.TaskState
import com.ccvvv.autosubhubclient.core.TaskTransitions

data class BackendCursor(
    val backendIdentity: String,
    val lastUploadedCandidateCommit: String,
    val lastReceiptCommit: String,
    val lastReceiptId: String,
    val contentSha256: String,
    val verifiedAtEpochMillis: Long
)

data class StorageSummary(
    val registeredArtifactCount: Int,
    val registeredBytes: Long,
    val cleanupEligibleCount: Int,
    val cleanupEligibleBytes: Long,
    val keepLocalCount: Int
)

data class StoredArtifact(
    val artifactId: String,
    val taskId: String,
    val relativePath: String,
    val byteCount: Long,
    val sha256: String?,
    val keepLocalCopy: Boolean,
    val cleanupEligible: Boolean
)

/**
 * Small SQLite-backed task journal. It stores metadata only; payloads and credentials
 * remain in app-private files and are never copied into the database.
 */
class TaskStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {
    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE tasks (
                task_id TEXT PRIMARY KEY NOT NULL,
                state TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                backend_identity TEXT,
                receipt_id TEXT,
                content_sha256 TEXT,
                remote_revision TEXT,
                verified_at INTEGER,
                complete_bundle_verified INTEGER NOT NULL DEFAULT 0
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE artifacts (
                artifact_id TEXT PRIMARY KEY NOT NULL,
                task_id TEXT NOT NULL,
                relative_path TEXT NOT NULL,
                byte_count INTEGER NOT NULL,
                sha256 TEXT,
                keep_local_copy INTEGER NOT NULL DEFAULT 0,
                cleanup_eligible INTEGER NOT NULL DEFAULT 0,
                cleaned INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY(task_id) REFERENCES tasks(task_id) ON DELETE CASCADE,
                UNIQUE(task_id, relative_path)
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE cleanup_audit (
                audit_id INTEGER PRIMARY KEY AUTOINCREMENT,
                task_id TEXT NOT NULL,
                artifact_id TEXT NOT NULL,
                event TEXT NOT NULL,
                detail TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE backend_cursors (
                backend_identity TEXT PRIMARY KEY NOT NULL,
                last_uploaded_candidate_commit TEXT NOT NULL,
                last_receipt_commit TEXT NOT NULL,
                last_receipt_id TEXT NOT NULL,
                content_sha256 TEXT NOT NULL,
                verified_at INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX idx_artifacts_task ON artifacts(task_id)")
        db.execSQL("CREATE INDEX idx_artifacts_cleanup ON artifacts(cleanup_eligible, cleaned)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Migrations are additive; never drop user task data.
        var version = oldVersion
        if (version < 2) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS backend_cursors (
                    backend_identity TEXT PRIMARY KEY NOT NULL,
                    last_uploaded_candidate_commit TEXT NOT NULL,
                    last_receipt_commit TEXT NOT NULL,
                    last_receipt_id TEXT NOT NULL,
                    content_sha256 TEXT NOT NULL,
                    verified_at INTEGER NOT NULL
                )""".trimIndent()
            )
            version = 2
        }
        if (version != newVersion) {
            throw IllegalStateException("No migration path from database $oldVersion to $newVersion")
        }
    }

    fun createTask(taskId: String, now: Long = System.currentTimeMillis()) {
        require(taskId.matches(Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")))
        require(taskId != "." && taskId != "..")
        val values = ContentValues().apply {
            put("task_id", taskId)
            put("state", TaskState.QUEUED.name)
            put("created_at", now)
            put("updated_at", now)
        }
        writableDatabase.insertOrThrow("tasks", null, values)
    }

    fun getTaskState(taskId: String): TaskState? =
        readableDatabase.query(
            "tasks",
            arrayOf("state"),
            "task_id = ?",
            arrayOf(taskId),
            null,
            null,
            null
        ).use { cursor ->
            if (cursor.moveToFirst()) TaskState.valueOf(cursor.getString(0)) else null
        }

    fun transitionTask(taskId: String, next: TaskState, now: Long = System.currentTimeMillis()) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val current = getTaskState(taskId)
                ?: throw IllegalArgumentException("Unknown task: $taskId")
            require(next != TaskState.PUBLISHED_CONFIRMED &&
                next != TaskState.CLEANUP_ELIGIBLE && next != TaskState.CLEANED) {
                "This transition requires a dedicated proof-checked storage operation"
            }
            TaskTransitions.requireTransition(current, next)
            val values = ContentValues().apply {
                put("state", next.name)
                put("updated_at", now)
            }
            check(db.update("tasks", values, "task_id = ?", arrayOf(taskId)) == 1)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun addArtifact(
        artifactId: String,
        taskId: String,
        relativePath: String,
        byteCount: Long,
        sha256: String? = null
    ) {
        require(artifactId.isNotBlank())
        require(relativePath.startsWith("tasks/$taskId/")) {
            "Artifact must be stored inside its task-specific workspace directory"
        }
        require(!relativePath.startsWith("/"))
        require(!relativePath.split('/').contains(".."))
        require(byteCount >= 0)
        require(sha256 == null || sha256.matches(Regex("(?i)^[a-f0-9]{64}$")))
        val values = ContentValues().apply {
            put("artifact_id", artifactId)
            put("task_id", taskId)
            put("relative_path", relativePath)
            put("byte_count", byteCount)
            if (sha256 == null) putNull("sha256") else put("sha256", sha256)
        }
        writableDatabase.insertOrThrow("artifacts", null, values)
    }

    /** Only a complete, validated remote proof can advance REMOTE_VERIFYING to confirmed. */
    fun confirmRemotePublication(
        taskId: String,
        proof: RemotePublicationProof,
        now: Long = System.currentTimeMillis()
    ) {
        require(proof.isValid()) { "Remote publication proof is incomplete or invalid" }
        val db = writableDatabase
        db.beginTransaction()
        try {
            val current = getTaskState(taskId)
                ?: throw IllegalArgumentException("Unknown task: $taskId")
            TaskTransitions.requireTransition(current, TaskState.PUBLISHED_CONFIRMED)
            require(current == TaskState.REMOTE_VERIFYING) {
                "Remote verification must be the immediately preceding state"
            }
            val values = ContentValues().apply {
                put("state", TaskState.PUBLISHED_CONFIRMED.name)
                put("updated_at", now)
                put("backend_identity", proof.backendIdentity)
                put("receipt_id", proof.receiptId)
                put("content_sha256", proof.contentSha256.lowercase())
                put("remote_revision", proof.remoteRevision)
                put("verified_at", proof.verifiedAtEpochMillis)
                put("complete_bundle_verified", 1)
            }
            check(db.update("tasks", values, "task_id = ?", arrayOf(taskId)) == 1)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Mark payloads eligible only after the task's proof is re-read and validated from SQLite. */
    fun markCleanupEligible(taskId: String, now: Long = System.currentTimeMillis()) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val proof = readProof(db, taskId)
                ?: throw IllegalStateException("No verified remote publication proof")
            require(proof.isValid()) { "Stored remote proof is invalid" }
            val artifactCount = db.rawQuery(
                "SELECT COUNT(*) FROM artifacts WHERE task_id = ?", arrayOf(taskId)
            ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }
            require(artifactCount > 0) { "No registered artifacts for task" }
            val state = getTaskState(taskId)
                ?: throw IllegalArgumentException("Unknown task: $taskId")
            TaskTransitions.requireTransition(state, TaskState.CLEANUP_ELIGIBLE)
            val taskValues = ContentValues().apply {
                put("state", TaskState.CLEANUP_ELIGIBLE.name)
                put("updated_at", now)
            }
            check(db.update("tasks", taskValues, "task_id = ?", arrayOf(taskId)) == 1)
            db.execSQL(
                "UPDATE artifacts SET cleanup_eligible = 1 WHERE task_id = ? AND keep_local_copy = 0",
                arrayOf(taskId)
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun setKeepLocalCopy(artifactId: String, keep: Boolean) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val taskId = db.query(
                "artifacts", arrayOf("task_id"), "artifact_id = ?",
                arrayOf(artifactId), null, null, null
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: throw IllegalArgumentException("Unknown artifact: $artifactId")
            val values = ContentValues().apply {
                put("keep_local_copy", if (keep) 1 else 0)
                if (keep) put("cleanup_eligible", 0)
                else if (getTaskState(taskId) == TaskState.CLEANUP_ELIGIBLE) put("cleanup_eligible", 1)
            }
            check(db.update("artifacts", values, "artifact_id = ?", arrayOf(artifactId)) == 1)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getBackendCursor(backendIdentity: String): BackendCursor? {
        require(backendIdentity.isNotBlank())
        return readableDatabase.query(
            "backend_cursors",
            arrayOf(
                "backend_identity", "last_uploaded_candidate_commit", "last_receipt_commit",
                "last_receipt_id", "content_sha256", "verified_at"
            ),
            "backend_identity = ?",
            arrayOf(backendIdentity),
            null,
            null,
            null
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else BackendCursor(
                backendIdentity = cursor.getString(0),
                lastUploadedCandidateCommit = cursor.getString(1),
                lastReceiptCommit = cursor.getString(2),
                lastReceiptId = cursor.getString(3),
                contentSha256 = cursor.getString(4),
                verifiedAtEpochMillis = cursor.getLong(5)
            )
        }
    }

    /** Cursor advancement is forbidden until a matching remote proof is supplied. */
    fun saveBackendCursorAfterVerifiedUpload(
        backendIdentity: String,
        candidateCommit: String,
        receiptCommit: String,
        proof: RemotePublicationProof
    ) {
        val advance = BackendCursorPolicy.validate(
            backendIdentity, candidateCommit, receiptCommit, proof
        )
        val values = ContentValues().apply {
            put("backend_identity", advance.backendIdentity)
            put("last_uploaded_candidate_commit", advance.candidateCommit)
            put("last_receipt_commit", advance.receiptCommit)
            put("last_receipt_id", advance.receiptId)
            put("content_sha256", advance.contentSha256)
            put("verified_at", advance.verifiedAtEpochMillis)
        }
        writableDatabase.insertWithOnConflict(
            "backend_cursors", null, values, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun getStorageSummary(): StorageSummary =
        readableDatabase.rawQuery(
            """SELECT
                COUNT(*),
                COALESCE(SUM(byte_count), 0),
                COALESCE(SUM(CASE WHEN cleanup_eligible = 1 AND cleaned = 0 AND keep_local_copy = 0 THEN 1 ELSE 0 END), 0),
                COALESCE(SUM(CASE WHEN cleanup_eligible = 1 AND cleaned = 0 AND keep_local_copy = 0 THEN byte_count ELSE 0 END), 0),
                COALESCE(SUM(CASE WHEN keep_local_copy = 1 AND cleaned = 0 THEN 1 ELSE 0 END), 0)
            FROM artifacts""".trimIndent(),
            null
        ).use { cursor ->
            cursor.moveToFirst()
            StorageSummary(
                registeredArtifactCount = cursor.getInt(0),
                registeredBytes = cursor.getLong(1),
                cleanupEligibleCount = cursor.getInt(2),
                cleanupEligibleBytes = cursor.getLong(3),
                keepLocalCount = cursor.getInt(4)
            )
        }

    fun listCleanupEligible(): List<StoredArtifact> =
        readableDatabase.query(
            "artifacts",
            arrayOf("artifact_id", "task_id", "relative_path", "byte_count", "sha256", "keep_local_copy", "cleanup_eligible"),
            "cleanup_eligible = 1 AND cleaned = 0 AND keep_local_copy = 0",
            null,
            null,
            null,
            "task_id, artifact_id"
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        StoredArtifact(
                            artifactId = cursor.getString(0),
                            taskId = cursor.getString(1),
                            relativePath = cursor.getString(2),
                            byteCount = cursor.getLong(3),
                            sha256 = if (cursor.isNull(4)) null else cursor.getString(4),
                            keepLocalCopy = cursor.getInt(5) == 1,
                            cleanupEligible = cursor.getInt(6) == 1
                        )
                    )
                }
            }
        }

    fun recordCleanupAudit(taskId: String, artifactId: String, event: String, detail: String) {
        val values = ContentValues().apply {
            put("task_id", taskId)
            put("artifact_id", artifactId)
            put("event", event)
            put("detail", detail)
            put("created_at", System.currentTimeMillis())
        }
        writableDatabase.insertOrThrow("cleanup_audit", null, values)
    }

    fun markArtifactCleaned(artifactId: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val taskId = db.query(
                "artifacts",
                arrayOf("task_id"),
                "artifact_id = ?",
                arrayOf(artifactId),
                null,
                null,
                null
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: throw IllegalArgumentException("Unknown artifact: $artifactId")

            val values = ContentValues().apply {
                put("cleaned", 1)
                put("cleanup_eligible", 0)
            }
            check(db.update("artifacts", values, "artifact_id = ?", arrayOf(artifactId)) == 1)

            val remaining = db.rawQuery(
                "SELECT COUNT(*) FROM artifacts WHERE task_id = ? AND cleaned = 0 AND keep_local_copy = 0",
                arrayOf(taskId)
            ).use { cursor ->
                cursor.moveToFirst()
                cursor.getInt(0)
            }
            if (remaining == 0 && getTaskState(taskId) == TaskState.CLEANUP_ELIGIBLE) {
                val taskValues = ContentValues().apply {
                    put("state", TaskState.CLEANED.name)
                    put("updated_at", System.currentTimeMillis())
                }
                db.update("tasks", taskValues, "task_id = ?", arrayOf(taskId))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun readProof(db: SQLiteDatabase, taskId: String): RemotePublicationProof? =
        db.query(
            "tasks",
            arrayOf("backend_identity", "receipt_id", "content_sha256", "remote_revision", "verified_at", "complete_bundle_verified"),
            "task_id = ?",
            arrayOf(taskId),
            null,
            null,
            null
        ).use { cursor ->
            if (!cursor.moveToFirst() || cursor.isNull(0) || cursor.isNull(1) ||
                cursor.isNull(2) || cursor.isNull(3) || cursor.isNull(4)
            ) return@use null
            RemotePublicationProof(
                backendIdentity = cursor.getString(0),
                receiptId = cursor.getString(1),
                contentSha256 = cursor.getString(2),
                remoteRevision = cursor.getString(3),
                verifiedAtEpochMillis = cursor.getLong(4),
                completeBundleVerified = cursor.getInt(5) == 1
            )
        }

    companion object {
        private const val DATABASE_NAME = "autosubhub_tasks.db"
        private const val DATABASE_VERSION = 2
    }
}
