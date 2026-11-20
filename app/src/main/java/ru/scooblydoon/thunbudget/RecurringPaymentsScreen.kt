package ru.scooblydoon.thunbudget

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.YearMonth

@Composable
internal fun RecurringPaymentsScreen(
    payments: List<RecurringPayment>,
    categories: List<BudgetCategory>,
    onAdd: (RecurringPayment) -> Unit,
    onToggle: (RecurringPayment) -> Unit,
    onDelete: (RecurringPayment) -> Unit,
    onConfirm: (RecurringPayment) -> Unit
) {
    var showAdd by remember { mutableStateOf(false) }
    val today = LocalDate.now()
    val month = YearMonth.from(today)
    val pending = payments.filter { it.active && it.dueDate(month) <= today && !it.isCompleted(month) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(16.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Регулярные платежи", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Подтверждай оплату — только тогда она попадёт в историю расходов.", style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = { showAdd = true }) { Text("+ Добавить") }
            }
        }
        if (pending.isNotEmpty()) item { Text("Ожидают подтверждения", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        items(pending, key = { "pending-" + it.id }) { payment ->
            Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(payment.title, fontWeight = FontWeight.SemiBold)
                            Text("${payment.category} · срок ${payment.dueDate(month)}", style = MaterialTheme.typography.bodySmall)
                        }
                        Text("${payment.amount.toLong()} ₽", fontWeight = FontWeight.Bold)
                    }
                    Button(onClick = { onConfirm(payment) }, modifier = Modifier.fillMaxWidth()) { Text("Подтвердить оплату и внести в расходы") }
                }
            }
        }
        item { Text("Все платежи", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        items(payments, key = { it.id }) { payment ->
            Card(shape = RoundedCornerShape(16.dp)) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(payment.title, fontWeight = FontWeight.SemiBold)
                        Text("${payment.amount.toLong()} ₽ · ${payment.category} · ${payment.dayOfMonth}-е число каждого месяца", style = MaterialTheme.typography.bodySmall)
                        Text(if (payment.active) "Активен" else "Приостановлен", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { onToggle(payment) }) { Text(if (payment.active) "Пауза" else "Включить") }
                    TextButton(onClick = { onDelete(payment) }) { Text("Удалить") }
                }
            }
        }
        if (payments.isEmpty()) item { Text("Пока нет регулярных платежей. Добавь, например, аренду, интернет или подписку.") }
    }
    if (showAdd) {
        var title by remember { mutableStateOf("") }
        var amount by remember { mutableStateOf("") }
        var category by remember(categories) { mutableStateOf(categories.firstOrNull()?.name ?: "Прочее") }
        var day by remember { mutableStateOf("1") }
        var categoryExpanded by remember { mutableStateOf(false) }
        val parsedAmount = amount.replace(',', '.').toDoubleOrNull()
        val parsedDay = day.toIntOrNull()
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Новый регулярный платёж") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(title, { title = it }, label = { Text("Название") }, singleLine = true)
                    OutlinedTextField(amount, { amount = it }, label = { Text("Сумма, ₽") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                    ExposedDropdownMenuBox(expanded = categoryExpanded, onExpandedChange = { categoryExpanded = it }) {
                        OutlinedTextField(category, {}, readOnly = true, modifier = Modifier.menuAnchor().fillMaxWidth(), label = { Text("Категория") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(categoryExpanded) })
                        ExposedDropdownMenu(expanded = categoryExpanded, onDismissRequest = { categoryExpanded = false }) {
                            categories.filterNot { it.name == BudgetAllocationCalculator.RESERVE_CATEGORY }.forEach { item ->
                                DropdownMenuItem(text = { Text(item.name) }, onClick = { category = item.name; categoryExpanded = false })
                            }
                        }
                    }
                    OutlinedTextField(day, { day = it.filter(Char::isDigit).take(2) }, label = { Text("Число месяца (1–31)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                }
            },
            confirmButton = {
                TextButton(enabled = title.isNotBlank() && parsedAmount != null && parsedAmount.isFinite() && parsedAmount > 0 && parsedDay != null && parsedDay in 1..31,
                    onClick = { onAdd(RecurringPayment(title = title.trim(), amount = parsedAmount!!, category = category, dayOfMonth = parsedDay!!)); showAdd = false }) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Отмена") } }
        )
    }
}
