package com.tiktokmusic.clean

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

object ImageLoader {
    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = maxMemory / 8
    private val memoryCache = object : LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            return bitmap.byteCount / 1024
        }
    }

    private val executor = Executors.newFixedThreadPool(4)
    private val mainHandler = Handler(Looper.getMainLooper())

    fun load(url: String?, imageView: ImageView, isCircle: Boolean = false) {
        if (url.isNullOrEmpty()) return
        val tagUrl = url
        imageView.tag = tagUrl

        val cached = memoryCache.get(url)
        if (cached != null) {
            imageView.setImageBitmap(if (isCircle) getCircularBitmap(cached) else cached)
            return
        }

        executor.execute {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 6000
                conn.readTimeout = 6000
                conn.doInput = true
                conn.connect()
                val input: InputStream = conn.inputStream
                val bitmap = BitmapFactory.decodeStream(input)
                input.close()
                conn.disconnect()

                if (bitmap != null) {
                    memoryCache.put(url, bitmap)
                    mainHandler.post {
                        if (imageView.tag == tagUrl) {
                            imageView.setImageBitmap(if (isCircle) getCircularBitmap(bitmap) else bitmap)
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun getCircularBitmap(src: Bitmap): Bitmap {
        val size = Math.min(src.width, src.height)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = Rect(0, 0, size, size)
        val rectF = RectF(rect)

        val left = (src.width - size) / 2
        val top = (src.height - size) / 2
        val srcRect = Rect(left, top, left + size, top + size)

        canvas.drawARGB(0, 0, 0, 0)
        canvas.drawOval(rectF, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(src, srcRect, rect, paint)
        return output
    }
}