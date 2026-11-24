package ru.scooblydoon.thunbudget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun NotificationReviewQueueScreen(repository: NotificationReviewQueueRepository) {
    var rows by remember { mutableStateOf(repository.loadAll()) }
    var selected by remember { mutableStateOf<NotificationQueueItem?>(null) }
    var showResolved by remember { mutableStateOf(false) }
    var expandedPayloadIds by remember { mutableStateOf(emptySet<String>()) }
    fun refresh() { rows = repository.loadAll() }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 12.dp)) {
                Text("Импорт из уведомлений", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "Здесь можно проверить распознанные банковские уведомления. Подтверждение пока не добавляет операцию в бюджет.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { refresh() }) { Text("Обновить") }
                    OutlinedButton(onClick = { showResolved = !showResolved }) {
                        Text(if (showResolved) "Скрыть обработанные" else "Обработанные")
                    }
                }
            }
        }
        val visible = rows.filter {
            if (showResolved) it.status != NotificationQueueStatus.PENDING
            else it.status == NotificationQueueStatus.PENDING
        }
        if (visible.isEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Text(
                        if (showResolved) "Обработанных уведомлений пока нет."
                        else "Очередь пока пуста. Новые уведомления появятся здесь после подключения источника уведомлений.",
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        items(visible, key = { it.id }) { item ->
            Card {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Row {
                        Text(item.effectiveMerchant ?: item.operationType.toDisplayName(),
                            modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        Text(item.status.name.toDisplayName(), color = MaterialTheme.colorScheme.primary)
                    }
                    Text(item.effectiveAmountKopecks?.let { formatKopecks(it) + " ₽" } ?: "Сумма не распознана",
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("${item.effectiveFlow.toDisplayName()} · ${item.effectiveCategory ?: "Без категории"}",
                        style = MaterialTheme.typography.bodyMedium)
                    Text("Получено: ${formatObservedTime(item.observedAtMillis)} · ${item.sourcePackage}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (item.reviewReasons.isNotEmpty()) {
                        Text("Требует проверки: ${item.reviewReasons.joinToString()}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    if (item.sourcePayload.isNotBlank()) {
                        TextButton(onClick = {
                            expandedPayloadIds = if (item.id in expandedPayloadIds)
                                expandedPayloadIds - item.id else expandedPayloadIds + item.id
                        }) { Text(if (item.id in expandedPayloadIds) "Скрыть исходный текст" else "Показать исходный текст") }
                        if (item.id in expandedPayloadIds) {
                            Text(item.sourcePayload, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (item.status == NotificationQueueStatus.PENDING) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { selected = item }) { Text("Изменить") }
                            Button(onClick = {
                                repository.confirm(item.id, System.currentTimeMillis())
                                refresh()
                            }) { Text("Подтвердить") }
                            TextButton(onClick = {
                                repository.reject(item.id, System.currentTimeMillis())
                                refresh()
                            }) { Text("Отклонить") }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.width(1.dp).padding(bottom = 24.dp)) }
    }

    selected?.let { item ->
        var amount by remember(item.id) {
            mutableStateOf(item.effectiveAmountKopecks?.let { formatKopecks(it) }.orEmpty())
        }
        var merchant by remember(item.id) { mutableStateOf(item.effectiveMerchant.orEmpty()) }
        var category by remember(item.id) { mutableStateOf(item.effectiveCategory.orEmpty()) }
        var flow by remember(item.id) {
            mutableStateOf(runCatching { TBankNotificationParser.Flow.valueOf(item.effectiveFlow) }
                .getOrDefault(TBankNotificationParser.Flow.REVIEW))
        }
        var flowMenuExpanded by remember(item.id) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text("Проверка операции") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = amount, onValueChange = { amount = it },
                        label = { Text("Сумма, ₽ (оставьте пустой, чтобы очистить)") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = merchant, onValueChange = { merchant = it },
                        label = { Text("Магазин / получатель") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = category, onValueChange = { category = it },
                        label = { Text("Категория") }, modifier = Modifier.fillMaxWidth())
                    Column {
                        Text("Направление операции", style = MaterialTheme.typography.labelMedium)
                        OutlinedButton(onClick = { flowMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(flow.toDisplayName())
                        }
                        DropdownMenu(expanded = flowMenuExpanded, onDismissRequest = { flowMenuExpanded = false }) {
                            TBankNotificationParser.Flow.values().forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.toDisplayName()) },
                                    onClick = { flow = option; flowMenuExpanded = false }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val parsedAmount = amount.trim().takeIf { it.isNotEmpty() }
                        ?.replace(" ", "")?.replace(',', '.')?.toBigDecimalOrNull()
                        ?.let { runCatching { it.movePointRight(2).longValueExact() }.getOrNull() }
                    if (amount.isBlank() || parsedAmount != null && parsedAmount > 0L) {
                        repository.editPending(
                            id = item.id,
                            amountKopecks = parsedAmount,
                            clearAmount = amount.isBlank(),
                            flow = flow.name,
                            merchant = merchant,
                            category = category
                        )
                        refresh()
                        selected = null
                    }
                }) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { selected = null }) { Text("Отмена") } }
        )
    }
}

private fun String.toDisplayName(): String = when (this) {
    "PURCHASE" -> "Покупка"
    "TRANSFER_IN" -> "Входящий перевод"
    "TRANSFER_OUT" -> "Исходящий перевод"
    "CASH_WITHDRAWAL" -> "Снятие наличных"
    "CASH_DEPOSIT" -> "Внесение наличных"
    "REFUND" -> "Возврат"
    "REVERSAL" -> "Отмена операции"
    "CASHBACK" -> "Кэшбэк"
    "SUBSCRIPTION" -> "Подписка"
    "BALANCE_NOTICE" -> "Информация об остатке"
    "UNKNOWN" -> "Неизвестная операция"
    "PENDING" -> "Ожидает проверки"
    "CONFIRMED" -> "Подтверждено"
    "REJECTED" -> "Отклонено"
    "EXPENSE" -> "Расход"
    "INCOME" -> "Доход"
    "INTERNAL" -> "Между своими счетами"
    "NONE" -> "Не учитывать"
    "REVIEW" -> "Нужна проверка"
    else -> this
}

private fun TBankNotificationParser.Flow.toDisplayName(): String = name.toDisplayName()

private fun formatKopecks(value: Long): String =
    java.math.BigDecimal.valueOf(value, 2).stripTrailingZeros().toPlainString()

private fun formatObservedTime(value: Long): String = runCatching {
    DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
        .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(value))
}.getOrDefault("—")
