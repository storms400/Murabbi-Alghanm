package dz.murabbi.alghanm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import dz.murabbi.alghanm.data.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = FarmDatabase.get(this)
        setContent { MaterialTheme { CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            FarmScreen(FarmViewModel(db.farmDao()))
        } } }
    }
}

class FarmViewModel(private val dao: FarmDao) : ViewModel() {
    val animals = dao.activeAnimals().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val purchases = dao.purchases().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val tasks = dao.tasks().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addAnimal(tag: String, sex: String, category: String, weight: Double, onError: (String) -> Unit) {
        viewModelScope.launch {
            try { dao.insertAnimal(Animal(tag.trim(), sex, category, weight)) }
            catch (_: Exception) { onError("تعذر الحفظ: تأكد أن رقم الحيوان غير مكرر.") }
        }
    }
    fun archive(animal: Animal) { viewModelScope.launch { dao.archiveAnimal(animal.tag, "أرشفة يدوية") } }
    fun addPurchase(name: String, qty: Double, price: Double?, supplier: String) {
        viewModelScope.launch {
            dao.addPurchase(FeedPurchase(feedName=name, quantityKg=qty, totalPriceDzd=price,
                supplier=supplier, purchaseDate=LocalDate.now().toString()))
        }
    }
    fun addTask(type: String, date: String, count: Int, notes: String) {
        viewModelScope.launch { dao.addTask(FarmTask(taskType=type, taskDate=date, animalCount=count, notes=notes)) }
    }
}

