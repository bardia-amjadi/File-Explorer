package com.bardia.fileexplorer.util

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import com.bardia.fileexplorer.core.FileEntry
import com.bardia.fileexplorer.core.FileTypes

/**
 * Hands files to other apps. Only secure `content://` URIs are ever passed out (document-provider URIs with a
 * temporary read grant); `file://` URIs are never exposed, and app-private files are skipped here.
 */
object IntentUtil {
    fun mimeFor(entry: FileEntry): String =
        FileTypes.effectiveMime(entry)
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(entry.extension)
            ?: "*/*"

    /** Opens a file in another app (with a chooser). Returns an error message, or null on success. */
    fun open(context: Context, entry: FileEntry): String? {
        if (entry.isLocal) return "This file lives in the app's private storage and cannot be opened by other apps."
        val uri = Uri.parse(entry.uri)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mimeFor(entry))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData = ClipData.newRawUri(entry.name, uri)
        return try {
            context.startActivity(Intent.createChooser(intent, "Open ${entry.name} with"))
            null
        } catch (e: ActivityNotFoundException) {
            "No installed app can open this type of file."
        } catch (e: SecurityException) {
            "Android blocked access to this file."
        }
    }

    /** Shares files via the system Sharesheet. Folders cannot be shared; the message explains that. */
    fun share(context: Context, entries: List<FileEntry>): String? {
        val files = entries.filter { !it.isDirectory && !it.isLocal }
        val folders = entries.count { it.isDirectory }
        if (files.isEmpty()) {
            return if (folders > 0) "Folders cannot be shared directly. Create a ZIP archive first, then share the archive." else "Nothing to share."
        }
        val uris = files.map { Uri.parse(it.uri) }
        val intent = if (files.size == 1) {
            Intent(Intent.ACTION_SEND).setType(mimeFor(files[0])).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            val mimes = files.map { mimeFor(it) }.toSet()
            val type = if (mimes.size == 1) mimes.first() else "*/*"
            Intent(Intent.ACTION_SEND_MULTIPLE).setType(type).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        val clip = ClipData.newRawUri("files", uris[0])
        for (i in 1 until uris.size) clip.addItem(ClipData.Item(uris[i]))
        intent.clipData = clip
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return try {
            context.startActivity(Intent.createChooser(intent, "Share"))
            if (folders > 0) "Shared ${files.size} file(s). $folders folder(s) were skipped (folders cannot be shared)." else null
        } catch (e: ActivityNotFoundException) {
            "No app available to share with."
        } catch (e: SecurityException) {
            "Android blocked sharing this file."
        }
    }

    fun copyText(context: Context, label: String, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    }
}
