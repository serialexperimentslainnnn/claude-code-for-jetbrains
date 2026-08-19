package dev.lain.claudejb.git

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class GitBranchTopologyTest {

    @Test
    fun `a plain number is the count`() {
        assertEquals(0, GitBranchTopology.commitCount("0"))
        assertEquals(7, GitBranchTopology.commitCount("7"))
        assertEquals(1234, GitBranchTopology.commitCount("1234"))
    }

    @Test
    fun `the trailing newline the command prints is not part of the number`() {
        assertEquals(3, GitBranchTopology.commitCount("3\n"))
        assertEquals(3, GitBranchTopology.commitCount("  3  "))
    }

    @Test
    fun `null in is null out, because that is how the platform reports a failed command`() {
        assertNull(GitBranchTopology.commitCount(null))
    }

    @Test
    fun `output that is not a number is unknown, never zero`() {
        assertNull(GitBranchTopology.commitCount(""))
        assertNull(GitBranchTopology.commitCount("   "))
        assertNull(GitBranchTopology.commitCount("fatal: bad revision 'origin/gone..main'"))
        assertNull(GitBranchTopology.commitCount("12 34"))
    }

    @Test
    fun `a negative count is not a count`() {
        assertNull(GitBranchTopology.commitCount("-1"))
    }

    @Test
    fun `NONE is empty in every field, which is what every degradation path returns`() {
        val none = GitBranchTopology.NONE
        assertNull(none.branch)
        assertNull(none.upstream)
        assertNull(none.ahead)
        assertNull(none.behind)
        assertNull(none.mergeBase)
    }

    @Test
    fun `a branch that tracks nothing carries the branch and nothing else`() {
        val local = GitBranchTopology(branch = "feature/release_5.5.0")
        assertEquals("feature/release_5.5.0", local.branch)
        assertNull(local.upstream)
        assertNull(local.ahead)
        assertNull(local.behind)
    }

    @Test
    fun `a tracked branch carries its upstream, both counts and the divergence point`() {
        val topology = GitBranchTopology(
            branch = "feature/release_5.5.0",
            upstream = "origin/feature/release_5.5.0",
            ahead = 2,
            behind = 0,
            mergeBase = "c532a3f9b1d4e5f60718293a4b5c6d7e8f901234",
        )
        assertEquals("origin/feature/release_5.5.0", topology.upstream)
        assertEquals(2, topology.ahead)
        assertEquals(0, topology.behind)
        assertEquals("c532a3f", GitCommitInfo.shortHash(topology.mergeBase.orEmpty()))
    }

    @Test
    fun `two topologies with the same fields are the same value`() {
        val a = GitBranchTopology(branch = "develop", upstream = "origin/develop", ahead = 0, behind = 3)
        val b = GitBranchTopology(branch = "develop", upstream = "origin/develop", ahead = 0, behind = 3)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, a.copy(behind = null))
    }
}
