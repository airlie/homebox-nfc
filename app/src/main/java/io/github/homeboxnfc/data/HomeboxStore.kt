package io.github.homeboxnfc.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.homeboxnfc.HomeboxNfcApplication
import io.github.homeboxnfc.homebox.HomeboxClient
import io.github.homeboxnfc.homebox.HomeboxException
import io.github.homeboxnfc.ui.TreeIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Saved Homebox data with pending changes applied: what every screen works from. */
class WorkingView(snapshot: Snapshot, val pendingCount: Int) {
    val fetchedAt: Long = snapshot.fetchedAt
    val tagName: String = snapshot.tagName
    val taggedIds: Set<String> = snapshot.taggedIds
    val index = TreeIndex(snapshot.roots)
}

data class SyncStatus(
    val syncing: Boolean = false,
    val refreshing: Boolean = false,
    /** Why pending changes aren't going through, if they aren't. */
    val problem: String? = null,
    /** True when the problem needs the user (login, Cloudflare), not just a connection. */
    val needsUser: Boolean = false,
    /** Last error from refreshing the saved data. */
    val refreshError: String? = null,
)

enum class SyncOutcome { DONE, WAITING_FOR_NETWORK, NEEDS_USER }

/**
 * Offline-first access to Homebox.
 *
 * - A [Snapshot] of the tree is saved to disk and refreshed whenever Homebox is reachable.
 * - Edits go into an outbox (also on disk) and are sent in order. If Homebox can't be reached,
 *   they wait: they're retried when the network comes back while the app is running, and by a
 *   WorkManager job (which needs a connection) if the app has been closed.
 */
