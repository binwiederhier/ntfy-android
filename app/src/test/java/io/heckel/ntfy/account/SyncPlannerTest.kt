package io.heckel.ntfy.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPlannerTest {
    private val a = SyncKey("https://ntfy.sh", "a")
    private val b = SyncKey("https://ntfy.sh", "b")
    private val c = SyncKey("https://ntfy.example.com", "c")

    @Test
    fun firstSync_merge_addsBothWays() {
        val plan = SyncPlanner.plan(
            local = mapOf(a to null, b to null),
            remote = mapOf(b to null, c to "Server C"),
            synced = null,
            firstSyncMode = FirstSyncMode.MERGE
        )
        assertEquals(mapOf(c to "Server C"), plan.addLocal)
        assertEquals(mapOf(a to null), plan.addRemote)
        assertTrue(plan.removeLocal.isEmpty())
        assertTrue(plan.removeRemote.isEmpty())
        assertEquals(mapOf(a to null, b to null, c to "Server C"), plan.synced)
    }

    @Test
    fun firstSync_merge_displayName_remoteWinsUnlessUnset() {
        val plan = SyncPlanner.plan(
            local = mapOf(a to "Local A", b to "Local B"),
            remote = mapOf(a to "Remote A", b to null),
            synced = null,
            firstSyncMode = FirstSyncMode.MERGE
        )
        assertEquals(mapOf(a to "Remote A"), plan.renameLocal)
        assertEquals(mapOf(b to "Local B"), plan.renameRemote)
        assertEquals(mapOf(a to "Remote A", b to "Local B"), plan.synced)
    }

    @Test
    fun firstSync_useAccount_replacesLocal() {
        val plan = SyncPlanner.plan(
            local = mapOf(a to "Local A", b to null),
            remote = mapOf(a to "Remote A", c to null),
            synced = null,
            firstSyncMode = FirstSyncMode.USE_ACCOUNT
        )
        assertEquals(mapOf(c to null), plan.addLocal)
        assertEquals(setOf(b), plan.removeLocal)
        assertEquals(mapOf(a to "Remote A"), plan.renameLocal)
        assertTrue(plan.addRemote.isEmpty())
        assertTrue(plan.removeRemote.isEmpty())
        assertTrue(plan.renameRemote.isEmpty())
        assertEquals(mapOf(a to "Remote A", c to null), plan.synced)
    }

    @Test
    fun firstSync_usePhone_replacesRemote() {
        val plan = SyncPlanner.plan(
            local = mapOf(a to "Local A", b to null),
            remote = mapOf(a to "Remote A", c to null),
            synced = null,
            firstSyncMode = FirstSyncMode.USE_PHONE
        )
        assertEquals(mapOf(b to null), plan.addRemote)
        assertEquals(setOf(c), plan.removeRemote)
        assertEquals(mapOf(a to "Local A"), plan.renameRemote)
        assertTrue(plan.addLocal.isEmpty())
        assertTrue(plan.removeLocal.isEmpty())
        assertTrue(plan.renameLocal.isEmpty())
        assertEquals(mapOf(a to "Local A", b to null), plan.synced)
    }

    @Test
    fun laterSync_newOnEitherSide_isAdded() {
        val plan = SyncPlanner.plan(
            local = mapOf(a to null, b to null),
            remote = mapOf(a to null, c to null),
            synced = mapOf(a to null)
        )
        assertEquals(mapOf(c to null), plan.addLocal)
        assertEquals(mapOf(b to null), plan.addRemote)
        assertTrue(plan.removeLocal.isEmpty())
        assertTrue(plan.removeRemote.isEmpty())
    }

    @Test
    fun laterSync_deletedOnEitherSide_isRemoved() {
        val plan = SyncPlanner.plan(
            local = mapOf(a to null, b to null),
            remote = mapOf(a to null, c to null),
            synced = mapOf(a to null, b to null, c to null)
        )
        assertEquals(setOf(b), plan.removeLocal) // Deleted on another device
        assertEquals(setOf(c), plan.removeRemote) // Deleted on this phone
        assertTrue(plan.addLocal.isEmpty())
        assertTrue(plan.addRemote.isEmpty())
        assertEquals(mapOf(a to null), plan.synced)
    }

    @Test
    fun laterSync_deletedOnBothSides_isForgotten() {
        val plan = SyncPlanner.plan(local = mapOf(), remote = mapOf(), synced = mapOf(a to null))
        assertTrue(plan.isEmpty())
        assertEquals(mapOf<SyncKey, String?>(), plan.synced)
    }

    @Test
    fun laterSync_displayName_changedSideWins() {
        val plan = SyncPlanner.plan(
            local = mapOf(a to "New A", b to "B", c to "Phone C"),
            remote = mapOf(a to "A", b to "New B", c to "Server C"),
            synced = mapOf(a to "A", b to "B", c to "C")
        )
        assertEquals(mapOf(a to "New A"), plan.renameRemote) // Renamed on this phone
        assertEquals(mapOf(b to "New B", c to "Server C"), plan.renameLocal) // Renamed elsewhere, or both (server wins)
        assertEquals(mapOf(a to "New A", b to "New B", c to "Server C"), plan.synced)
    }

    @Test
    fun laterSync_displayName_canBeCleared() {
        val plan = SyncPlanner.plan(
            local = mapOf(a to "A", b to null),
            remote = mapOf(a to null, b to "B"),
            synced = mapOf(a to "A", b to "B")
        )
        assertEquals(mapOf(a to null), plan.renameLocal)
        assertEquals(mapOf(b to null), plan.renameRemote)
    }

    @Test
    fun displayName_emptyEqualsUnset() {
        val plan = SyncPlanner.plan(local = mapOf(a to ""), remote = mapOf(a to null), synced = mapOf(a to null))
        assertTrue(plan.isEmpty())
    }

    @Test
    fun nothingChanged_isEmpty() {
        val plan = SyncPlanner.plan(
            local = mapOf(a to "A", c to null),
            remote = mapOf(a to "A", c to null),
            synced = mapOf(a to "A", c to null)
        )
        assertTrue(plan.isEmpty())
        assertEquals(mapOf(a to "A", c to null), plan.synced)
    }

    @Test
    fun failedRemoteAdd_isRetriedNotDeleted() {
        val plan = SyncPlanner.plan(local = mapOf(a to null, b to null), remote = mapOf(a to null), synced = mapOf(a to null))
        val synced = plan.syncedAfter(failed = setOf(b))
        assertEquals(mapOf(a to null), synced)

        // Next round: b is still only local, and was never synced, so it is uploaded again
        val next = SyncPlanner.plan(local = mapOf(a to null, b to null), remote = mapOf(a to null), synced = synced)
        assertEquals(mapOf(b to null), next.addRemote)
        assertTrue(next.removeLocal.isEmpty())
    }

    @Test
    fun failedRemoteRemove_isRetriedNotReAdded() {
        val plan = SyncPlanner.plan(local = mapOf(), remote = mapOf(a to null), synced = mapOf(a to null))
        val synced = plan.syncedAfter(failed = setOf(a))
        assertEquals(mapOf(a to null), synced)

        // Next round: a is still only remote, and was synced before, so it is removed again
        val next = SyncPlanner.plan(local = mapOf(), remote = mapOf(a to null), synced = synced)
        assertEquals(setOf(a), next.removeRemote)
        assertTrue(next.addLocal.isEmpty())
    }

    @Test
    fun failedRemoteRename_isRetriedNotReverted() {
        val plan = SyncPlanner.plan(local = mapOf(a to "New A"), remote = mapOf(a to "A"), synced = mapOf(a to "A"))
        val synced = plan.syncedAfter(failed = setOf(a))
        assertEquals(mapOf(a to "A"), synced)

        val next = SyncPlanner.plan(local = mapOf(a to "New A"), remote = mapOf(a to "A"), synced = synced)
        assertEquals(mapOf(a to "New A"), next.renameRemote)
        assertTrue(next.renameLocal.isEmpty())
    }

    @Test
    fun needsFirstSyncChoice() {
        // Only ask when both sides have topics and they differ; otherwise merging loses nothing
        assertFalse(SyncPlanner.needsFirstSyncChoice(local = setOf(), remote = setOf(a)))
        assertFalse(SyncPlanner.needsFirstSyncChoice(local = setOf(a), remote = setOf()))
        assertFalse(SyncPlanner.needsFirstSyncChoice(local = setOf(a, b), remote = setOf(b, a)))
        assertTrue(SyncPlanner.needsFirstSyncChoice(local = setOf(a), remote = setOf(b)))
        assertTrue(SyncPlanner.needsFirstSyncChoice(local = setOf(a, b), remote = setOf(a)))
    }
}
