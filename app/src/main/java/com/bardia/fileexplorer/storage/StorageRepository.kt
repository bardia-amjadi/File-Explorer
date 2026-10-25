package com.bardia.fileexplorer.storage

import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.DocumentsContract
import com.bardia.fileexplorer.core.Crumb
import com.bardia.fileexplorer.persistence.GrantedRoot
import com.bardia.fileexplorer.persistence.MetadataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Real capacity numbers for one mounted storage volume (null when Android does not expose them). */
data class VolumeInfo(
    val description: String,
    val isPrimary: Boolean,
    val isRemovable: Boolean,
    val mounted: Boolean,
    val totalBytes: Long?,
    val freeBytes: Long?,
    /** Intent for the system folder picker pre-targeted at this volume. */
    val grantIntent: Intent?
) {
    val usedBytes: Long? get() = if (totalBytes != null && freeBytes != null) totalBytes - freeBytes else null
    val usedFraction: Float? get() = if (totalBytes != null && totalBytes > 0 && usedBytes != null) (usedBytes!!.toFloat() / totalBytes).coerceIn(0f, 1f) else null
}

enum class RootStatus { OK, ACCESS_REVOKED, UNAVAILABLE }
data class RootState(val root: GrantedRoot, val status: RootStatus)

/**
 * Centralised storage-access layer: which folders the user granted (persisted SAF grants), whether they are still
 * valid, and which volumes exist. This is the only place that calls take/releasePersistableUriPermission.
 */
