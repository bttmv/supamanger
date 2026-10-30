package com.supamanager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContent { MaterialTheme { CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) { Surface(Modifier.fillMaxSize().systemBarsPadding()) { Root() } } } }
    }
}

@Composable fun bg(key: Any, f: () -> String): State<String> {
    val s = remember(key) { mutableStateOf("جارٍ التحميل...") }
    LaunchedEffect(key) { s.value = withContext(Dispatchers.IO) { runCatching(f).getOrElse { "خطأ: ${it.message}" } } }
    return s
}
fun pretty(s: String) = runCatching { if (s.trim().startsWith("[")) JSONArray(s).toString(2) else JSONObject(s).toString(2) }.getOrDefault(s)

@Composable fun Root() {
    val c = LocalContext.current
    var list by remember { mutableStateOf(Store.load(c)) }
    var sel by remember { mutableStateOf<Project?>(null) }
    var add by remember { mutableStateOf(false) }
    var gh by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val p = sel
    if (gh) { GitHubScreen(list) { gh = false }; return }
    if (p != null) { ProjectScreen(p) { sel = null }; return }
    Column(Modifier.padding(16.dp)) {
        Text("مشاريع Supabase", style = MaterialTheme.typography.headlineSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ add = true }) { Text("إضافة مشروع") }
            OutlinedButton({ gh = true }) { Text("GitHub") }
            OutlinedButton({ scope.launch(Dispatchers.IO) {
                list.forEach { Store.log(c, it.name, runCatching { Api.keepAlive(it) }.getOrElse { e -> "فشل: ${e.message}" }) }; tick++ } }) { Text("إنعاش الكل الآن") }
        }
        key(tick) { LazyColumn(Modifier.padding(top = 8.dp)) { items(list) { pr ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp), onClick = { sel = pr }) { Column(Modifier.padding(12.dp)) {
                Text(pr.name, style = MaterialTheme.typography.titleMedium); Text(pr.url, style = MaterialTheme.typography.bodySmall)
                Text("آخر إنعاش: " + Store.lastLog(c, pr.name), style = MaterialTheme.typography.bodySmall)
                TextButton({ list = list - pr; Store.save(c, list) }) { Text("حذف") } } } } } }
    }
    if (add) {
        var n by remember { mutableStateOf("") }; var u by remember { mutableStateOf("") }; var k by remember { mutableStateOf("") }; var t by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { add = false }, confirmButton = {
            Button({ list = list + Project(n, u.trim().trimEnd('/'), k.trim(), t.trim()); Store.save(c, list); add = false }) { Text("حفظ") } },
            title = { Text("مشروع جديد") }, text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(n, { n = it }, label = { Text("الاسم") })
                OutlinedTextField(u, { u = it }, label = { Text("https://xxxx.supabase.co") })
                OutlinedTextField(k, { k = it }, label = { Text("service_role key") })
                OutlinedTextField(t, { t = it }, label = { Text("Personal Access Token (اختياري)") }) } })
    }
}

@Composable fun ProjectScreen(p: Project, back: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    Column {
        Row { TextButton(back) { Text("رجوع") }; Text(p.name, Modifier.padding(12.dp), style = MaterialTheme.typography.titleMedium) }
        ScrollableTabRow(tab) { listOf("الجداول", "التخزين", "المستخدمون", "SQL").forEachIndexed { i, t -> Tab(tab == i, { tab = i }) { Text(t, Modifier.padding(12.dp)) } } }
        Box(Modifier.padding(12.dp)) { when (tab) { 0 -> Tables(p); 1 -> Storage(p); 2 -> Users(p); else -> Sql(p) } }
    }
}