@Composable
fun FarmScreen(vm: FarmViewModel) {
    val animals by vm.animals.collectAsState()
    val purchases by vm.purchases.collectAsState()
    val tasks by vm.tasks.collectAsState()
    var tab by remember { mutableStateOf("الرئيسية") }
    var showAdd by remember { mutableStateOf(false) }
    var tag by remember { mutableStateOf("") }
    var sex by remember { mutableStateOf("أنثى") }
    var category by remember { mutableStateOf("جافة") }
    var weight by remember { mutableStateOf("45") }
    var error by remember { mutableStateOf("") }
    var feedName by remember { mutableStateOf("شعير") }
    var quantity by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    var supplier by remember { mutableStateOf("") }
    var taskType by remember { mutableStateOf("تنقية الفرشة") }
    var notes by remember { mutableStateOf("") }

    val categories = listOf("جافة", "حامل - جنين واحد", "حامل - توأم", "مرضعة - حمل واحد", "مرضعة - توأم", "فحل", "حمل")
    Scaffold(topBar = { TopAppBar(title = { Text("🐑 مربي الغنم") }, subtitle = { Text("إدارة المزرعة والقطيع") }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(horizontal=8.dp), horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                listOf("الرئيسية","القطيع","المشتريات","الرعاية").forEach { name ->
                    FilterChip(selected=tab==name,onClick={tab=name},label={Text(name)})
                }
            }
            when(tab) {
                "الرئيسية" -> Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                    Text("ملخص القطيع", style=MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(10.dp))
                    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                        Text("إجمالي الحيوانات النشطة", style=MaterialTheme.typography.labelLarge)
                        Text("${animals.size} رأس", style=MaterialTheme.typography.headlineMedium)
                        Text("النعاج: ${animals.count{it.sex=="أنثى"}}")
                        Text("الحوامل: ${animals.count{it.category.startsWith("حامل")}}")
                        Text("المرضعات: ${animals.count{it.category.startsWith("مرضعة")}}")
                        Text("الفحول: ${animals.count{it.category=="فحل"}}")
                        Text("الحملان: ${animals.count{it.category=="حمل"}}")
                    } }
                    Spacer(Modifier.height(12.dp))
                    Text("الأعداد تتغير تلقائياً حسب السجلات النشطة. البيع والنفوق يجب أن يؤديا إلى الأرشفة، لا حذف الحيوان.")
                }
                "القطيع" -> Column(Modifier.fillMaxSize().padding(12.dp)) {
                    Button(onClick={showAdd=!showAdd}, modifier=Modifier.fillMaxWidth()) { Text(if(showAdd) "إغلاق نموذج الإضافة" else "＋ إضافة حيوان") }
                    if(showAdd) {
                        OutlinedTextField(tag,{tag=it},label={Text("رقم الحيوان")},modifier=Modifier.fillMaxWidth())
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            listOf("أنثى","ذكر").forEach { FilterChip(selected=sex==it,onClick={sex=it},label={Text(it)}) }
                        }
                        var expanded by remember { mutableStateOf(false) }
                        ExposedDropdownMenuBox(expanded=expanded,onExpandedChange={expanded=!expanded}) {
                            OutlinedTextField(category,{category=it},readOnly=true,label={Text("الفئة")},modifier=Modifier.menuAnchor().fillMaxWidth())
                            ExposedDropdownMenu(expanded=expanded,onDismissRequest={expanded=false}) {
                                categories.forEach { DropdownMenuItem(text={Text(it)},onClick={category=it;expanded=false}) }
                            }
                        }
                        OutlinedTextField(weight,{weight=it},label={Text("الوزن بالكيلوغرام")},modifier=Modifier.fillMaxWidth())
                        if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error)
                        Button(onClick={
                            val w=weight.toDoubleOrNull()
                            if(tag.isBlank()||w==null||w<=0) error="أدخل رقم الحيوان ووزناً صحيحاً."
                            else { vm.addAnimal(tag,sex,category,w){error=it};tag="";error="";showAdd=false }
                        },modifier=Modifier.fillMaxWidth()) { Text("حفظ") }
                    }
                    LazyColumn {
                        items(animals,key={it.tag}) { a ->
                            ListItem(
                                headlineContent={Text(a.tag)},
                                supportingContent={Text("${a.category} · ${a.sex} · ${a.weightKg} كغ")},
                                trailingContent={TextButton(onClick={vm.archive(a)}){Text("أرشفة")}}
                            )
                            HorizontalDivider()
                        }
                    }
                }
                "المشتريات" -> Column(Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState())) {
                    Text("تسجيل شراء علف",style=MaterialTheme.typography.titleLarge)
                    OutlinedTextField(feedName,{feedName=it},label={Text("نوع العلف")},modifier=Modifier.fillMaxWidth())
                    OutlinedTextField(quantity,{quantity=it},label={Text("الكمية بالكغ")},modifier=Modifier.fillMaxWidth())
                    OutlinedTextField(price,{price=it},label={Text("السعر الإجمالي بالدينار (اختياري)")},modifier=Modifier.fillMaxWidth())
                    OutlinedTextField(supplier,{supplier=it},label={Text("المورد")},modifier=Modifier.fillMaxWidth())
                    Button(onClick={val q=quantity.toDoubleOrNull();val p=if(price.isBlank()) null else price.toDoubleOrNull()
                        if(q!=null&&q>0&&(price.isBlank()||p!=null&&p>=0)){vm.addPurchase(feedName,q,p,supplier);quantity="";price="";supplier=""}
                    },modifier=Modifier.fillMaxWidth()){Text("حفظ عملية الشراء")}
                    Text("السعر الفارغ يعني غير معروف، ولا يُعامل كصفر.")
                    purchases.forEach { ListItem(headlineContent={Text("${it.feedName} · ${it.quantityKg} كغ")},supportingContent={Text("${it.purchaseDate} · ${it.supplier}")}, trailingContent={Text(it.totalPriceDzd?.let{"$it دج"}?: "غير معروف")}) }
                }
                "الرعاية" -> Column(Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState())) {
                    Text("أعمال الحظيرة والجز",style=MaterialTheme.typography.titleLarge)
                    OutlinedTextField(taskType,{taskType=it},label={Text("نوع العمل")},modifier=Modifier.fillMaxWidth())
                    OutlinedTextField(notes,{notes=it},label={Text("ملاحظات")},modifier=Modifier.fillMaxWidth())
                    Button(onClick={vm.addTask(taskType,LocalDate.now().toString(),animals.size,notes);notes=""},modifier=Modifier.fillMaxWidth()){Text("تسجيل العمل اليوم")}
                    tasks.forEach { ListItem(headlineContent={Text(it.taskType)},supportingContent={Text("${it.taskDate} · ${it.animalCount} رأس · ${it.notes}")}) }
                    Text("تنبيه: توقيت جز الصوف والتنظيف يتغير بحسب الطقس والرطوبة وحالة القطيع. التطهير بعد التنظيف والتجفيف ووفق تعليمات المنتج.")
                }
            }
        }
    }
}
