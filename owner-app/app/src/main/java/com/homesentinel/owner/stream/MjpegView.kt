package com.homesentinel.owner.stream

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class MjpegView(context: Context, attrs: AttributeSet?) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    private val tag = "MjpegView"
    private var streamThread: Thread? = null
    private val isStreaming = AtomicBoolean(false)
    private var streamUrl: String? = null
    private val currentBitmap = AtomicReference<Bitmap?>(null)

    private val paint = Paint().apply {
        isAntiAlias = true
        isFilterBitmap = true
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // infinite stream
        .build()

    init {
        holder.addCallback(this)
    }

    fun startStream(url: String) {
        streamUrl = url
        if (isStreaming.get()) {
            stopStream()
        }
        isStreaming.set(true)
        streamThread = Thread(::streamLoop, "MjpegStreamWorker").apply { start() }
    }

    fun stopStream() {
        isStreaming.set(false)
        streamThread?.interrupt()
        streamThread = null
    }

    fun captureCurrentFrame(): Bitmap? = currentBitmap.get()

    private fun streamLoop() {
        while (isStreaming.get() && !Thread.currentThread().isInterrupted) {
            val url = streamUrl ?: break
            var response: Response? = null
            var inputStream: InputStream? = null

            try {
                val request = Request.Builder().url(url).build()
                response = client.newCall(request).execute()
                val body = response.body ?: continue
                inputStream = BufferedInputStream(body.byteStream())

                readMjpegFrames(inputStream)
            } catch (e: Exception) {
                Log.d(tag, "Stream disconnected, retrying in 2s: ${e.message}")
                try {
                    Thread.sleep(2000)
                } catch (ie: InterruptedException) {
                    break
                }
            } finally {
                try {
                    inputStream?.close()
                    response?.close()
                } catch (e: Exception) {
                    // Ignore
                }
            }
        }
    }

    private fun readMjpegFrames(stream: InputStream) {
        val headerBuffer = ByteArray(256)
        while (isStreaming.get()) {
            // Find start of JPEG (SOI: 0xFF, 0xD8)
            var b = stream.read()
            while (b != -1) {
                if (b == 0xFF) {
                    val next = stream.read()
                    if (next == 0xD8) {
                        break
                    }
                }
                b = stream.read()
            }
            if (b == -1) break

            // Read until end of JPEG (EOI: 0xFF, 0xD9)
            val frameBytes = ArrayList<Byte>(16384)
            frameBytes.add(0xFF.toByte())
            frameBytes.add(0xD8.toByte())

            var prev = 0xD8
            var curr = stream.read()
            while (curr != -1) {
                frameBytes.add(curr.toByte())
                if (prev == 0xFF && curr == 0xD9) {
                    break
                }
                prev = curr
                curr = stream.read()
            }

            if (curr == -1) break

            val rawArray = ByteArray(frameBytes.size)
            for (i in frameBytes.indices) {
                rawArray[i] = frameBytes[i]
            }

            val bitmap = BitmapFactory.decodeByteArray(rawArray, 0, rawArray.size)
            if (bitmap != null) {
                currentBitmap.set(bitmap)
                drawFrame(bitmap)
            }
        }
    }

    private fun drawFrame(bitmap: Bitmap) {
        val canvas: Canvas? = holder.lockCanvas()
        if (canvas != null) {
            try {
                canvas.drawColor(Color.BLACK)
                val destRect = Rect(0, 0, width, height)
                val srcRect = Rect(0, 0, bitmap.width, bitmap.height)
                canvas.drawBitmap(bitmap, srcRect, destRect, paint)
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        streamUrl?.let { startStream(it) }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopStream()
    }
}
