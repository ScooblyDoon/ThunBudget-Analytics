package ru.scooblydoon.thunbudget

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

object RecurringPaymentNotifications {
    private const val TAG = "RecurringPayReminder"
    private const val CHANNEL_ID = "recurring_payments"
    private const val STATE_PREFS = "recurring_payment_notification_state"

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<RecurringPaymentReminderWorker>(24, TimeUnit.HOURS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "thunbudget_recurring_payment_reminders",
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
        Log.i(TAG, "Daily reminder worker scheduled or updated")
    }

    internal fun show(context: Context, reminder: RecurringPaymentReminder): Boolean {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT < 26) {
            Log.w(TAG, "Notification skipped: unsupported Android version")
            return false
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Notification skipped: POST_NOTIFICATIONS permission is not granted")
            return false
        }
        if (!manager.areNotificationsEnabled()) {
            Log.w(TAG, "Notification skipped: notifications are disabled by the system")
            return false
        }

        val kind = if (reminder.isDayBefore) "day_before" else "due_day"
        val stateKey = "sent_${reminder.payment.id}_${reminder.dueDate}_$kind"
        val state = context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
        if (state.getBoolean(stateKey, false)) {
            Log.d(TAG, "Duplicate reminder suppressed: payment=${reminder.payment.id}, date=${reminder.dueDate}, kind=$kind")
            return false
        }

        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Регулярные платежи", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent(context, MainActivity::class.java)
        intent.putExtra("open_recurring_payments", true)
        val notificationId = stateKey.hashCode()
        val pending = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val dateLabel = reminder.dueDate.format(DateTimeFormatter.ofPattern("d MMMM"))
        val title = if (reminder.isDayBefore) {
            "Завтра платёж: ${reminder.payment.title}"
        } else {
            "Сегодня платёж: ${reminder.payment.title}"
        }
        val notification = android.app.Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText("${reminder.payment.amount.toLong()} ₽ · ${reminder.payment.category} · срок $dateLabel")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()

        manager.notify(notificationId, notification)
        check(state.edit().putBoolean(stateKey, true).commit()) {
            "Не удалось сохранить состояние отправки напоминания."
        }
        Log.i(TAG, "Reminder posted: payment=${reminder.payment.id}, dueDate=${reminder.dueDate}, kind=$kind")
        return true
    }
}

class RecurringPaymentReminderWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = runCatching {
        val today = LocalDate.now()
        val store = RecurringPaymentStore(applicationContext)
        val payments = store.load()
        val reminders = recurringPaymentRemindersFor(payments, today)
        Log.i("RecurringPayReminder", "Checking reminders: date=$today, activePayments=${payments.count { it.active }}, dueReminders=${reminders.size}")
        reminders.forEach { RecurringPaymentNotifications.show(applicationContext, it) }
        Result.success()
    }.getOrElse {
        Log.e("RecurringPayReminder", "Reminder worker failed", it)
        Result.retry()
    }
}
