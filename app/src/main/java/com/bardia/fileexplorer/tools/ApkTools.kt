package com.bardia.fileexplorer.tools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.bardia.fileexplorer.core.FileEntry
import com.bardia.fileexplorer.storage.FileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class ApkDetails(
    val label: String,
    val packageName: String,
    val versionName: String?,
    val versionCode: Long,
    val minSdk: Int?,
    val targetSdk: Int,
    val permissions: List<String>,
    val icon: Drawable?,
    val cachedFile: File
)

/**
 * Reads package information from an APK and hands it to the system installer. The app never installs silently:
 * Android's installer always shows its own confirmation, and the "install unknown apps" permission is granted by
 * the user in system settings. Package visibility rules mean the installed version of an app is intentionally
 * not queried (no QUERY_ALL_PACKAGES).
 */
class ApkTools(private val context: Context, private val fs: FileSystem) {

    private val dir get() = File(context.cacheDir, "apk").apply { mkdirs() }

    /** Copies the APK to the cache (the PackageManager needs a real path) and parses it. */
    suspend fun inspect(apk: FileEntry): ApkDetails = withContext(Dispatchers.IO) {
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "inspect.apk")
        fs.openInput(Uri.parse(apk.uri)).use { input -> target.outputStream().use { input.copyTo(it, 128 * 1024) } }
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val info = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageArchiveInfo(target.path, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
        } else {
            pm.getPackageArchiveInfo(target.path, PackageManager.GET_PERMISSIONS)
        } ?: throw IllegalStateException("This file is not a valid APK package")
        val appInfo = info.applicationInfo ?: throw IllegalStateException("Package has no application info")
        appInfo.sourceDir = target.path
        appInfo.publicSourceDir = target.path
        ApkDetails(
            label = appInfo.loadLabel(pm).toString(),
            packageName = info.packageName,
            versionName = info.versionName,
            versionCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong(),
            minSdk = if (Build.VERSION.SDK_INT >= 24) appInfo.minSdkVersion else null,
            targetSdk = appInfo.targetSdkVersion,
            permissions = info.requestedPermissions?.toList().orEmpty(),
            icon = try { appInfo.loadIcon(pm) } catch (e: Exception) { null },
            cachedFile = target
        )
    }

    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Opens the system screen where the user can allow this app to hand over APKs for installation. */
    fun unknownSourcesIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /** Intent for the system package installer (user confirmation is always shown by Android). */
    fun installIntent(details: ApkDetails): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", details.cachedFile)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
