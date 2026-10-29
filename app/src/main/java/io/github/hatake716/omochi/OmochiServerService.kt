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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Owns the user-started loopback IDE session independently from the workbench Activity.
 *
 * Opening an OAuth page moves Omochi into the background. A normal app process can then
 * be frozen or reclaimed, which also stops the PRoot/code-server child process. Keeping
 * the session in a visible foreground service gives Android an accurate lifecycle signal
 * and leaves the user in control through the persistent notification.
 *
 * Session control (install check, [OmochiServerManager.start] and
 * [OmochiServerManager.stop]) runs on one process-wide session thread in the order the
 * commands arrived, so the main thread never waits for a launch or a process-tree kill.
 */
class OmochiServerService : Service() {
    private val main = Handler(Looper.getMainLooper())

    /** The newest onStartCommand id. Main thread only. */
    private var latestStartId = 0

    /**
     * True from ACTION_STOP until a newer command arrives. The manager publishes Stopped
     * while the service is winding down; posting that as a regular notification after
     * stopForeground() would leave an orphaned "準備中" entry in the shade.
     */
    private var stopping = false
    private var destroyed = false

    private val stateListener: (OmochiServerManager.State) -> Unit = { state ->
        if (!stopping && !destroyed) updateNotification(state)
    }

    override fun onCreate() {
        super.onCreate()
        instanceAlive = true
        createNotificationChannel()
        // onStartCommand promotes again and handles a refusal there.
        runCatching { promoteToForeground(notificationFor(OmochiServerManager.state())) }
            .onFailure { Log.w(TAG, "Could not enter the foreground on create", it) }
        OmochiServerManager.addListener(stateListener)
        WorkspaceSyncManager.start(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        startPending = false

        if (intent?.action == ACTION_STOP) {
            stopping = true
            // A stop never needs the foreground state, but refresh the entry so the user
            // sees the request while the process tree is being terminated.
            runCatching { promoteToForeground(stoppingNotification()) }
            runSessionTask {
                OmochiServerManager.stop()
                main.post { finishIfLatest(startId) }
            }
            return START_NOT_STICKY
        }

        stopping = false
        // Promote synchronously so the Android foreground-service deadline is always met.
        val promoted = runCatching {
            promoteToForeground(notificationFor(OmochiServerManager.state()))
        }
        if (promoted.isFailure) {
            val error = promoted.exceptionOrNull()
            // A sticky restart (null Intent) can be refused while the app is in the
            // background. The session cannot be protected then, so give up quietly
            // instead of crashing; explicit starts keep failing loudly as before.
            if (intent != null) throw error!!
            Log.w(TAG, "Sticky restart could not enter the foreground", error)
            stopping = true
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val restart = intent?.action == ACTION_RESTART
        val appContext = applicationContext
        runSessionTask {
            if (restart) OmochiServerManager.stop()
            if (OmochiRuntime.isInstalled(appContext)) {
                OmochiServerManager.start(appContext)
            } else {
                main.post { finishIfLatest(startId) }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        instanceAlive = false
        OmochiServerManager.removeListener(stateListener)
        WorkspaceSyncManager.stop()
        // Never leave an unmanaged PRoot child behind if the service is explicitly
        // stopped. Process death does not normally dispatch this callback. The session
        // thread keeps the order with a start that recreates the service right away.
        runSessionTask { OmochiServerManager.stop() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Leaves the foreground and stops, unless a newer command arrived meanwhile (for
     * example the workbench reopened right after "セッションを停止"); that command then
     * owns the service.
     */
    private fun finishIfLatest(startId: Int) {
        if (destroyed || startId != latestStartId) return
        stopping = true
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    private fun promoteToForeground(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun updateNotification(state: OmochiServerManager.State) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notificationFor(state))
    }

    private fun notificationFor(state: OmochiServerManager.State): Notification {
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
        return buildNotification(
            title = title,
            detail = detail,
            ongoing = state !is OmochiServerManager.State.Failed,
            stopAction = true,
        )
    }

    private fun stoppingNotification(): Notification = buildNotification(
        title = "Omochi IDEを停止しています",
        detail = "IDEサーバーとターミナルを終了しています",
        ongoing = true,
        stopAction = false,
    )

    private fun buildNotification(
        title: String,
        detail: String,
        ongoing: Boolean,
        stopAction: Boolean,
    ): Notification {
        val openIntent = Intent(this, WorkbenchActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_terminal_service)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setContentIntent(openPendingIntent)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "ワークベンチを開く", openPendingIntent)
        if (stopAction) {
            val stopPendingIntent = PendingIntent.getService(
                this,
                REQUEST_STOP,
                Intent(this, OmochiServerService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, "セッションを停止", stopPendingIntent)
        }
        return builder.build()
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
        private const val TAG = "OmochiService"
        private const val CHANNEL_ID = "omochi_ide_session"
        private const val NOTIFICATION_ID = 716
        private const val REQUEST_OPEN = 717
        private const val REQUEST_STOP = 718

        private const val ACTION_START = "io.github.hatake716.omochi.action.START_IDE"
        private const val ACTION_RESTART = "io.github.hatake716.omochi.action.RESTART_IDE"
        private const val ACTION_STOP = "io.github.hatake716.omochi.action.STOP_IDE"

        /**
         * One thread for the whole process, so a stop queued by onDestroy() still runs
         * before the start of a service instance created right after it.
         */
        private val sessionExecutor: ExecutorService = Executors.newSingleThreadExecutor { task ->
            Thread(task, "OmochiSession")
        }

        /** Set between onCreate() and onDestroy(). */
        @Volatile
        private var instanceAlive = false

        /** A start was handed to Android but onStartCommand has not run yet. */
        @Volatile
        private var startPending = false

        private fun runSessionTask(task: () -> Unit) {
            sessionExecutor.execute {
                runCatching(task).onFailure { Log.e(TAG, "IDE session task failed", it) }
            }
        }

        fun start(context: Context): Result<Unit> = runCatching {
            val intent = Intent(context, OmochiServerService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
            startPending = true
        }

        fun restart(context: Context): Result<Unit> = runCatching {
            val intent = Intent(context, OmochiServerService::class.java).setAction(ACTION_RESTART)
            ContextCompat.startForegroundService(context, intent)
            startPending = true
        }

        /**
         * Stops the session. Without a running (or starting) service there is no
         * foreground session to end, so no service is created just to stop it; a server
         * process left behind is still terminated, off the main thread.
         */
        fun stop(context: Context) {
            if (instanceAlive || startPending) {
                val intent = Intent(context, OmochiServerService::class.java).setAction(ACTION_STOP)
                if (runCatching { context.startService(intent) }.isSuccess) return
            }
            if (OmochiServerManager.hasSession()) {
                runSessionTask { OmochiServerManager.stop() }
            }
        }
    }
}
