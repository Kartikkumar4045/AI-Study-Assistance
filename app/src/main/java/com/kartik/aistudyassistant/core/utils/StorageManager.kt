package com.kartik.aistudyassistant.core.utils

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.google.firebase.database.FirebaseDatabase

object StorageManager {
    const val MAX_STORAGE_BYTES = 20L * 1024L * 1024L // 5 MB

    private fun getStorageRef(userId: String) =
        FirebaseDatabase.getInstance().reference.child("Users").child(userId).child("storageUsed")

    fun getStorageUsed(userId: String, onComplete: (Long) -> Unit, onError: (Exception) -> Unit) {
        getStorageRef(userId).get().addOnSuccessListener { snapshot ->
            val usedBytes = snapshot.getValue(Long::class.java) ?: 0L
            onComplete(usedBytes)
        }.addOnFailureListener {
            onError(it)
        }
    }

    fun addStorageUsed(userId: String, bytes: Long) {
        getStorageRef(userId).get().addOnSuccessListener { snapshot ->
            val usedBytes = snapshot.getValue(Long::class.java) ?: 0L
            getStorageRef(userId).setValue(usedBytes + bytes)
        }
    }

    fun removeStorageUsed(userId: String, bytes: Long) {
        getStorageRef(userId).get().addOnSuccessListener { snapshot ->
            val usedBytes = snapshot.getValue(Long::class.java) ?: 0L
            val newValue = (usedBytes - bytes).coerceAtLeast(0L)
            getStorageRef(userId).setValue(newValue)
        }
    }

    fun getFileSize(context: Context, uri: Uri): Long {
        var size = 0L
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (index != -1) size = cursor.getLong(index)
                }
            }
        }
        return size
    }
}
