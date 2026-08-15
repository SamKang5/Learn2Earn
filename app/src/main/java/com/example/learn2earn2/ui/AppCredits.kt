package com.example.learn2earn2.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

object AppCredits {
    private data class Builder(val name: String, val url: String)

    private val builders = listOf(
        Builder("Đỗ Ngọc Thiên Bảo (elax)", "https://elaxuwu.me/"),
        Builder("Phạm Hoàng Khang", "https://github.com/SamKang5")
    )

    fun show(context: Context) {
        AlertDialog.Builder(context)
            .setTitle("Built by")
            .setItems(builders.map { it.name }.toTypedArray()) { _, index ->
                openLink(context, builders[index].url)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun openLink(context: Context, url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        runCatching { context.startActivity(intent) }
            .onFailure { Toast.makeText(context, "Couldn't open link.", Toast.LENGTH_SHORT).show() }
    }
}
