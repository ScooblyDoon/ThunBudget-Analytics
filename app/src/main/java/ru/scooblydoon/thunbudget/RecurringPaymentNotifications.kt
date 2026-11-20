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
import java.util.concurrent.TimeUnit

object RecurringPaymentNotifications {
    private const val CHANNEL_ID = "recurring_payments"
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<RecurringPaymentReminderWorker>(24, TimeUnit.HOURS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "thunbudget_recurring_payment_reminders",
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    internal fun show(context: Context, payment: RecurringPayment) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Регулярные платежи", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent(context, MainActivity::class.java)
        intent.putExtra("open_recurring_payments", true)
        val pending = PendingIntent.getActivity(context, payment.id.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = if (Build.VERSION.SDK_INT >= 26) {
            android.app.Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Пора оплатить: ${payment.title}")
                .setContentText("${payment.amount.toLong()} ₽ · ${payment.category}. Нажми, чтобы подтвердить расход.")
                .setContentIntent(pending).setAutoCancel(true).build()
        } else return
        manager.notify(payment.id.hashCode(), notification)
    }
}

class RecurringPaymentReminderWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = runCatching {
        val store = RecurringPaymentStore(applicationContext)
        store.pendingFor(LocalDate.now()).forEach { RecurringPaymentNotifications.show(applicationContext, it) }
        Result.success()
    }.getOrElse { Result.retry() }
}
