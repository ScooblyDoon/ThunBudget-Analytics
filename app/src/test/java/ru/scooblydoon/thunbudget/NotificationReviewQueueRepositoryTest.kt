package ru.scooblydoon.thunbudget

import org.junit.Assert.*
import org.junit.Test

class NotificationReviewQueueRepositoryTest {
    private class MemoryStore : NotificationQueueStore {
        var value: String? = null
        var failWrites = false
        override fun read(): String? = value
        override fun write(value: String): Boolean {
            if (failWrites) return false
            this.value = value
            return true
        }
    }

    private val packageName = TBankNotificationParser.T_BANK_PACKAGE
    private fun event(text: String, key: String? = null, ref: String? = null): TBankNotificationParser.ParsedEvent =
        TBankNotificationParser.parse(
            TBankNotificationParser.Input(
                packageName = packageName, title = "Т-Банк", text = text,
                postTimeMillis = 1_795_000_000_000L, notificationKey = key, bankReferenceId = ref
            )
        )!!

    @Test fun accumulatesSeveralNotificationsWithoutOverwriting() {
        val store = MemoryStore()
        val queue = NotificationReviewQueueRepository(store) { "id-${store.value?.length ?: 0}-${store.value.hashCode()}" }
        assertEquals(NotificationQueueEnqueueResult.INSERTED, queue.enqueue(event("Покупка Магнит 100 ₽"), packageName, "one"))
        assertEquals(NotificationQueueEnqueueResult.INSERTED, queue.enqueue(event("Покупка Аптека 200 ₽"), packageName, "two"))
        assertEquals(2, queue.loadPending().size)
        assertEquals(listOf("one", "two"), queue.loadPending().map { it.sourcePayload })
    }

    @Test fun sameMerchantAndAmountWithoutStableIdentityRemainSeparate() {
        val queue = NotificationReviewQueueRepository(MemoryStore())
        queue.enqueue(event("Покупка КрасАвтоТранс 48 ₽"), packageName, "first")
        queue.enqueue(event("Покупка КрасАвтоТранс 48 ₽"), packageName, "second")
        assertEquals(2, queue.loadPending().size)
    }

    @Test fun stableIdentityPreventsDuplicateDelivery() {
        val queue = NotificationReviewQueueRepository(MemoryStore())
        val candidate = event("Покупка Магнит 100 ₽", key = "same-key")
        assertEquals(NotificationQueueEnqueueResult.INSERTED, queue.enqueue(candidate, packageName, "first"))
        assertEquals(NotificationQueueEnqueueResult.ALREADY_QUEUED, queue.enqueue(candidate, packageName, "replay"))
        assertEquals(1, queue.loadAll().size)
    }

    @Test fun reviewedStableIdentityIsNotReintroduced() {
        val queue = NotificationReviewQueueRepository(MemoryStore())
        val candidate = event("Покупка Магнит 100 ₽", key = "same-key")
        queue.enqueue(candidate, packageName, "first")
        val id = queue.loadPending().single().id
        assertTrue(queue.confirm(id, 123L))
        assertEquals(NotificationQueueEnqueueResult.ALREADY_REVIEWED, queue.enqueue(candidate, packageName, "replay"))
        assertEquals(1, queue.loadAll().size)
    }

    @Test fun pendingItemsSurviveRepositoryRecreation() {
        val store = MemoryStore()
        val first = NotificationReviewQueueRepository(store) { "fixed-id" }
        first.enqueue(event("Покупка Магнит 1 250,50 ₽"), packageName, "payload")
        val reopened = NotificationReviewQueueRepository(store)
        val item = reopened.loadPending().single()
        assertEquals("fixed-id", item.id)
        assertEquals(125050L, item.amountKopecks)
        assertEquals("payload", item.sourcePayload)
    }

    @Test fun editsAndDecisionsAffectOnlySelectedItem() {
        val queue = NotificationReviewQueueRepository(MemoryStore()) { java.util.UUID.randomUUID().toString() }
        queue.enqueue(event("Покупка Магнит 100 ₽"), packageName, "one")
        queue.enqueue(event("Покупка Аптека 200 ₽"), packageName, "two")
        val (first, second) = queue.loadPending()
        assertTrue(queue.editPending(first.id, amountKopecks = 15000L, category = "Продукты"))
        assertTrue(queue.confirm(first.id, 500L))
        assertTrue(queue.reject(second.id, 600L))
        val all = queue.loadAll().associateBy { it.id }
        assertEquals(NotificationQueueStatus.CONFIRMED, all[first.id]!!.status)
        assertEquals(15000L, all[first.id]!!.effectiveAmountKopecks)
        assertEquals("Продукты", all[first.id]!!.effectiveCategory)
        assertEquals(NotificationQueueStatus.REJECTED, all[second.id]!!.status)
        assertTrue(queue.loadPending().isEmpty())
    }

    @Test fun confirmedOrRejectedItemsCannotBeEditedOrTransitionedAgain() {
        val queue = NotificationReviewQueueRepository(MemoryStore())
        queue.enqueue(event("Покупка Магнит 100 ₽"), packageName, "one")
        val id = queue.loadPending().single().id
        assertTrue(queue.reject(id, 1L))
        assertFalse(queue.confirm(id, 2L))
        assertFalse(queue.editPending(id, merchant = "Изменение"))
    }

    @Test fun nonTrustedPackageIsRejected() {
        val queue = NotificationReviewQueueRepository(MemoryStore())
        try {
            queue.enqueue(event("Покупка Магнит 100 ₽"), "com.other.app", "payload")
            fail("Expected untrusted source to be rejected")
        } catch (_: IllegalArgumentException) { }
        assertTrue(queue.loadAll().isEmpty())
    }

    @Test fun malformedRowDoesNotEraseOtherValidRows() {
        val store = MemoryStore()
        val queue = NotificationReviewQueueRepository(store) { "valid-id" }
        queue.enqueue(event("Покупка Магнит 100 ₽"), packageName, "valid")
        store.value = store.value + "broken-row\n"
        assertEquals(1, queue.loadPending().size)
    }

    @Test fun failedPersistenceIsReportedAndDoesNotPretendToSucceed() {
        val store = MemoryStore()
        val queue = NotificationReviewQueueRepository(store)
        store.failWrites = true
        try {
            queue.enqueue(event("Покупка Магнит 100 ₽"), packageName, "payload")
            fail("Expected persistence failure")
        } catch (_: IllegalStateException) { }
        assertTrue(queue.loadAll().isEmpty())
    }
}
