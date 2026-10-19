package com.homesentinel.owner.data

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.sqrt

object FaceEmbeddingUtils {

    fun extractEmbedding(bitmap: Bitmap): FloatArray {
        val scaled = Bitmap.createScaledBitmap(bitmap, 8, 8, true)
        val features = FloatArray(64)
        var sum = 0.0f

        var idx = 0
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                val pixel = scaled.getPixel(x, y)
                val r = Color.red(pixel) / 255.0f
                val g = Color.green(pixel) / 255.0f
                val b = Color.blue(pixel) / 255.0f
                val luminance = 0.299f * r + 0.587f * g + 0.114f * b
                features[idx] = luminance
                sum += luminance * luminance
                idx++
            }
        }

        val norm = sqrt(sum)
        if (norm > 0) {
            for (i in features.indices) {
                features[i] /= norm
            }
        }
        return features
    }

    fun serializeEmbedding(embedding: FloatArray): String {
        return embedding.joinToString(",") { it.toString() }
    }
}
