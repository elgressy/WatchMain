package com.pulsewave.relax.wear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.health.services.client.HealthServices
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DeltaDataType
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Locale

private const val HR_PATH = "/hr"
private const val CHANNEL_ID = "hr_measure"
private const val NOTIFICATION_ID = 1
private const val MIN_SEND_INTERVAL_MS = 1000L

/**
 * Foreground service that keeps a live Health Services heart-rate stream
 * running (so readings survive screen-off) and relays each sample to the
 * paired phone over the Wearable Data Layer at message path "/hr".
 */
class HeartRateForegroundService : Service() {

    private val measureClient by lazy { HealthServices.getClient(this).measureClient }
    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    private var lastSentAt = 0L

    private val callback = object : MeasureCallback {
        override fun onDataReceived(data: DataPointContainer) {
            val bpm = data.getData(DataType.HEART_RATE_BPM).lastOrNull()?.value ?: return
            if (bpm <= 0) return
            HeartRateBus.updateBpm(bpm)

            val now = System.currentTimeMillis()
            if (now - lastSentAt >= MIN_SEND_INTERVAL_MS) {
                lastSentAt = now
                sendToPhone(bpm)
            }
        }

        override fun onAvailabilityChanged(dataType: DeltaDataType<*, *>, availability: Availability) {}
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        measureClient.registerMeasureCallback(DataType.HEART_RATE_BPM, callback)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    private fun sendToPhone(bpm: Double) {
        serviceScope.launch {
            try {
                val nodes = Wearable.getNodeClient(this@HeartRateForegroundService).connectedNodes.await()
                HeartRateBus.updateConnected(nodes.isNotEmpty())
                val payload = String.format(Locale.US, "%.1f", bpm).toByteArray(Charsets.UTF_8)
                nodes.forEach { node ->
                    Wearable.getMessageClient(this@HeartRateForegroundService)
                        .sendMessage(node.id, HR_PATH, payload)
                        .await()
                }
            } catch (_: Exception) {
                // Phone temporarily unreachable; next sample will retry.
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.hr_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.hr_notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_myplaces)
            .setOngoing(true)
            .build()

    override fun onDestroy() {
        measureClient.unregisterMeasureCallbackAsync(DataType.HEART_RATE_BPM, callback)
        serviceScope.cancel()
        HeartRateBus.clearBpm()
        HeartRateBus.updateConnected(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
