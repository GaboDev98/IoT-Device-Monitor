package com.gabodev.iotdevicemonitor

import com.gabodev.iotdevicemonitor.data.PushTokens
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class IotMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        FirebaseAuth.getInstance().currentUser?.uid?.let { PushTokens.register(it, token) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val title = message.notification?.title ?: message.data["title"] ?: return
        val body = message.notification?.body ?: message.data["body"].orEmpty()
        // One notification per device+metric so a recovery replaces its own alert.
        val key = "${message.data["deviceId"]}:${message.data["metric"]}"
        AlertNotifications.show(this, key.hashCode(), title, body)
    }
}
