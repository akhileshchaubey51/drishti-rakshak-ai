package com.homesentinel.owner.data

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

object CloudinaryManager {

    private const val TAG = "CloudinaryManager"
    const val CLOUD_NAME = "prmmfvpo"
    const val UPLOAD_PRESET = "smart_home_security"
    const val DEFAULT_FOLDER = "smart-home-security"
    private const val UPLOAD_URL = "https://api.cloudinary.com/v1_1/$CLOUD_NAME/image/upload"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /**
     * Uploads bitmap photo to Cloudinary using unsigned preset 'smart_home_security'.
     * Returns secure Cloudinary HTTPS URL or fallback Base64 data string.
     */
    suspend fun uploadImage(bitmap: Bitmap, folderName: String = DEFAULT_FOLDER): String = withContext(Dispatchers.IO) {
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos)
        val imageBytes = baos.toByteArray()
        val base64Data = Base64.encodeToString(imageBytes, Base64.NO_WRAP)

        try {
            val multipartBuilder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    "member_${System.currentTimeMillis()}.jpg",
                    imageBytes.toRequestBody("image/jpeg".toMediaType())
                )
                .addFormDataPart("upload_preset", UPLOAD_PRESET)
                .addFormDataPart("folder", folderName)

            val request = Request.Builder()
                .url(UPLOAD_URL)
                .post(multipartBuilder.build())
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (response.isSuccessful) {
                val json = JSONObject(responseBody)
                val secureUrl = json.optString("secure_url")
                if (secureUrl.isNotEmpty()) {
                    Log.d(TAG, "Member photo uploaded to Cloudinary: $secureUrl")
                    return@withContext secureUrl
                }
            } else {
                Log.w(TAG, "Cloudinary upload response: $responseBody")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Cloudinary upload exception: ${e.message}")
        }

        return@withContext base64Data
    }
}
