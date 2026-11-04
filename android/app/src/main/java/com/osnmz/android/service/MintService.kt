package com.osnmz.android.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.osnmz.android.data.JobManager
import com.osnmz.android.data.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Single foreground service for ALL jobs (one service, not one per job).
 * Its only job is to keep the process alive while jobs run and to show a
 * persistent notification. It does not hold any private key.
 *
 * Limits, stated honestly in the README: a force-stop, a reboot without
 * relaunch, or an aggressive OEM battery killer can still end the service.
 * Jobs never auto-resume after a process death; the user restarts them.
 */
class MintService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(
            NotificationHelper.SERVICE_ID,
            NotificationHelper.serviceNotification(this, "Menyiapkan job…"),
        )
        scope.launch {
            // Grace period: a job that just started may not have written
            // its LOOKING_UP status yet when this loop first runs. Stopping
            // on the first empty check was silently killing the persistent
            // notification (and the keep-alive) for short jobs like Lookup.
            var emptyChecks = 0
            while (true) {
                val jobs = try { JobManager.get(applicationContext).jobs.value } catch (_: Exception) { emptyList() }
                val running = jobs.filter {
                    it.status.name in setOf("LOOKING_UP", "WAITING", "PREPARING", "SUBMITTING", "CONFIRMING")
                }
                if (running.isEmpty()) {
                    emptyChecks++
                    if (emptyChecks < 3) {
                        // Wait ~10s total before giving up, so a just-started
                        // job still gets its foreground notification.
                        delay(5000)
                        continue
                    }
                    // No active job: stop quietly instead of lingering.
                    stopSelf()
                    break
                }
                emptyChecks = 0
                val summary = running.joinToString("; ") { "${it.name}: ${it.status}" }
                val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
                nm.notify(
                    NotificationHelper.SERVICE_ID,
                    NotificationHelper.serviceNotification(applicationContext, summary),
                )
                delay(5000)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
