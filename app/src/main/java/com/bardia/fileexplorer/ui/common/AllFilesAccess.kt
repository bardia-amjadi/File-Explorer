package com.bardia.fileexplorer.ui.common

import android.content.ActivityNotFoundException
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bardia.fileexplorer.app.LocalContainer
import com.bardia.fileexplorer.design.FluentCard

/** Opens Android's "All files access" screen for this app (falls back to the generic list on odd devices). */
fun openAllFilesSettings(context: Context, c: com.bardia.fileexplorer.app.AppContainer) {
    try {
        context.startActivity(c.storage.allFilesIntent())
    } catch (e: ActivityNotFoundException) {
        try { context.startActivity(c.storage.allFilesFallbackIntent()) } catch (e2: ActivityNotFoundException) { }
    }
}

/** Shows the current all-files-access state and a button to change it. Re-checks whenever the app resumes. */
@Composable
fun AllFilesAccessCard(modifier: Modifier = Modifier) {
    val c = LocalContainer.current
    val ctx = LocalContext.current
    val tick by rememberResumeTick()
    val granted by produceState(c.storage.isAllFilesGranted(), tick) { value = c.storage.isAllFilesGranted() }
    FluentCard(modifier) {
        Column(Modifier.padding(14.dp)) {
            Text(if (granted) "All files access: ON" else "All files access: OFF", style = MaterialTheme.typography.titleSmall)
            Text(
                if (granted) "You can browse real paths like /storage/emulated/0 and your SD card or USB drive. " +
                    "Android still hides /Android/data and /Android/obb from every normal app; that cannot be unlocked."
                else "Turn this on to browse every folder on the device without picking folders one by one.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
            )
            Button(onClick = { openAllFilesSettings(ctx, c) }) { Text(if (granted) "Open settings" else "Allow all files access") }
        }
    }
}
