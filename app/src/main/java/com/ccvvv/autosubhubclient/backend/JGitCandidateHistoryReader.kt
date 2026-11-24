package com.ccvvv.autosubhubclient.backend

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.EmptyTreeIterator
import org.eclipse.jgit.util.io.DisabledOutputStream
import java.io.File

/**
 * Reads candidate history from the configured data branch using embedded JGit.
 *
 * A single Git fetch obtains the branch history; this avoids one GitHub REST request per commit.
 * The local repository is an app-private bare-worktree-style clone (no checkout is performed).
 * Call from a background executor.
 */
class JGitCandidateHistoryReader(
    private val maxHistoryCommits: Int = DEFAULT_MAX_HISTORY_COMMITS
) {
    init {
        require(maxHistoryCommits > 0)
    }

    data class History(
        /** Candidate commits in newest-first order, with deletion-only commits excluded. */
        val commitsNewestFirst: List<String>,
        /** Latest commit at the fetched data-branch tip, whether or not it changed the candidate file. */
        val branchTip: String
    )

    fun read(config: BackendConfig, repositoryDirectory: File): History {
        config.validate()
        require(repositoryDirectory.isAbsolute) { "Git repository directory must be absolute" }
        require(repositoryDirectory.exists() || repositoryDirectory.mkdirs()) {
            "Cannot create Git repository directory"
        }
        require(repositoryDirectory.isDirectory) { "Git repository path is not a directory" }

        val remoteUrl = canonicalRemoteUrl(config)
        val git = openOrClone(repositoryDirectory, remoteUrl, config.dataBranch)
        try {
            val configuredRemote = git.repository.config.getString("remote", "origin", "url")
            require(configuredRemote == remoteUrl) {
                "Existing local Git cache belongs to a different repository; refusing to reuse it"
            }

            git.fetch()
                .setRemote("origin")
                .call()

            val remoteRef = "refs/remotes/origin/${config.dataBranch}"
            val branchTipId = git.repository.resolve("$remoteRef^{commit}")
                ?: throw IllegalStateException("Configured data branch was not fetched: ${config.dataBranch}")

            val commits = collectCandidateCommits(git.repository, branchTipId)
            require(commits.isNotEmpty()) {
                "No add/modify history found for candidate/subscription.yaml"
            }
            return History(commitsNewestFirst = commits, branchTip = branchTipId.name)
        } finally {
            git.close()
        }
    }

    private fun openOrClone(directory: File, remoteUrl: String, branch: String): Git {
        val gitDirectory = File(directory, ".git")
        if (gitDirectory.exists()) {
            require(gitDirectory.isDirectory) { "Local Git metadata path is not a directory" }
            return try {
                Git.open(directory)
            } catch (error: Exception) {
                throw IllegalStateException("Existing candidate history cache is not a valid Git repository", error)
            }
        }

        val existingChildren = directory.listFiles().orEmpty()
        require(existingChildren.isEmpty()) {
            "Candidate history cache directory is not empty and has no Git metadata"
        }

        return try {
            Git.cloneRepository()
                .setURI(remoteUrl)
                .setDirectory(directory)
                .setBranch("refs/heads/$branch")
                .setNoCheckout(true)
                .setCloneAllBranches(false)
                .call()
        } catch (error: Exception) {
            throw IllegalStateException("Unable to clone configured candidate data branch", error)
        }
    }

    private fun collectCandidateCommits(repository: Repository, branchTip: org.eclipse.jgit.lib.ObjectId): List<String> {
        val result = ArrayList<String>()
        Git(repository).use { git ->
            RevWalk(repository).use { walk ->
                val head = walk.parseCommit(branchTip)
                val pathHistory = git.log()
                    .add(head)
                    .addPath(CANDIDATE_PATH)
                    .call()

                for (commit in pathHistory) {
                    if (isAddOrModify(repository, walk, commit)) {
                        result.add(commit.name)
                        require(result.size <= maxHistoryCommits) {
                            "Candidate history exceeds the configured safety limit of $maxHistoryCommits commits"
                        }
                    }
                }
            }
        }
        return result
    }

    private fun isAddOrModify(repository: Repository, walk: RevWalk, commit: RevCommit): Boolean {
        val reader = repository.newObjectReader()
        try {
            val newTree = CanonicalTreeParser().apply {
                reset(reader, commit.tree.id)
            }
            val oldTree = if (commit.parentCount == 0) {
                EmptyTreeIterator()
            } else {
                val firstParent = walk.parseCommit(commit.getParent(0).id)
                CanonicalTreeParser().apply {
                    reset(reader, firstParent.tree.id)
                }
            }

            DiffFormatter(DisabledOutputStream.INSTANCE).use { formatter ->
                formatter.setRepository(repository)
                formatter.setDetectRenames(false)
                return formatter.scan(oldTree, newTree).any { entry ->
                    entry.newPath == CANDIDATE_PATH &&
                        isCandidatePathChange(entry.changeType)
                }
            }
        } finally {
            reader.close()
        }
    }

    private fun canonicalRemoteUrl(config: BackendConfig): String {
        val (owner, repository) = config.ownerAndRepository
        return "https://github.com/$owner/$repository.git"
    }

    companion object {
        const val CANDIDATE_PATH = "candidate/subscription.yaml"
        const val DEFAULT_MAX_HISTORY_COMMITS = 50_000
    }
}

internal fun isCandidatePathChange(changeType: DiffEntry.ChangeType): Boolean =
    changeType == DiffEntry.ChangeType.ADD || changeType == DiffEntry.ChangeType.MODIFY
