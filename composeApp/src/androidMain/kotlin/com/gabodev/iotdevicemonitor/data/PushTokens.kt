package com.gabodev.iotdevicemonitor.data

import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.tasks.await

/**
 * FCM tokens live at /users/{uid}/fcmTokens/{token} so the Cloud Function can notify every
 * device the user is signed in on.
 */
object PushTokens {
    private fun ref(uid: String) = FirebaseDatabase.getInstance().getReference("users/$uid/fcmTokens")

    /** Fire-and-forget: called on login and when FCM rotates the token. */
    fun register(uid: String, token: String? = null) {
        if (token != null) {
            ref(uid).child(token).setValue(true)
            return
        }
        FirebaseMessaging.getInstance().token.addOnSuccessListener { ref(uid).child(it).setValue(true) }
    }

    /** Stop notifying this phone (logout). Best effort: never blocks signing out. */
    suspend fun unregister(uid: String) {
        runCatching {
            val token = FirebaseMessaging.getInstance().token.await()
            ref(uid).child(token).removeValue().await()
        }
    }

    /** Removes all of the user's push data (account deletion). */
    suspend fun deleteAll(uid: String) {
        FirebaseDatabase.getInstance().getReference("users/$uid").removeValue().await()
    }
}
