package com.homesentinel.owner.ui

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.homesentinel.owner.R
import com.homesentinel.owner.data.AlertRecord
import com.homesentinel.owner.data.CloudDatabaseManager
import com.homesentinel.owner.data.OwnerDatabase
import com.homesentinel.owner.databinding.ActivityAlertLogBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AlertLogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlertLogBinding
    private lateinit var db: OwnerDatabase
    private lateinit var adapter: OwnerAlertAdapter
    private lateinit var cloudDbManager: CloudDatabaseManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAlertLogBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = OwnerDatabase.getInstance(this)
        cloudDbManager = CloudDatabaseManager(this)

        setupRecyclerView()
        setupListeners()
        loadLocalAlerts()

        // Realtime sync from Cloud Database (No WiFi needed!)
        cloudDbManager.startListeningToSecurityAlerts { alertList, _ ->
            runOnUiThread {
                adapter.submitList(alertList)
                binding.tvEmptyAlerts.visibility = if (alertList.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun setupRecyclerView() {
        adapter = OwnerAlertAdapter { alert ->
            showDetailDialog(alert)
        }
        binding.rvAlerts.layoutManager = LinearLayoutManager(this)
        binding.rvAlerts.adapter = adapter
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnClearAlerts.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Clear Alerts")
                .setMessage("Clear all security alerts from Cloud and phone storage?")
                .setPositiveButton("Clear All") { _, _ ->
                    cloudDbManager.clearAllAlerts {
                        runOnUiThread {
                            adapter.submitList(emptyList())
                            binding.tvEmptyAlerts.visibility = View.VISIBLE
                            Toast.makeText(this@AlertLogActivity, "All alerts wiped clean!", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun loadLocalAlerts() {
        lifecycleScope.launch(Dispatchers.IO) {
            val list = db.alertRecordDao().getAll()
            withContext(Dispatchers.Main) {
                adapter.submitList(list)
                binding.tvEmptyAlerts.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun showDetailDialog(alert: AlertRecord) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_large_image_preview, null)
        val ivSnapshot = dialogView.findViewById<com.homesentinel.owner.ui.ZoomableImageView>(R.id.ivLargeSnapshot)
        val tvBadge = dialogView.findViewById<TextView>(R.id.tvDialogBadge)
        val tvLabel = dialogView.findViewById<TextView>(R.id.tvDialogLabel)
        val tvTimestamp = dialogView.findViewById<TextView>(R.id.tvDialogTimestamp)
        val tvConfidence = dialogView.findViewById<TextView>(R.id.tvDialogConfidence)
        val btnClose = dialogView.findViewById<Button>(R.id.btnDialogClose)

        tvBadge.text = "🚨 ${alert.alertType.uppercase()}"
        tvConfidence.text = "${(alert.confidence * 100).toInt()}% Confidence"
        tvLabel.text = alert.label
        val df = SimpleDateFormat("EEEE, dd MMM yyyy • hh:mm:ss a", Locale.getDefault())
        tvTimestamp.text = df.format(Date(alert.timestamp))

        val path = alert.snapshotPath
        if (!path.isNullOrEmpty()) {
            if (path.length > 200 && !path.startsWith("http") && !path.startsWith("/")) {
                try {
                    val decodedBytes = Base64.decode(path, Base64.DEFAULT)
                    val bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
                    ivSnapshot.setImageBitmap(bitmap)
                    ivSnapshot.post { ivSnapshot.fitImageToView() }
                } catch (e: Exception) {
                    ivSnapshot.setImageResource(R.drawable.ic_camera)
                }
            } else {
                Glide.with(this)
                    .asBitmap()
                    .load(path)
                    .placeholder(R.drawable.ic_camera)
                    .error(R.drawable.ic_camera)
                    .into(object : com.bumptech.glide.request.target.CustomTarget<Bitmap>() {
                        override fun onResourceReady(resource: Bitmap, transition: com.bumptech.glide.request.transition.Transition<in Bitmap>?) {
                            ivSnapshot.setImageBitmap(resource)
                            ivSnapshot.post { ivSnapshot.fitImageToView() }
                        }
                        override fun onLoadCleared(placeholder: android.graphics.drawable.Drawable?) {
                            ivSnapshot.setImageDrawable(placeholder)
                        }
                    })
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    override fun onDestroy() {
        super.onDestroy()
        cloudDbManager.stopListening()
    }
}
