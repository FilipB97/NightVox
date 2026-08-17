package pl.nightvox.util

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/** Wysyłka plików na zewnątrz apki. Zawsze przez FileProvider — pliki są app-private. */
object Sharing {

    private fun authority(context: Context) = "${context.packageName}.fileprovider"

    fun uriFor(context: Context, file: File) =
        FileProvider.getUriForFile(context, authority(context), file)

    fun shareFile(context: Context, file: File, mimeType: String, title: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uriFor(context, file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