class HomeboxStore(
    private val context: Context,
    private val prefs: Prefs,
    private val homebox: HomeboxClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val snapshotFile = File(context.filesDir, "snapshot.json")
    private val outboxFile = File(context.filesDir, "outbox.json")
    private val lock = Any()
    /** Serializes all network work (sync and refresh) so they never interleave. */
    private val netMutex = Mutex()
    private val ids = AtomicLong(System.currentTimeMillis())

    private var snapshot: Snapshot? = snapshotFile.takeIf { it.exists() }?.let { Snapshot.fromJson(it.readText()) }

    private val _outbox = MutableStateFlow(
        outboxFile.takeIf { it.exists() }?.let { Change.listFromJson(it.readText()) } ?: emptyList()
    )
    val outbox: StateFlow<List<Change>> = _outbox.asStateFlow()

    private val _view = MutableStateFlow<WorkingView?>(null)
    val view: StateFlow<WorkingView?> = _view.asStateFlow()

    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    init {
        recompute()
    }

    val hasPending: Boolean get() = _outbox.value.any { it.error == null }

    // ---- local edits --------------------------------------------------------------------

    fun newChange(
        entityId: String,
        entityName: String,
        summary: String,
        parentId: String? = null,
        addTag: String? = null,
        setFields: Map<String, String> = emptyMap(),
        setFieldsIfEmpty: Map<String, String> = emptyMap(),
    ) = Change(
        id = ids.incrementAndGet(),
        server = homebox.baseUrl,
        entityId = entityId,
        entityName = entityName,
        summary = summary,
        createdAt = System.currentTimeMillis(),
        parentId = parentId,
        addTag = addTag?.trim()?.ifBlank { null },
        setFields = setFields.filterKeys { it.isNotBlank() },
        setFieldsIfEmpty = setFieldsIfEmpty.filterKeys { it.isNotBlank() },
    )

    /** Queues [change] (if it does anything) and tries to send it right away. */
    fun enqueue(change: Change) {
        if (change.parentId == null && change.addTag == null && change.setFields.isEmpty() && change.setFieldsIfEmpty.isEmpty()) return
        synchronized(lock) {
            _outbox.value = _outbox.value + change
            saveOutboxLocked()
        }
        recompute()
        syncSoon()
    }

    fun retry(changeId: Long) {
        synchronized(lock) {
            _outbox.value = _outbox.value.map { if (it.id == changeId) it.copy(error = null) else it }
            saveOutboxLocked()
        }
        syncSoon()
    }

    fun discard(changeId: Long) {
        synchronized(lock) {
            _outbox.value = _outbox.value.filterNot { it.id == changeId }
            saveOutboxLocked()
        }
        recompute()
    }

    /** Call when leaving Settings: the server address or NFC tag name may have changed. */
    fun onSettingsChanged() {
        recompute()
        if (_view.value == null) refreshIfOlderThan(0)
        if (hasPending) syncSoon()
    }

    // ---- network ------------------------------------------------------------------------

    fun syncSoon() {
        scope.launch { syncNow() }
    }

    /** Sends pending changes in order until done or blocked. */
    suspend fun syncNow(fromWorker: Boolean = false): SyncOutcome = netMutex.withLock { pushLocked(fromWorker) }

    /** Sends pending changes, then re-downloads the saved data. Returns an error message or null. */
    suspend fun refresh(): String? = netMutex.withLock {
        _status.update { it.copy(refreshing = true) }
        try {
            pushLocked(fromWorker = false)
            val tagName = prefs.nfcTagName.trim()
            val roots = homebox.tree()
            val tagId = if (tagName.isBlank()) null else homebox.findTag(tagName)
            val tagged = tagId?.let { homebox.entityIdsWithTag(it) } ?: emptySet()
            val fresh = Snapshot(homebox.baseUrl, System.currentTimeMillis(), tagName, roots, tagged)
            synchronized(lock) {
                snapshot = fresh
                snapshotFile.writeText(fresh.toJson())
            }
            recompute()
            _status.update { it.copy(refreshError = null) }
            null
        } catch (e: HomeboxException) {
            _status.update { it.copy(refreshError = e.message) }
            e.message
        } catch (e: Exception) {
            val msg = "Couldn't read Homebox data: ${e.message ?: e.javaClass.simpleName}"
            _status.update { it.copy(refreshError = msg) }
            msg
        } finally {
            _status.update { it.copy(refreshing = false) }
        }
    }

    /** Refreshes in the background unless the saved data is newer than [maxAgeMs]. */
    fun refreshIfOlderThan(maxAgeMs: Long) {
        val v = _view.value
        if (homebox.baseUrl.isBlank() || !homebox.hasToken) return
        if (v != null && System.currentTimeMillis() - v.fetchedAt < maxAgeMs) return
        if (_status.value.refreshing) return
        scope.launch { refresh() }
    }

    private fun pushLocked(fromWorker: Boolean): SyncOutcome {
        val tagIds = HashMap<String, String>()
        try {
            while (true) {
                val next = _outbox.value.firstOrNull { it.error == null } ?: break
                _status.update { it.copy(syncing = true) }
                if (next.server != homebox.baseUrl) {
                    markError(next.id, "Made for a different Homebox address (${next.server}).")
                    continue
                }
                try {
                    send(next, tagIds)
                    commit(next)
                } catch (e: HomeboxException) {
                    when {
                        e.kind == HomeboxException.Kind.NETWORK -> {
                            _status.update { it.copy(problem = "Waiting for a connection. ${e.message}", needsUser = false) }
                            if (!fromWorker) scheduleWorker()
                            return SyncOutcome.WAITING_FOR_NETWORK
                        }
                        e.needsUser -> {
                            _status.update { it.copy(problem = e.message, needsUser = true) }
                            return SyncOutcome.NEEDS_USER
                        }
                        else -> markError(next.id, e.message ?: "Homebox refused this change.")
                    }
                } catch (e: Exception) {
                    markError(next.id, "Unexpected error: ${e.message ?: e.javaClass.simpleName}")
                }
            }
            _status.update { it.copy(problem = null, needsUser = false) }
            return SyncOutcome.DONE
        } finally {
            _status.update { it.copy(syncing = false) }
        }
    }

    private fun send(c: Change, tagIds: MutableMap<String, String>) {
        val info = homebox.entityInfo(c.entityId)
        val tagId = c.addTag?.let { name -> tagIds.getOrPut(name.lowercase()) { homebox.ensureTag(name) } }
        val newTags = if (tagId != null && tagId !in info.tagIds) info.tagIds + tagId else null
        val move = c.parentId?.takeIf { it != info.parentId }
        if (move != null || newTags != null) homebox.patch(c.entityId, parentId = move, tagIds = newTags)

        val fields = LinkedHashMap<String, String>()
        for ((k, v) in c.setFieldsIfEmpty) if (info.field(k).isNullOrBlank()) fields[k] = v
        for ((k, v) in c.setFields) if (info.field(k) != v) fields[k] = v
        homebox.setTextFields(c.entityId, fields)
    }

    /** Homebox accepted [c]: drop it from the outbox and fold it into the saved data. */
    private fun commit(c: Change) {
        synchronized(lock) {
            _outbox.value = _outbox.value.filterNot { it.id == c.id }
            saveOutboxLocked()
            snapshot = snapshot?.let { s ->
                if (s.server == c.server) s.with(listOf(c)).also { snapshotFile.writeText(it.toJson()) } else s
            }
        }
        recompute()
    }

    private fun markError(id: Long, message: String) {
        synchronized(lock) {
            _outbox.value = _outbox.value.map { if (it.id == id) it.copy(error = message) else it }
            saveOutboxLocked()
        }
    }

    private fun scheduleWorker() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    // ---- state --------------------------------------------------------------------------

    private fun recompute() {
        synchronized(lock) {
            val base = homebox.baseUrl
            val s = snapshot?.takeIf { it.server == base && it.tagName.equals(prefs.nfcTagName.trim(), ignoreCase = true) }
            val pending = _outbox.value.filter { it.server == base }
            _view.value = s?.let { WorkingView(it.with(pending), pending.count { c -> c.error == null }) }
        }
    }

    private fun saveOutboxLocked() {
        outboxFile.writeText(Change.listToJson(_outbox.value))
    }

    companion object {
        private const val WORK_NAME = "homebox-sync"
    }
}

/** Sends queued changes once there's a connection, even if the app has been closed. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val store = (applicationContext as HomeboxNfcApplication).store
        return when (store.syncNow(fromWorker = true)) {
            SyncOutcome.WAITING_FOR_NETWORK -> if (runAttemptCount < 20) Result.retry() else Result.failure()
            else -> Result.success()
        }
    }
}
