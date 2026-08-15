package com.example.learn2earn2.quiz

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream

/** Converts a parent-only content Uri into compact data another device can display. */
object QuizImageData {
    private const val MAX_SIDE = 640
    private const val MAX_BYTES = 72 * 1024

    fun encode(context: Context, rawUri: String): String? = runCatching {
        val source = context.contentResolver.openInputStream(Uri.parse(rawUri))?.use(BitmapFactory::decodeStream)
            ?: return null
        val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(source.width, source.height).coerceAtLeast(1))
        var bitmap = if (scale < 1f) Bitmap.createScaledBitmap(source, (source.width * scale).toInt(), (source.height * scale).toInt(), true) else source
        var ownsBitmap = bitmap !== source
        val output = ByteArrayOutputStream()
        repeat(6) {
            output.reset()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 72, output)
            if (output.size() <= MAX_BYTES) {
                val encoded = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
                if (ownsBitmap) bitmap.recycle()
                source.recycle()
                return encoded
            }
            val nextWidth = (bitmap.width * 0.72f).toInt()
            val nextHeight = (bitmap.height * 0.72f).toInt()
            if (nextWidth < 96 || nextHeight < 96) return@repeat
            val smaller = Bitmap.createScaledBitmap(bitmap, nextWidth, nextHeight, true)
            if (ownsBitmap) bitmap.recycle()
            bitmap = smaller
            ownsBitmap = true
        }
        if (ownsBitmap) bitmap.recycle()
        source.recycle()
        null
    }.getOrNull()
}
