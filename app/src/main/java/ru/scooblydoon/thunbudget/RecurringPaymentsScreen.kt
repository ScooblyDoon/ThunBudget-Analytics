package ru.scooblydoon.thunbudget

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecurringPaymentsScreen(
    payments: List<RecurringPayment>,
    categories: List<BudgetCategory>,
    onAdd: (RecurringPayment) -> Unit,
    onUpdate: (RecurringPayment) -> Unit,
    onToggle: (RecurringPayment) -> Unit,
    onDelete: (RecurringPayment) -> Unit,
    onConfirm: (RecurringPayment, YearMonth) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val uiPrefs = remember(context) {
        context.getSharedPreferences("thunbudget_ui", Context.MODE_PRIVATE)
    }
    var showAdd by remember { mutableStateOf(false) }
    var editingPayment by remember { mutableStateOf<RecurringPayment?>(null) }
    var displayMode by remember {
        mutableStateOf(uiPrefs.getString("recurring_payments_display_mode", "list") ?: "list")
    }
    var pendingExpanded by remember {
        mutableStateOf(uiPrefs.getBoolean("pending_payments_expanded", true))
    }
    var displayedMonth by remember { mutableStateOf(YearMonth.now()) }
    var selectedDate by remember { mutableStateOf<LocalDate?>(null) }
    var selectedPaymentId by remember { mutableStateOf<String?>(null) }

    val today = LocalDate.now()
    val currentMonth = YearMonth.from(today)
    val pending = payments.filter {
        it.active && it.dueDate(currentMonth) <= today && !it.isCompleted(currentMonth)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                "Регулярные платежи",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Подтверждай оплату — только тогда она попадёт в историю расходов.",
                style = MaterialTheme.typography.bodySmall
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = displayMode == "list",
                onClick = {
                    displayMode = "list"
                    uiPrefs.edit().putString("recurring_payments_display_mode", displayMode).apply()
                },
                label = { Text("Список") }
            )
            FilterChip(
                selected = displayMode == "calendar",
                onClick = {
                    displayMode = "calendar"
                    uiPrefs.edit().putString("recurring_payments_display_mode", displayMode).apply()
                },
                label = { Text("Календарь") }
            )
        }

        if (displayMode == "calendar") {
            RecurringPaymentsCalendar(
                month = displayedMonth,
                payments = payments,
                selectedDate = selectedDate,
                onPreviousMonth = { displayedMonth = displayedMonth.minusMonths(1) },
                onNextMonth = { displayedMonth = displayedMonth.plusMonths(1) },
                onSelectDate = { date ->
                    selectedDate = date
                    selectedPaymentId = payments.firstOrNull { it.dueDate(YearMonth.from(date)) == date }?.id
                },
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp)
            ) {
                if (pending.isNotEmpty()) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Ожидают подтверждения (${pending.size})",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = {
                                    pendingExpanded = !pendingExpanded
                                    uiPrefs.edit().putBoolean("pending_payments_expanded", pendingExpanded).apply()
                                }
                            ) {
                                Text(if (pendingExpanded) "Скрыть ▲" else "Показать ▼")
                            }
                        }
                    }
                    if (pendingExpanded) {
                        items(pending, key = { "pending-" + it.id }) { payment ->
                            Card(
                                shape = RoundedCornerShape(18.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                            ) {
                                Column(
                                    Modifier.fillMaxWidth().padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(payment.title, fontWeight = FontWeight.SemiBold)
                                            Text(
                                                "${payment.category} · срок ${payment.dueDate(currentMonth).format(DATE_FORMAT)}",
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                        Text(recurringPaymentMoney(payment.amount), fontWeight = FontWeight.Bold)
                                    }
                                    Button(
                                        onClick = { onConfirm(payment, currentMonth) },
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text("Подтвердить оплату и внести в расходы") }
                                }
                            }
                        }
                    }
                }

                item {
                    Text(
                        "Все платежи",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                if (payments.isEmpty()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Text(
                                "Пока нет регулярных платежей. Нажми «+ Добавить», чтобы создать первый платёж.",
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                } else {
                    items(payments, key = { it.id }) { payment ->
                        Card(
                            modifier = Modifier.clickable {
                                selectedDate = payment.dueDate(currentMonth)
                                selectedPaymentId = payment.id
                                displayedMonth = currentMonth
                            },
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Text(payment.title, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "${recurringPaymentMoney(payment.amount)} · ${payment.category} · ${payment.dayOfMonth}-е число каждого месяца",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        if (payment.active) "Активен" else "Приостановлен",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                TextButton(onClick = { onToggle(payment) }) {
                                    Text(if (payment.active) "Пауза" else "Включить")
                                }
                                TextButton(onClick = { onDelete(payment) }) { Text("Удалить") }
                            }
                        }
                    }
                }
            }
        }

        Button(
            onClick = { showAdd = true },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text("+ Добавить плановый расход")
        }
    }

    val detailsDate = selectedDate
    if (detailsDate != null) {
        val detailsMonth = YearMonth.from(detailsDate)
        val datePayments = payments
            .filter { it.dueDate(detailsMonth) == detailsDate }
            .sortedBy { it.title.lowercase(Locale.ROOT) }
        val selectedIndex = datePayments.indexOfFirst { it.id == selectedPaymentId }
            .let { if (it < 0) 0 else it }
        val selectedPayment = datePayments.getOrNull(selectedIndex)

        if (selectedPayment != null) {
            RecurringPaymentDetailsDialog(
                payment = selectedPayment,
                month = detailsMonth,
                paymentIndex = selectedIndex,
                paymentCount = datePayments.size,
                onPrevious = {
                    val index = (selectedIndex - 1).coerceAtLeast(0)
                    selectedPaymentId = datePayments[index].id
                },
                onNext = {
                    val index = (selectedIndex + 1).coerceAtMost(datePayments.lastIndex)
                    selectedPaymentId = datePayments[index].id
                },
                onCancel = {
                    selectedDate = null
                    selectedPaymentId = null
                },
                onEdit = {
                    editingPayment = selectedPayment
                    selectedDate = null
                    selectedPaymentId = null
                },
                onToggle = { onToggle(selectedPayment) },
                onDelete = {
                    onDelete(selectedPayment)
                    selectedDate = null
                    selectedPaymentId = null
                },
                onConfirm = {
                    onConfirm(selectedPayment, detailsMonth)
                    selectedDate = null
                    selectedPaymentId = null
                }
            )
        } else {
            selectedDate = null
        }
    }

    if (showAdd || editingPayment != null) {
        RecurringPaymentEditorDialog(
            payment = editingPayment,
            categories = categories,
            onDismiss = {
                showAdd = false
                editingPayment = null
            },
            onSave = { payment ->
                if (editingPayment == null) onAdd(payment) else onUpdate(payment)
                showAdd = false
                editingPayment = null
            }
        )
    }
}

