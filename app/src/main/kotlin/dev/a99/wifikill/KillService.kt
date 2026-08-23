package dev.a99.wifikill

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service while any client is blocked. Two jobs:
 *
 * 1. Keep the app process alive so the spoofer liveness watchdog in
 *    [ArpSpoofer] stays armed and detached spoofers remain controllable --
 *    without it, swiping the app away would leave victims poisoned with no
 *    UI to undo it (their self-restore only fires when the app pid dies).
 * 2. Show a persistent notification with a "Restore all" action, the only
 *    off-switch reachable while no activity is open.
 *
 * Holds no state of its own: it mirrors [ArpSpoofer.activeCount] into the
 * notification and stops itself when the count reaches zero.
 */
class KillService : Service() {

    private val spoofer get() = WifiKillApp.get(this).spoofer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            spoofer.activeCount.collect { count ->
                when {
                    count <= 0 -> stopSelf()
                    else -> getSystemService(NotificationManager::class.java).notify(
                        NOTIFICATION_ID, buildNotification(count)
                    )
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RESTORE_ALL) {
            scope.launch { spoofer.unkillAll() }
            // The count collector stops the service once it observes zero.
            return START_NOT_STICKY
        }
        promoteToForeground()
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun promoteToForeground() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(spoofer.activeCount.value), type
        )
    }

    private fun buildNotification(count: Int): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val restoreAll = PendingIntent.getService(
            this, 0,
            Intent(this, KillService::class.java).setAction(ACTION_RESTORE_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, WifiKillApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_wifi)
            .setContentTitle(getString(R.string.notif_title, count))
            .setContentText(getString(R.string.notif_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.notif_action_restore_all), restoreAll)
            .build()
    }

    companion object {
        const val ACTION_RESTORE_ALL = "dev.a99.wifikill.action.RESTORE_ALL"
        private const val NOTIFICATION_ID = 1

        /** Safe to call repeatedly; the service itself is idempotent. */
        fun start(context: android.content.Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, KillService::class.java)
            )
        }
    }
}
