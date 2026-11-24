package com.ccvvv.autosubhubclient.backend

import org.eclipse.jgit.diff.DiffEntry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JGitCandidateHistoryReaderTest {
    @Test
    fun includesAddedCandidateSnapshots() {
        assertTrue(isCandidatePathChange(DiffEntry.ChangeType.ADD))
    }

    @Test
    fun includesModifiedCandidateSnapshots() {
        assertTrue(isCandidatePathChange(DiffEntry.ChangeType.MODIFY))
    }

    @Test
    fun excludesDeletionOnlyCommits() {
        assertFalse(isCandidatePathChange(DiffEntry.ChangeType.DELETE))
    }

    @Test
    fun excludesRenamesAndCopies() {
        assertFalse(isCandidatePathChange(DiffEntry.ChangeType.RENAME))
        assertFalse(isCandidatePathChange(DiffEntry.ChangeType.COPY))
    }
}
