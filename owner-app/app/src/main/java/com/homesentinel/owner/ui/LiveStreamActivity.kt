package com.homesentinel.owner.ui

import android.graphics.Bitmap
import android.os.Bundle
import android.view.animation.AnimationUtils
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.homesentinel.owner.R
import com.homesentinel.owner.databinding.ActivityLiveStreamBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

class LiveStreamActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLiveStreamBinding
    private val client = OkHttpClient()
    private var isSirenFired = false
    private var monitorBaseUrl = "http://192.168.1.100:8080"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLiveStreamBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences("home_sentinel_owner", MODE_PRIVATE)
        val ip = prefs.getString("monitor_ip", "192.168.1.100") ?: "192.168.1.100"
        val port = prefs.getString("monitor_port", "8080") ?: "8080"
        monitorBaseUrl = "http://$ip:$port"

        setupUI()
        setupListeners()
    }

    private fun setupUI() {
        val streamUrl = "$monitorBaseUrl/stream"
        binding.tvStreamUrl.text = streamUrl

        val pulseAnim = AnimationUtils.loadAnimation(this, R.anim.pulse)
        binding.tvLivePill.startAnimation(pulseAnim)

        // Start MJPEG decoding
        binding.mjpegView.startStream(streamUrl)
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnTakeSnapshot.setOnClickListener {
            val bitmap = binding.mjpegView.captureCurrentFrame()
            if (bitmap != null) {
                saveSnapshot(bitmap)
            } else {
                Toast.makeText(this, "Camera frame not ready yet", Toast.LENGTH_SHORT).show()
            }
        }

        binding.tvStreamUrl.setOnClickListener {
            showConfigureIpDialog()
        }

        binding.btnRemoteAlarm.setOnClickListener {
            toggleRemoteSiren()
        }
    }

    private fun showConfigureIpDialog() {
        val prefs = getSharedPreferences("home_sentinel_owner", MODE_PRIVATE)
        val currentIp = prefs.getString("monitor_ip", "192.168.1.100") ?: "192.168.1.100"

        val input = android.widget.EditText(this).apply {
            setText(currentIp)
            hint = "e.g. 192.168.1.5"
            setTextColor(android.graphics.Color.WHITE)
            setPadding(50, 40, 50, 40)
        }

        android.app.AlertDialog.Builder(this)
            .setTitle("Configure CCTV Monitor IP")
            .setMessage("Enter the IP address shown on your Monitor App screen:")
            .setView(input)
            .setPositiveButton("Save & Connect") { _, _ ->
                val newIp = input.text.toString().trim()
                if (newIp.isNotEmpty()) {
                    prefs.edit().putString("monitor_ip", newIp).apply()
                    monitorBaseUrl = "http://$newIp:8080"
                    binding.tvStreamUrl.text = "$monitorBaseUrl/stream"
                    binding.mjpegView.stopStream()
                    binding.mjpegView.startStream("$monitorBaseUrl/stream")
                    Toast.makeText(this, "Connecting to $newIp:8080...", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveSnapshot(bitmap: Bitmap) {
        lifecycleScope.launch(Dispatchers.IO) {
            val file = File(getExternalFilesDir(null), "cctv_snapshot_${System.currentTimeMillis()}.jpg")
            val out = FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            out.flush()
            out.close()

            withContext(Dispatchers.Main) {
                Toast.makeText(this@LiveStreamActivity, "Snapshot saved: ${file.name}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun toggleRemoteSiren() {
        val endpoint = if (isSirenFired) "/siren/silence" else "/siren/trigger"
        val url = "$monitorBaseUrl$endpoint"

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val req = Request.Builder().url(url).build()
                val resp = client.newCall(req).execute()
                val success = resp.isSuccessful

                withContext(Dispatchers.Main) {
                    if (success) {
                        isSirenFired = !isSirenFired
                        if (isSirenFired) {
                            binding.btnRemoteAlarm.text = "🔇 Silence Siren"
                            binding.btnRemoteAlarm.setBackgroundResource(R.drawable.btn_purple_rounded)
                            Toast.makeText(this@LiveStreamActivity, "🚨 SIREN SOUNDED ON MONITOR!", Toast.LENGTH_SHORT).show()
                        } else {
                            binding.btnRemoteAlarm.text = "🚨 Trigger Siren"
                            binding.btnRemoteAlarm.setBackgroundResource(R.drawable.btn_red_rounded)
                            Toast.makeText(this@LiveStreamActivity, "Siren Silenced on Monitor", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Toast.makeText(this@LiveStreamActivity, "Monitor unreachable on $url", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@LiveStreamActivity, "Network error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.mjpegView.stopStream()
    }
}
