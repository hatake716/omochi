package io.github.hatake716.omochi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Owns the user-started loopback IDE session independently from the workbench Activity.
 *
 * Opening an OAuth page moves Omochi into the background. A normal app process can then
 * be frozen or reclaimed, which also stops the PRoot/code-server child process. Keeping
 * the session in a visible foreground service gives Android an accurate lifecycle signal
 * and leaves the user in control through the persistent notification.
 */
class OmochiServerService : Service() {
    private val stateListener: (OmochiServerManager.State) -> Unit = { state ->
        updateNotification(state)
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        promoteToForeground(OmochiServerManager.state())
        OmochiServerManager.addListener(stateListener)
        WorkspaceSyncManager.start(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A sticky service may be recreated with a null Intent. Promote first so the
        // Android foreground-service deadline is always met.
        promoteToForeground(OmochiServerManager.state())

        when (intent?.action) {
            ACTION_STOP -> {
                OmochiServerManager.stop()
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_RESTART -> {
                OmochiServerManager.stop()
                if (OmochiRuntime.isInstalled(this)) {
                    OmochiServerManager.start(applicationContext)
                } else {
                    stopUnavailableSession()
                    return START_NOT_STICKY
                }
            }

            else -> {
                if (OmochiRuntime.isInstalled(this)) {
                    OmochiServerManager.start(applicationContext)
                } else {
                    stopUnavailableSession()
                    return START_NOT_STICKY
                }
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        OmochiServerManager.removeListener(stateListener)
        WorkspaceSyncManager.stop()
        // Never leave an unmanaged PRoot child behind if the service is explicitly
        // stopped. Process death does not normally dispatch this callback.
        OmochiServerManager.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun stopUnavailableSession() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun promoteToForeground(state: OmochiServerManager.State) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notificationFor(state), type)
    }

    private fun updateNotification(state: OmochiServerManager.State) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notificationFor(state))
    }

    private fun notificationFor(state: OmochiServerManager.State): Notification {
        val openIntent = Intent(this, WorkbenchActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopPendingIntent = PendingIntent.getService(
            this,
            REQUEST_STOP,
            Intent(this, OmochiServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val (title, detail) = when (state) {
            OmochiServerManager.State.Stopped ->
                "Omochi IDEを準備中" to "ローカルセッションを開始しています"
            is OmochiServerManager.State.Starting ->
                "Omochi IDEを起動中" to state.message.lineSequence().firstOrNull().orEmpty()
            is OmochiServerManager.State.Running ->
                "Omochi IDEセッション実行中" to "ブラウザ認証中もターミナルを維持します"
            is OmochiServerManager.State.Failed ->
                "Omochi IDEの再接続が必要です" to state.message.lineSequence().firstOrNull().orEmpty()
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_terminal_service)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setContentIntent(openPendingIntent)
            .setOngoing(state !is OmochiServerManager.State.Failed)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "ワークベンチを開く", openPendingIntent)
            .addAction(0, "セッションを停止", stopPendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Omochi IDEセッション",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "ブラウザ認証中も端末内のIDEとターミナルを維持します"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "omochi_ide_session"
        private const val NOTIFICATION_ID = 716
        private const val REQUEST_OPEN = 717
        private const val REQUEST_STOP = 718

        private const val ACTION_START = "io.github.hatake716.omochi.action.START_IDE"
        private const val ACTION_RESTART = "io.github.hatake716.omochi.action.RESTART_IDE"
        private const val ACTION_STOP = "io.github.hatake716.omochi.action.STOP_IDE"

        fun start(context: Context): Result<Unit> = runCatching {
            val intent = Intent(context, OmochiServerService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
            Unit
        }

        fun restart(context: Context): Result<Unit> = runCatching {
            val intent = Intent(context, OmochiServerService::class.java).setAction(ACTION_RESTART)
            ContextCompat.startForegroundService(context, intent)
            Unit
        }

        fun stop(context: Context) {
            val intent = Intent(context, OmochiServerService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
                .onFailure {
                    // If no service instance exists there is nothing to stop. Keep the
                    // in-process state consistent for callers such as setup migration.
                    OmochiServerManager.stop()
                }
        }
    }
}
