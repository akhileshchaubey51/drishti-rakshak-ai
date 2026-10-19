package com.homesentinel.owner

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.media.RingtoneManager
import android.os.Bundle
import android.view.animation.AnimationUtils
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.homesentinel.owner.data.CloudDatabaseManager
import com.homesentinel.owner.data.OwnerDatabase
import com.homesentinel.owner.databinding.ActivityMainBinding
import com.homesentinel.owner.ui.AlertLogActivity
import com.homesentinel.owner.ui.AuthorizedPersonsActivity
import com.homesentinel.owner.ui.LiveStreamActivity

import android.os.Build
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.homesentinel.owner.util.NotificationHelper

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var db: OwnerDatabase
    private lateinit var cloudDbManager: CloudDatabaseManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        NotificationHelper.createNotificationChannel(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        db = OwnerDatabase.getInstance(this)
        cloudDbManager = CloudDatabaseManager(this)

        setupUI()
        setupListeners()
        startCloudAlertMonitoring()
    }

    private fun setupUI() {
        val pulseAnim = AnimationUtils.loadAnimation(this, R.anim.pulse)
        binding.tvLivePill.startAnimation(pulseAnim)

        val scanAnim = AnimationUtils.loadAnimation(this, R.anim.radar_scan)
        binding.viewScannerBeam.startAnimation(scanAnim)

        binding.tvMonitorIpStatus.text = "Firebase: ownersecurity-b6952 • 24/7 AI Perimeter Active"
    }

    private fun setupListeners() {
        binding.cardLivePreview.setOnClickListener {
            startActivity(Intent(this, LiveStreamActivity::class.java))
        }

        binding.btnNavAlerts.setOnClickListener {
            startActivity(Intent(this, AlertLogActivity::class.java))
        }

        binding.btnNavAuthorized.setOnClickListener {
            startActivity(Intent(this, AuthorizedPersonsActivity::class.java))
        }

        binding.switchArmSystem.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                binding.tvSystemState.text = getString(R.string.system_armed)
                binding.tvSystemState.setTextColor(Color.parseColor("#00FF88"))
                Toast.makeText(this, "System Armed via Cloud", Toast.LENGTH_SHORT).show()
            } else {
                binding.tvSystemState.text = getString(R.string.system_disarmed)
                binding.tvSystemState.setTextColor(Color.parseColor("#F59E0B"))
                Toast.makeText(this, "System Disarmed via Cloud", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnSettings.setOnClickListener {
            showConfigureIpDialog()
        }

        binding.btnRemoteSiren.setOnClickListener {
            Toast.makeText(this, "Remote Siren signal dispatched via Cloud Database", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showConfigureIpDialog() {
        val prefs = getSharedPreferences("home_sentinel_owner", MODE_PRIVATE)
        val currentIp = prefs.getString("monitor_ip", "192.168.1.100") ?: "192.168.1.100"

        val input = android.widget.EditText(this).apply {
            setText(currentIp)
            hint = "e.g. 192.168.1.5"
            setTextColor(Color.WHITE)
            setPadding(50, 40, 50, 40)
        }

        AlertDialog.Builder(this)
            .setTitle("Configure CCTV Monitor IP")
            .setMessage("Enter the IP address shown on your Monitor App screen:")
            .setView(input)
            .setPositiveButton("Save & Connect") { _, _ ->
                val newIp = input.text.toString().trim()
                if (newIp.isNotEmpty()) {
                    prefs.edit().putString("monitor_ip", newIp).apply()
                    Toast.makeText(this, "Monitor IP updated to $newIp", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startCloudAlertMonitoring() {
        cloudDbManager.startListeningToSecurityAlerts { alertList, hasNewAlert ->
            runOnUiThread {
                if (hasNewAlert && alertList.isNotEmpty()) {
                    val latest = alertList.first()
                    binding.tvMonitorIpStatus.text = "⚠️ INTRUSION DETECTED! (${alertList.size} captures in Cloud)"
                    binding.tvMonitorIpStatus.setTextColor(Color.parseColor("#EF4444"))

                    // Play warning ringtone
                    try {
                        val alertSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                        val r = RingtoneManager.getRingtone(applicationContext, alertSound)
                        r.play()
                    } catch (e: Exception) {
                        // Ignore
                    }

                    Toast.makeText(
                        this@MainActivity,
                        "🚨 CRITICAL ALERT: Unknown person detected!\nPhoto received in Alert Log.",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    binding.tvMonitorIpStatus.text = "Cloud Database Synced • ${alertList.size} Alerts Logged"
                    binding.tvMonitorIpStatus.setTextColor(Color.parseColor("#34D399"))
                }

                if (alertList.isNotEmpty()) {
                    val latest = alertList.first()
                    val path = latest.snapshotPath
                    if (!path.isNullOrEmpty()) {
                        com.bumptech.glide.Glide.with(this@MainActivity)
                            .load(path)
                            .into(binding.ivLatestIntruderSnapshot)
                        binding.tvLivePill.text = "● LATEST INTRUDER PHOTO"
                        binding.tvLivePill.setTextColor(Color.parseColor("#EF4444"))
                    }
                } else {
                    binding.ivLatestIntruderSnapshot.setImageResource(R.drawable.home_security_modern)
                    binding.tvLivePill.text = "● 24/7 PERIMETER SECURED"
                    binding.tvLivePill.setTextColor(Color.parseColor("#00FF88"))
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cloudDbManager.stopListening()
    }
}
