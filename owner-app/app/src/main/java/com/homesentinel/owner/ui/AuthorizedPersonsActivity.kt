package com.homesentinel.owner.ui

import android.app.AlertDialog
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.homesentinel.owner.R
import com.homesentinel.owner.data.AuthorizedUser
import com.homesentinel.owner.data.CloudDatabaseManager
import com.homesentinel.owner.data.FaceEmbeddingUtils
import com.homesentinel.owner.data.OwnerDatabase
import com.homesentinel.owner.databinding.ActivityAuthorizedPersonsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class AuthorizedPersonsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAuthorizedPersonsBinding
    private lateinit var db: OwnerDatabase
    private lateinit var adapter: AuthorizedUserAdapter
    private lateinit var cloudDbManager: CloudDatabaseManager

    private var tempSelectedBitmap: Bitmap? = null
    private var previewImageView: ImageView? = null

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            launchCameraSafe()
        } else {
            Toast.makeText(this, "Camera permission needed. You can also pick from Gallery.", Toast.LENGTH_LONG).show()
        }
    }

    private val takePhotoLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap != null) {
            val softwareBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
            tempSelectedBitmap = softwareBitmap
            previewImageView?.setImageBitmap(softwareBitmap)
        }
    }

    private val galleryPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            try {
                val bitmap = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(contentResolver, uri))
                } else {
                    @Suppress("DEPRECATION")
                    android.provider.MediaStore.Images.Media.getBitmap(contentResolver, uri)
                }
                val softwareBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                tempSelectedBitmap = softwareBitmap
                previewImageView?.setImageBitmap(softwareBitmap)
            } catch (e: Exception) {
                Toast.makeText(this, "Failed to load image: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun launchCameraSafe() {
        try {
            takePhotoLauncher.launch(null)
        } catch (e: Exception) {
            Toast.makeText(this, "Camera unavailable (${e.message}). Please use 'Gallery' button.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAuthorizedPersonsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = OwnerDatabase.getInstance(this)
        cloudDbManager = CloudDatabaseManager(this)

        setupRecyclerView()
        setupListeners()
        loadAuthorizedList()

        // Realtime sync from Cloud Database (Firestore authorizedUsers)
        cloudDbManager.startListeningToAuthorizedMembers { memberList ->
            runOnUiThread {
                adapter.submitList(memberList)
                binding.tvEmpty.visibility = if (memberList.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cloudDbManager.stopListening()
    }

    private fun setupRecyclerView() {
        adapter = AuthorizedUserAdapter { user ->
            confirmDelete(user)
        }
        binding.rvAuthorized.layoutManager = LinearLayoutManager(this)
        binding.rvAuthorized.adapter = adapter
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnAddPerson.setOnClickListener {
            showAddPersonDialog()
        }
    }

    private fun loadAuthorizedList() {
        lifecycleScope.launch(Dispatchers.IO) {
            val list = db.authorizedUserDao().getAll()
            withContext(Dispatchers.Main) {
                adapter.submitList(list)
                binding.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun showAddPersonDialog() {
        tempSelectedBitmap = null
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_authorized, null, false)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        previewImageView = dialogView.findViewById(R.id.ivFacePreview)
        val btnCapture = dialogView.findViewById<Button>(R.id.btnCapturePhoto)
        val btnPickGallery = dialogView.findViewById<Button>(R.id.btnPickGallery)
        val etName = dialogView.findViewById<EditText>(R.id.etName)
        val etRole = dialogView.findViewById<EditText>(R.id.etRole)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancel)
        val btnSaveAndSync = dialogView.findViewById<Button>(R.id.btnSaveAndSync)

        btnCapture.setOnClickListener {
            if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                launchCameraSafe()
            } else {
                cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
            }
        }

        btnPickGallery.setOnClickListener {
            galleryPickerLauncher.launch("image/*")
        }

        val btnFlipPhoto = dialogView.findViewById<Button>(R.id.btnFlipPhoto)
        btnFlipPhoto.setOnClickListener {
            val bmp = tempSelectedBitmap
            if (bmp != null) {
                val matrix = android.graphics.Matrix().apply { preScale(-1f, 1f) }
                val flipped = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
                tempSelectedBitmap = flipped
                previewImageView?.setImageBitmap(flipped)
                Toast.makeText(this, "Photo Flipped! (Mirror toggled)", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Capture or pick a photo first", Toast.LENGTH_SHORT).show()
            }
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnSaveAndSync.setOnClickListener {
            val name = etName.text.toString().trim()
            val role = etRole.text.toString().trim()

            if (name.isEmpty()) {
                Toast.makeText(this, "Please enter person name", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val roleText = if (role.isEmpty()) "Family Member" else role
            val bitmap = tempSelectedBitmap

            lifecycleScope.launch(Dispatchers.IO) {
                var photoPath: String? = null
                var embeddingString = ""

                if (bitmap != null) {
                    val file = File(filesDir, "member_${System.currentTimeMillis()}.jpg")
                    val out = FileOutputStream(file)
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                    out.flush()
                    out.close()
                    photoPath = file.absolutePath

                    val embedding = FaceEmbeddingUtils.extractEmbedding(bitmap)
                    embeddingString = FaceEmbeddingUtils.serializeEmbedding(embedding)
                }

                val user = AuthorizedUser(
                    name = name,
                    role = roleText,
                    photoPath = photoPath
                )
                db.authorizedUserDao().insert(user)

                // Upload directly to Cloud Database (Firestore)
                cloudDbManager.addAuthorizedMemberToCloud(
                    name = name,
                    role = roleText,
                    photoBitmap = bitmap,
                    embedding = embeddingString
                ) { success, errorMsg ->
                    runOnUiThread {
                        dialog.dismiss()
                        if (success) {
                            Toast.makeText(this@AuthorizedPersonsActivity, "✅ $name Saved to Cloud! Camera will recognize them.", Toast.LENGTH_LONG).show()
                        } else {
                            val detail = if (!errorMsg.isNullOrEmpty()) " (Cloud: $errorMsg)" else ""
                            Toast.makeText(this@AuthorizedPersonsActivity, "$name saved locally$detail", Toast.LENGTH_LONG).show()
                        }
                        loadAuthorizedList()
                    }
                }
            }
        }

        dialog.show()
    }

    private fun confirmDelete(user: AuthorizedUser) {
        AlertDialog.Builder(this)
            .setTitle("Remove Person")
            .setMessage("Remove ${user.name} from authorized whitelist? The camera will sound siren if this person is seen.")
            .setPositiveButton("Remove") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    db.authorizedUserDao().delete(user)
                    cloudDbManager.deleteAuthorizedMemberFromCloud(user.name)
                    loadAuthorizedList()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