class StorageRepository(
    private val context: Context,
    private val fs: FileSystem,
    private val metadata: MetadataStore
) {
    private val resolver get() = context.contentResolver

    val rootsFlow get() = metadata.roots

    /** Registers a folder returned by ACTION_OPEN_DOCUMENT_TREE. Returns the stored root, or throws a readable error. */
    suspend fun addRoot(treeUri: Uri): GrantedRoot = withContext(Dispatchers.IO) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            resolver.takePersistableUriPermission(treeUri, flags)
        } catch (e: SecurityException) {
            // Some providers (read-only media, some USB stacks) only grant read access.
            try {
                resolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e2: SecurityException) {
                throw IllegalStateException("Android did not allow keeping access to this folder.")
            }
        }
        val rootDoc = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        val name = fs.stat(rootDoc)?.name ?: rootDoc.lastPathSegment ?: "Folder"
        val root = GrantedRoot(treeUri.toString(), rootDoc.toString(), name)
        metadata.addRoot(root)
        root
    }

    suspend fun removeRoot(root: GrantedRoot) = withContext(Dispatchers.IO) {
        try {
            resolver.releasePersistableUriPermission(
                Uri.parse(root.treeUri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) {
            // Already released or never persisted: nothing more to do.
        }
        metadata.removeRoot(root.treeUri)
    }

    /** Checks every stored root against the system's persisted grants and the provider. */
    suspend fun checkRoots(): List<RootState> = withContext(Dispatchers.IO) {
        val persisted = resolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri.toString() }.toSet()
        val allFiles = isAllFilesGranted()
        metadata.roots.first().map { r ->
            val status = when {
                r.treeUri.startsWith("file:") -> when {
                    !allFiles -> RootStatus.ACCESS_REVOKED
                    !java.io.File(Uri.parse(r.rootUri).path ?: "").exists() -> RootStatus.UNAVAILABLE
                    else -> RootStatus.OK
                }
                r.treeUri !in persisted -> RootStatus.ACCESS_REVOKED
                fs.stat(Uri.parse(r.rootUri)) == null -> RootStatus.UNAVAILABLE
                else -> RootStatus.OK
            }
            RootState(r, status)
        }
    }

    /** True when the user enabled "All files access" for this app in system settings. */
    fun isAllFilesGranted(): Boolean = android.os.Environment.isExternalStorageManager()

    /** Intent that opens this app's "All files access" switch. */
    fun allFilesIntent(): Intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))

    fun allFilesFallbackIntent(): Intent = Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)

    /** With all-files access: registers every mounted volume as a real-path root; without it: forgets those roots. */
    suspend fun syncLocalRoots() = withContext(Dispatchers.IO) {
        val sm = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        val current = metadata.roots.first().filter { it.treeUri.startsWith("file:") }
        if (!isAllFilesGranted()) {
            current.forEach { metadata.removeRoot(it.treeUri) }
            return@withContext
        }
        val wanted = sm.storageVolumes.filter { it.state == android.os.Environment.MEDIA_MOUNTED || it.state == android.os.Environment.MEDIA_MOUNTED_READ_ONLY }
            .mapNotNull { it.directory }
        for (dir in wanted) {
            val uri = Uri.fromFile(dir).toString()
            if (current.none { it.treeUri == uri }) metadata.addRoot(GrantedRoot(uri, uri, dir.path))
        }
        current.filter { c -> wanted.none { Uri.fromFile(it).toString() == c.treeUri } }.forEach { metadata.removeRoot(it.treeUri) }
    }

    fun volumes(): List<VolumeInfo> {
        val sm = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        val stats = context.getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
        return sm.storageVolumes.map { v -> volumeInfo(v, stats) }
    }

    private fun volumeInfo(v: StorageVolume, stats: StorageStatsManager): VolumeInfo {
        val mounted = v.state == android.os.Environment.MEDIA_MOUNTED || v.state == android.os.Environment.MEDIA_MOUNTED_READ_ONLY
        var total: Long? = null
        var free: Long? = null
        if (mounted) {
            try {
                if (v.isPrimary) {
                    // Reports the advertised capacity (like the system Settings app) without any permission.
                    total = stats.getTotalBytes(StorageManager.UUID_DEFAULT)
                    free = stats.getFreeBytes(StorageManager.UUID_DEFAULT)
                } else {
                    val dir = v.directory
                    if (dir != null) {
                        val sf = StatFs(dir.path)
                        total = sf.totalBytes
                        free = sf.availableBytes
                    }
                }
            } catch (e: Exception) {
                total = null
                free = null // Unknown: the UI shows "unavailable" instead of a made-up number.
            }
        }
        return VolumeInfo(
            description = v.getDescription(context) ?: "Storage",
            isPrimary = v.isPrimary,
            isRemovable = v.isRemovable,
            mounted = mounted,
            totalBytes = total,
            freeBytes = free,
            grantIntent = if (mounted) v.createOpenDocumentTreeIntent() else null
        )
    }

    companion object {
        private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

        /** Initial-folder hint for the system picker, e.g. "Documents" or "DCIM/Camera" on primary storage. */
        fun primaryFolderHint(relativePath: String): Uri =
            DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, "primary:$relativePath")

        /**
         * If [root] contains (or is) the primary-storage folder [relativePath], returns the trail leading to it.
         * Works with the external storage provider, whose document ids are "volume:path/segments".
         */
        fun trailTo(root: GrantedRoot, relativePath: String): List<Crumb>? {
            if (root.treeUri.startsWith("file:")) {
                val base = java.io.File(Uri.parse(root.rootUri).path ?: return null)
                if (!base.path.endsWith("/emulated/0")) return null
                var dir = base
                val trail = arrayListOf(Crumb(root.rootUri, root.name))
                for (seg in relativePath.split('/').filter { it.isNotEmpty() }) {
                    dir = java.io.File(dir, seg)
                    if (!dir.isDirectory) return null
                    trail.add(Crumb(Uri.fromFile(dir).toString(), seg))
                }
                return trail
            }
            return try {
                val tree = Uri.parse(root.treeUri)
                val rootId = DocumentsContract.getTreeDocumentId(tree)
                val target = "primary:$relativePath"
                val base = Crumb(root.rootUri, root.name)
                when {
                    rootId == target -> listOf(base)
                    target.startsWith(if (rootId.endsWith(":") || rootId.endsWith("/")) rootId else "$rootId/") -> {
                        val rest = target.removePrefix(rootId).trimStart('/', ':').split('/').filter { it.isNotEmpty() }
                        var id = rootId
                        val trail = arrayListOf(base)
                        for (seg in rest) {
                            id = if (id.endsWith(":") || id.endsWith("/")) id + seg else "$id/$seg"
                            trail.add(Crumb(DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(), seg))
                        }
                        trail
                    }
                    else -> null
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}