@Composable fun Tables(p: Project) {
    var t by remember { mutableStateOf<String?>(null) }; var r by remember { mutableIntStateOf(0) }
    var json by remember { mutableStateOf("") }; var filter by remember { mutableStateOf("") }; var msg by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    if (t == null) {
        val res by bg(p) { Api.tables(p).joinToString("\n") }
        Column(Modifier.verticalScroll(rememberScrollState())) { res.lines().forEach { if (it.isNotBlank()) TextButton({ t = it }) { Text(it) } } }
    } else {
        val res by bg(r to t) { pretty(Api.rows(p, t!!)) }
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Row { TextButton({ t = null }) { Text("← الجداول") }; Text(t!!, Modifier.padding(12.dp)) }
            OutlinedTextField(json, { json = it }, Modifier.fillMaxWidth(), label = { Text("إدخال صف JSON: {\"col\":\"val\"}") })
            Button({ scope.launch(Dispatchers.IO) { msg = Api.insert(p, t!!, json); r++ } }) { Text("إضافة") }
            OutlinedTextField(filter, { filter = it }, Modifier.fillMaxWidth(), label = { Text("حذف بشرط مثل: id=eq.5") })
            Button({ scope.launch(Dispatchers.IO) { msg = Api.delete(p, t!!, filter); r++ } }, enabled = filter.isNotBlank()) { Text("حذف") }
            if (msg.isNotBlank()) Text(msg, style = MaterialTheme.typography.bodySmall)
            Text(res, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable fun Storage(p: Project) {
    val c = LocalContext.current; val scope = rememberCoroutineScope()
    var bucket by remember { mutableStateOf("") }; var r by remember { mutableIntStateOf(0) }; var msg by remember { mutableStateOf("") }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u: Uri? -> u ?: return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val name = c.contentResolver.query(u, null, null, null, null)?.use { it.moveToFirst(); it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) } ?: "file"
            msg = Api.upload(p, bucket, name, c.contentResolver.openInputStream(u)!!.readBytes(), c.contentResolver.getType(u) ?: "application/octet-stream"); r++ } }
    val res by bg(Triple(p, bucket, r)) { pretty(if (bucket.isEmpty()) Api.buckets(p) else Api.objects(p, bucket)) }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        OutlinedTextField(bucket, { bucket = it }, label = { Text("اسم الـ bucket (فارغ = عرض الكل)") })
        Button({ pick.launch("*/*") }, enabled = bucket.isNotBlank()) { Text("رفع ملف") }
        if (msg.isNotBlank()) Text(msg, style = MaterialTheme.typography.bodySmall)
        Text(res, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable fun Users(p: Project) { val res by bg(p) { pretty(Api.users(p)) }; Column(Modifier.verticalScroll(rememberScrollState())) { Text(res, style = MaterialTheme.typography.bodySmall) } }

@Composable fun Sql(p: Project) {
    var q by remember { mutableStateOf("select now();") }; var out by remember { mutableStateOf("") }; val scope = rememberCoroutineScope()
    Column(Modifier.verticalScroll(rememberScrollState())) {
        OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), minLines = 4)
        Button({ scope.launch(Dispatchers.IO) { out = pretty(runCatching { Api.sql(p, q) }.getOrElse { it.message ?: "" }) } }) { Text("تشغيل") }
        Text(out, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable fun GitHubScreen(projects: List<Project>, back: () -> Unit) {
    val c = LocalContext.current; val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf(Store.p(c).getString("gh_token", "") ?: "") }
    var repo by remember { mutableStateOf(Store.p(c).getString("gh_repo", "supabase-keepalive") ?: "") }
    var log by remember { mutableStateOf("") }
    fun run(f: (Gh, String) -> String) {
        Store.p(c).edit().putString("gh_token", token.trim()).putString("gh_repo", repo.trim()).apply()
        scope.launch(Dispatchers.IO) { log = "جارٍ التنفيذ..."; log = runCatching { val g = Gh(token.trim()); f(g, g.user()) }.getOrElse { "خطأ: ${it.message}" } }
    }
    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row { TextButton(back) { Text("رجوع") }; Text("إنعاش سحابي عبر GitHub Actions", Modifier.padding(12.dp), style = MaterialTheme.typography.titleMedium) }
        Text("أنشئ توكن من GitHub: Settings ← Developer settings ← Personal access tokens (classic) ← فعّل repo و workflow. ثم الصقه هنا واضغط «إعداد كامل».", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth(), label = { Text("GitHub token") }, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
        OutlinedTextField(repo, { repo = it }, Modifier.fillMaxWidth(), label = { Text("اسم المستودع الخاص") })
        Button({ run { g, u -> g.setup(u, repo.trim(), projects, c.assets.open("keepalive.yml").readBytes()) } }, enabled = token.isNotBlank()) { Text("إعداد كامل (إنشاء + أسرار + ملف + تشغيل)") }
        OutlinedButton({ run { g, u -> g.dispatch(u, repo.trim()) } }, enabled = token.isNotBlank()) { Text("تشغيل الإنعاش الآن") }
        OutlinedButton({ run { g, u -> g.runs(u, repo.trim()) } }, enabled = token.isNotBlank()) { Text("آخر التشغيلات") }
        Text("بعد إضافة مشروع جديد اضغط «إعداد كامل» مرة أخرى لتحديث القائمة.", style = MaterialTheme.typography.bodySmall)
        Text(log, style = MaterialTheme.typography.bodySmall)
    }
}
