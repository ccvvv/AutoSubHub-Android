package com.ccvvv.autosubhubclient.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidateQueuePlannerTest {
    private fun commit(value: Int) = value.toString(16).padStart(40, '0')

    @Test
    fun reconcilesLocalAndRemoteCursorsUsingTheMoreAdvancedCursor() {
        val history = listOf(5, 4, 3, 2, 1).map(::commit)
        val plan = CandidateQueuePlanner.plan(
            historyNewestFirst = history,
            localCursor = commit(3),
            remoteReceiptCursor = commit(4)
        )
        assertEquals(listOf(commit(5)), plan.pendingCommits)
        assertEquals(commit(5), plan.branchTip)
    }

    @Test
    fun bootstrapsRecentHistoryInOldestToNewestOrder() {
        val history = listOf(5, 4, 3, 2, 1).map(::commit)
        val plan = CandidateQueuePlanner.plan(history, null, null, bootstrapLimit = 2)
        assertEquals(listOf(commit(4), commit(5)), plan.pendingCommits)
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesToSkipWhenSavedCursorIsMissingFromHistory() {
        CandidateQueuePlanner.plan(
            historyNewestFirst = listOf(5, 4, 3).map(::commit),
            localCursor = commit(2),
            remoteReceiptCursor = null
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesWhenThereAreNoPendingCommits() {
        CandidateQueuePlanner.plan(
            historyNewestFirst = listOf(5, 4, 3).map(::commit),
            localCursor = commit(5),
            remoteReceiptCursor = null
        )
    }

    @Test
    fun branchTipIsAlwaysNewestHistoryEntry() {
        val history = listOf(9, 8, 7, 6).map(::commit)
        val plan = CandidateQueuePlanner.plan(history, null, null, bootstrapLimit = 1)
        assertTrue(plan.pendingCommits.size == 1)
        assertEquals(commit(9), plan.branchTip)
    }
}
