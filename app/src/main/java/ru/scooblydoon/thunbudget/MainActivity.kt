package ru.scooblydoon.thunbudget

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private class BudgetStore(context: Context) {
    private val prefs = context.getSharedPreferences("thunbudget", Context.MODE_PRIVATE)
    fun loadIncome() = prefs.getString("income", "100000")!!.toDoubleOrNull() ?: 100000.0
    fun saveIncome(value: Double) { prefs.edit().putString("income", value.toString()).apply() }
    fun loadSavingsGoal() = prefs.getString("savings", "10")!!.toDoubleOrNull() ?: 10.0
    fun saveSavingsGoal(value: Double) { prefs.edit().putString("savings", value.toString()).apply() }
    fun loadCategories(): List<BudgetCategory> {
        val raw = prefs.getString("categories", null) ?: return listOf(
            BudgetCategory("Продукты", 25.0), BudgetCategory("Транспорт", 10.0),
            BudgetCategory("Жильё и ЖКХ", 20.0), BudgetCategory("Кафе и развлечения", 10.0),
            BudgetCategory("Покупки", 10.0), BudgetCategory("Здоровье", 5.0),
            BudgetCategory("Накопления", 10.0), BudgetCategory("Прочее", 10.0)
        )
        return runCatching {
            val a = JSONArray(raw); (0 until a.length()).map { val o=a.getJSONObject(it); BudgetCategory(o.getString("name"),o.getDouble("percent")) }
        }.getOrDefault(emptyList())
    }
    fun saveCategories(items: List<BudgetCategory>) {
        val a=JSONArray(); items.forEach { a.put(JSONObject().put("name",it.name).put("percent",it.percent)) }
        prefs.edit().putString("categories",a.toString()).apply()
    }
    fun loadExpenses(): List<Expense> {
        val raw=prefs.getString("expenses","[]") ?: "[]"
        return runCatching {
            val a=JSONArray(raw); (0 until a.length()).map { val o=a.getJSONObject(it); Expense(o.getLong("id"),o.getString("title"),o.getString("category"),o.getDouble("amount"),o.getString("date")) }.sortedByDescending { it.date }
        }.getOrDefault(emptyList())
    }
    fun saveExpenses(items: List<Expense>) {
        val a=JSONArray(); items.forEach { a.put(JSONObject().put("id",it.id).put("title",it.title).put("category",it.category).put("amount",it.amount).put("date",it.date)) }
        prefs.edit().putString("expenses",a.toString()).apply()
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ThunBudgetApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThunBudgetApp() {
    val context= LocalContext.current
    val store= remember { BudgetStore(context) }
    var income by remember { mutableDoubleStateOf(store.loadIncome()) }
    var savingsGoal by remember { mutableDoubleStateOf(store.loadSavingsGoal()) }
    var categories by remember { mutableStateOf(store.loadCategories()) }
    var expenses by remember { mutableStateOf(store.loadExpenses()) }
    var showAdd by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }
    val today = LocalDate.now()
    val baseMonth = if (today.dayOfMonth <= 8) today.minusMonths(1) else today
    var selectedPeriod by remember { mutableIntStateOf(if (today.dayOfMonth >= 24 || today.dayOfMonth <= 8) 1 else 0) }
    var periodStart by remember { mutableStateOf((if (today.dayOfMonth <= 8) baseMonth.withDayOfMonth(24) else baseMonth.withDayOfMonth(9)).toString()) }
    var periodEnd by remember { mutableStateOf((if (today.dayOfMonth <= 8) today.withDayOfMonth(8) else if (today.dayOfMonth >= 24) today.plusMonths(1).withDayOfMonth(8) else baseMonth.withDayOfMonth(23)).toString()) }
    var selectedExpense by remember { mutableStateOf<Expense?>(null) }
    val formatter= remember { DateTimeFormatter.ofPattern("dd.MM.yyyy") }
    val totalSpent=expenses.filter { it.date >= periodStart && it.date <= periodEnd }.sumOf { it.amount }
    val planned=plannedForPeriod(income, categories.sumOf { it.percent }, periodStart, periodEnd)
    val remaining=planned-totalSpent
    val monthExpenses=expenses.filter { it.date.startsWith(LocalDate.now().toString().take(7)) }
    val monthTotal=monthExpenses.sumOf { it.amount }
    val exportLauncher= rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) { uri ->
        if (uri != null) runCatching { XlsxExporter.write(context, uri, expenses, categories, income, LocalDate.now().year) }
    }
    MaterialTheme(colorScheme=lightColorScheme(primary=Color(0xFF315CBA),secondary=Color(0xFF168A72),background=Color(0xFFF5F6FA),surface=Color.White)) {
        Scaffold(
            topBar={ TopAppBar(title={ Column { Text("ThunBudget",fontWeight=FontWeight.Bold); Text("Личный бюджет",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant) } }, actions={ TextButton(onClick={showSettings=true}) { Text("Настройки") } }) },
            floatingActionButton={ if(tab!=2) FloatingActionButton(onClick={showAdd=true}) { Text("+",style=MaterialTheme.typography.headlineMedium) } },
            bottomBar={ NavigationBar { NavigationBarItem(selected=tab==0,onClick={tab=0},icon={},label={Text("Обзор")}); NavigationBarItem(selected=tab==1,onClick={tab=1},icon={},label={Text("Аналитика")}); NavigationBarItem(selected=tab==2,onClick={tab=2},icon={},label={Text("История")}) } }
        ) { padding ->
            when(tab) {
                0 -> LazyColumn(Modifier.padding(padding).fillMaxSize().padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(top=12.dp,bottom=96.dp)) {
                    item {
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            SegmentedButton(selected=selectedPeriod==0,onClick={ selectedPeriod=0; periodStart=baseMonth.withDayOfMonth(9).toString(); periodEnd=baseMonth.withDayOfMonth(23).toString() },shape=SegmentedButtonDefaults.itemShape(0,2)) { Text("9–23 число") }
                            SegmentedButton(selected=selectedPeriod==1,onClick={ selectedPeriod=1; periodStart=baseMonth.withDayOfMonth(24).toString(); periodEnd=baseMonth.plusMonths(1).withDayOfMonth(8).toString() },shape=SegmentedButtonDefaults.itemShape(1,2)) { Text("24–8 число") }
                        }
                    }
                    item { Text("${LocalDate.parse(periodStart).format(formatter)} — ${LocalDate.parse(periodEnd).format(formatter)}",style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                    item { Card(colors=CardDefaults.cardColors(containerColor=Color(0xFF315CBA)),shape=RoundedCornerShape(22.dp)) { Column(Modifier.fillMaxWidth().padding(20.dp)) {
                        Text("Остаток планового бюджета",color=Color.White.copy(alpha=.8f))
                        Text(money(remaining),style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Bold,color=Color.White)
                        Spacer(Modifier.height(8.dp)); Text("Потрачено: ${money(totalSpent)} из ${money(planned)}",color=Color.White)
                        LinearProgressIndicator(progress={ if(planned>0) (totalSpent/planned).coerceIn(0.0,1.0).toFloat() else 0f },modifier=Modifier.fillMaxWidth().padding(top=12.dp),color=Color.White,trackColor=Color.White.copy(alpha=.25f))
                    } } }
                    item { Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        MetricCard("Доход в месяц",money(income),Modifier.weight(1f))
                        MetricCard("Средний расход/день",money(if(LocalDate.parse(periodEnd).isBefore(LocalDate.now())) 0.0 else remaining.coerceAtLeast(0.0)/(java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(),LocalDate.parse(periodEnd))+1).coerceAtLeast(1)),Modifier.weight(1f))
                    } }
                    item { Text("Бюджет по категориям",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold) }
                    items(categories) { cat ->
                        val spent=expenses.filter { it.category==cat.name && it.date>=periodStart && it.date<=periodEnd }.sumOf { it.amount }
                        val cap=plannedForPeriod(income,cat.percent,periodStart,periodEnd)
                        Card(shape=RoundedCornerShape(16.dp)) { Column(Modifier.fillMaxWidth().padding(14.dp)) {
                            Row(verticalAlignment=Alignment.CenterVertically) { Text(cat.name,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f)); Text("${cat.percent.toInt()}% · ${money(cap)}",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                            LinearProgressIndicator(progress={ if(cap>0) (spent/cap).coerceIn(0.0,1.0).toFloat() else 0f },modifier=Modifier.fillMaxWidth().padding(vertical=8.dp),color=if(spent>cap) Color(0xFFC74444) else Color(0xFF168A72))
                            Row { Text("Факт: ${money(spent)}",style=MaterialTheme.typography.bodySmall); Spacer(Modifier.weight(1f)); Text(if(spent>cap) "Превышение: ${money(spent-cap)}" else "Осталось: ${money(cap-spent)}",style=MaterialTheme.typography.bodySmall,color=if(spent>cap) Color(0xFFC74444) else Color(0xFF168A72)) }
                        } }
                    }
                }
                1 -> LazyColumn(Modifier.padding(padding).fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    item { Text("Аналитика",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold) }
                    item { MetricCard("Расходы за календарный месяц",money(monthTotal),Modifier.fillMaxWidth()) }
                    item { MetricCard("Расходы с начала года",money(expenses.filter { it.date.startsWith(LocalDate.now().year.toString()) }.sumOf { it.amount }),Modifier.fillMaxWidth()) }
                    item { Text("Статистика за год по категориям",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold) }
                    items(categories) { cat ->
                        val annualSpent=expenses.filter { it.category==cat.name && it.date.startsWith(LocalDate.now().year.toString()) }.sumOf { it.amount }
                        val annualPlan=income*12*cat.percent/100.0
                        Card { Column(Modifier.padding(14.dp)) {
                            Row { Text(cat.name,modifier=Modifier.weight(1f)); Text(money(annualSpent),fontWeight=FontWeight.SemiBold) }
                            Text("План: ${money(annualPlan)} · " + if(annualSpent>annualPlan) "Превышение: ${money(annualSpent-annualPlan)}" else "Не выбрано: ${money(annualPlan-annualSpent)}",style=MaterialTheme.typography.bodySmall,color=if(annualSpent>annualPlan) Color(0xFFC74444) else Color(0xFF168A72))
                        } }
                    }
                    item { Text("Рекомендации по перераспределению",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold) }
                    item {
                        val periodFacts=categories.associate { c -> c.name to expenses.filter { it.category==c.name && it.date>=periodStart && it.date<=periodEnd }.sumOf { it.amount } }
                        val deficits=categories.map { c -> c.name to (periodFacts[c.name]!!-plannedForPeriod(income,c.percent,periodStart,periodEnd)) }.filter { it.second>0 }.sortedByDescending { it.second }
                        val donors=categories.map { c -> c.name to (plannedForPeriod(income,c.percent,periodStart,periodEnd)-periodFacts[c.name]!!) }.filter { it.second>0 }.sortedByDescending { it.second }.toMutableList()
                        if(deficits.isEmpty()) Text("Превышений за выбранный период нет.")
                        else if(donors.isEmpty()) Text("Свободного остатка в других категориях нет. Чтобы не выйти за пределы дохода, сократите расходы или уменьшите лимит категории с превышением.")
                        else deficits.forEach { deficit ->
                            var left=deficit.second
                            donors.toList().forEach { donor ->
                                if(left>0 && donor.first!=deficit.first && donor.second>0) {
                                    val transfer=minOf(left,donor.second)
                                    if(transfer>0) {
                                        Text("Для «${deficit.first}» не хватает ${money(left)}. Можно перенести ${money(transfer)} из «${donor.first}» (свободно ${money(donor.second)}).",style=MaterialTheme.typography.bodySmall)
                                        left-=transfer
                                        val idx=donors.indexOfFirst { it.first==donor.first }
                                        if(idx>=0) donors[idx]=donor.first to (donor.second-transfer)
                                    }
                                }
                            }
                            if(left>0) Text("Непокрытое превышение по «${deficit.first}»: ${money(left)}.",style=MaterialTheme.typography.bodySmall,color=Color(0xFFC74444))
                        }
                    }
                    item { Text("Структура расходов",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold) }
                    items(categories) { cat ->
                        val amount=monthExpenses.filter { it.category==cat.name }.sumOf { it.amount }
                        if(amount>0) Card { Column(Modifier.padding(14.dp)) { Row { Text(cat.name,modifier=Modifier.weight(1f)); Text(money(amount),fontWeight=FontWeight.SemiBold) }; LinearProgressIndicator(progress={if(monthTotal>0)(amount/monthTotal).toFloat() else 0f},modifier=Modifier.fillMaxWidth().padding(top=8.dp)) } }
                    }
                    item { Text("Цель накоплений: ${savingsGoal.toInt()}% от дохода — ${money(income*savingsGoal/100)}",style=MaterialTheme.typography.bodyLarge) }
                    item { OutlinedButton(onClick={exportLauncher.launch("thunbudget-${LocalDate.now()}.xlsx")},modifier=Modifier.fillMaxWidth()) { Text("Экспорт полного отчёта в Excel (.xlsx)") } }
                }
                else -> LazyColumn(Modifier.padding(padding).fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    item { Text("История операций",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold) }
                    if(expenses.isEmpty()) item { Text("Пока нет расходов. Нажми +, чтобы добавить первую операцию.",color=MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(expenses) { e -> Card(onClick={ selectedExpense=e }) { Row(Modifier.fillMaxWidth().padding(14.dp),verticalAlignment=Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(e.title,fontWeight=FontWeight.SemiBold); Text("${e.category} · ${e.date}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }; Text("−${money(e.amount)}",fontWeight=FontWeight.Bold,color=Color(0xFFC74444)) } } }
                }
            }
        }
    }
    if(showAdd) AddExpenseDialog(categories,onDismiss={showAdd=false},onSave={title,category,amount,date->
        val updated=(listOf(Expense(System.currentTimeMillis(),title,category,amount,date))+expenses).sortedByDescending { it.date }
        val spentAfter=updated.filter { it.category==category && it.date>=periodStart && it.date<=periodEnd }.sumOf { it.amount }
        val cap=categories.firstOrNull { it.name==category }?.let { plannedForPeriod(income,it.percent,periodStart,periodEnd) } ?: 0.0
        expenses=updated; store.saveExpenses(expenses); showAdd=false
        if(spentAfter>cap) OverspendNotifier.notify(context, "Превышение бюджета", "Категория «$category»: превышение ${money(spentAfter-cap)} за выбранный период.")
    })
    selectedExpense?.let { selected ->
        ExpenseEditDialog(selected,categories,onDismiss={selectedExpense=null},onSave={edited->
            expenses=expenses.map { if(it.id==selected.id) edited else it }.sortedByDescending { it.date }
            store.saveExpenses(expenses); selectedExpense=null
        },onDelete={
            expenses=expenses.filterNot { it.id==selected.id }; store.saveExpenses(expenses); selectedExpense=null
        })
    }
    if(showSettings) SettingsDialog(income,savingsGoal,categories,periodStart,periodEnd,onDismiss={showSettings=false},onSave={newIncome,newSavings,newCats,start,end->
        income=newIncome; savingsGoal=newSavings; categories=newCats; periodStart=start; periodEnd=end
        store.saveIncome(income); store.saveSavingsGoal(savingsGoal); store.saveCategories(categories); showSettings=false
    })
}

@Composable
private fun MetricCard(label:String,value:String,modifier:Modifier=Modifier) {
    Card(modifier=modifier,shape=RoundedCornerShape(16.dp)) { Column(Modifier.padding(14.dp)) { Text(label,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.height(6.dp)); Text(value,fontWeight=FontWeight.Bold,style=MaterialTheme.typography.titleLarge) } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddExpenseDialog(categories:List<BudgetCategory>,onDismiss:()->Unit,onSave:(String,String,Double,String)->Unit) {
    var title by remember { mutableStateOf("") }; var amount by remember { mutableStateOf("") }; var category by remember { mutableStateOf(categories.firstOrNull()?.name ?: "Прочее") }; var date by remember { mutableStateOf(LocalDate.now().toString()) }; var expanded by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest=onDismiss,title={Text("Новый расход")},text={
        Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(title,{title=it},label={Text("Описание")},singleLine=true)
            OutlinedTextField(amount,{amount=it},label={Text("Сумма, ₽")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),singleLine=true)
            ExposedDropdownMenuBox(expanded=expanded,onExpandedChange={expanded=it}) {
                OutlinedTextField(value=category,onValueChange={},modifier=Modifier.menuAnchor(),readOnly=true,label={Text("Категория")},trailingIcon={ExposedDropdownMenuDefaults.TrailingIcon(expanded)})
                ExposedDropdownMenu(expanded=expanded,onDismissRequest={expanded=false}) { categories.forEach { DropdownMenuItem(text={Text(it.name)},onClick={category=it.name;expanded=false}) } }
            }
            OutlinedTextField(date,{date=it},label={Text("Дата (ГГГГ-ММ-ДД)")},singleLine=true)
        }
    },confirmButton={ TextButton(onClick={ val v=amount.replace(',','.').toDoubleOrNull(); if(title.isNotBlank() && v!=null && v>0 && runCatching{LocalDate.parse(date)}.isSuccess) onSave(title,category,v,date) },enabled=title.isNotBlank() && (amount.replace(',','.').toDoubleOrNull() ?: 0.0)>0) { Text("Сохранить") } },dismissButton={TextButton(onClick=onDismiss){Text("Отмена")}})
}

@Composable
private fun SettingsDialog(income:Double,savings:Double,categories:List<BudgetCategory>,start:String,end:String,onDismiss:()->Unit,onSave:(Double,Double,List<BudgetCategory>,String,String)->Unit) {
    var inc by remember { mutableStateOf(income.toString()) }; var sav by remember { mutableStateOf(savings.toString()) }; var cats by remember { mutableStateOf(categories) }; var from by remember { mutableStateOf(start) }; var to by remember { mutableStateOf(end) }
    AlertDialog(onDismissRequest=onDismiss,title={Text("Настройки бюджета")},text={
        LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.heightIn(max=500.dp)) {
            item { OutlinedTextField(inc,{inc=it},label={Text("Месячный доход, ₽")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),singleLine=true) }
            item { OutlinedTextField(sav,{sav=it},label={Text("Цель накоплений, %")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),singleLine=true) }
            item { Text("Период планирования",fontWeight=FontWeight.Bold) }
            item { OutlinedTextField(from,{from=it},label={Text("Начало (ГГГГ-ММ-ДД)")},singleLine=true) }
            item { OutlinedTextField(to,{to=it},label={Text("Окончание (ГГГГ-ММ-ДД)")},singleLine=true) }
            item { Text("Доли бюджета по категориям, %",fontWeight=FontWeight.Bold) }
            items(cats.size) { index ->
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text(cats[index].name,modifier=Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)
                    OutlinedTextField(cats[index].percent.toString(),{v-> cats=cats.toMutableList().also { it[index]=it[index].copy(percent=v.replace(',','.').toDoubleOrNull() ?: 0.0) } },modifier=Modifier.width(90.dp),singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal))
                }
            }
            item { Text("Сумма долей: ${cats.sumOf{it.percent}.toInt()}%",style=MaterialTheme.typography.labelMedium) }
        }
    },confirmButton={TextButton(onClick={val i=inc.replace(',','.').toDoubleOrNull();val s=sav.replace(',','.').toDoubleOrNull();if(i!=null&&i>0&&s!=null&&s>=0&&s<=100&&runCatching{LocalDate.parse(from);LocalDate.parse(to)}.isSuccess&&!LocalDate.parse(to).isBefore(LocalDate.parse(from)))onSave(i,s,cats,from,to)},enabled=(inc.replace(',','.').toDoubleOrNull()?:0.0)>0){Text("Сохранить")}},dismissButton={TextButton(onClick=onDismiss){Text("Отмена")}})
}

private fun plannedForPeriod(income: Double, percent: Double, start: String, end: String): Double {
    val from = LocalDate.parse(start)
    val to = LocalDate.parse(end)
    if (to.isBefore(from)) return 0.0
    var day = from
    var total = 0.0
    while (!day.isAfter(to)) {
        total += income * percent / 100.0 / day.lengthOfMonth()
        day = day.plusDays(1)
    }
    return total
}

private fun money(value:Double):String = String.format(Locale("ru","RU"),"%,.0f ₽",value)
