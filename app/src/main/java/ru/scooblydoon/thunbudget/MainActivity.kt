package ru.scooblydoon.thunbudget

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.drawscope.Stroke
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
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private data class PeriodIncome(val start: String, val end: String, val amount: Double)

private class BudgetStore(context: Context) {
    private val prefs = context.getSharedPreferences("thunbudget", Context.MODE_PRIVATE)
    fun loadPeriodIncomes(): List<PeriodIncome> = runCatching {
        val a = JSONArray(prefs.getString("period_incomes", "[]") ?: "[]")
        (0 until a.length()).map { val o=a.getJSONObject(it); PeriodIncome(o.getString("start"),o.getString("end"),o.getDouble("amount")) }
    }.getOrDefault(emptyList())
    fun savePeriodIncomes(items: List<PeriodIncome>) {
        val a=JSONArray(); items.forEach { a.put(JSONObject().put("start",it.start).put("end",it.end).put("amount",it.amount)) }
        prefs.edit().putString("period_incomes",a.toString()).apply()
    }
    fun loadIncome() = prefs.getString("income", "100000")!!.toDoubleOrNull() ?: 100000.0
    fun saveIncome(value: Double) { prefs.edit().putString("income", value.toString()).apply() }
    fun loadSavingsGoal() = prefs.getString("savings", "10")!!.toDoubleOrNull() ?: 10.0
    fun saveSavingsGoal(value: Double) { prefs.edit().putString("savings", value.toString()).apply() }
    fun loadPeriodStart() = prefs.getString("period_start", null)
    fun loadPeriodEnd() = prefs.getString("period_end", null)
    fun savePeriod(start:String,end:String) { prefs.edit().putString("period_start",start).putString("period_end",end).apply() }
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
    var periodIncomes by remember { mutableStateOf(store.loadPeriodIncomes()) }
    var showAdd by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showPeriodEditor by remember { mutableStateOf(false) }
    var showPeriodIncomeEditor by remember { mutableStateOf(false) }
    var showCategoryManager by remember { mutableStateOf(false) }
    var editingCategory by remember { mutableStateOf<BudgetCategory?>(null) }
    var tab by remember { mutableIntStateOf(0) }
    var showMonthCategories by remember { mutableStateOf(true) }
    var showPeriodCategories by remember { mutableStateOf(true) }
    var showYearCategories by remember { mutableStateOf(true) }
    var showAdvice by remember { mutableStateOf(true) }
    var showSavingsInfo by remember { mutableStateOf(true) }
    var showPlanChart by remember { mutableStateOf(true) }
    var showPieChart by remember { mutableStateOf(true) }
    val today = LocalDate.now()
    val baseMonth = if (today.dayOfMonth <= 8) today.minusMonths(1) else today
    var selectedPeriod by remember { mutableIntStateOf(if (store.loadPeriodStart()!=null) -1 else if (today.dayOfMonth >= 24 || today.dayOfMonth <= 8) 1 else 0) }
    var periodStart by remember { mutableStateOf(store.loadPeriodStart() ?: (if (today.dayOfMonth <= 8 || today.dayOfMonth >= 24) baseMonth.withDayOfMonth(24) else baseMonth.withDayOfMonth(9)).toString()) }
    var periodEnd by remember { mutableStateOf(store.loadPeriodEnd() ?: (if (today.dayOfMonth <= 8) today.withDayOfMonth(8) else if (today.dayOfMonth >= 24) today.plusMonths(1).withDayOfMonth(8) else baseMonth.withDayOfMonth(23)).toString()) }
    var selectedExpense by remember { mutableStateOf<Expense?>(null) }
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    var addFromCategory by remember { mutableStateOf<String?>(null) }
    var historySort by remember { mutableStateOf("Дата") }
    var historyAscending by remember { mutableStateOf(false) }
    var amountLimit by remember { mutableFloatStateOf(100000f) }
    var analyticsMonth by remember { mutableStateOf(YearMonth.now()) }
    var analyticsPeriodMonth by remember { mutableStateOf(if (today.dayOfMonth <= 8) YearMonth.now().minusMonths(1) else YearMonth.now()) }
    var analyticsPeriodType by remember { mutableIntStateOf(if (today.dayOfMonth >= 24 || today.dayOfMonth <= 8) 1 else 0) }
    val formatter= remember { DateTimeFormatter.ofPattern("dd.MM.yyyy") }
    val periodIncome = periodIncomes.firstOrNull { it.start==periodStart && it.end==periodEnd }?.amount ?: 0.0
    val overviewMonth = YearMonth.from(LocalDate.parse(periodEnd))
    val monthPeriodIncome = periodIncomes.filter { runCatching { YearMonth.from(LocalDate.parse(it.end))==overviewMonth }.getOrDefault(false) }.sumOf { it.amount }
    val displayedMonthlyIncome = if(monthPeriodIncome>0.0) monthPeriodIncome else income
    val analyticsMonthIncome = periodIncomes.filter { runCatching { YearMonth.from(LocalDate.parse(it.end))==analyticsMonth }.getOrDefault(false) }.sumOf { it.amount }.let { if(it>0.0) it else income }
    val totalSpent=expenses.filter { it.date >= periodStart && it.date <= periodEnd }.sumOf { it.amount }
    val planned=if(periodIncome>0.0) periodIncome*categories.sumOf { it.percent }/100.0 else plannedForPeriod(income, categories.sumOf { it.percent }, periodStart, periodEnd)
    val remaining=planned-totalSpent
    val monthExpenses=expenses.filter { it.date.startsWith(LocalDate.now().toString().take(7)) }
    val monthTotal=monthExpenses.sumOf { it.amount }
    val exportLauncher= rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) { uri ->
        if (uri != null) runCatching { XlsxExporter.write(context, uri, expenses, categories, income, LocalDate.now().year) }
    }
    MaterialTheme(colorScheme=lightColorScheme(primary=Color(0xFF315CBA),secondary=Color(0xFF168A72),background=Color(0xFFF5F6FA),surface=Color.White)) {
        Scaffold(
            topBar={ TopAppBar(title={ Column { Text("ThunBudget",fontWeight=FontWeight.Bold); Text("Личный бюджет",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant) } }, actions={ TextButton(onClick={showSettings=true}) { Text("Настройки") } }) },
            floatingActionButton={ if(tab!=3) FloatingActionButton(onClick={showAdd=true}) { Text("+",style=MaterialTheme.typography.headlineMedium) } },
            bottomBar={ NavigationBar { NavigationBarItem(selected=tab==0,onClick={tab=0},icon={},label={Text("Обзор")}); NavigationBarItem(selected=tab==1,onClick={tab=1},icon={},label={Text("Аналитика")}); NavigationBarItem(selected=tab==2,onClick={tab=2},icon={},label={Text("Графики")}); NavigationBarItem(selected=tab==3,onClick={tab=3},icon={},label={Text("История")}) } }
        ) { padding ->
            when(tab) {
                0 -> LazyColumn(Modifier.padding(padding).fillMaxSize().padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(top=12.dp,bottom=96.dp)) {
                    item {
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            SegmentedButton(selected=selectedPeriod==0,onClick={ selectedPeriod=0; periodStart=baseMonth.withDayOfMonth(9).toString(); periodEnd=baseMonth.withDayOfMonth(23).toString(); store.savePeriod(periodStart,periodEnd) },shape=SegmentedButtonDefaults.itemShape(0,2)) { Text("9–23 число") }
                            SegmentedButton(selected=selectedPeriod==1,onClick={ selectedPeriod=1; periodStart=baseMonth.withDayOfMonth(24).toString(); periodEnd=baseMonth.plusMonths(1).withDayOfMonth(8).toString(); store.savePeriod(periodStart,periodEnd) },shape=SegmentedButtonDefaults.itemShape(1,2)) { Text("24–8 число") }
                        }
                        TextButton(onClick={showPeriodEditor=true},modifier=Modifier.fillMaxWidth()) { Text("Изменить отчётный период вручную") }
                    }
                    item { Text("${LocalDate.parse(periodStart).format(formatter)} — ${LocalDate.parse(periodEnd).format(formatter)}",style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                    item { Card(colors=CardDefaults.cardColors(containerColor=Color(0xFF315CBA)),shape=RoundedCornerShape(22.dp)) { Column(Modifier.fillMaxWidth().padding(20.dp)) {
                        Text("Остаток планового бюджета",color=Color.White.copy(alpha=.8f))
                        Text(money(remaining),style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Bold,color=Color.White)
                        Spacer(Modifier.height(8.dp)); Text("Потрачено: ${money(totalSpent)} из ${money(planned)}",color=Color.White)
                        LinearProgressIndicator(progress={ if(planned>0) (totalSpent/planned).coerceIn(0.0,1.0).toFloat() else 0f },modifier=Modifier.fillMaxWidth().padding(top=12.dp),color=Color.White,trackColor=Color.White.copy(alpha=.25f))
                    } } }
                    item { Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        Card(onClick={showPeriodIncomeEditor=true},modifier=Modifier.weight(1f),shape=RoundedCornerShape(16.dp)) { Column(Modifier.fillMaxWidth().padding(14.dp)) { Text("Доход за отчётный период",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.height(6.dp)); Text(money(periodIncome),fontWeight=FontWeight.Bold,style=MaterialTheme.typography.titleLarge); Text(if(periodIncome>0) "Нажмите, чтобы изменить" else "Нажмите, чтобы внести",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary) } }
                        MetricCard("Доход в месяц",money(displayedMonthlyIncome),Modifier.weight(1f))
                        MetricCard("Средний расход/день",money(if(LocalDate.parse(periodEnd).isBefore(LocalDate.now())) 0.0 else remaining.coerceAtLeast(0.0)/(java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(),LocalDate.parse(periodEnd))+1).coerceAtLeast(1)),Modifier.weight(1f))
                    } }
                    item {
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Text("Бюджет по категориям",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f))
                            IconButton(onClick={showCategoryManager=true}) { Text("+",style=MaterialTheme.typography.headlineMedium,color=MaterialTheme.colorScheme.primary) }
                        }
                    }
                    items(categories) { cat ->
                        val spent=expenses.filter { it.category==cat.name && it.date>=periodStart && it.date<=periodEnd }.sumOf { it.amount }
                        val cap=if(periodIncome>0.0) periodIncome*cat.percent/100.0 else plannedForPeriod(income,cat.percent,periodStart,periodEnd)
                        Card(onClick={selectedCategory=cat.name},shape=RoundedCornerShape(16.dp)) { Column(Modifier.fillMaxWidth().padding(14.dp)) {
                            Row(verticalAlignment=Alignment.CenterVertically) {
                                Text(cat.name,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f))
                                IconButton(onClick={editingCategory=cat}) { Text("✎",color=MaterialTheme.colorScheme.primary) }
                                Text("${cat.percent.toInt()}% · ${money(cap)}",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            LinearProgressIndicator(progress={ if(cap>0) (spent/cap).coerceIn(0.0,1.0).toFloat() else 0f },modifier=Modifier.fillMaxWidth().padding(vertical=8.dp),color=if(spent>cap) Color(0xFFC74444) else Color(0xFF168A72))
                            Row { Text("Факт: ${money(spent)}",style=MaterialTheme.typography.bodySmall); Spacer(Modifier.weight(1f)); Text(if(spent>cap) "Превышение: ${money(spent-cap)}" else "Осталось: ${money(cap-spent)}",style=MaterialTheme.typography.bodySmall,color=if(spent>cap) Color(0xFFC74444) else Color(0xFF168A72)) }
                        } }
                    }
                }
                1 -> LazyColumn(Modifier.padding(padding).fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(bottom=24.dp)) {
                    item { Text("Аналитика",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold) }
                    item { Text("Текущий отчётный период",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold) }
                    item {
                        val currentStart = LocalDate.parse(periodStart)
                        val currentEnd = LocalDate.parse(periodEnd)
                        val total = expenses.filter { it.date >= periodStart && it.date <= periodEnd }.sumOf { it.amount }
                        MetricCard("${currentStart.format(formatter)} — ${currentEnd.format(formatter)} · расходы",money(total),Modifier.fillMaxWidth())
                    }
                    item { Text("Расходы за отчётный месяц",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold) }
                    item {
                        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick={analyticsMonth=analyticsMonth.minusMonths(1)}) { Text("‹") }
                            Text(analyticsMonth.format(DateTimeFormatter.ofPattern("LLLL yyyy",Locale("ru","RU")).withLocale(Locale("ru","RU"))),modifier=Modifier.weight(1f),fontWeight=FontWeight.SemiBold)
                            TextButton(onClick={if(analyticsMonth<YearMonth.now()) analyticsMonth=analyticsMonth.plusMonths(1)}) { Text("›") }
                        }
                        val from=analyticsMonth.atDay(1).toString()
                        val to=minOf(analyticsMonth.atEndOfMonth(),if(analyticsMonth==YearMonth.now()) today else analyticsMonth.atEndOfMonth()).toString()
                        val monthTotal=expenses.filter { it.date>=from && it.date<=to }.sumOf { it.amount }
                        MetricCard("Итого за месяц",money(monthTotal),Modifier.fillMaxWidth())
                    }
                    item { TextButton(onClick={showMonthCategories=!showMonthCategories},modifier=Modifier.fillMaxWidth()) { Text(if(showMonthCategories) "Скрыть категории ▲" else "Показать категории ▼") } }
                    if(showMonthCategories) items(categories,key={ "month-"+it.name }) { cat ->
                        val from=analyticsMonth.atDay(1).toString()
                        val to=minOf(analyticsMonth.atEndOfMonth(),if(analyticsMonth==YearMonth.now()) today else analyticsMonth.atEndOfMonth()).toString()
                        val fact=expenses.filter { it.category==cat.name && it.date>=from && it.date<=to }.sumOf { it.amount }
                        val plan=plannedForPeriod(analyticsMonthIncome,cat.percent,from,to)
                        Card { Column(Modifier.padding(14.dp)) {
                            Row { Text(cat.name,modifier=Modifier.weight(1f),fontWeight=FontWeight.SemiBold); Text(money(fact),fontWeight=FontWeight.Bold) }
                            LinearProgressIndicator(progress={if(plan>0)(fact/plan).coerceIn(0.0,1.0).toFloat() else 0f},modifier=Modifier.fillMaxWidth().padding(vertical=8.dp),color=if(fact>plan) Color(0xFFC74444) else Color(0xFF168A72))
                            Text("План: ${money(plan)} · "+if(fact>plan) "Превышение: ${money(fact-plan)}" else "Остаток: ${money(plan-fact)}",style=MaterialTheme.typography.bodySmall,color=if(fact>plan) Color(0xFFC74444) else Color(0xFF168A72))
                        } }
                    }
                    item { Divider(); Text("Расходы за выбранный отчётный период",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold) }
                    item {
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected=analyticsPeriodType==0,onClick={analyticsPeriodType=0},label={Text("9–23")})
                            FilterChip(selected=analyticsPeriodType==1,onClick={analyticsPeriodType=1},label={Text("24–8")})
                        }
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            TextButton(onClick={analyticsPeriodMonth=analyticsPeriodMonth.minusMonths(1)}) { Text("‹") }
                            Text(analyticsPeriodMonth.format(DateTimeFormatter.ofPattern("LLLL yyyy",Locale("ru","RU"))),modifier=Modifier.weight(1f),fontWeight=FontWeight.SemiBold)
                            TextButton(onClick={if(analyticsPeriodMonth<YearMonth.now()) analyticsPeriodMonth=analyticsPeriodMonth.plusMonths(1)}) { Text("›") }
                        }
                    }
                    item {
                        val pStart=if(analyticsPeriodType==0) analyticsPeriodMonth.atDay(9) else analyticsPeriodMonth.atDay(24)
                        val pEnd=if(analyticsPeriodType==0) analyticsPeriodMonth.atDay(23) else analyticsPeriodMonth.plusMonths(1).atDay(8)
                        val visibleEnd=minOf(pEnd,today)
                        val periodTotal=if(visibleEnd.isBefore(pStart)) 0.0 else expenses.filter { it.date>=pStart.toString() && it.date<=visibleEnd.toString() }.sumOf { it.amount }
                        MetricCard("${pStart.format(formatter)} — ${pEnd.format(formatter)} · потрачено",money(periodTotal),Modifier.fillMaxWidth())
                    }
                    item { TextButton(onClick={showPeriodCategories=!showPeriodCategories},modifier=Modifier.fillMaxWidth()) { Text(if(showPeriodCategories) "Скрыть категории ▲" else "Показать категории ▼") } }
                    if(showPeriodCategories) items(categories,key={ "period-"+it.name }) { cat ->
                        val pStart=if(analyticsPeriodType==0) analyticsPeriodMonth.atDay(9) else analyticsPeriodMonth.atDay(24)
                        val pEnd=if(analyticsPeriodType==0) analyticsPeriodMonth.atDay(23) else analyticsPeriodMonth.plusMonths(1).atDay(8)
                        val visibleEnd=minOf(pEnd,today)
                        val fact=if(visibleEnd.isBefore(pStart)) 0.0 else expenses.filter { it.category==cat.name && it.date>=pStart.toString() && it.date<=visibleEnd.toString() }.sumOf { it.amount }
                        val pIncome=periodIncomes.firstOrNull { it.start==pStart.toString() && it.end==pEnd.toString() }?.amount ?: 0.0
                        val plan=if(pIncome>0.0) pIncome*cat.percent/100.0 else plannedForPeriod(analyticsMonthIncome,cat.percent,pStart.toString(),pEnd.toString())
                        Card { Column(Modifier.padding(14.dp)) {
                            Row { Text(cat.name,modifier=Modifier.weight(1f),fontWeight=FontWeight.SemiBold); Text(money(fact),fontWeight=FontWeight.Bold) }
                            Text("План: ${money(plan)} · "+if(fact>plan) "Превышение: ${money(fact-plan)}" else "Остаток бюджета: ${money(plan-fact)}",style=MaterialTheme.typography.bodySmall,color=if(fact>plan) Color(0xFFC74444) else Color(0xFF168A72))
                        } }
                    }
                    item { Divider(); Text("Расходы за год",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold) }
                    item { MetricCard("Всего с начала года",money(expenses.filter { it.date>=LocalDate.of(today.year,1,1).toString() && it.date<=today.toString() }.sumOf { it.amount }),Modifier.fillMaxWidth()) }
                    item { TextButton(onClick={showYearCategories=!showYearCategories},modifier=Modifier.fillMaxWidth()) { Text(if(showYearCategories) "Скрыть категории ▲" else "Показать категории ▼") } }
                    if(showYearCategories) items(categories,key={ "year-"+it.name }) { cat ->
                        val yearStart=LocalDate.of(today.year,1,1).toString()
                        val fact=expenses.filter { it.category==cat.name && it.date>=yearStart && it.date<=today.toString() }.sumOf { it.amount }
                        val plan=plannedForPeriod(income,cat.percent,yearStart,today.toString())
                        Card { Column(Modifier.padding(14.dp)) {
                            Row { Text(cat.name,modifier=Modifier.weight(1f),fontWeight=FontWeight.SemiBold); Text(money(fact),fontWeight=FontWeight.Bold) }
                            Text("План: ${money(plan)} · "+if(fact>plan) "Превышение: ${money(fact-plan)}" else "Не выбрано: ${money(plan-fact)}",style=MaterialTheme.typography.bodySmall,color=if(fact>plan) Color(0xFFC74444) else Color(0xFF168A72))
                        } }
                    }
                    item { Row(verticalAlignment=Alignment.CenterVertically) { Text("Рекомендации по перераспределению",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f)); TextButton(onClick={showAdvice=!showAdvice}) { Text(if(showAdvice) "Скрыть" else "Показать") } } }
                    if(showAdvice) item {
                        val periodFacts=categories.associate { cat -> cat.name to expenses.filter { it.category==cat.name && it.date>=periodStart && it.date<=periodEnd }.sumOf { it.amount } }
                        val deficits=categories.map { cat -> cat.name to (periodFacts[cat.name]!!-plannedForPeriod(income,cat.percent,periodStart,periodEnd)) }.filter { it.second>0 }.sortedByDescending { it.second }
                        val donors=categories.map { cat -> cat.name to (plannedForPeriod(income,cat.percent,periodStart,periodEnd)-periodFacts[cat.name]!!) }.filter { it.second>0 && it.first!="Накопления" }.sortedByDescending { it.second }.toMutableList()
                        if(deficits.isEmpty()) Text("Превышений за текущий период нет.")
                        else deficits.forEach { deficit ->
                            var left=deficit.second
                            donors.toList().forEach { donor -> if(left>0 && donor.first!=deficit.first && donor.second>0) {
                                val transfer=minOf(left,donor.second)
                                Text("Для «${deficit.first}» можно перенести ${money(transfer)} из «${donor.first}».",style=MaterialTheme.typography.bodySmall)
                                left-=transfer
                                val idx=donors.indexOfFirst { it.first==donor.first }; if(idx>=0) donors[idx]=donor.first to (donor.second-transfer)
                            } }
                            if(left>0) Text("Непокрытое превышение «${deficit.first}»: ${money(left)}.",color=Color(0xFFC74444),style=MaterialTheme.typography.bodySmall)
                        }
                    }
                    item { Row(verticalAlignment=Alignment.CenterVertically) { if(showSavingsInfo) Text("Цель накоплений: ${savingsGoal.toInt()}% от дохода — ${money(displayedMonthlyIncome*savingsGoal/100)}",style=MaterialTheme.typography.bodyLarge,modifier=Modifier.weight(1f)); TextButton(onClick={showSavingsInfo=!showSavingsInfo}) { Text(if(showSavingsInfo) "Скрыть" else "Показать") } } }
                    item { OutlinedButton(onClick={exportLauncher.launch("thunbudget-${LocalDate.now()}.xlsx")},modifier=Modifier.fillMaxWidth()) { Text("Экспорт полного отчёта в Excel (.xlsx)") } }
                }
                2 -> ChartsScreen(
                    expenses=expenses,categories=categories,month=analyticsMonth,onMonthChange={analyticsMonth=it},
                    showPlan=showPlanChart,onShowPlanChange={showPlanChart=it},showPie=showPieChart,onShowPieChange={showPieChart=it},
                    fallbackIncome=income,periodIncomes=periodIncomes
                )
                else -> LazyColumn(Modifier.padding(padding).fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    item { Text("История операций",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold) }
                    item {
                        var expandedSort by remember { mutableStateOf(false) }
                        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            ExposedDropdownMenuBox(expanded=expandedSort,onExpandedChange={expandedSort=it}) {
                                OutlinedTextField(value="Сортировка: $historySort",onValueChange={},readOnly=true,modifier=Modifier.menuAnchor().fillMaxWidth(),trailingIcon={ExposedDropdownMenuDefaults.TrailingIcon(expandedSort)})
                                ExposedDropdownMenu(expanded=expandedSort,onDismissRequest={expandedSort=false}) {
                                    listOf("Название","Дата","Категория","Сумма").forEach { option -> DropdownMenuItem(text={Text(option)},onClick={historySort=option;expandedSort=false}) }
                                }
                            }
                            Row(verticalAlignment=Alignment.CenterVertically) {
                                Text("Порядок",modifier=Modifier.weight(1f))
                                TextButton(onClick={historyAscending=!historyAscending}) { Text(if(historyAscending) "По возрастанию ↑" else "По убыванию ↓") }
                            }
                            Text("Показывать операции до ${money(amountLimit.toDouble())}",style=MaterialTheme.typography.bodySmall)
                            Slider(value=amountLimit,onValueChange={amountLimit=it},valueRange=0f..(expenses.maxOfOrNull { it.amount }?.toFloat()?.coerceAtLeast(100000f) ?: 100000f))
                        }
                    }
                    if(expenses.isEmpty()) item { Text("Пока нет расходов. Нажми +, чтобы добавить первую операцию.",color=MaterialTheme.colorScheme.onSurfaceVariant) }
                    val filteredExpenses=expenses.filter { it.amount<=amountLimit.toDouble() }
                    val sortedExpenses=when(historySort) {
                        "Название" -> if(historyAscending) filteredExpenses.sortedBy { it.title.lowercase() } else filteredExpenses.sortedByDescending { it.title.lowercase() }
                        "Категория" -> if(historyAscending) filteredExpenses.sortedBy { it.category.lowercase() } else filteredExpenses.sortedByDescending { it.category.lowercase() }
                        "Сумма" -> if(historyAscending) filteredExpenses.sortedBy { it.amount } else filteredExpenses.sortedByDescending { it.amount }
                        else -> if(historyAscending) filteredExpenses.sortedBy { it.date } else filteredExpenses.sortedByDescending { it.date }
                    }
                    items(sortedExpenses,key={it.id}) { e -> Card(onClick={ selectedExpense=e }) { Row(Modifier.fillMaxWidth().padding(14.dp),verticalAlignment=Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(e.title,fontWeight=FontWeight.SemiBold); Text("${e.category} · ${e.date}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }; Text("−${money(e.amount)}",fontWeight=FontWeight.Bold,color=Color(0xFFC74444)) } } }
                }
            }
        }
    }
    if(showPeriodIncomeEditor) PeriodIncomeDialog(periodStart,periodEnd,periodIncome,onDismiss={showPeriodIncomeEditor=false},onSave={amount->
        periodIncomes=periodIncomes.filterNot { it.start==periodStart && it.end==periodEnd } + PeriodIncome(periodStart,periodEnd,amount)
        store.savePeriodIncomes(periodIncomes)
        showPeriodIncomeEditor=false
    })
    if(showPeriodEditor) PeriodEditorDialog(periodStart,periodEnd,onDismiss={showPeriodEditor=false},onSave={from,to->
        periodStart=from; periodEnd=to; store.savePeriod(from,to); selectedPeriod=-1; showPeriodEditor=false
    })
    if(showCategoryManager) CategoryManagerDialog(categories,onDismiss={showCategoryManager=false},onAdd={name,percent->
        if(categories.none{it.name.equals(name,true)}) {
            categories=categories+BudgetCategory(name,percent); store.saveCategories(categories)
        }
    },onDelete={cat->
        categories=categories.filterNot{it.name==cat.name}; store.saveCategories(categories); showCategoryManager=false
    },onEdit={cat->editingCategory=cat;showCategoryManager=false})
    editingCategory?.let { oldCat ->
        CategoryEditDialog(oldCat,onDismiss={editingCategory=null},onSave={name,percent->
            if(categories.none{it.name.equals(name,true)&&it.name!=oldCat.name}) {
                categories=categories.map{if(it.name==oldCat.name) it.copy(name=name,percent=percent) else it}
                expenses=expenses.map{if(it.category==oldCat.name) it.copy(category=name) else it}
                store.saveCategories(categories); store.saveExpenses(expenses)
            }
            editingCategory=null
        })
    }
    selectedCategory?.let { categoryName ->
        val cat=categories.firstOrNull { it.name==categoryName }
        var categorySort by remember(categoryName) { mutableStateOf("Дата") }
        var categoryAscending by remember(categoryName) { mutableStateOf(false) }
        var expandedExpenseId by remember(categoryName) { mutableStateOf<Long?>(null) }
        val rawPeriod=expenses.filter { it.category==categoryName && it.date>=periodStart && it.date<=periodEnd }
        val inPeriod=when(categorySort) {
            "Название" -> if(categoryAscending) rawPeriod.sortedBy { it.title.lowercase() } else rawPeriod.sortedByDescending { it.title.lowercase() }
            "Сумма" -> if(categoryAscending) rawPeriod.sortedBy { it.amount } else rawPeriod.sortedByDescending { it.amount }
            else -> if(categoryAscending) rawPeriod.sortedBy { it.date } else rawPeriod.sortedByDescending { it.date }
        }
        AlertDialog(onDismissRequest={selectedCategory=null},title={Text(categoryName)},text={
            Column(verticalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.heightIn(max=500.dp)) {
                Text("Операции за отчётный период",fontWeight=FontWeight.SemiBold)
                Text("${LocalDate.parse(periodStart).format(formatter)} — ${LocalDate.parse(periodEnd).format(formatter)}",style=MaterialTheme.typography.bodySmall)
                Text("Потрачено: ${money(inPeriod.sumOf { it.amount })} · Бюджет: ${money(cat?.let { if(periodIncome>0) periodIncome*it.percent/100.0 else plannedForPeriod(income,it.percent,periodStart,periodEnd) } ?: 0.0)}")
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    var sortExpanded by remember { mutableStateOf(false) }
                    Box(Modifier.weight(1f)) {
                        OutlinedButton(onClick={sortExpanded=true},modifier=Modifier.fillMaxWidth()) { Text("Сортировка: $categorySort") }
                        DropdownMenu(expanded=sortExpanded,onDismissRequest={sortExpanded=false}) { listOf("Дата","Название","Сумма").forEach { option -> DropdownMenuItem(text={Text(option)},onClick={categorySort=option;sortExpanded=false}) } }
                    }
                    TextButton(onClick={categoryAscending=!categoryAscending}) { Text(if(categoryAscending) "↑" else "↓") }
                }
                if(inPeriod.isEmpty()) Text("За этот период операций пока нет.")
                else LazyColumn(Modifier.heightIn(max=280.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    items(inPeriod,key={it.id}) { expense ->
                        Card(onClick={expandedExpenseId=if(expandedExpenseId==expense.id) null else expense.id}) {
                            Column(Modifier.fillMaxWidth().padding(10.dp)) {
                                Row(verticalAlignment=Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) { Text(expense.title,fontWeight=FontWeight.Medium); Text(expense.date,style=MaterialTheme.typography.bodySmall) }
                                    Text("−${money(expense.amount)}",fontWeight=FontWeight.SemiBold)
                                }
                                if(expandedExpenseId==expense.id) {
                                    Divider(Modifier.padding(vertical=6.dp))
                                    Text("Категория: ${expense.category}",style=MaterialTheme.typography.bodySmall)
                                    Text("Дата: ${expense.date}",style=MaterialTheme.typography.bodySmall)
                                    TextButton(onClick={selectedExpense=expense;selectedCategory=null}) { Text("Изменить операцию") }
                                }
                            }
                        }
                    }
                }
            }
        },confirmButton={TextButton(onClick={addFromCategory=categoryName;selectedCategory=null;showAdd=true}) { Text("Добавить расход") }},dismissButton={TextButton(onClick={selectedCategory=null}) { Text("Закрыть") }})
    }
    if(showAdd) AddExpenseDialog(categories,initialCategory=addFromCategory,onDismiss={showAdd=false},onSave={title,category,amount,date->
        val updated=(listOf(Expense(System.currentTimeMillis(),title,category,amount,date))+expenses).sortedByDescending { it.date }
        val spentAfter=updated.filter { it.category==category && it.date>=periodStart && it.date<=periodEnd }.sumOf { it.amount }
        val cap=categories.firstOrNull { it.name==category }?.let { plannedForPeriod(income,it.percent,periodStart,periodEnd) } ?: 0.0
        expenses=updated; store.saveExpenses(expenses); showAdd=false; addFromCategory=null
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
    if(showSettings) SettingsDialog(income,savingsGoal,categories,periodStart,periodEnd,onDismiss={showSettings=false},onSave={newIncome,newSavings,newCats,start,end,renameMap->
        if (renameMap.isNotEmpty()) {
            expenses = expenses.map { expense -> expense.copy(category = renameMap[expense.category] ?: expense.category) }
            store.saveExpenses(expenses)
        }
        income=newIncome; savingsGoal=newSavings; categories=newCats; periodStart=start; periodEnd=end; store.savePeriod(start,end)
        store.saveIncome(income); store.saveSavingsGoal(savingsGoal); store.saveCategories(categories); showSettings=false
    })
}

@Composable
private fun PeriodIncomeDialog(start:String,end:String,current:Double,onDismiss:()->Unit,onSave:(Double)->Unit) {
    var amount by remember(start,end) { mutableStateOf(if(current>0) current.toString() else "") }
    val parsed=amount.replace(',','.').toDoubleOrNull()
    AlertDialog(onDismissRequest=onDismiss,title={Text("Доход за отчётный период")},text={
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("${LocalDate.parse(start).format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))} — ${LocalDate.parse(end).format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))}",style=MaterialTheme.typography.bodySmall)
            OutlinedTextField(value=amount,onValueChange={amount=it},label={Text("Доход за период, ₽")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),singleLine=true)
            Text("Доходы за два отчётных периода автоматически суммируются в месячный доход.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    },confirmButton={TextButton(onClick={if(parsed!=null&&parsed>=0) onSave(parsed)},enabled=parsed!=null&&parsed>=0){Text("Сохранить")}},dismissButton={
        Row { if(current>0) TextButton(onClick={onSave(0.0)}) { Text("Очистить") }; TextButton(onClick=onDismiss){Text("Отмена")} }
    })
}

@Composable
private fun ChartsScreen(
    expenses:List<Expense>,categories:List<BudgetCategory>,month:YearMonth,onMonthChange:(YearMonth)->Unit,
    showPlan:Boolean,onShowPlanChange:(Boolean)->Unit,showPie:Boolean,onShowPieChange:(Boolean)->Unit,
    fallbackIncome:Double,periodIncomes:List<PeriodIncome>
) {
    val from=month.atDay(1).toString(); val to=month.atEndOfMonth().toString()
    val monthExpenses=expenses.filter { it.date>=from && it.date<=to }
    val total=monthExpenses.sumOf { it.amount }
    val monthIncome=periodIncomes.filter { runCatching { YearMonth.from(LocalDate.parse(it.end))==month }.getOrDefault(false) }.sumOf { it.amount }.let { if(it>0) it else fallbackIncome }
    val palette=listOf(Color(0xFF315CBA),Color(0xFF168A72),Color(0xFFF2A541),Color(0xFF9A6AD6),Color(0xFFE16C70),Color(0xFF5C9DB8),Color(0xFF8B9A45),Color(0xFF7A8290))
    LazyColumn(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(bottom=24.dp)) {
        item { Text("Графики",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold) }
        item { Row(verticalAlignment=Alignment.CenterVertically) {
            TextButton(onClick={onMonthChange(month.minusMonths(1))}) { Text("‹") }
            Text(month.format(DateTimeFormatter.ofPattern("LLLL yyyy",Locale("ru","RU"))),modifier=Modifier.weight(1f),fontWeight=FontWeight.SemiBold)
            TextButton(onClick={if(month<YearMonth.now()) onMonthChange(month.plusMonths(1))}) { Text("›") }
        } }
        item { MetricCard("Фактические расходы за месяц",money(total),Modifier.fillMaxWidth()) }
        item { Row(verticalAlignment=Alignment.CenterVertically) { Text("Плановые и фактические расходы",fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f)); TextButton(onClick={onShowPlanChange(!showPlan)}) { Text(if(showPlan) "Скрыть" else "Показать") } } }
        if(showPlan) {
            items(categories,key={"chart-plan-"+it.name}) { cat ->
                val fact=monthExpenses.filter { it.category==cat.name }.sumOf { it.amount }
                val plan=plannedForPeriod(monthIncome,cat.percent,from,to)
                Card { Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) { Text(cat.name,modifier=Modifier.weight(1f),fontWeight=FontWeight.SemiBold); Text("${money(fact)} / ${money(plan)}",fontWeight=FontWeight.SemiBold) }
                    Row(horizontalArrangement=Arrangement.spacedBy(6.dp),verticalAlignment=Alignment.CenterVertically) {
                        Text("Факт",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        LinearProgressIndicator(progress={if(plan>0)(fact/plan).coerceIn(0.0,1.0).toFloat() else 0f},modifier=Modifier.weight(1f),color=if(fact>plan) Color(0xFFC74444) else Color(0xFF168A72))
                    }
                    Text("${if(fact>plan) "Превышение" else "Остаток"}: ${money(kotlin.math.abs(plan-fact))}",style=MaterialTheme.typography.bodySmall,color=if(fact>plan) Color(0xFFC74444) else Color(0xFF168A72))
                } }
            }
        }
        item { Row(verticalAlignment=Alignment.CenterVertically) { Text("Структура расходов",fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f)); TextButton(onClick={onShowPieChange(!showPie)}) { Text(if(showPie) "Скрыть" else "Показать") } } }
        if(showPie) {
            item {
                val values=categories.map { cat -> cat to monthExpenses.filter { it.category==cat.name }.sumOf { it.amount } }.filter { it.second>0 }
                Card { Column(Modifier.fillMaxWidth().padding(16.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    if(values.isEmpty()) Text("За выбранный месяц расходов пока нет.") else {
                        Box(contentAlignment=Alignment.Center) {
                            Canvas(Modifier.size(210.dp)) {
                                var angle=-90f
                                val sum=values.sumOf { it.second }.toFloat().coerceAtLeast(1f)
                                values.forEachIndexed { index,pair ->
                                    val sweep=pair.second.toFloat()/sum*360f
                                    drawArc(color=palette[index%palette.size],startAngle=angle,sweepAngle=sweep,useCenter=false,style=Stroke(width=size.minDimension*0.22f))
                                    angle+=sweep
                                }
                            }
                            Column(horizontalAlignment=Alignment.CenterHorizontally) { Text(money(total),fontWeight=FontWeight.Bold,style=MaterialTheme.typography.titleLarge); Text("Всего",style=MaterialTheme.typography.labelSmall) }
                        }
                        values.forEachIndexed { index,pair ->
                            Row(verticalAlignment=Alignment.CenterVertically) {
                                Box(Modifier.size(10.dp).background(palette[index%palette.size],RoundedCornerShape(3.dp)))
                                Spacer(Modifier.width(8.dp)); Text(pair.first.name,modifier=Modifier.weight(1f)); Text("${money(pair.second)} · ${if(total>0)(pair.second/total*100).toInt() else 0}%",fontWeight=FontWeight.SemiBold)
                            }
                        }
                    }
                } }
            }
        }
    }
}

