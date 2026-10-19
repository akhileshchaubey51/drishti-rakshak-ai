package com.homesentinel.owner.data

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CloudDatabaseManager(private val context: Context) {

    companion object {
        private const val TAG = "CloudDatabaseManager"
        const val OWNER_UID = "9BsnYtyeWebctRXsMfE29oCMjxG3"
    }

    private val tag = TAG
    private val firestore by lazy {
        try { FirebaseFirestore.getInstance() } catch (e: Exception) { null }
    }
    private val localDb = OwnerDatabase.getInstance(context)
    private var alertsListener: ListenerRegistration? = null

    init {
        try {
            com.google.firebase.auth.FirebaseAuth.getInstance().signInAnonymously()
                .addOnSuccessListener {
                    Log.d(tag, "Firebase anonymous auth successful for Owner App")
                }
                .addOnFailureListener { e ->
                    Log.w(tag, "Firebase anonymous auth failed: ${e.message}")
                }
        } catch (e: Exception) {
            Log.w(tag, "FirebaseAuth init skipped: ${e.message}")
        }
    }

    /**
     * Uploads an authorized member's details and face photo to Cloudinary & Firestore.
     * Stored under "authorizedUsers" with ownerUid.
     */
    fun addAuthorizedMemberToCloud(
        name: String,
        role: String,
        photoBitmap: Bitmap?,
        embedding: String,
        onComplete: (Boolean, String?) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 1. Upload photo to Cloudinary
                var cloudinaryUrl = ""
                if (photoBitmap != null) {
                    cloudinaryUrl = CloudinaryManager.uploadImage(photoBitmap, "smart-home-security")
                }

                // 2. Insert into Firestore "authorizedUsers"
                val memberData = hashMapOf(
                    "ownerUid" to OWNER_UID,
                    "name" to name,
                    "role" to role,
                    "imageUrl" to cloudinaryUrl,
                    "faceEmbedding" to embedding,
                    "timestamp" to System.currentTimeMillis()
                )

                val fs = firestore
                if (fs == null) {
                    withContext(Dispatchers.Main) {
                        onComplete(false, "Firestore instance not available")
                    }
                    return@launch
                }

                fs.collection("authorizedUsers")
                    .add(memberData)
                    .addOnSuccessListener {
                        Log.d(tag, "Authorized member added to Firestore: $name, Photo: $cloudinaryUrl")
                        onComplete(true, null)
                    }
                    .addOnFailureListener { e ->
                        Log.e(tag, "Failed to upload authorized member to Firestore: ${e.message}")
                        onComplete(false, e.localizedMessage ?: e.message ?: "Firestore error")
                    }
            } catch (e: Exception) {
                Log.e(tag, "Error saving member: ${e.message}")
                withContext(Dispatchers.Main) {
                    onComplete(false, e.localizedMessage ?: e.message ?: "Unknown error")
                }
            }
        }
    }

    /**
     * Deletes authorized member from Firestore so camera marks them as unknown
     */
    fun deleteAuthorizedMemberFromCloud(name: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                firestore?.collection("authorizedUsers")
                    ?.whereEqualTo("ownerUid", OWNER_UID)
                    ?.whereEqualTo("name", name)
                    ?.get()
                    ?.addOnSuccessListener { snapshot ->
                        for (doc in snapshot.documents) {
                            doc.reference.delete()
                        }
                    }
            } catch (e: Exception) {
                Log.e(tag, "Error deleting member: ${e.message}")
            }
        }
    }

    private var authorizedListener: ListenerRegistration? = null

    /**
     * Listens in real-time to the exact Firestore "securityEvents" collection.
     * Removes compound whereEqualTo+orderBy filter to completely eliminate the Firestore composite index error!
     * When the Monitor Camera clicks an unknown person's photo, Owner receives it immediately.
     */
    fun startListeningToSecurityAlerts(onAlertsUpdated: (List<AlertRecord>, Boolean) -> Unit) {
        try {
            var initialLoad = true
            // Listen to securityEvents collection directly without restrictive filter
            alertsListener = firestore?.collection("securityEvents")
                ?.addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.e(tag, "Firestore securityEvents listen error: ${error.message}")
                        loadLocalAlerts(onAlertsUpdated)
                        return@addSnapshotListener
                    }

                    if (snapshot != null) {
                        val alertList = mutableListOf<AlertRecord>()
                        for (doc in snapshot.documents) {
                            val eventType = doc.getString("type") ?: doc.getString("eventType") ?: "Person detected"
                            val imageUrl = doc.getString("imageUrl") ?: ""
                            val status = doc.getString("status") ?: "Reported"
                            val location = doc.getString("location") ?: "Camera"
                            val confidence = doc.getDouble("confidence")?.toFloat() ?: 0.95f
                            val timestamp = doc.getLong("timestamp") ?: System.currentTimeMillis()

                            val record = AlertRecord(
                                alertType = eventType,
                                label = "$eventType - $location ($status)",
                                confidence = confidence,
                                isAuthorized = false,
                                sirenTriggered = true,
                                snapshotPath = imageUrl,
                                timestamp = timestamp
                            )
                            alertList.add(record)
                        }

                        // Always sort latest alerts at the very top!
                        alertList.sortByDescending { it.timestamp }

                        // Mirror exact cloud state into local Room DB (if cloud is empty, local clears too!)
                        CoroutineScope(Dispatchers.IO).launch {
                            localDb.alertRecordDao().clearAll()
                            for (a in alertList) {
                                localDb.alertRecordDao().insert(a)
                            }
                        }

                        val hasNewAlert = !initialLoad && snapshot.documentChanges.any {
                            it.type == com.google.firebase.firestore.DocumentChange.Type.ADDED
                        }
                        initialLoad = false

                        if (hasNewAlert && alertList.isNotEmpty()) {
                            val newest = alertList.first()
                            com.homesentinel.owner.util.NotificationHelper.showIntruderNotification(
                                context,
                                "🚨 INTRUDER ALERT! Unknown Person",
                                "HomeSentinel detected an unknown person at ${newest.label}",
                                newest.snapshotPath
                            )
                        }

                        onAlertsUpdated(alertList, hasNewAlert)
                    }
                }
        } catch (e: Exception) {
            Log.e(tag, "Error attaching securityEvents listener: ${e.message}")
            loadLocalAlerts(onAlertsUpdated)
        }
    }

    /**
     * Clears all alerts from both Cloud Firestore and local device database.
     */
    fun clearAllAlerts(onComplete: (Boolean) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                localDb.alertRecordDao().clearAll()
                val fs = firestore
                if (fs != null) {
                    fs.collection("securityEvents").get().addOnSuccessListener { snapshot ->
                        for (doc in snapshot.documents) {
                            doc.reference.delete()
                        }
                        onComplete(true)
                    }.addOnFailureListener {
                        onComplete(true)
                    }
                } else {
                    onComplete(true)
                }
            } catch (e: Exception) {
                onComplete(true)
            }
        }
    }

    /**
     * Real-time sync for authorized family members from Firestore "authorizedUsers".
     */
    fun startListeningToAuthorizedMembers(onUpdate: (List<AuthorizedUser>) -> Unit) {
        try {
            authorizedListener = firestore?.collection("authorizedUsers")
                ?.addSnapshotListener { snapshots, error ->
                    if (error != null) {
                        Log.w(tag, "authorizedUsers listen error: ${error.message}")
                        return@addSnapshotListener
                    }
                    if (snapshots != null) {
                        val memberList = mutableListOf<AuthorizedUser>()
                        for (doc in snapshots.documents) {
                            val ownerUid = doc.getString("ownerUid")
                            if (!ownerUid.isNullOrEmpty() && ownerUid != OWNER_UID) {
                                continue
                            }
                            val name = doc.getString("name") ?: continue
                            val role = doc.getString("role") ?: "Family Member"
                            val imageUrl = doc.getString("imageUrl") ?: ""
                            val timestamp = doc.getLong("timestamp") ?: System.currentTimeMillis()

                            memberList.add(
                                AuthorizedUser(
                                    name = name,
                                    role = role,
                                    photoPath = imageUrl,
                                    timestamp = timestamp
                                )
                            )
                        }
                        memberList.sortByDescending { it.timestamp }
                        onUpdate(memberList)
                    }
                }
        } catch (e: Exception) {
            Log.e(tag, "Could not attach authorizedUsers listener: ${e.message}")
        }
    }

    private fun loadLocalAlerts(onAlertsUpdated: (List<AlertRecord>, Boolean) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            val list = localDb.alertRecordDao().getAll()
            withContext(Dispatchers.Main) {
                onAlertsUpdated(list, false)
            }
        }
    }

    fun stopListening() {
        alertsListener?.remove()
        authorizedListener?.remove()
    }
}
