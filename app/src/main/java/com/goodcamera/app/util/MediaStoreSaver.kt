package com.goodcamera.app.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.IOException
import java.io.OutputStream

/**
 * MediaStore (DCIM/GoodCamera) への保存ヘルパー。
 *
 * - Android 10 以降は IS_PENDING を立てて書き込み、完了後に公開する
 *   (書き込み途中のファイルが他アプリやギャラリーに見えないようにする)
 * - 書き込みに失敗した場合は作成した項目を削除し、空のファイルを残さない
 */
object MediaStoreSaver {

    private val RELATIVE_DIR = Environment.DIRECTORY_DCIM + "/GoodCamera"

    /**
     * ストリームに書き込んで保存し、保存先 URI を文字列で返す。
     * 失敗時は例外を投げる (作成済みの項目は削除される)。
     */
    fun save(
        context: Context,
        filename: String,
        mimeType: String,
        write: (OutputStream) -> Unit,
    ): String {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_DIR)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore insert failed: $filename")

        try {
            val out = resolver.openOutputStream(uri)
                ?: throw IOException("Cannot open output stream: $uri")
            out.use { write(it) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val publish = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                resolver.update(uri, publish, null, null)
            }
            return uri.toString()
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
    }

    /** Bitmap を JPEG として保存する。 */
    fun saveJpeg(context: Context, bitmap: Bitmap, filename: String, quality: Int = 95): String {
        return save(context, filename, "image/jpeg") { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) {
                throw IOException("JPEG compress failed")
            }
        }
    }
}