@Composable
private fun MetricCard(label:String,value:String,modifier:Modifier=Modifier) {
    Card(modifier=modifier,shape=RoundedCornerShape(16.dp)) { Column(Modifier.padding(14.dp)) { Text(label,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.height(6.dp)); Text(value,fontWeight=FontWeight.Bold,style=MaterialTheme.typography.titleLarge) } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddExpenseDialog(categories:List<BudgetCategory>,initialCategory:String?=null,onDismiss:()->Unit,onSave:(String,String,Double,String)->Unit) {
    var title by remember { mutableStateOf("") }; var amount by remember { mutableStateOf("") }; var category by remember(initialCategory,categories) { mutableStateOf(initialCategory ?: categories.firstOrNull()?.name ?: "Прочее") }; var date by remember { mutableStateOf(LocalDate.now().toString()) }; var expanded by remember { mutableStateOf(false) }
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
private fun SettingsDialog(income:Double,savings:Double,categories:List<BudgetCategory>,start:String,end:String,onDismiss:()->Unit,onSave:(Double,Double,List<BudgetCategory>,String,String,Map<String,String>)->Unit) {
    var inc by remember { mutableStateOf(income.toString()) }
    var sav by remember { mutableStateOf(savings.toString()) }
    var cats by remember { mutableStateOf(categories) }
    var sourceNames by remember { mutableStateOf(categories.map { it.name }) }
    var from by remember { mutableStateOf(start) }
    var to by remember { mutableStateOf(end) }
    AlertDialog(onDismissRequest=onDismiss,title={Text("Настройки бюджета")},text={
        LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.heightIn(max=540.dp)) {
            item { OutlinedTextField(inc,{inc=it},label={Text("Месячный доход, ₽")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),singleLine=true) }
            item { OutlinedTextField(sav,{sav=it},label={Text("Цель накоплений, %")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),singleLine=true) }
            item { Text("Период планирования",fontWeight=FontWeight.Bold) }
            item { OutlinedTextField(from,{from=it},label={Text("Начало (ГГГГ-ММ-ДД)")},singleLine=true) }
            item { OutlinedTextField(to,{to=it},label={Text("Окончание (ГГГГ-ММ-ДД)")},singleLine=true) }
            item { Text("Категории бюджета",fontWeight=FontWeight.Bold) }
            items(cats.size) { index ->
                Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(cats[index].name,{v->cats=cats.toMutableList().also{it[index]=it[index].copy(name=v)}},label={Text("Название категории")},singleLine=true,modifier=Modifier.fillMaxWidth())
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        OutlinedTextField(cats[index].percent.toString(),{v->cats=cats.toMutableList().also{it[index]=it[index].copy(percent=v.replace(',','.').toDoubleOrNull()?:0.0)}},label={Text("Доля, %")},modifier=Modifier.weight(1f),singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal))
                        TextButton(onClick={cats=cats.filterIndexed{idx,_->idx!=index};sourceNames=sourceNames.filterIndexed{idx,_->idx!=index}}) { Text("Удалить",color=Color(0xFFC74444)) }
                    }
                }
            }
            item { OutlinedButton(onClick={cats=cats+BudgetCategory("",0.0);sourceNames=sourceNames+""},modifier=Modifier.fillMaxWidth()) { Text("Добавить категорию") } }
            item { Text("Сумма долей: ${cats.sumOf{it.percent}}% (не более 100%)",style=MaterialTheme.typography.labelMedium,color=if(cats.sumOf{it.percent}>100.0) Color(0xFFC74444) else MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    },confirmButton={
        TextButton(onClick={
            val i=inc.replace(',','.').toDoubleOrNull(); val s=sav.replace(',','.').toDoubleOrNull()
            val validDates=runCatching{LocalDate.parse(from);LocalDate.parse(to)}.isSuccess
            if(i!=null&&i>0&&s!=null&&s>=0&&s<=100&&cats.isNotEmpty()&&cats.all{it.name.isNotBlank()&&it.percent>=0}&&cats.map{it.name.trim().lowercase()}.distinct().size==cats.size&&cats.sumOf{it.percent}<=100.0&&validDates&&!LocalDate.parse(to).isBefore(LocalDate.parse(from)))
                onSave(i,s,cats.map{it.copy(name=it.name.trim())},from,to,sourceNames.zip(cats).filter{it.first.isNotBlank()}.associate{it.first to it.second.name.trim()})
        },enabled=(inc.replace(',','.').toDoubleOrNull()?:0.0)>0) { Text("Сохранить") }
    },dismissButton={TextButton(onClick=onDismiss){Text("Отмена")}})
}

@Composable
private fun PeriodEditorDialog(start:String,end:String,onDismiss:()->Unit,onSave:(String,String)->Unit) {
    var from by remember(start) { mutableStateOf(start) }
    var to by remember(end) { mutableStateOf(end) }
    val valid=runCatching { !LocalDate.parse(to).isBefore(LocalDate.parse(from)) }.getOrDefault(false)
    AlertDialog(onDismissRequest=onDismiss,title={Text("Отчётный период")},text={
        Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("Укажи любые даты. Например, с 1-го по 14-е число или с 1-го по конец месяца.")
            OutlinedTextField(from,{from=it},label={Text("Начало (ГГГГ-ММ-ДД)")},singleLine=true)
            OutlinedTextField(to,{to=it},label={Text("Окончание (ГГГГ-ММ-ДД)")},singleLine=true)
            if(!valid) Text("Проверь даты и порядок периода.",color=Color(0xFFC74444),style=MaterialTheme.typography.bodySmall)
        }
    },confirmButton={TextButton(onClick={if(valid) onSave(from,to)},enabled=valid){Text("Применить")}},dismissButton={TextButton(onClick=onDismiss){Text("Отмена")}})
}

@Composable
private fun CategoryManagerDialog(categories:List<BudgetCategory>,onDismiss:()->Unit,onAdd:(String,Double)->Unit,onDelete:(BudgetCategory)->Unit,onEdit:(BudgetCategory)->Unit) {
    var name by remember { mutableStateOf("") }
    var percent by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest=onDismiss,title={Text("Категории бюджета")},text={
        Column(verticalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.heightIn(max=480.dp)) {
            Text("Удаление категории не удаляет старые операции — они останутся в истории.")
            categories.forEach { cat ->
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text(cat.name,modifier=Modifier.weight(1f))
                    IconButton(onClick={onEdit(cat)}) { Text("✎") }
                    IconButton(onClick={onDelete(cat)}) { Text("×",color=Color(0xFFC74444)) }
                }
            }
            Divider()
            Text("Добавить категорию",fontWeight=FontWeight.SemiBold)
            OutlinedTextField(name,{name=it},label={Text("Название")},singleLine=true)
            OutlinedTextField(percent,{percent=it},label={Text("Доля бюджета, %")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal))
        }
    },confirmButton={
        val value=percent.replace(',','.').toDoubleOrNull()
        TextButton(onClick={if(name.isNotBlank()&&value!=null&&value>=0) {onAdd(name.trim(),value);name="";percent=""}},enabled=name.isNotBlank()&&value!=null&&value>=0) { Text("Добавить") }
    },dismissButton={TextButton(onClick=onDismiss){Text("Готово")}})
}

