package ru.scooblydoon.thunbudget

import java.util.Base64
import java.util.UUID

interface NotificationQueueStore {
    fun read(): String?
    fun write(value: String): Boolean
}

enum class NotificationQueueStatus { PENDING, CONFIRMED, REJECTED }
enum class NotificationQueueEnqueueResult { INSERTED, ALREADY_QUEUED, ALREADY_REVIEWED }

data class NotificationQueueItem(
    val id: String,
    val sourcePackage: String,
    val notificationKey: String?,
    val bankReferenceId: String?,
    val operationType: String,
    val flow: String,
    val amountKopecks: Long?,
    val currency: String?,
    val merchant: String?,
    val category: String?,
    val observedAtMillis: Long,
    val confidence: Double,
    val reviewReasons: List<String>,
    val sourcePayload: String,
    val status: NotificationQueueStatus = NotificationQueueStatus.PENDING,
    val editedAmountKopecks: Long? = null,
    val amountWasCleared: Boolean = false,
    val editedFlow: String? = null,
    val editedMerchant: String? = null,
    val editedCategory: String? = null,
    val decisionAtMillis: Long? = null
) {
    val effectiveAmountKopecks: Long? get() =
        if (amountWasCleared) null else editedAmountKopecks ?: amountKopecks
    val effectiveFlow: String get() = editedFlow ?: flow
    val effectiveMerchant: String? get() = editedMerchant ?: merchant
    val effectiveCategory: String? get() = editedCategory ?: category
    val stableDeduplicationKey: String?
        get() = bankReferenceId?.takeIf(String::isNotBlank)?.let { "tbank:$sourcePackage:$it" }
            ?: notificationKey?.takeIf(String::isNotBlank)?.let { "tbank:$sourcePackage:$it" }
}

