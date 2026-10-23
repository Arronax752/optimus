package com.example.optimus.vision

import android.graphics.*
import androidx.camera.core.ImageProxy
import androidx.core.graphics.createBitmap

fun ImageProxy.toBitmapSafe(): Bitmap? {
    return try {
        val plane = planes[0]
        val buffer = plane.buffer
        buffer.rewind()

        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width

        val bitmap = createBitmap(width + rowPadding / pixelStride, height)

        bitmap.copyPixelsFromBuffer(buffer)

        val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)

        // ✅ FIX: APPLY ROTATION + MIRROR
        val matrix = Matrix().apply {
            postRotate(imageInfo.rotationDegrees.toFloat())
            postScale(-1f, 1f) // front camera mirror
        }

        Bitmap.createBitmap(
            cropped,
            0,
            0,
            cropped.width,
            cropped.height,
            matrix,
            true
        )

    } catch (_: Exception) {
        null
    }
}
