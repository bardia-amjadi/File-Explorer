package com.bardia.fileexplorer.operations

import android.content.Context
import android.net.Uri
import com.bardia.fileexplorer.archive.ZipEngine
import com.bardia.fileexplorer.core.FileEntry
import com.bardia.fileexplorer.core.NameUtils
import com.bardia.fileexplorer.storage.FileSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

sealed class OperationRequest(val title: String) {
    class Copy(val sources: List<FileEntry>, val destDir: String, val recordUndo: Boolean = true) :
        OperationRequest(if (sources.size == 1) "Copying ${sources[0].name}" else "Copying ${sources.size} items")

    class Move(
        val sources: List<FileEntry>, val destDir: String, val recordUndo: Boolean = true,
        val preset: ConflictAction? = null, label: String? = null
    ) : OperationRequest(label ?: if (sources.size == 1) "Moving ${sources[0].name}" else "Moving ${sources.size} items")

    /** [permanent] = true bypasses the trash (cannot be undone). */
    class Delete(val sources: List<FileEntry>, val permanent: Boolean, val recordUndo: Boolean = true, label: String? = null) :
        OperationRequest(label ?: if (sources.size == 1) "Deleting ${sources[0].name}" else "Deleting ${sources.size} items")

    class Compress(val sources: List<FileEntry>, val destDir: String, val archiveName: String) :
        OperationRequest("Creating $archiveName")

    class Extract(val zip: FileEntry, val destDir: String, val selected: Set<String>?, val intoSubfolder: Boolean) :
        OperationRequest("Extracting ${zip.name}")

    class Restore(val items: List<TrashItem>, val destDirOverride: String?) :
        OperationRequest(if (items.size == 1) "Restoring ${items[0].name}" else "Restoring ${items.size} items")
}

enum class OpStatus { QUEUED, RUNNING, DONE, DONE_WITH_ERRORS, CANCELLED, FAILED }

data class OperationState(
    val id: Long,
    val title: String,
    val status: OpStatus,
    val bytesDone: Long = 0,
    val bytesTotal: Long = -1,
    val itemsDone: Int = 0,
    val current: String = "",
    val errors: List<String> = emptyList(),
    val message: String? = null
) {
    val isActive get() = status == OpStatus.QUEUED || status == OpStatus.RUNNING
    /** 0..1, or null when the total is unknown (indeterminate). */
    val progress: Float? get() = if (bytesTotal > 0) (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f) else null
}

data class PendingConflict(val opId: Long, val opTitle: String, val info: ConflictInfo, internal val deferred: CompletableDeferred<ConflictDecision>)

/** One-shot notification for the snackbar. [undoId] is set when the operation can be reversed. */
data class OpEvent(val message: String, val isError: Boolean = false, val undoId: Long? = null)

/**
 * Runs file operations off the main thread, one at a time (a queue), tracking progress per operation.
 * Conflicts suspend the worker until the user answers in the UI. Every operation reports partial failures
 * explicitly; nothing is marked "done" unless all items really succeeded.
 */
