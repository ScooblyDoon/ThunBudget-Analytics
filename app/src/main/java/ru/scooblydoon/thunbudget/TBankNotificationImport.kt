package ru.scooblydoon.thunbudget

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

internal enum class BankOperationType { EXPENSE, INCOME }

internal data class PendingBankOperation(
    val id: String,
    val title: String,
    val amount: Double,
    val date: String,
    val type: BankOperationType,
    val source: String = "Т-Банк"
)

internal object TBankNotificationParser {
    private val amountPattern = Regex(
        """(?<!\d)(\d{1,3}(?:[ \u00A0\u202F]\d{3})+|\d+)(?:[,.]\d{1,2})?\s*(?:₽|руб(?:лей|ля|ль|\.)?)""",
        RegexOption.IGNORE_CASE
    )
    private val expensePattern = Regex("""покупк|оплат|списан|перевод\s+на|сняти|плат[её]ж""", RegexOption.IGNORE_CASE)
    private val incomePattern = Regex("""поступлен|зачислен|пополнен|входящ(?:ий|его)\s+перевод|перевод\s+от|вам\s+перевели|получен\s+перевод|возврат""", RegexOption.IGNORE_CASE)

    fun parse(title: String?, text: String?, bigText: String?, postTime: Long): PendingBankOperation? {
        val parts = listOfNotNull(title, text, bigText)
            .map { it.replace(Regex("""\s+"""), " ").trim() }
            .filter { it.isNotBlank() }
        if (parts.isEmpty()) return null
        val combined = parts.distinct().joinToString(" ").take(700)
        if (Regex("""баланс|доступно на сч[её]те|остаток на сч[её]те""", RegexOption.IGNORE_CASE).containsMatchIn(combined) &&
            !expensePattern.containsMatchIn(combined) && !incomePattern.containsMatchIn(combined)
        ) return null

        val amountTexts = parts.flatMap { part -> amountPattern.findAll(part).map { it.groupValues[1] }.toList() }
            .map { it.replace(Regex("""[ \u00A0\u202F]"""), "").replace(',', '.') }
            .distinct()
        // The same amount can be repeated in collapsed/expanded notification text.
        // Different amounts (for example, purchase + balance) are ambiguous and are ignored.
        if (amountTexts.size != 1) return null
        val amount = amountTexts.single().toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 } ?: return null

        val isExpense = expensePattern.containsMatchIn(combined)
        val isIncome = incomePattern.containsMatchIn(combined)
        if (isExpense == isIncome) return null

        val titleSource = parts.filter { amountPattern.containsMatchIn(it) }.maxByOrNull { it.length }
            ?: parts.firstOrNull { it.length > 2 }
            ?: "Операция Т-Банка"
        val cleanTitle = titleSource
            .replace(amountPattern, "")
            .replace(Regex("""(?i)покупка|оплата|списание|поступление|зачисление|пополнение|перевод|снятие|возврат"""), "")
            .replace(Regex("""[•·,:;—–-]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .ifBlank { if (isExpense) "Покупка по карте Т-Банка" else "Поступление Т-Банка" }
            .take(100)
        val date = runCatching {
            Instant.ofEpochMilli(postTime).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        }.getOrElse { LocalDate.now().toString() }
        val fingerprint = listOf(date, amount.toString(), if (isExpense) "expense" else "income", combined.lowercase(Locale.ROOT))
            .joinToString("|")
        val id = MessageDigest.getInstance("SHA-256")
            .digest(fingerprint.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return PendingBankOperation(
            id = id,
            title = cleanTitle,
            amount = amount,
            date = date,
            type = if (isExpense) BankOperationType.EXPENSE else BankOperationType.INCOME
        )
    }
}

internal object BankNotificationStore {
    private const val PREFS = "thunbudget_bank_import"
    private const val PENDING = "pending_tbank_operations"
    private const val MAX_PENDING = 50
    private const val TBANK_PACKAGE = "com.idamob.tinkoff.android"

    fun isSupportedPackage(packageName: String): Boolean = packageName == TBANK_PACKAGE

    fun add(context: Context, operation: PendingBankOperation) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = load(context)
        if (current.any { it.id == operation.id }) return
        val next = (listOf(operation) + current).take(MAX_PENDING)
        val array = JSONArray()
        next.forEach { item ->
            array.put(JSONObject()
                .put("id", item.id)
                .put("title", item.title)
                .put("amount", item.amount)
                .put("date", item.date)
                .put("type", item.type.name)
                .put("source", item.source))
        }
        prefs.edit().putString(PENDING, array.toString()).apply()
    }

    fun load(context: Context): List<PendingBankOperation> {
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(PENDING, "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.getJSONObject(index)
                val amount = item.getDouble("amount")
                val date = item.getString("date")
                val type = BankOperationType.valueOf(item.getString("type"))
                if (!amount.isFinite() || amount <= 0.0 || runCatching { LocalDate.parse(date) }.isFailure) null
                else PendingBankOperation(
                    id = item.getString("id"),
                    title = item.getString("title").take(100),
                    amount = amount,
                    date = date,
                    type = type,
                    source = item.optString("source", "Т-Банк")
                )
            }
        }.getOrDefault(emptyList())
    }

    fun remove(context: Context, id: String) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val remaining = load(context).filterNot { it.id == id }
        val array = JSONArray()
        remaining.forEach { item ->
            array.put(JSONObject().put("id", item.id).put("title", item.title)
                .put("amount", item.amount).put("date", item.date)
                .put("type", item.type.name).put("source", item.source))
        }
        prefs.edit().putString(PENDING, array.toString()).apply()
    }

    fun isAccessEnabled(context: Context): Boolean {
        val enabled = android.provider.Settings.Secure.getString(
            context.contentResolver, "enabled_notification_listeners"
        ) ?: return false
        return enabled.split(':').any { it.contains(context.packageName) }
    }

    fun openAccessSettings(context: Context) {
        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

class TBankNotificationListenerService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || !BankNotificationStore.isSupportedPackage(sbn.packageName)) return
        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val operation = TBankNotificationParser.parse(title, text, bigText, sbn.postTime) ?: return
        BankNotificationStore.add(applicationContext, operation)
    }
}
