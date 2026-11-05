package dz.murabbi.alghanm

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dz.murabbi.alghanm.data.*
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale

@Composable
fun SmartFarmPanel(
    animals: List<Animal>,
    purchases: List<FeedPurchase>,
    consumptions: List<FeedConsumption>,
    tasks: List<FarmTask>,
    events: List<HerdEvent>,
    onAddEvent: (String, String, String, String, (Boolean) -> Unit) -> Unit,
    onAddTask: (String, String, Int, String, (Boolean) -> Unit) -> Unit
) {
    val context = LocalContext.current
    val today = LocalDate.now()
    var section by remember { mutableStateOf("التغذية الذكية") }
    var feedGroup by remember { mutableStateOf("القطيع كله") }
    var dailyPerHead by remember { mutableStateOf("") }
    var targetTag by remember(animals) { mutableStateOf(animals.firstOrNull()?.tag ?: "عام") }
    var eventType by remember { mutableStateOf("تطعيم") }
    var eventDate by remember { mutableStateOf(today.toString()) }
    var eventAmount by remember { mutableStateOf("") }
    var eventNotes by remember { mutableStateOf("") }
    var taskType by remember { mutableStateOf("فحص بيطري") }
    var taskDate by remember { mutableStateOf(today.plusDays(1).toString()) }
    var taskCount by remember(animals.size) { mutableStateOf(animals.size.coerceAtLeast(1).toString()) }
    var taskNotes by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }

    val selectedAnimals = when (feedGroup) {
        "نعاج" -> animals.filter { it.category.contains("نعجة") || it.category.contains("نعاج") }
        "فحول" -> animals.filter { it.category.contains("فحل") }
        "حملان" -> animals.filter { it.category.contains("حمل") || it.category.contains("خروف") }
        else -> animals
    }
    val daily = (dailyPerHead.replace(',', '.').toDoubleOrNull() ?: 0.0) * selectedAnimals.size
    val totalFeedCost = purchases.sumOf { it.totalPriceDzd ?: 0.0 }
    fun amountFrom(note: String): Double {
        val match = Regex("(?:المبلغ|قيمة البيع|قيمة المصروف|الوزن)[:： ]*([0-9]+(?:[.,][0-9]+)?)")
            .find(note) ?: return 0.0
        return match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: 0.0
    }
    val sales = events.filter { it.eventType == "بيع" }.sumOf { amountFrom(it.notes) }
    val extraExpenses = events.filter { it.eventType == "مصروف" }.sumOf { amountFrom(it.notes) }
    val estimatedBalance = sales - totalFeedCost - extraExpenses
    val upcoming = tasks.mapNotNull { task ->
        val date = runCatching { LocalDate.parse(task.taskDate) }.getOrNull() ?: return@mapNotNull null
        val days = ChronoUnit.DAYS.between(today, date)
        if (!task.completed && days <= 7) task to days else null
    }.sortedBy { it.second }

    fun csv(value: String) = "\"" + value.replace("\"", "\"\"").replace("\n", " ") + "\""
    val exportText = buildString {
        appendLine("النوع,المعرف,التاريخ,التفاصيل")
        animals.forEach { appendLine("حيوان,${csv(it.tag)},,${csv("${it.sex} - ${it.category} - ${it.weightKg} كغ")}") }
        purchases.forEach { appendLine("شراء علف,${csv(it.feedName)},${csv(it.purchaseDate)},${csv("${it.quantityKg} كغ - ${it.totalPriceDzd ?: 0.0} دج")}") }
        consumptions.forEach { appendLine("استهلاك,${csv(it.feedName)},${csv(it.consumptionDate)},${csv("${it.quantityKg} كغ - ${it.animalCategory}")}") }
        tasks.forEach { appendLine("مهمة,${csv(it.taskType)},${csv(it.taskDate)},${csv(it.notes)}") }
        events.forEach { appendLine("حدث,${csv(it.animalTag)},${csv(it.eventDate)},${csv("${it.eventType} - ${it.notes}")}") }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("الإدارة الذكية للمزرعة", style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary)
        Text("أدوات إضافية مدمجة مع بيانات القطيع الحالية.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        DropdownField(
            "القسم الذكي", section,
            listOf("التغذية الذكية", "الصحة والتناسل", "الوزن والنمو", "المواعيد والتنبيهات", "المالية والتقارير"),
            { section = it }
        )

        when (section) {
            "التغذية الذكية" -> {
                Text("حاسبة الاحتياج اليومي", style = MaterialTheme.typography.titleMedium)
                DropdownField("الفئة", feedGroup, listOf("القطيع كله", "نعاج", "فحول", "حملان"), { feedGroup = it })
                OutlinedTextField(
                    value = dailyPerHead, onValueChange = { dailyPerHead = it },
                    label = { Text("العلف اليومي للحيوان الواحد بالكغ") },
                    supportingText = { Text("أدخل الحصة التي تعتمدها أنت أو يحددها المختص البيطري.") },
                    modifier = Modifier.fillMaxWidth()
                )
                Card {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("عدد الحيوانات: ${selectedAnimals.size}")
                        Text("الاحتياج اليومي: ${"%.2f".format(Locale.US, daily)} كغ")
                        Text("7 أيام: ${"%.1f".format(Locale.US, daily * 7)} كغ")
                        Text("30 يوماً: ${"%.1f".format(Locale.US, daily * 30)} كغ")
                        Text("90 يوماً: ${"%.1f".format(Locale.US, daily * 90)} كغ")
                    }
                }
                Text("مخزون الأعلاف", style = MaterialTheme.typography.titleMedium)
                val feedNames = (purchases.map { it.feedName } + consumptions.map { it.feedName }).distinct().sorted()
                if (feedNames.isEmpty()) Text("لم تسجل مشتريات أو استهلاكاً بعد.")
                feedNames.forEach { name ->
                    val bought = purchases.filter { it.feedName == name }.sumOf { it.quantityKg }
                    val used = consumptions.filter { it.feedName == name }.sumOf { it.quantityKg }
                    ListItem(
                        headlineContent = { Text(name) },
                        supportingContent = { Text("مشتَرى: ${"%.1f".format(Locale.US, bought)} كغ • مستهلك: ${"%.1f".format(Locale.US, used)} كغ") },
                        trailingContent = { Text("${"%.1f".format(Locale.US, bought - used)} كغ",
                            color = if (bought - used <= 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
                    )
                    HorizontalDivider()
                }
            }
            "الصحة والتناسل" -> {
                Text("سجل صحي وتناسلي موحد", style = MaterialTheme.typography.titleMedium)
                DropdownField("الحيوان", targetTag, listOf("عام") + animals.map { it.tag }, { targetTag = it })
                DropdownField("نوع الحدث", eventType,
                    listOf("تلقيح", "تطعيم", "علاج", "فحص بيطري", "ولادة", "فطام", "بيع", "مصروف"),
                    { eventType = it })
                OutlinedTextField(eventDate, { eventDate = it }, label = { Text("التاريخ (YYYY-MM-DD)") }, modifier = Modifier.fillMaxWidth())
                if (eventType == "بيع" || eventType == "مصروف") {
                    OutlinedTextField(eventAmount, { eventAmount = it }, label = { Text("المبلغ بالدينار الجزائري") }, modifier = Modifier.fillMaxWidth())
                }
                OutlinedTextField(eventNotes, { eventNotes = it }, label = { Text("التفاصيل والملاحظات") }, modifier = Modifier.fillMaxWidth())
                Button(
                    enabled = !saving,
                    onClick = {
                        val amount = eventAmount.replace(',', '.').toDoubleOrNull()
                        if (runCatching { LocalDate.parse(eventDate) }.isFailure ||
                            ((eventType == "بيع" || eventType == "مصروف") && (amount == null || amount <= 0.0))
                        ) {
                            message = "تحقق من التاريخ والمبلغ."
                        } else {
                            val detail = buildString {
                                if (eventType == "بيع") append("قيمة البيع: $amount دج. ")
                                if (eventType == "مصروف") append("قيمة المصروف: $amount دج. ")
                                append(eventNotes.trim())
                            }
                            saving = true
                            onAddEvent(targetTag, eventType, eventDate, detail) { ok ->
                                saving = false
                                message = if (ok) "تم حفظ السجل." else "تعذر حفظ السجل."
                                if (ok) { eventAmount = ""; eventNotes = "" }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (saving) "جارٍ الحفظ..." else "حفظ الحدث") }
                if (message.isNotBlank()) Text(message)
                Text("آخر السجلات", style = MaterialTheme.typography.titleMedium)
                events.take(8).forEach { event ->
                    ListItem(
                        headlineContent = { Text("${event.eventType} • ${event.animalTag}") },
                        supportingContent = { Text("${event.eventDate} — ${event.notes}") }
                    )
                    HorizontalDivider()
                }
            }
            "الوزن والنمو" -> {
                Text("تسجيل متابعة الوزن", style = MaterialTheme.typography.titleMedium)
                DropdownField("الحيوان", targetTag, animals.map { it.tag }, { targetTag = it })
                OutlinedTextField(eventDate, { eventDate = it }, label = { Text("تاريخ القياس") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(eventAmount, { eventAmount = it }, label = { Text("الوزن بالكغ") }, modifier = Modifier.fillMaxWidth())
                Button(
                    enabled = !saving && animals.isNotEmpty(),
                    onClick = {
                        val weight = eventAmount.replace(',', '.').toDoubleOrNull()
                        if (weight == null || weight <= 0 || runCatching { LocalDate.parse(eventDate) }.isFailure) {
                            message = "أدخل وزناً وتاريخاً صحيحين."
                        } else {
                            saving = true
                            onAddEvent(targetTag, "وزن", eventDate, "الوزن: $weight كغ") { ok ->
                                saving = false
                                message = if (ok) "تم تسجيل الوزن." else "تعذر تسجيل الوزن."
                                if (ok) eventAmount = ""
                            }
                        }
                    }, modifier = Modifier.fillMaxWidth()
                ) { Text("حفظ قياس الوزن") }
                if (message.isNotBlank()) Text(message)
                Text("سجل الأوزان", style = MaterialTheme.typography.titleMedium)
                events.filter { it.eventType == "وزن" && it.animalTag == targetTag }
                    .sortedByDescending { it.eventDate }.take(12).forEach { item ->
                        ListItem(
                            headlineContent = { Text("${amountFrom(item.notes)} كغ") },
                            supportingContent = { Text(item.eventDate) }
                        )
                    }
            }
            "المواعيد والتنبيهات" -> {
                Text("إضافة موعد أو مهمة", style = MaterialTheme.typography.titleMedium)
                DropdownField("نوع الموعد", taskType,
                    listOf("موعد تلقيح", "ولادة متوقعة", "تطعيم", "علاج", "فحص بيطري", "قص الصوف", "شراء علف", "أخرى"),
                    { taskType = it })
                OutlinedTextField(taskDate, { taskDate = it }, label = { Text("التاريخ (YYYY-MM-DD)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(taskCount, { taskCount = it }, label = { Text("عدد الحيوانات") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(taskNotes, { taskNotes = it }, label = { Text("ملاحظات") }, modifier = Modifier.fillMaxWidth())
                Button(
                    enabled = !saving,
                    onClick = {
                        val count = taskCount.toIntOrNull()
                        if (count == null || count <= 0 || runCatching { LocalDate.parse(taskDate) }.isFailure) {
                            message = "تحقق من التاريخ وعدد الحيوانات."
                        } else {
                            saving = true
                            onAddTask(taskType, taskDate, count, taskNotes) { ok ->
                                saving = false
                                message = if (ok) "تمت إضافة الموعد." else "تعذرت إضافة الموعد."
                                if (ok) taskNotes = ""
                            }
                        }
                    }, modifier = Modifier.fillMaxWidth()
                ) { Text("إضافة موعد") }
                if (message.isNotBlank()) Text(message)
                Text("المواعيد المتأخرة والقريبة خلال 7 أيام", style = MaterialTheme.typography.titleMedium)
                if (upcoming.isEmpty()) Text("لا توجد مواعيد متأخرة أو قريبة.")
                upcoming.forEach { (task, days) ->
                    ListItem(
                        headlineContent = { Text(task.taskType) },
                        supportingContent = { Text("${task.taskDate} • ${task.animalCount} حيوان • ${task.notes}") },
                        trailingContent = {
                            Text(if (days < 0) "متأخر ${-days} يوم" else if (days == 0L) "اليوم" else "بعد ${days} يوم",
                                color = if (days < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        }
                    )
                    HorizontalDivider()
                }
            }
            else -> {
                Text("ملخص مالي تقديري", style = MaterialTheme.typography.titleMedium)
                SummaryCard("تكلفة مشتريات الأعلاف المسجلة", "${"%.0f".format(Locale.US, totalFeedCost)} دج")
                SummaryCard("المصاريف الإضافية المسجلة", "${"%.0f".format(Locale.US, extraExpenses)} دج")
                SummaryCard("إيرادات البيع المسجلة", "${"%.0f".format(Locale.US, sales)} دج")
                SummaryCard("الرصيد التقديري", "${"%.0f".format(Locale.US, estimatedBalance)} دج")
                Text("هذا ملخص للسجلات المدخلة فقط، وليس ربحاً محاسبياً كاملاً.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(
                    onClick = {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/csv"
                            putExtra(Intent.EXTRA_SUBJECT, "نسخة بيانات مربي الغنم")
                            putExtra(Intent.EXTRA_TEXT, exportText)
                        }
                        context.startActivity(Intent.createChooser(intent, "تصدير بيانات المزرعة"))
                    }, modifier = Modifier.fillMaxWidth()
                ) { Text("تصدير سجلات المزرعة CSV") }
                Text("يشمل التصدير الحيوانات والمشتريات والاستهلاك والمهام والأحداث المسجلة.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
