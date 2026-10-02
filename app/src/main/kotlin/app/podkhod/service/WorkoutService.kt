package app.podkhod.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import app.podkhod.MainActivity
import app.podkhod.R
import app.podkhod.data.db.WorkoutSessionEntity
import app.podkhod.data.settings.AppSettings
import app.podkhod.data.settings.SettingsRepository
import app.podkhod.data.workout.WorkoutRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * Keeps the active workout visible and the rest timer reliable while the screen is off.
 *
 * The timer state lives in Room (rest_ends_at), not here: the service only mirrors it into a notification
 * and fires the end-of-rest alert. If the process dies, the next start reads the same state back.
 */
class WorkoutService : Service() {

    private val workouts: WorkoutRepository by inject()
    private val settingsRepo: SettingsRepository by inject()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observeJob: Job? = null
    private var restJob: Job? = null
    private var alertedRestEnd: Long? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var inForeground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!enterForeground(placeholderNotification())) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (observeJob == null) observeJob = scope.launch { observe() }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    private suspend fun observe() {
        combine(workouts.activeSession, settingsRepo.settings) { s, st -> s to st }.collectLatest { (session, settings) ->
            if (session == null) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return@collectLatest
            }
            render(session)
            scheduleRestEnd(session, settings)
        }
    }

    private fun render(session: WorkoutSessionEntity) {
        val now = System.currentTimeMillis()
        val restEnd = session.restEndsAt?.takeIf { it > now }
        val builder = NotificationCompat.Builder(this, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_stat_workout)
            .setContentTitle(session.title)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setUsesChronometer(true)
        if (restEnd != null) {
            builder.setContentText(getString(R.string.notif_resting))
                .setWhen(restEnd)
                .setChronometerCountDown(true)
        } else {
            builder.setContentText(getString(R.string.notif_in_progress))
                .setWhen(session.startedAt)
                .setChronometerCountDown(false)
        }
        enterForeground(builder.build())
    }

    private fun scheduleRestEnd(session: WorkoutSessionEntity, settings: AppSettings) {
        restJob?.cancel()
        val endsAt = session.restEndsAt
        if (endsAt == null) {
            releaseWakeLock()
            return
        }
        val wait = endsAt - System.currentTimeMillis()
        if (wait <= 0) {
            releaseWakeLock()
            if (alertedRestEnd != endsAt && wait > -STALE_ALERT_MS) alertRestEnd(endsAt, settings)
            scope.launch { workouts.clearExpiredRest(session.id) }
            return
        }
        acquireWakeLock(wait + WAKELOCK_SLACK_MS)
        restJob = scope.launch {
            delay(wait)
            alertRestEnd(endsAt, settings)
            workouts.clearExpiredRest(session.id)
            releaseWakeLock()
        }
    }

    private fun alertRestEnd(endsAt: Long, settings: AppSettings) {
        alertedRestEnd = endsAt
        val channel = when {
            settings.restSound && settings.restVibration -> CHANNEL_REST_SOUND_VIBRATE
            settings.restSound -> CHANNEL_REST_SOUND
            settings.restVibration -> CHANNEL_REST_VIBRATE
            else -> return
        }
        val notification = NotificationCompat.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_stat_workout)
            .setContentTitle(getString(R.string.notif_rest_over_title))
            .setContentText(getString(R.string.notif_rest_over_text))
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .setTimeoutAfter(REST_ALERT_TIMEOUT_MS)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        notifySafely(REST_ALERT_ID, notification)
    }

    private fun notifySafely(id: Int, notification: Notification) {
        try {
            NotificationManagerCompat.from(this).notify(id, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS denied: the in-app timer still shows the end of rest.
            Log.w(TAG, "notification not permitted")
        }
    }

    private fun enterForeground(notification: Notification): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(ONGOING_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(ONGOING_ID, notification)
            }
            inForeground = true
            true
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException after a sticky restart from the background.
            Log.w(TAG, "cannot enter foreground", e)
            inForeground
        } catch (e: SecurityException) {
            Log.w(TAG, "foreground service not permitted", e)
            inForeground
        }
    }

    private fun placeholderNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_stat_workout)
            .setContentTitle(getString(R.string.notif_in_progress))
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun acquireWakeLock(timeoutMs: Long) {
        releaseWakeLock()
        val pm = getSystemService<PowerManager>() ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "podkhod:rest").apply {
            setReferenceCounted(false)
            acquire(timeoutMs)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    companion object {
        private const val TAG = "WorkoutService"
        private const val ONGOING_ID = 1001
        private const val REST_ALERT_ID = 1002
        private const val WAKELOCK_SLACK_MS = 15_000L
        private const val REST_ALERT_TIMEOUT_MS = 30_000L
        private const val STALE_ALERT_MS = 10_000L

        const val CHANNEL_ONGOING = "workout_ongoing"
        const val CHANNEL_REST_SOUND_VIBRATE = "rest_end_sound_vibrate"
        const val CHANNEL_REST_SOUND = "rest_end_sound"
        const val CHANNEL_REST_VIBRATE = "rest_end_vibrate"

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, WorkoutService::class.java))
            } catch (e: IllegalStateException) {
                Log.w(TAG, "cannot start service", e)
            }
        }

        fun createChannels(context: Context) {
            val nm = context.getSystemService<NotificationManager>() ?: return
            val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val vibration = longArrayOf(0, 400, 200, 400)
            nm.createNotificationChannels(
                listOf(
                    NotificationChannel(CHANNEL_ONGOING, context.getString(R.string.channel_ongoing), NotificationManager.IMPORTANCE_LOW).apply {
                        setShowBadge(false)
                    },
                    NotificationChannel(CHANNEL_REST_SOUND_VIBRATE, context.getString(R.string.channel_rest_sound_vibrate), NotificationManager.IMPORTANCE_HIGH).apply {
                        setSound(sound, attrs)
                        enableVibration(true)
                        vibrationPattern = vibration
                    },
                    NotificationChannel(CHANNEL_REST_SOUND, context.getString(R.string.channel_rest_sound), NotificationManager.IMPORTANCE_HIGH).apply {
                        setSound(sound, attrs)
                        enableVibration(false)
                    },
                    NotificationChannel(CHANNEL_REST_VIBRATE, context.getString(R.string.channel_rest_vibrate), NotificationManager.IMPORTANCE_HIGH).apply {
                        setSound(null, null)
                        enableVibration(true)
                        vibrationPattern = vibration
                    },
                ),
            )
        }
    }
}