@Composable
private fun CategoryEditDialog(category:BudgetCategory,onDismiss:()->Unit,onSave:(String,Double)->Unit) {
    var name by remember(category.name) { mutableStateOf(category.name) }
    var percent by remember(category.name) { mutableStateOf(category.percent.toString()) }
    val value=percent.replace(',','.').toDoubleOrNull()
    AlertDialog(onDismissRequest=onDismiss,title={Text("Изменить категорию")},text={
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name,{name=it},label={Text("Название категории")},singleLine=true)
            OutlinedTextField(percent,{percent=it},label={Text("Доля бюджета, %")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal))
        }
    },confirmButton={TextButton(onClick={if(name.isNotBlank()&&value!=null&&value>=0) onSave(name.trim(),value)},enabled=name.isNotBlank()&&value!=null&&value>=0){Text("Сохранить")}},dismissButton={TextButton(onClick=onDismiss){Text("Отмена")}})
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


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExpenseEditDialog(
    expense: Expense,
    categories: List<BudgetCategory>,
    onDismiss: () -> Unit,
    onSave: (Expense) -> Unit,
    onDelete: () -> Unit
) {
    var title by remember(expense.id) { mutableStateOf(expense.title) }
    var amount by remember(expense.id) { mutableStateOf(expense.amount.toString()) }
    var category by remember(expense.id) { mutableStateOf(expense.category) }
    var date by remember(expense.id) { mutableStateOf(expense.date) }
    var expanded by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest=onDismiss,
        title={Text("Изменить расход")},
        text={
            Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(title,{title=it},label={Text("Описание")},singleLine=true)
                OutlinedTextField(amount,{amount=it},label={Text("Сумма, ₽")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),singleLine=true)
                ExposedDropdownMenuBox(expanded=expanded,onExpandedChange={expanded=it}) {
                    OutlinedTextField(value=category,onValueChange={},modifier=Modifier.menuAnchor(),readOnly=true,label={Text("Категория")},trailingIcon={ExposedDropdownMenuDefaults.TrailingIcon(expanded)})
                    ExposedDropdownMenu(expanded=expanded,onDismissRequest={expanded=false}) { categories.forEach { DropdownMenuItem(text={Text(it.name)},onClick={category=it.name;expanded=false}) } }
                }
                OutlinedTextField(date,{date=it},label={Text("Дата (ГГГГ-ММ-ДД)")},singleLine=true)
            }
        },
        confirmButton={
            TextButton(onClick={
                val v=amount.replace(',','.').toDoubleOrNull()
                if(title.isNotBlank() && v!=null && v>0 && runCatching{LocalDate.parse(date)}.isSuccess)
                    onSave(expense.copy(title=title,category=category,amount=v,date=date))
            },enabled=title.isNotBlank() && (amount.replace(',','.').toDoubleOrNull() ?: 0.0)>0) { Text("Сохранить") }
        },
        dismissButton={
            Row {
                TextButton(onClick=onDelete) { Text("Удалить",color=Color(0xFFC74444)) }
                TextButton(onClick=onDismiss) { Text("Отмена") }
            }
        }
    )
}
