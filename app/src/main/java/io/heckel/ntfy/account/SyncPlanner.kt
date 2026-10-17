package io.heckel.ntfy.account

/**
 * Identifies a subscription across devices. The account stores subscriptions by base URL and topic,
 * so that's what local and remote subscriptions are matched on.
 */
data class SyncKey(val baseUrl: String, val topic: String)

/**
 * What to do on the first sync after logging in, when the phone and the account may disagree.
 * After that, changes are synced in both directions (see [SyncPlanner.plan]).
 */
enum class FirstSyncMode {
    MERGE, // Keep everything from both sides
    USE_ACCOUNT, // Make the phone look like the account
    USE_PHONE // Make the account look like the phone
}

/**
 * The changes needed to bring local and remote subscriptions in line. All maps are keyed by
 * subscription and hold the display name (null if unset).
 */
class SyncPlan(
    val addLocal: Map<SyncKey, String?>,
    val removeLocal: Set<SyncKey>,
    val renameLocal: Map<SyncKey, String?>,
    val addRemote: Map<SyncKey, String?>,
    val removeRemote: Set<SyncKey>,
    val renameRemote: Map<SyncKey, String?>,
    val synced: Map<SyncKey, String?>, // State to remember for the next sync, if all remote changes succeed
    private val remote: Map<SyncKey, String?>
) {
    fun isEmpty(): Boolean {
        return addLocal.isEmpty() && removeLocal.isEmpty() && renameLocal.isEmpty() &&
            addRemote.isEmpty() && removeRemote.isEmpty() && renameRemote.isEmpty()
    }

    /**
     * Returns the state to remember if some remote changes failed. A failed key is remembered as it
     * still is on the server, so the next sync sees the same difference and retries it, rather than
     * mistaking it for a change made on another device (e.g. deleting a topic that failed to upload).
     */
    fun syncedAfter(failed: Set<SyncKey>): Map<SyncKey, String?> {
        val result = synced.toMutableMap()
        failed.forEach { key ->
            if (remote.containsKey(key)) {
                result[key] = remote[key]
            } else {
                result.remove(key)
            }
        }
        return result
    }
}

/**
 * Decides how to sync subscriptions between the phone and the ntfy account, without touching either.
 *
 * Later syncs are a three-way merge against what was synced last time ([plan]'s `synced`): a topic
 * missing on one side was deleted there if it was synced before, and is new on the other side if not.
 * The same goes for display names; if both sides changed, the server wins.
 */
object SyncPlanner {
    fun plan(
        local: Map<SyncKey, String?>,
        remote: Map<SyncKey, String?>,
        synced: Map<SyncKey, String?>?,
        firstSyncMode: FirstSyncMode = FirstSyncMode.MERGE
    ): SyncPlan {
        val addLocal = mutableMapOf<SyncKey, String?>()
        val removeLocal = mutableSetOf<SyncKey>()
        val renameLocal = mutableMapOf<SyncKey, String?>()
        val addRemote = mutableMapOf<SyncKey, String?>()
        val removeRemote = mutableSetOf<SyncKey>()
        val renameRemote = mutableMapOf<SyncKey, String?>()
        val newSynced = mutableMapOf<SyncKey, String?>()
        val localNames = local.mapValues { normalize(it.value) }
        val remoteNames = remote.mapValues { normalize(it.value) }
        val syncedNames = synced?.mapValues { normalize(it.value) }

        // Topics on both sides: only the display name can differ
        localNames.keys.intersect(remoteNames.keys).forEach { key ->
            val localName = localNames[key]
            val remoteName = remoteNames[key]
            val keepLocalName = when {
                localName == remoteName -> null
                syncedNames == null -> firstSyncMode == FirstSyncMode.USE_PHONE || (firstSyncMode == FirstSyncMode.MERGE && remoteName == null)
                !syncedNames.containsKey(key) -> false
                else -> remoteName == syncedNames[key] // Only the phone changed it
            }
            when (keepLocalName) {
                null -> newSynced[key] = localName
                true -> {
                    renameRemote[key] = localName
                    newSynced[key] = localName
                }
                false -> {
                    renameLocal[key] = remoteName
                    newSynced[key] = remoteName
                }
            }
        }

        // Topics only on the phone: new here, or deleted elsewhere
        localNames.keys.subtract(remoteNames.keys).forEach { key ->
            val deletedElsewhere = if (syncedNames == null) {
                firstSyncMode == FirstSyncMode.USE_ACCOUNT
            } else {
                syncedNames.containsKey(key)
            }
            if (deletedElsewhere) {
                removeLocal.add(key)
            } else {
                addRemote[key] = localNames[key]
                newSynced[key] = localNames[key]
            }
        }

        // Topics only on the server: new there, or deleted on this phone
        remoteNames.keys.subtract(localNames.keys).forEach { key ->
            val deletedHere = if (syncedNames == null) {
                firstSyncMode == FirstSyncMode.USE_PHONE
            } else {
                syncedNames.containsKey(key)
            }
            if (deletedHere) {
                removeRemote.add(key)
            } else {
                addLocal[key] = remoteNames[key]
                newSynced[key] = remoteNames[key]
            }
        }
        return SyncPlan(addLocal, removeLocal, renameLocal, addRemote, removeRemote, renameRemote, newSynced, remoteNames)
    }

    /**
     * Whether the first sync needs the user to pick a [FirstSyncMode]. If either side is empty, or
     * both have the same topics, merging loses nothing, so there's nothing to ask.
     */
    fun needsFirstSyncChoice(local: Set<SyncKey>, remote: Set<SyncKey>): Boolean {
        return local.isNotEmpty() && remote.isNotEmpty() && local != remote
    }

    private fun normalize(displayName: String?): String? {
        return if (displayName.isNullOrEmpty()) null else displayName
    }
}
