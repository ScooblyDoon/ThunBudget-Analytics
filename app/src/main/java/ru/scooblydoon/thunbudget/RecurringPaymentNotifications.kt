package ru.scooblydoon.thunbudget

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

internal fun recurringPaymentReminderDaysUntilDue(today: LocalDate, dueDate: LocalDate): Long? {
    val days = ChronoUnit.DAYS.between(today, dueDate)
    return days.takeIf { it == 1L || it == 0L }
}

object RecurringPaymentNotifications {
    private const val CHANNEL_ID = "recurring_payments"
    private const val WORK_NAME = "thunbudget_recurring_payment_reminders"
    private const val SENT_PREFIX = "recurring_payment_reminder_sent_"

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<RecurringPaymentReminderWorker>(24, TimeUnit.HOURS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    internal fun show(context: Context, payment: RecurringPayment, month: YearMonth, daysUntilDue: Long) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT < 26) return

        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Регулярные платежи", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent(context, MainActivity::class.java)
        intent.putExtra("open_recurring_payments", true)
        val notificationId = "${payment.id}_${month}".hashCode()
        val pending = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val dueText = if (daysUntilDue == 1L) "Завтра планируется оплата" else "Сегодня срок оплаты"
        val notification = android.app.Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("$dueText: ${payment.title}")
            .setContentText("${payment.amount.toLong()} ₽ · ${payment.category}. Нажми, чтобы подтвердить расход.")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        manager.notify(notificationId, notification)
    }

    internal fun wasReminderSent(context: Context, paymentId: String, month: YearMonth, daysUntilDue: Long): Boolean {
        val key = reminderKey(paymentId, month, daysUntilDue)
        return context.getSharedPreferences("thunbudget_notification_state", Context.MODE_PRIVATE)
            .getBoolean(key, false)
    }

    internal fun markReminderSent(context: Context, paymentId: String, month: YearMonth, daysUntilDue: Long) {
        val key = reminderKey(paymentId, month, daysUntilDue)
        check(
            context.getSharedPreferences("thunbudget_notification_state", Context.MODE_PRIVATE)
                .edit().putBoolean(key, true).commit()
        ) { "Не удалось сохранить состояние напоминания." }
    }

    private fun reminderKey(paymentId: String, month: YearMonth, daysUntilDue: Long) =
        SENT_PREFIX + paymentId + "_" + month + "_" + daysUntilDue
}

class RecurringPaymentReminderWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = runCatching {
        val today = LocalDate.now()
        val store = RecurringPaymentStore(applicationContext)
        val monthsToCheck = listOf(YearMonth.from(today), YearMonth.from(today).plusMonths(1))

        store.load().filter { it.active }.forEach { payment ->
            monthsToCheck.forEach { month ->
                if (payment.isCompleted(month)) return@forEach
                val dueDate = payment.dueDate(month)
                val daysUntilDue = recurringPaymentReminderDaysUntil(today, dueDate) ?: return@forEach
                if (!RecurringPaymentNotifications.wasReminderSent(applicationContext, payment.id, month, daysUntilDue)) {
                    RecurringPaymentNotifications.show(applicationContext, payment, month, daysUntilDue)
                    RecurringPaymentNotifications.markReminderSent(applicationContext, payment.id, month, daysUntilDue)
                }
            }
        }
        Result.success()
    }.getOrElse { Result.retry() }
}
