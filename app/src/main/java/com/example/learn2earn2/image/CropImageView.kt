package com.example.learn2earn2.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.max

class CropImageView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shadePaint = Paint().apply { color = Color.argb(150, 0, 0, 0) }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2
    }
    private val imageMatrix = Matrix()
    private val cropRect = RectF()
    private var bitmap: Bitmap? = null
    private var aspectRatio = 4f / 3f
    private var scale = 1f
    private var baseScale = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var isScaling = false
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            isScaling = true
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scale = (scale * detector.scaleFactor).coerceIn(baseScale, baseScale * 4f)
            constrainImage()
            invalidate()
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            isScaling = false
        }
    })

    fun setImage(uri: Uri, ratio: Float) {
        aspectRatio = ratio
        bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        post { resetImage() }
    }

    fun cropToFile(): Uri? {
        val source = bitmap ?: return null
        val width = cropRect.width().toInt().coerceAtLeast(1)
        val height = cropRect.height().toInt().coerceAtLeast(1)
        val cropped = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(cropped).apply {
            translate(-cropRect.left, -cropRect.top)
            drawBitmap(source, imageMatrix, imagePaint)
        }
        val outputDir = File(context.filesDir, "quiz-images").apply { mkdirs() }
        val file = File(outputDir, "quiz-crop-${UUID.randomUUID()}.jpg")
        FileOutputStream(file).use { cropped.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        return Uri.fromFile(file)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resetImage()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(18, 33, 43))
        val source = bitmap ?: return
        canvas.drawBitmap(source, imageMatrix, imagePaint)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shadePaint)
        canvas.save()
        canvas.clipRect(cropRect)
        canvas.drawBitmap(source, imageMatrix, imagePaint)
        canvas.restore()
        canvas.drawRect(cropRect, borderPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_MOVE -> if (!isScaling) {
                offsetX += event.x - lastX
                offsetY += event.y - lastY
                lastX = event.x
                lastY = event.y
                constrainImage()
                invalidate()
            }
        }
        return true
    }

    private fun resetImage() {
        val source = bitmap ?: return
        if (width == 0 || height == 0) return
        val margin = resources.displayMetrics.density * 20
        val availableWidth = width - margin * 2
        val availableHeight = height - margin * 2
        val cropWidth: Float
        val cropHeight: Float
        if (availableWidth / availableHeight > aspectRatio) {
            cropHeight = availableHeight
            cropWidth = cropHeight * aspectRatio
        } else {
            cropWidth = availableWidth
            cropHeight = cropWidth / aspectRatio
        }
        cropRect.set(
            (width - cropWidth) / 2f,
            (height - cropHeight) / 2f,
            (width + cropWidth) / 2f,
            (height + cropHeight) / 2f
        )
        baseScale = max(cropRect.width() / source.width, cropRect.height() / source.height)
        scale = baseScale
        offsetX = cropRect.centerX() - source.width * scale / 2f
        offsetY = cropRect.centerY() - source.height * scale / 2f
        updateMatrix()
        invalidate()
    }

    private fun constrainImage() {
        val source = bitmap ?: return
        val imageWidth = source.width * scale
        val imageHeight = source.height * scale
        offsetX = offsetX.coerceIn(cropRect.right - imageWidth, cropRect.left)
        offsetY = offsetY.coerceIn(cropRect.bottom - imageHeight, cropRect.top)
        updateMatrix()
    }

    private fun updateMatrix() {
        imageMatrix.reset()
        imageMatrix.postScale(scale, scale)
        imageMatrix.postTranslate(offsetX, offsetY)
    }
}
