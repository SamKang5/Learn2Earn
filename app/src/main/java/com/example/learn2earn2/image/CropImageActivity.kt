package com.example.learn2earn2.image

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.example.learn2earn2.R

class CropImageActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crop_image)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, bars.top, view.paddingRight, bars.bottom)
            insets
        }
        val cropView = findViewById<CropImageView>(R.id.crop_image_view)
        val source = intent.getStringExtra(EXTRA_SOURCE_URI)?.let(android.net.Uri::parse)
        if (source == null) {
            finish()
            return
        }
        runCatching { cropView.setImage(source, intent.getFloatExtra(EXTRA_ASPECT_RATIO, 4f / 3f)) }
            .onFailure {
                Toast.makeText(this, "Could not open image", Toast.LENGTH_SHORT).show()
                finish()
            }
        findViewById<Button>(R.id.btn_crop_cancel).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_crop_apply).setOnClickListener {
            val result = cropView.cropToFile()
            if (result == null) return@setOnClickListener
            setResult(RESULT_OK, Intent().putExtra(EXTRA_CROPPED_URI, result.toString()))
            finish()
        }
    }

    companion object {
        const val EXTRA_SOURCE_URI = "source_uri"
        const val EXTRA_CROPPED_URI = "cropped_uri"
        const val EXTRA_ASPECT_RATIO = "aspect_ratio"

        fun intent(context: Context, sourceUri: String, aspectRatio: Float) = Intent(context, CropImageActivity::class.java)
            .putExtra(EXTRA_SOURCE_URI, sourceUri)
            .putExtra(EXTRA_ASPECT_RATIO, aspectRatio)
    }
}
