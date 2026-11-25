package ru.scooblydoon.thunbudget

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

object RecurringPaymentNotifications {
    private const val TAG = "RecurringPayReminder"
    private const val CHANNEL_ID = "recurring_payments"
    private const val STATE_PREFS = "recurring_payment_notification_state"
    private const val ALARM_REQUEST_CODE = 8400
    private val DAILY_CHECK_TIME: LocalTime = LocalTime.of(7, 0)

    /**
     * Schedules a local-time daily check at 07:00. Unlike a 24-hour periodic
     * WorkManager interval, this is anchored to the calendar day and survives
     * normal device idle through setAndAllowWhileIdle.
     */
    fun schedule(context: Context) {
        val appContext = context.applicationContext
        WorkManager.getInstance(appContext).cancelUniqueWork("thunbudget_recurring_payment_reminders")
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val now = ZonedDateTime.now()
        var nextRun = now.toLocalDate().atTime(DAILY_CHECK_TIME).atZone(now.zone)
        if (!nextRun.isAfter(now)) nextRun = nextRun.plusDays(1)

        val intent = Intent(appContext, RecurringPaymentAlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            appContext,
            ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextRun.toInstant().toEpochMilli(), pendingIntent)
        Log.i(TAG, "Daily calendar alarm scheduled for $nextRun")
    }

    /** Runs immediately when the app opens, then restores the next daily alarm. */
    fun checkAndReschedule(context: Context) {
        val appContext = context.applicationContext
        try {
            val today = LocalDate.now()
            val payments = RecurringPaymentStore(appContext).load()
            val reminders = recurringPaymentRemindersFor(payments, today)
            Log.i(TAG, "Checking reminders: date=$today, activePayments=${payments.count { it.active }}, dueReminders=${reminders.size}")
            reminders.forEach { show(appContext, it) }
        } catch (error: Exception) {
            Log.e(TAG, "Reminder check failed", error)
        } finally {
            schedule(appContext)
        }
    }

    internal fun show(context: Context, reminder: RecurringPaymentReminder): Boolean {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
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

        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Регулярные платежи", NotificationManager.IMPORTANCE_DEFAULT)
            )
            if (manager.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE) {
                Log.w(TAG, "Notification skipped: recurring payment channel is disabled")
                return false
            }
        }
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
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            android.app.Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(context)
        }
        val notification = builder
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

class RecurringPaymentAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        RecurringPaymentNotifications.checkAndReschedule(context)
    }
}

class RecurringPaymentBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> RecurringPaymentNotifications.schedule(context)
        }
    }
}


/** Compatibility worker for periodic jobs scheduled by older app versions. */
class RecurringPaymentReminderWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        RecurringPaymentNotifications.checkAndReschedule(applicationContext)
        return Result.success()
    }
}
