package com.gabodev.iotdevicemonitor.data

import com.gabodev.iotdevicemonitor.models.AlertConfig
import com.gabodev.iotdevicemonitor.models.Device
import com.gabodev.iotdevicemonitor.models.Threshold
import com.gabodev.iotdevicemonitor.models.DeviceType
import com.gabodev.iotdevicemonitor.models.Reading
import com.gabodev.iotdevicemonitor.utils.orEmpty
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseException
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.Query
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

private const val DEVICES = "devices"

class FirebasePacaRepository : PacaRepository {
    private val database = FirebaseDatabase.getInstance()
    private val auth = FirebaseAuth.getInstance()

    private var devicesQuery: Query? = null
    private var devicesListener: ValueEventListener? = null
    private var authListener: FirebaseAuth.AuthStateListener? = null

    private var readingsRef: DatabaseReference? = null
    private var readingsListener: ValueEventListener? = null

    override suspend fun registerDevice(device: Device): RegisterResult {
        val uid = auth.currentUser?.uid ?: return RegisterResult.NotSignedIn
        return withContext(Dispatchers.IO) {
            try {
                // updateChildren (not setValue) so re-registering your own device keeps its readings.
                val fields = mapOf<String, Any?>(
                    "owner" to uid,
                    "name" to device.name.orEmpty(),
                    "type" to device.type.name,
                    "location" to device.location?.takeIf { it.isNotBlank() }
                )
                database.getReference("$DEVICES/${device.id}").updateChildren(fields).await()
                RegisterResult.Success
            } catch (e: CancellationException) {
                throw e
            } catch (e: DatabaseException) {
                // The rules only reject a well-formed write when the ID belongs to someone else.
                // (Task exceptions carry the server error only in the message.)
                if (e.message?.contains("Permission denied", ignoreCase = true) == true) {
                    RegisterResult.AlreadyClaimed
                } else {
                    RegisterResult.Error
                }
            } catch (e: Exception) {
                RegisterResult.Error
            }
        }
    }

    override suspend fun saveAlerts(deviceId: String, config: AlertConfig): Boolean =
        withContext(Dispatchers.IO) {
            try {
                database.getReference("$DEVICES/$deviceId/alerts").setValue(config.toMap()).await()
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
        }

    override suspend fun getReadingDetail(deviceId: String, readingId: String): Reading? {
        return withContext(Dispatchers.IO) {
            try {
                val snapshot = database.getReference("$DEVICES/$deviceId/readings/$readingId").get().await()
                if (snapshot.exists()) parseReadingSnapshot(snapshot) else null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
    }

    override fun listenForDevicesUpdates(devicesFlow: MutableStateFlow<List<Device>>) {
        authListener?.let { auth.removeAuthStateListener(it) }
        // Re-point the query whenever the signed-in user changes (login, logout, account switch).
        val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            detachDevicesListener()
            val uid = firebaseAuth.currentUser?.uid
            if (uid == null) {
                devicesFlow.value = emptyList()
                return@AuthStateListener
            }
            PushTokens.register(uid)
            val query = database.getReference(DEVICES).orderByChild("owner").equalTo(uid)
            val valueListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    devicesFlow.value = snapshot.children.mapNotNull { parseDeviceSnapshot(it) }
                }

                override fun onCancelled(error: DatabaseError) {}
            }
            query.addValueEventListener(valueListener)
            devicesQuery = query
            devicesListener = valueListener
        }
        auth.addAuthStateListener(listener)
        authListener = listener
    }

    override fun listenForReadingsUpdates(deviceId: String, readingsFlow: MutableStateFlow<Map<String, Reading>>) {
        readingsListener?.let { readingsRef?.removeEventListener(it) }
        readingsFlow.value = emptyMap()

        val ref = database.getReference("$DEVICES/$deviceId/readings")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                readingsFlow.value = snapshot.children.mapNotNull { parseReadingSnapshot(it) }
                    .associateBy { it.timestamp }
            }

            override fun onCancelled(error: DatabaseError) {}
        }
        ref.addValueEventListener(listener)
        readingsRef = ref
        readingsListener = listener
    }

    private fun detachDevicesListener() {
        devicesListener?.let { devicesQuery?.removeEventListener(it) }
        devicesQuery = null
        devicesListener = null
    }

    private fun parseDeviceSnapshot(deviceSnapshot: DataSnapshot): Device? {
        val deviceId = deviceSnapshot.key ?: return null
        val typeString = deviceSnapshot.child("type").getValue(String::class.java)
        val deviceType = DeviceType.entries.firstOrNull { it.name == typeString } ?: DeviceType.BIODIGESTER_BALE

        return Device(
            alerts = parseAlerts(deviceSnapshot.child("alerts")),
            id = deviceId,
            name = deviceSnapshot.child("name").getValue(String::class.java),
            type = deviceType,
            location = deviceSnapshot.child("location").getValue(String::class.java),
            readings = deviceSnapshot.child("readings").children
                .mapNotNull { parseReadingSnapshot(it) }
                .associateBy { it.timestamp }
        )
    }

    private fun parseAlerts(snapshot: DataSnapshot): AlertConfig {
        if (!snapshot.exists()) return AlertConfig()
        fun threshold(key: String): Threshold {
            val t = snapshot.child(key)
            return Threshold(
                enabled = t.child("enabled").getValue(Boolean::class.java) ?: false,
                min = t.child("min").getValue(Double::class.java),
                max = t.child("max").getValue(Double::class.java),
            )
        }
        val offline = snapshot.child("offline")
        return AlertConfig(
            temperature = threshold("temperature"),
            humidity = threshold("humidity"),
            pressure = threshold("pressure"),
            offlineEnabled = offline.child("enabled").getValue(Boolean::class.java) ?: false,
            offlineMinutes = offline.child("minutes").getValue(Int::class.java) ?: 30,
        )
    }

    private fun Threshold.toMap() = mapOf("enabled" to enabled, "min" to min, "max" to max)

    private fun AlertConfig.toMap() = mapOf(
        "temperature" to temperature.toMap(),
        "humidity" to humidity.toMap(),
        "pressure" to pressure.toMap(),
        "offline" to mapOf("enabled" to offlineEnabled, "minutes" to offlineMinutes),
    )

    private fun parseReadingSnapshot(readingSnapshot: DataSnapshot): Reading? {
        val timestamp = readingSnapshot.key.orEmpty()
        if (timestamp.isEmpty()) return null
        return Reading(
            id = timestamp,
            humidity = readingSnapshot.child("humidity").getValue(String::class.java).orEmpty(),
            pressure = readingSnapshot.child("pressure").getValue(String::class.java).orEmpty(),
            temperature = readingSnapshot.child("temperature").getValue(String::class.java).orEmpty(),
            timestamp = timestamp
        )
    }
}