private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")

@Composable
private fun RecurringPaymentsCalendar(
    month: YearMonth,
    payments: List<RecurringPayment>,
    selectedDate: LocalDate?,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onSelectDate: (LocalDate) -> Unit,
    modifier: Modifier = Modifier
) {
    val today = LocalDate.now()
    val firstWeekdayOffset = month.atDay(1).dayOfWeek.value - 1
    val dateSlots: List<LocalDate?> =
        List(firstWeekdayOffset) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
    val weeks = dateSlots.chunked(7)
    val weekdayLabels = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onPreviousMonth) { Text("‹") }
            Text(
                month.format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale("ru"))).replaceFirstChar { it.uppercase(Locale("ru")) },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            TextButton(onClick = onNextMonth) { Text("›") }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            weekdayLabels.forEach { label ->
                Text(
                    label,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        weeks.forEach { week ->
            Row(modifier = Modifier.fillMaxWidth()) {
                week.forEach { date ->
                    if (date == null) {
                        Spacer(modifier = Modifier.weight(1f).height(54.dp))
                    } else {
                        val dayPayments = payments.filter { it.dueDate(month) == date }
                        val hasPayments = dayPayments.isNotEmpty()
                        val isSelected = selectedDate == date
                        val isToday = date == today
                        val containerColor = when {
                            isSelected -> MaterialTheme.colorScheme.primary
                            hasPayments -> MaterialTheme.colorScheme.primaryContainer
                            else -> MaterialTheme.colorScheme.surface
                        }
                        val contentColor = when {
                            isSelected -> MaterialTheme.colorScheme.onPrimary
                            hasPayments -> MaterialTheme.colorScheme.onPrimaryContainer
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(2.dp)
                                .height(50.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(containerColor)
                                .clickable(enabled = hasPayments) { onSelectDate(date) },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                date.dayOfMonth.toString(),
                                color = contentColor,
                                fontWeight = if (hasPayments || isToday) FontWeight.Bold else FontWeight.Normal,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (hasPayments) {
                                Text(
                                    if (dayPayments.size > 1) dayPayments.size.toString() else "●",
                                    color = contentColor,
                                    style = MaterialTheme.typography.labelSmall,
                                    lineHeight = MaterialTheme.typography.labelSmall.lineHeight
                                )
                            } else if (isToday) {
                                Text("•", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                repeat((7 - week.size).coerceAtLeast(0)) {
                    Spacer(modifier = Modifier.weight(1f).height(54.dp))
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer))
            Text(
                "Есть запланированный платёж · нажми на дату для подробностей",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (payments.isEmpty()) {
            Text(
                "На этот месяц платежей пока нет. Добавь регулярный платёж — его дата появится в календаре.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RecurringPaymentDetailsDialog(
    payment: RecurringPayment,
    month: YearMonth,
    paymentIndex: Int,
    paymentCount: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onCancel: () -> Unit,
    onEdit: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    onConfirm: () -> Unit
) {
    val today = LocalDate.now()
    val dueDate = payment.dueDate(month)
    val canConfirm = payment.canConfirm(month, today)
    val status = when {
        payment.isCompleted(month) -> "Оплачен за этот месяц"
        !payment.active -> "Приостановлен"
        dueDate.isAfter(today) -> "Запланирован"
        else -> "Ожидает подтверждения"
    }

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Платёж на ${dueDate.format(DATE_FORMAT)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (paymentCount > 1) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                        TextButton(onClick = onPrevious, enabled = paymentIndex > 0) { Text("‹") }
                        Text("${paymentIndex + 1} из $paymentCount", style = MaterialTheme.typography.labelLarge)
                        TextButton(onClick = onNext, enabled = paymentIndex < paymentCount - 1) { Text("›") }
                    }
                }
                Text(payment.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(recurringPaymentMoney(payment.amount), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Категория: ${payment.category}", style = MaterialTheme.typography.bodyMedium)
                    Text("Периодичность: ежемесячно, ${payment.dayOfMonth}-е число", style = MaterialTheme.typography.bodyMedium)
                    Text("Статус: $status", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onEdit) { Text("Изменить") }
                    TextButton(onClick = onToggle) { Text(if (payment.active) "Пауза" else "Включить") }
                    TextButton(onClick = onDelete) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                        Text("Отмена", maxLines = 1)
                    }
                    Button(
                        onClick = onConfirm,
                        enabled = canConfirm,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            when {
                                payment.isCompleted(month) -> "Оплачен"
                                !payment.active -> "На паузе"
                                dueDate.isAfter(today) -> "Ещё рано"
                                else -> "Подтвердить"
                            },
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecurringPaymentEditorDialog(
    payment: RecurringPayment?,
    categories: List<BudgetCategory>,
    onDismiss: () -> Unit,
    onSave: (RecurringPayment) -> Unit
) {
    var title by remember(payment?.id) { mutableStateOf(payment?.title ?: "") }
    var amount by remember(payment?.id) { mutableStateOf(payment?.amount?.toString() ?: "") }
    var category by remember(payment?.id, categories) {
        mutableStateOf(payment?.category ?: categories.firstOrNull { it.name != BudgetAllocationCalculator.RESERVE_CATEGORY }?.name ?: "Прочее")
    }
    var day by remember(payment?.id) { mutableStateOf(payment?.dayOfMonth?.toString() ?: "1") }
    var categoryExpanded by remember { mutableStateOf(false) }
    val parsedAmount = amount.replace(',', '.').toDoubleOrNull()
    val parsedDay = day.toIntOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (payment == null) "Новый регулярный платёж" else "Изменить платёж") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Название") }, singleLine = true)
                OutlinedTextField(
                    amount,
                    { amount = it },
                    label = { Text("Сумма, ₽") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
                ExposedDropdownMenuBox(expanded = categoryExpanded, onExpandedChange = { categoryExpanded = it }) {
                    OutlinedTextField(
                        category,
                        {},
                        readOnly = true,
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        label = { Text("Категория") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(categoryExpanded) }
                    )
                    ExposedDropdownMenu(expanded = categoryExpanded, onDismissRequest = { categoryExpanded = false }) {
                        categories.filterNot { it.name == BudgetAllocationCalculator.RESERVE_CATEGORY }.forEach { item ->
                            DropdownMenuItem(
                                text = { Text(item.name) },
                                onClick = { category = item.name; categoryExpanded = false }
                            )
                        }
                    }
                }
                OutlinedTextField(
                    day,
                    { day = it.filter(Char::isDigit).take(2) },
                    label = { Text("Число месяца (1–31)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank() && parsedAmount != null && parsedAmount.isFinite() &&
                    parsedAmount > 0 && parsedDay != null && parsedDay in 1..31,
                onClick = {
                    if (parsedAmount != null && parsedDay != null) {
                        val updated = payment?.copy(
                            title = title.trim(),
                            amount = parsedAmount,
                            category = category,
                            dayOfMonth = parsedDay
                        ) ?: RecurringPayment(
                            title = title.trim(),
                            amount = parsedAmount,
                            category = category,
                            dayOfMonth = parsedDay
                        )
                        onSave(updated)
                    }
                }
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}


private fun recurringPaymentMoney(value: Double): String =
    String.format(Locale("ru", "RU"), "%,.0f ₽", value)
