package com.localgpt.app.reminder

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Worker yang menampilkan notifikasi pengingat ketika waktu pemicu tiba.
 *
 * Dijadwalkan melalui [scheduleReminder]; jangan di-enqueue langsung.
 */
class ReminderWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val note = inputData.getString(KEY_NOTE)?.trim().orEmpty()
        if (note.isEmpty()) return Result.failure()
        showReminderNotification(applicationContext, note)
        return Result.success()
    }
}