/** Offline-first review queue, isolated from both financial ledgers. */
class NotificationReviewQueueRepository(
    private val store: NotificationQueueStore,
    private val idFactory: () -> String = { UUID.randomUUID().toString() }
) {
    companion object {
        private const val HEADER_V1 = "THUNBUDGET_NOTIFICATION_QUEUE_V1"
        private const val HEADER = "THUNBUDGET_NOTIFICATION_QUEUE_V2"
        private const val FIELD_COUNT_V1 = 21
        private const val FIELD_COUNT = 22
        private const val MAX_SOURCE_PAYLOAD_CHARS = 2_000
        private const val T_BANK_PACKAGE = TBankNotificationParser.T_BANK_PACKAGE
    }

    @Synchronized
    fun loadAll(): List<NotificationQueueItem> =
        decode(store.read().orEmpty()).sortedWith(compareBy<NotificationQueueItem> { it.observedAtMillis }.thenBy { it.id })

    @Synchronized
    fun loadPending(): List<NotificationQueueItem> =
        loadAll().filter { it.status == NotificationQueueStatus.PENDING }

    @Synchronized
    fun enqueue(
        event: TBankNotificationParser.ParsedEvent,
        sourcePackage: String,
        sourcePayload: String
    ): NotificationQueueEnqueueResult {
        require(sourcePackage == T_BANK_PACKAGE) { "Источник уведомления не входит в список доверенных." }
        val current = loadAll().toMutableList()
        val key = event.safeDeduplicationKey
        if (key != null) {
            val existing = current.firstOrNull { it.stableDeduplicationKey == key }
            if (existing != null) return if (existing.status == NotificationQueueStatus.PENDING)
                NotificationQueueEnqueueResult.ALREADY_QUEUED else NotificationQueueEnqueueResult.ALREADY_REVIEWED
        }
        current += NotificationQueueItem(
            id = idFactory(), sourcePackage = sourcePackage,
            notificationKey = event.notificationKey, bankReferenceId = event.bankReferenceId,
            operationType = event.operationType.name, flow = event.flow.name,
            amountKopecks = event.amountKopecks, currency = event.currency,
            merchant = event.merchant, category = event.category,
            observedAtMillis = event.observedAtMillis, confidence = event.confidence,
            reviewReasons = event.reviewReasons.map { it.name },
            sourcePayload = sourcePayload.take(MAX_SOURCE_PAYLOAD_CHARS)
        )
        check(persist(current)) { "Не удалось сохранить очередь уведомлений." }
        return NotificationQueueEnqueueResult.INSERTED
    }

    @Synchronized
    fun editPending(
        id: String,
        amountKopecks: Long? = null,
        flow: String? = null,
        merchant: String? = null,
        category: String? = null,
        clearAmount: Boolean = false
    ): Boolean {
        require(amountKopecks == null || amountKopecks > 0L) { "Сумма должна быть больше нуля." }
        require(flow == null || runCatching { TBankNotificationParser.Flow.valueOf(flow) }.isSuccess) {
            "Недопустимое направление операции."
        }
        val current = loadAll()
        val item = current.firstOrNull { it.id == id && it.status == NotificationQueueStatus.PENDING } ?: return false
        val updated = item.copy(
            editedAmountKopecks = amountKopecks,
            amountWasCleared = clearAmount,
            editedFlow = flow?.takeIf(String::isNotBlank),
            editedMerchant = merchant?.trim()?.takeIf(String::isNotEmpty),
            editedCategory = category?.trim()?.takeIf(String::isNotEmpty)
        )
        return persist(current.map { if (it.id == id) updated else it })
    }

    @Synchronized
    fun confirm(id: String, decidedAtMillis: Long): Boolean =
        transition(id, NotificationQueueStatus.CONFIRMED, decidedAtMillis)

    @Synchronized
    fun reject(id: String, decidedAtMillis: Long): Boolean =
        transition(id, NotificationQueueStatus.REJECTED, decidedAtMillis)

    private fun transition(id: String, status: NotificationQueueStatus, at: Long): Boolean {
        val current = loadAll()
        val item = current.firstOrNull { it.id == id && it.status == NotificationQueueStatus.PENDING } ?: return false
        return persist(current.map { if (it.id == id) item.copy(status = status, decisionAtMillis = at) else it })
    }

    private fun persist(items: List<NotificationQueueItem>): Boolean =
        runCatching { store.write(encode(items)) }.getOrDefault(false)

    private fun encode(items: List<NotificationQueueItem>): String = buildString {
        append(HEADER).append('\n')
        items.forEach { item ->
            val fields = listOf(
                item.id, item.sourcePackage, item.notificationKey, item.bankReferenceId,
                item.operationType, item.flow, item.amountKopecks?.toString(), item.currency,
                item.merchant, item.category, item.observedAtMillis.toString(),
                item.confidence.toString(), item.reviewReasons.joinToString(","), item.sourcePayload,
                item.status.name, item.editedAmountKopecks?.toString(), item.editedFlow,
                item.editedMerchant, item.editedCategory, item.decisionAtMillis?.toString(),
                item.stableDeduplicationKey, item.amountWasCleared.toString()
            )
            append(fields.joinToString("\t") { encodeField(it) }).append('\n')
        }
    }

    private fun decode(raw: String): List<NotificationQueueItem> {
        if (raw.isBlank()) return emptyList()
        val lines = raw.lineSequence().toList()
        val header = lines.firstOrNull()
        if (header != HEADER && header != HEADER_V1) return emptyList()
        val expectedFields = if (header == HEADER) FIELD_COUNT else FIELD_COUNT_V1
        return lines.drop(1).mapNotNull { line ->
            runCatching {
                val parts = line.split('\t')
                require(parts.size == expectedFields)
                val values = parts.map(::decodeField)
                val item = NotificationQueueItem(
                    id = values[0] ?: error("missing id"),
                    sourcePackage = values[1] ?: error("missing source"),
                    notificationKey = values[2], bankReferenceId = values[3],
                    operationType = values[4] ?: error("missing operation"),
                    flow = values[5] ?: error("missing flow"),
                    amountKopecks = values[6]?.toLong(), currency = values[7],
                    merchant = values[8], category = values[9],
                    observedAtMillis = values[10]?.toLong() ?: error("missing timestamp"),
                    confidence = values[11]?.toDouble() ?: error("missing confidence"),
                    reviewReasons = values[12]?.takeIf(String::isNotEmpty)?.split(',') ?: emptyList(),
                    sourcePayload = values[13] ?: "",
                    status = NotificationQueueStatus.valueOf(values[14] ?: error("missing status")),
                    editedAmountKopecks = values[15]?.toLong(),
                    editedFlow = values[16], editedMerchant = values[17], editedCategory = values[18],
                    decisionAtMillis = values[19]?.toLong(),
                    amountWasCleared = values.getOrNull(21)?.toBooleanStrictOrNull() ?: false
                )
                require(item.id.isNotBlank() && item.sourcePackage == T_BANK_PACKAGE)
                require(item.confidence in 0.0..1.0)
                item
            }.getOrNull()
        }.distinctBy { it.id }
    }

    private fun encodeField(value: String?): String =
        value?.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray(Charsets.UTF_8)) } ?: "~"

    private fun decodeField(value: String): String? =
        if (value == "~") null else String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)
}
