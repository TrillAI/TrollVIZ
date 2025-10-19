package com.trill.trillviz

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import java.io.OutputStream

object SaveUtils {
    fun saveBitmapToGallery(ctx: Context, bmp: Bitmap, displayName: String) {
        val mime = "image/jpeg"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/TrillViz")
            }
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        uri?.let {
            var os: OutputStream? = null
            try {
                os = ctx.contentResolver.openOutputStream(it)
                bmp.compress(Bitmap.CompressFormat.JPEG, 90, os)
            } finally {
                os?.close()
            }
        }
    }
}