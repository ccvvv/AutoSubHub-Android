package com.ccvvv.autosubhubclient.backend

data class CandidateQueuePlan(
    /** Candidate commits in oldest-to-newest order, ready for deterministic merging. */
    val pendingCommits: List<String>,
    /** Current published branch tip; used for the next reconciliation. */
    val branchTip: String
)

/**
 * Mirrors the proven AutoSubHub cursor reconciliation rule:
 * local and remote cursors are both considered, and the more advanced one wins.
 */
object CandidateQueuePlanner {
    fun plan(
        historyNewestFirst: List<String>,
        localCursor: String?,
        remoteReceiptCursor: String?,
        bootstrapLimit: Int = 20
    ): CandidateQueuePlan {
        require(bootstrapLimit > 0) { "Bootstrap limit must be positive" }
        require(historyNewestFirst.isNotEmpty()) { "No candidate subscription history found" }
        require(historyNewestFirst.all { it.matches(Regex("(?i)^[a-f0-9]{40}$")) }) {
            "Candidate history contains an invalid commit ID"
        }
        require(historyNewestFirst.distinct().size == historyNewestFirst.size) {
            "Candidate history contains duplicate commits"
        }

        val cursorCandidates = listOfNotNull(localCursor, remoteReceiptCursor)
        cursorCandidates.forEach { cursor ->
            require(cursor.matches(Regex("(?i)^[a-f0-9]{40}$"))) {
                "Saved candidate cursor is malformed"
            }
            require(cursor in historyNewestFirst) {
                "Saved candidate cursor is not present in published history; refusing to skip batches"
            }
        }

        val pendingNewestFirst = if (cursorCandidates.isNotEmpty()) {
            val mostAdvancedCursor = cursorCandidates.minBy { historyNewestFirst.indexOf(it) }
            historyNewestFirst.take(historyNewestFirst.indexOf(mostAdvancedCursor))
        } else {
            historyNewestFirst.take(bootstrapLimit)
        }

        require(pendingNewestFirst.isNotEmpty()) {
            "No new candidate batches since the last successful receipt upload"
        }

        return CandidateQueuePlan(
            pendingCommits = pendingNewestFirst.asReversed(),
            branchTip = historyNewestFirst.first()
        )
    }
}
