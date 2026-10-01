package com.localgpt.app.ui.chat

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File

/**
 * Blocking bitmap decoders with downsampling to roughly [reqSizePx] on the
 * longest side. Pure functions — always call from Dispatchers.IO (e.g. inside
 * `produceState`), never directly from composition.
 *
 * Extracted for B38: decoding on the main thread inside `remember {}` dropped
 * frames, and full-size decodes wasted memory for thumbnails and bubbles.
 * RGB_565 halves memory vs ARGB_8888; photos don't need an alpha channel.
 */

fun decodeSampledBitmapFromUri(
    resolver: ContentResolver,
    uri: Uri,
    reqSizePx: Int,
): Bitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val sample = sampleSizeFor(maxOf(bounds.outWidth, bounds.outHeight), reqSizePx)
        if (sample <= 0) {
            null
        } else {
            val opts =
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        }
    } catch (_: Throwable) {
        null
    }
}

fun decodeSampledBitmapFromFile(
    file: File,
    reqSizePx: Int,
): Bitmap? {
    if (!file.exists()) return null
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val sample = sampleSizeFor(maxOf(bounds.outWidth, bounds.outHeight), reqSizePx)
        if (sample <= 0) {
            null
        } else {
            val opts =
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
            BitmapFactory.decodeFile(file.absolutePath, opts)
        }
    } catch (_: Throwable) {
        null
    }
}

/** Largest power-of-two sample size keeping the longest side >= [reqSizePx]. */
private fun sampleSizeFor(
    maxDimension: Int,
    reqSizePx: Int,
): Int {
    if (maxDimension <= 0 || reqSizePx <= 0) return -1
    var sample = 1
    while (maxDimension / (sample * 2) >= reqSizePx) sample *= 2
    return sample
}