class OperationManager(
    private val context: Context,
    private val fs: FileSystem,
    private val trash: TrashManager,
    val undo: UndoManager,
    private val scope: CoroutineScope
) {
    private val engine = TransferEngine(fs)
    private val zip = ZipEngine(fs)

    private val ids = AtomicLong(1)
    private val queue = Channel<Pair<Long, OperationRequest>>(Channel.UNLIMITED)
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val cancelledWhileQueued = ConcurrentHashMap.newKeySet<Long>()

    private val _operations = MutableStateFlow<List<OperationState>>(emptyList())
    val operations: StateFlow<List<OperationState>> = _operations.asStateFlow()

    private val _pending = MutableStateFlow<PendingConflict?>(null)
    val pendingConflict: StateFlow<PendingConflict?> = _pending.asStateFlow()

    private val _events = MutableSharedFlow<OpEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<OpEvent> = _events.asSharedFlow()

    /** Emitted whenever the content of some folder may have changed, so open browsers can refresh. */
    private val _changed = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val changed: SharedFlow<Unit> = _changed.asSharedFlow()

    init {
        scope.launch(Dispatchers.IO) {
            for ((id, req) in queue) {
                if (cancelledWhileQueued.remove(id)) {
                    patch(id) { it.copy(status = OpStatus.CANCELLED, message = "Cancelled before it started") }
                    continue
                }
                val job = launch { execute(id, req) }
                jobs[id] = job
                job.join()
                jobs.remove(id)
            }
        }
    }

    // ------------------------------------------------------------------ public API

    fun submit(request: OperationRequest): Long {
        val id = ids.getAndIncrement()
        _operations.update { it + OperationState(id, request.title, OpStatus.QUEUED) }
        queue.trySend(id to request)
        return id
    }

    fun cancel(id: Long) {
        val job = jobs[id]
        if (job != null) job.cancel() else if (_operations.value.any { it.id == id && it.status == OpStatus.QUEUED }) cancelledWhileQueued.add(id)
    }

    fun dismiss(id: Long) = _operations.update { list -> list.filterNot { it.id == id && !it.isActive } }

    fun resolveConflict(decision: ConflictDecision) {
        _pending.value?.deferred?.complete(decision)
    }

    // ------------------------------------------------------------------ quick (non-queued) operations

    suspend fun createFolder(parentUri: String, name: String): Result<FileEntry> = withContext(Dispatchers.IO) {
        NameUtils.validate(name)?.let { return@withContext Result.failure(IllegalArgumentException(it)) }
        try {
            val parent = Uri.parse(parentUri)
            if (fs.list(parent).any { it.name.equals(name, ignoreCase = true) }) {
                return@withContext Result.failure(IllegalArgumentException("An item named \"$name\" already exists here"))
            }
            val uri = fs.createDirectory(parent, name)
            val entry = fs.stat(uri, parent) ?: FileEntry(uri.toString(), name, true, parentUri = parentUri)
            val undoId = undo.register("Undo new folder") {
                submit(OperationRequest.Delete(listOf(entry), permanent = true, recordUndo = false, label = "Removing folder $name"))
            }
            _events.tryEmit(OpEvent("Created folder \"$name\"", undoId = undoId))
            _changed.tryEmit(Unit)
            Result.success(entry)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun rename(entry: FileEntry, newName: String): Result<FileEntry> = withContext(Dispatchers.IO) {
        NameUtils.validate(newName)?.let { return@withContext Result.failure(IllegalArgumentException(it)) }
        if (newName == entry.name) return@withContext Result.success(entry)
        try {
            val parent = entry.parentUri?.let { Uri.parse(it) }
            if (parent != null && fs.list(parent).any { it.name.equals(newName, ignoreCase = true) && it.uri != entry.uri }) {
                return@withContext Result.failure(IllegalArgumentException("An item named \"$newName\" already exists here"))
            }
            val newUri = fs.rename(Uri.parse(entry.uri), newName)
            val updated = fs.stat(newUri, parent) ?: entry.copy(uri = newUri.toString(), name = newName)
            val oldName = entry.name
            val undoId = undo.register("Undo rename") {
                scope.launch(Dispatchers.IO) {
                    try {
                        fs.rename(Uri.parse(updated.uri), oldName)
                        _events.tryEmit(OpEvent("Renamed back to \"$oldName\""))
                    } catch (e: Exception) {
                        _events.tryEmit(OpEvent("Could not undo rename: ${e.message}", isError = true))
                    }
                    _changed.tryEmit(Unit)
                }
            }
            _events.tryEmit(OpEvent("Renamed to \"$newName\"", undoId = undoId))
            _changed.tryEmit(Unit)
            Result.success(updated)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ------------------------------------------------------------------ execution

    private inner class Sink(val id: Long) : ProgressSink {
        private var bytes = 0L
        private var total = -1L
        private var items = 0
        private var name = ""
        private var lastPublish = 0L

        override fun bytes(delta: Long) { bytes += delta; publish(false) }
        override fun current(name: String) { this.name = name; publish(false) }
        override fun itemDone() { items++; publish(false) }
        override fun total(bytes: Long, items: Int) { total = bytes; publish(true) }

        fun publish(force: Boolean) {
            val now = System.nanoTime()
            if (!force && now - lastPublish < 120_000_000L) return
            lastPublish = now
            patch(id) { it.copy(bytesDone = bytes, bytesTotal = total, itemsDone = items, current = name) }
        }
    }

    private fun patch(id: Long, f: (OperationState) -> OperationState) =
        _operations.update { list -> list.map { if (it.id == id) f(it) else it } }

    private suspend fun askConflict(opId: Long, title: String, info: ConflictInfo): ConflictDecision {
        val d = CompletableDeferred<ConflictDecision>()
        _pending.value = PendingConflict(opId, title, info, d)
        try {
            return d.await()
        } finally {
            _pending.value = null
        }
    }

    private suspend fun execute(id: Long, req: OperationRequest) {
        patch(id) { it.copy(status = OpStatus.RUNNING) }
        // Results of the previous operation must never leak into this one's undo registration.
        movedItems = emptyList()
        trashedIds = emptyList()
        doneCount = 0
        val sink = Sink(id)
        val preset = (req as? OperationRequest.Move)?.preset
        val ctx = TransferContext(fs, trash, sink, { info -> askConflict(id, req.title, info) }, preset)
        try {
            when (req) {
                is OperationRequest.Copy -> doCopy(req, ctx, sink)
                is OperationRequest.Move -> doMove(req, ctx, sink)
                is OperationRequest.Delete -> doDelete(req, ctx, sink)
                is OperationRequest.Compress -> doCompress(req, ctx, sink)
                is OperationRequest.Extract -> doExtract(req, ctx, sink)
                is OperationRequest.Restore -> doRestore(req, ctx, sink)
            }
        } catch (e: CancellationException) {
            sink.publish(true)
            patch(id) { it.copy(status = OpStatus.CANCELLED, errors = ctx.errors.toList(), message = "Cancelled. Items already processed were kept.") }
            _events.tryEmit(OpEvent("${req.title}: cancelled"))
            _changed.tryEmit(Unit)
            registerUndoForPartial(req, ctx)
            return
        } catch (e: Exception) {
            val msg = e.message ?: "Unexpected error"
            sink.publish(true)
            patch(id) { it.copy(status = OpStatus.FAILED, errors = ctx.errors.toList() + msg, message = msg) }
            _events.tryEmit(OpEvent("${req.title}: failed - $msg", isError = true))
            _changed.tryEmit(Unit)
            registerUndoForPartial(req, ctx)
            return
        }
        sink.publish(true)
        val hadErrors = ctx.errors.isNotEmpty()
        val summary = summaryFor(req, ctx)
        val undoId = registerUndoForPartial(req, ctx)
        patch(id) {
            it.copy(
                status = if (hadErrors) OpStatus.DONE_WITH_ERRORS else OpStatus.DONE,
                errors = ctx.errors.toList(), message = summary, current = ""
            )
        }
        _events.tryEmit(OpEvent(summary, isError = hadErrors, undoId = undoId))
        _changed.tryEmit(Unit)
        if (!hadErrors) {
            scope.launch {
                delay(2500)
                dismiss(id)
            }
        }
    }

    // Per-request results needed for undo/summary are kept here (set by the do* functions).
    private var movedItems: List<MovedItem> = emptyList()
    private var trashedIds: List<String> = emptyList()
    private var doneCount = 0

    private data class MovedItem(val newUri: String, val newName: String, val origParent: String, val origName: String)

    private fun summaryFor(req: OperationRequest, ctx: TransferContext): String {
        val failed = ctx.errors.size
        val verb = when (req) {
            is OperationRequest.Copy -> "Copied"
            is OperationRequest.Move -> "Moved"
            is OperationRequest.Delete -> if (req.permanent) "Permanently deleted" else "Moved to trash:"
            is OperationRequest.Compress -> "Created archive"
            is OperationRequest.Extract -> "Extracted"
            is OperationRequest.Restore -> "Restored"
        }
        val base = when (req) {
            is OperationRequest.Compress -> "$verb ${req.archiveName}"
            is OperationRequest.Extract -> "$verb ${req.zip.name}"
            else -> "$verb $doneCount item${if (doneCount == 1) "" else "s"}"
        }
        return if (failed > 0) "$base ($failed problem${if (failed == 1) "" else "s"} - see details)" else base
    }

    /** Registers an undo entry when the finished (or partially finished) request produced something reversible. */
    private fun registerUndoForPartial(req: OperationRequest, ctx: TransferContext): Long? {
        val created = ctx.created.toList()
        val replaced = ctx.replacedTrashIds.toList()
        return when (req) {
            is OperationRequest.Copy -> if (!req.recordUndo || (created.isEmpty() && replaced.isEmpty())) null else
                undo.register("Undo copy") { undoCreated(created, replaced, "Removing copied items") }
            is OperationRequest.Compress -> if (created.isEmpty()) null else
                undo.register("Undo archive") { undoCreated(created, emptyList(), "Removing archive") }
            is OperationRequest.Extract -> if (created.isEmpty()) null else
                undo.register("Undo extract") { undoCreated(created, replaced, "Removing extracted items") }
            is OperationRequest.Move -> {
                val moved = movedItems
                if (!req.recordUndo || moved.isEmpty()) null else undo.register("Undo move") { undoMove(moved, replaced) }
            }
            is OperationRequest.Delete -> {
                val ids = trashedIds
                if (req.permanent || !req.recordUndo || ids.isEmpty()) null else undo.register("Undo delete") {
                    val items = ids.mapNotNull { trash.find(it) }
                    if (items.isNotEmpty()) submit(OperationRequest.Restore(items, null))
                }
            }
            is OperationRequest.Restore -> null
        }
    }

    private fun undoCreated(created: List<String>, replacedIds: List<String>, label: String) {
        scope.launch(Dispatchers.IO) {
            val entries = created.mapNotNull { fs.stat(Uri.parse(it)) }
            if (entries.isNotEmpty()) submit(OperationRequest.Delete(entries, permanent = true, recordUndo = false, label = label))
            val items = replacedIds.mapNotNull { trash.find(it) }
            if (items.isNotEmpty()) submit(OperationRequest.Restore(items, null))
        }
    }

    private fun undoMove(moved: List<MovedItem>, replacedIds: List<String>) {
        scope.launch(Dispatchers.IO) {
            val byParent = moved.groupBy { it.origParent }
            for ((parent, group) in byParent) {
                val entries = group.mapNotNull { m ->
                    fs.stat(Uri.parse(m.newUri))?.copy(parentUri = null)?.let { m to it }
                }
                if (entries.isEmpty()) continue
                // Source parent unknown after the move; fast path is not needed, copy+delete is fully general.
                val sources = entries.map { (_, e) -> e }
                submit(OperationRequest.Move(sources, parent, recordUndo = false, preset = ConflictAction.KEEP_BOTH, label = "Moving items back"))
            }
            val items = replacedIds.mapNotNull { trash.find(it) }
            if (items.isNotEmpty()) submit(OperationRequest.Restore(items, null))
        }
    }

    // ---- individual operations ----------------------------------------------------------------

    private suspend fun doCopy(req: OperationRequest.Copy, ctx: TransferContext, sink: Sink) {
        val dest = Uri.parse(req.destDir)
        val (bytes, _) = engine.measure(req.sources)
        sink.total(bytes, 0)
        doneCount = 0
        for (src in req.sources) {
            if (src.isDirectory && fs.isSameOrInside(Uri.parse(src.uri), dest)) {
                ctx.errors.add("${src.name}: cannot copy a folder into itself")
                continue
            }
            ctx.forceKeepBoth = src.parentUri == req.destDir // duplicating in place: "name (1)", no prompt
            try {
                val o = engine.copy(ctx, src, dest)
                if (o.complete) doneCount++
            } catch (e: CancellationException) {
                throw e
            } catch (e: FileSystem.InsufficientStorageException) {
                throw e
            } catch (e: Exception) {
                ctx.errors.add("${src.name}: ${e.message ?: "failed"}")
            }
            ctx.forceKeepBoth = false
        }
    }

    private suspend fun doMove(req: OperationRequest.Move, ctx: TransferContext, sink: Sink) {
        val dest = Uri.parse(req.destDir)
        val (bytes, _) = engine.measure(req.sources)
        sink.total(bytes, 0)
        doneCount = 0
        val moved = ArrayList<MovedItem>()
        movedItems = emptyList()
        try {
            for (src in req.sources) {
                if (src.parentUri == req.destDir) {
                    ctx.errors.add("${src.name}: already in this folder")
                    continue
                }
                if (src.isDirectory && fs.isSameOrInside(Uri.parse(src.uri), dest)) {
                    ctx.errors.add("${src.name}: cannot move a folder into itself")
                    continue
                }
                try {
                    val o = engine.move(ctx, src, dest)
                    if (o.complete) doneCount++
                    // A moved item exists at the destination whenever the original is gone.
                    if (o.complete && o.uri != null && src.parentUri != null) {
                        moved.add(MovedItem(o.uri, o.name ?: src.name, src.parentUri, src.name))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: FileSystem.InsufficientStorageException) {
                    throw e
                } catch (e: Exception) {
                    ctx.errors.add("${src.name}: ${e.message ?: "failed"}")
                }
            }
        } finally {
            movedItems = moved.toList()
        }
    }

    private suspend fun doDelete(req: OperationRequest.Delete, ctx: TransferContext, sink: Sink) {
        sink.total(req.sources.size.toLong(), 0)
        doneCount = 0
        val ids = ArrayList<String>()
        trashedIds = emptyList()
        try {
            for (src in req.sources) {
                currentCoroutineContext().ensureActive()
                sink.current(src.name)
                try {
                    if (req.permanent) {
                        fs.delete(Uri.parse(src.uri))
                    } else {
                        ids.add(trash.moveToTrash(src).id)
                    }
                    doneCount++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ctx.errors.add("${src.name}: ${e.message ?: "failed"}")
                }
                sink.bytes(1)
                sink.itemDone()
            }
        } finally {
            trashedIds = ids.toList()
        }
    }

    private suspend fun doCompress(req: OperationRequest.Compress, ctx: TransferContext, sink: Sink) {
        val (bytes, _) = engine.measure(req.sources)
        sink.total(bytes, 0)
        val result = zip.create(ctx, req.sources, Uri.parse(req.destDir), req.archiveName)
        doneCount = if (result != null) 1 else 0
        if (result == null) ctx.errors.add("${req.archiveName}: skipped")
    }

    private suspend fun doExtract(req: OperationRequest.Extract, ctx: TransferContext, sink: Sink) {
        var dest = Uri.parse(req.destDir)
        if (req.intoSubfolder) {
            val base = req.zip.name.substringBeforeLast('.', req.zip.name).ifBlank { "Extracted" }
            // An existing folder with the archive's name is merged into; file clashes are asked one by one.
            val existing = ctx.children(dest)[base.lowercase()]
            dest = if (existing != null && existing.isDirectory) Uri.parse(existing.uri) else {
                val name = ctx.prepareTarget(dest, base, true, -1, 0) ?: throw UserCancelledException()
                val created = fs.createDirectory(dest, name)
                ctx.registerCreated(dest, created, name, true, false)
                created
            }
        }
        zip.extract(ctx, req.zip, dest, req.selected)
        doneCount = 1
    }

    private suspend fun doRestore(req: OperationRequest.Restore, ctx: TransferContext, sink: Sink) {
        sink.total(req.items.size.toLong(), 0)
        doneCount = 0
        for (item in req.items) {
            val destUri = req.destDirOverride ?: item.originalParentUri
            if (destUri == null || fs.stat(Uri.parse(destUri)) == null) {
                ctx.errors.add("${item.name}: the original folder is no longer available. Use Trash > Restore to... to pick another folder.")
                continue
            }
            val local = trash.localEntry(item)
            if (local == null) {
                ctx.errors.add("${item.name}: trash data is missing")
                continue
            }
            ctx.forceKeepBoth = false
            // Restoring never overwrites: if the name is taken again, the restored item gets "(1)".
            ctx.applyAll = ConflictAction.KEEP_BOTH
            try {
                sink.current(item.name)
                val o = engine.copy(ctx, local, Uri.parse(destUri))
                if (o.complete) {
                    trash.purge(item.id)
                    doneCount++
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ctx.errors.add("${item.name}: ${e.message ?: "failed"}")
            }
            sink.bytes(1)
        }
    }
}
