package com.benomad.rncdemo.data

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.core.content.FileProvider
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/** Icon ready for `showPoi`: URI readable by BeNav and display size. */
data class PoiIcon(val url: String, val width: Int, val height: Int)

/**
 * Collection point icons on BeNav's map: one PNG per waste stream, bundled in the
 * app (`assets/poi/dropoff-<stream>.png`). No network
 * access: the app copies the PNG to its storage and shares it with BeNav via `content://`
 * (FileProvider), the only way for BeNav to read an app file (`file://` is refused).
 *
 * `showPoi` is all-or-nothing: one unreadable icon and no point shows up.
 */
class PoiIcons(context: Context) {

    private val appContext = context.applicationContext
    private val cache = mutableMapOf<WasteStream, PoiIcon>()

    /** Icon for the stream, with read access granted to BeNav. Reads the disk: call off the main thread. */
    fun forStream(stream: WasteStream): PoiIcon = cache.getOrPut(stream) {
        val name = fileFor(stream)
        val dir = File(appContext.filesDir, "poi").apply { mkdirs() }
        val file = File(dir, name)
        appContext.assets.open("poi/$name").use { input -> file.outputStream().use { input.copyTo(it) } }
        val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", file)
        appContext.grantUriPermission(RncClient.BENAV_PACKAGE, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // BeNav stretches the image to the requested size: keep its aspect ratio.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val scale = ICON_SIZE.toDouble() / max(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
        PoiIcon(
            uri.toString(),
            (bounds.outWidth * scale).roundToInt().coerceAtLeast(1),
            (bounds.outHeight * scale).roundToInt().coerceAtLeast(1),
        )
    }

    companion object {
        /** Longest side of an icon on BeNav's map. */
        private const val ICON_SIZE = 64

        fun fileFor(stream: WasteStream) = "dropoff-${stream.name.lowercase()}.png"
    }
}
