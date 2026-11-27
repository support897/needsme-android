package xyz.needsme.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.ValueCallback
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

val Loam = Color(0xFF16120E); val Bone = Color(0xFFF0EAE0); val Sprout = Color(0xFFA8C256); val Muted = Color(0xFF6B6252)

class MainActivity : ComponentActivity() {
    lateinit var auth: AuthHost
    private var fileCb: ValueCallback<Array<Uri>>? = null
    var launchFile: ((ValueCallback<Array<Uri>>, Intent) -> Unit)? = null
    private val userState = mutableStateOf<User?>(null)
    private val checking = mutableStateOf(true)
    val openAcct = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        auth = AuthHost(this) { u -> userState.value = u; checking.value = false }
        handleLink(intent)
        Notify.ask(this)
        setContent { MaterialTheme(colorScheme = lightColorScheme(primary = Loam, secondary = Sprout, background = Color.White, surface = Color.White)) {
            App(this, userState.value, checking.value)
        } }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleLink(intent) }
    private fun handleLink(i: Intent?) { i?.data?.let { if (it.scheme == "needsme") auth.finish(it) }; i?.getStringExtra("acct")?.let { openAcct.value = it } }
    override fun onResume() { super.onResume(); auth.start() }
    override fun onPause() { super.onPause(); auth.stop() }
    fun logout(done: () -> Unit) { userState.value = null; auth.logout(done) }
}

@Composable
fun App(act: MainActivity, user: User?, checking: Boolean) {
    Surface(Modifier.fillMaxSize().systemBarsPadding(), color = Color.White) {
        when {
            user == null -> Box(Modifier.fillMaxSize()) {
                AndroidView(factory = { c -> FrameLayout(c).also { it.show(act.auth.web) } }, update = { it.show(act.auth.web) }, modifier = Modifier.fillMaxSize())
                if (checking) Box(Modifier.fillMaxSize().background(Color.White), Alignment.Center) { Text("NeedsMe Ai", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Loam) }
            }
            else -> Hub(act, user)
        }
    }
}

@Composable
fun Hub(act: MainActivity, user: User) {
    val ctx = act
    val store = remember { Store(ctx) }
    val all = remember { Catalog.parse(ctx.assets.open("catalog.json").bufferedReader().readText()) }
    var accounts by remember { mutableStateOf(store.accounts) }
    var tabs by remember { mutableStateOf(store.openTabs.filter { id -> store.account(id) != null }) }
    var current by remember { mutableStateOf<String?>(null) } // null = Home
    var picker by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Account?>(null) }
    var adding by remember { mutableStateOf<AI?>(null) }
    var settings by remember { mutableStateOf(false) }
    var syncMsg by remember { mutableStateOf("Not synced yet") }
    var photoV by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val webs = remember { mutableMapOf<String, AccountWeb>() }

    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val cb = pendingCb; pendingCb = null
        cb?.onReceiveValue(android.webkit.WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
    }
    val pick: (ValueCallback<Array<Uri>>, Intent) -> Unit = { cb, i -> pendingCb?.onReceiveValue(null); pendingCb = cb; chooser.launch(i) }

    var syncing by remember { mutableStateOf(false) }
    /** Items 2, 9, 14: one shared list for the whole Needs Me account (phone, web, every computer). */
    fun sharedSync() {
        if (syncing) return; syncing = true
        val gone = store.gone()
        val local = accounts.map { SharedList.fromAccount(it, Photos.dataUrl(ctx, it.id)) } +
            gone.map { (id, at) -> Shared(id, "", "", "", "", "", "", "", "", 7, 7, at, true) }
        scope.launch {
            try {
                val merged = withContext(Dispatchers.IO) { Sync.sharedRound(user.apiKey, user.refresh, user.uid, local) }
                val g = gone.toMutableMap(); var changed = false; val next = mutableListOf<Account>()
                for (s in merged) {
                    val mine = accounts.firstOrNull { it.id == s.id }
                    if (s.del) { if (mine != null) { changed = true; webs.remove(s.id)?.web?.destroy(); Profiles.delete(mine.profile); Photos.clear(ctx, s.id) }; g[s.id] = s.at; continue }
                    if (mine == null || s.at > mine.at) {
                        next += SharedList.toAccount(s, mine) { id -> all.firstOrNull { it.id == id }?.url }
                        withContext(Dispatchers.IO) { Photos.fromDataUrl(ctx, s.id, s.img) }; photoV++; changed = true
                    } else next += mine
                }
                if (changed) { accounts = next; store.save(next); tabs = tabs.filter { id -> next.any { it.id == id } }; store.setTabs(tabs) }
                store.setGone(g)
                syncMsg = "Synced"
            } catch (e: Exception) { syncMsg = "Not synced (${e.message})" } finally { syncing = false }
        }
    }
    fun persist(list: List<Account>) {
        val now = System.currentTimeMillis(); val g = store.gone().toMutableMap()
        val stamped = list.map { a -> val o = accounts.firstOrNull { it.id == a.id }; if (o == null || o.copy(at = 0L, setup = false) != a.copy(at = 0L, setup = false)) a.copy(at = now) else a }
        accounts.filter { o -> list.none { it.id == o.id } }.forEach { g[it.id] = now }
        list.forEach { g.remove(it.id) }
        store.setGone(g); accounts = stamped; store.save(stamped)
        sharedSync()
    }
    fun open(id: String) { if (id !in tabs) { tabs = tabs + id; store.setTabs(tabs) }; current = id }
    fun close(id: String) {
        tabs = tabs - id; store.setTabs(tabs); webs.remove(id)?.web?.destroy()
        if (current == id) current = tabs.lastOrNull()
    }
    LaunchedEffect(user.uid) { // every phone keeps the same list as the account: now, and every minute while open
        while (true) { sharedSync(); delay(60000) }
    }
    LaunchedEffect(act.openAcct.value, accounts) { act.openAcct.value?.let { id -> if (accounts.any { it.id == id }) { open(id); act.openAcct.value = null } } }
    var chatsTick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(15000); chatsTick++ } }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(6000); webs.forEach { (id, aw) -> store.account(id)?.let { a -> Notify.check(ctx, a, aw.web, id == current && act.hasWindowFocus(), all.firstOrNull { it.id == a.aiId }?.convo?.ifBlank { null }) } } } }
    LaunchedEffect(adding) { adding?.let { ai -> val a = Account(aiId = ai.id, aiName = ai.name, url = ai.url, nickname = "", setup = true); adding = null; persist(accounts + a); open(a.id) } }
    BackHandler(current != null) {
        val w = webs[current]?.web
        if (w != null && w.canGoBack()) w.goBack() else current = null
    }

    Column(Modifier.fillMaxSize()) {
        TabStrip(accounts, tabs, current, onHome = { current = null }, onTab = { current = it }, onClose = ::close, onAdd = { picker = true }, onSwitch = ::open, onAnother = { a -> adding = AI(a.aiId, a.aiName, a.url, "") })
        Box(Modifier.weight(1f)) {
            val cur = current?.let { store.account(it) ?: accounts.firstOrNull { a -> a.id == it } }
            if (cur == null) Home(all, accounts, user, chatsTick, onChat = { id, href -> open(id); webs[id]?.web?.loadUrl(href) }, onOpen = ::open,
                onPick = { ai -> val ex = accounts.firstOrNull { it.aiId == ai.id }; if (ex != null) open(ex.id) else adding = ai },
                onAddAccount = { adding = it }, onEdit = { editing = it }, onSettings = { settings = true })
            else {
                val aw = webs.getOrPut(cur.id) { AccountWeb(act, cur, pick) }
                Column(Modifier.fillMaxSize()) {
                    if (cur.setup) Row(Modifier.fillMaxWidth().background(Sprout).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Sign in below, then tap the button", color = Loam, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Button(onClick = { editing = cur }, colors = ButtonDefaults.buttonColors(containerColor = Loam)) { Text("I'm logged in", color = Bone) }
                    }
                    AndroidView(factory = { c -> FrameLayout(c).also { it.show(aw.web) } }, update = { it.show(aw.web) }, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
    if (picker) PickerDialog(all, accounts, onDismiss = { picker = false },
        onAi = { ai -> picker = false; adding = ai }, onAccount = { picker = false; open(it) })
    editing?.let { a -> AccountDialog(title = "Edit ${a.title}", initial = a, onDismiss = { editing = null },
        onSave = { n -> editing = null; val fin = n.copy(setup = false); persist(accounts.map { if (it.id == n.id) fin else it }); if (a.setup) { Notify.ask(act); Notify.post(act, fin, Notify.titleOf(fin), "Alerts are on. Tap an alert to jump back to this account.") } },
        onDelete = { editing = null; close(a.id); Profiles.delete(a.profile); persist(accounts.filter { it.id != a.id }) }) }
    if (settings) SettingsDialog(user, syncMsg, onDismiss = { settings = false }, onLogout = {
        settings = false; accounts.forEach { Profiles.delete(it.profile) }; store.clear(); webs.values.forEach { it.web.destroy() }; act.logout {}
    })
}
private var pendingCb: ValueCallback<Array<Uri>>? = null

@Composable
fun TabStrip(accounts: List<Account>, tabs: List<String>, current: String?, onHome: () -> Unit, onTab: (String) -> Unit, onClose: (String) -> Unit, onAdd: () -> Unit, onSwitch: (String) -> Unit = {}, onAnother: (Account) -> Unit = {}) {
    var menu by remember { mutableStateOf(false) }
    LazyRow(Modifier.fillMaxWidth().background(Bone).padding(vertical = 6.dp), contentPadding = PaddingValues(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        item { Chip("Home", current == null, onHome) }
        item { Box {
            Box(Modifier.size(34.dp).clip(CircleShape).background(Color.White).clickable { menu = true }, Alignment.Center) { Text("\u25BE", color = Loam, fontSize = 16.sp) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                accounts.groupBy { it.aiName }.forEach { (ai, list) ->
                    Text(ai, color = Muted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
                    list.forEach { a -> DropdownMenuItem(text = { Text((if (a.id == current) "\u2713 " else "") + a.title) }, onClick = { menu = false; onSwitch(a.id) }, leadingIcon = { Avatar(a, 24.dp) }) }
                    DropdownMenuItem(text = { Text("+ Add another $ai account", color = Muted) }, onClick = { menu = false; onAnother(list.first()) })
                }
                if (accounts.isEmpty()) DropdownMenuItem(text = { Text("No accounts yet") }, onClick = { menu = false })
            }
        } }
        items(tabs) { id ->
            val a = accounts.firstOrNull { it.id == id } ?: return@items
            Row(Modifier.clip(RoundedCornerShape(20.dp)).background(if (current == id) Loam else Color.White).clickable { onTab(id) }.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(a.initial, color = if (current == id) Bone else Loam, fontSize = 13.sp, modifier = Modifier.padding(end = 6.dp))
                Text(a.title, color = if (current == id) Bone else Loam, fontSize = 13.sp, maxLines = 1)
                Text("x", color = if (current == id) Bone else Muted, fontSize = 14.sp, modifier = Modifier.clickable { onClose(id) }.padding(horizontal = 10.dp, vertical = 2.dp))
            }
        }
        item { Box(Modifier.size(34.dp).clip(CircleShape).background(Sprout).clickable { onAdd() }, Alignment.Center) { Text("+", color = Loam, fontSize = 20.sp, fontWeight = FontWeight.Bold) } }
    }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(20.dp)).background(if (selected) Loam else Color.White).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp)) {
        Text(text, color = if (selected) Bone else Loam, fontSize = 13.sp)
    }
}

@Composable
fun Home(all: List<AI>, accounts: List<Account>, user: User, tick: Int = 0, onChat: (String, String) -> Unit = { _, _ -> }, onOpen: (String) -> Unit, onPick: (AI) -> Unit, onAddAccount: (AI) -> Unit, onEdit: (Account) -> Unit, onSettings: () -> Unit) {
    var q by remember { mutableStateOf("") }
    var group by remember { mutableStateOf("All") }
    var custom by remember { mutableStateOf(false) }
    var showAll by remember { mutableStateOf(accounts.isEmpty()) }
    val groups = remember(all) { listOf("All") + all.map { it.group }.distinct() }
    val shown = Catalog.filter(all, q, group)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Your AIs", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Loam)
                    Text("Sign in once. Stay signed in.", color = Muted, fontSize = 14.sp)
                }
                TextButton(onClick = onSettings) { Text("Settings", color = Loam) }
            }
        }
        if (accounts.isNotEmpty()) item {
            Text("Switch account", fontWeight = FontWeight.SemiBold, color = Loam, modifier = Modifier.padding(bottom = 6.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(accounts) { a ->
                    Column(Modifier.width(72.dp).clickable { onOpen(a.id) }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Avatar(a, 56.dp)
                        Text(a.title, fontSize = 12.sp, maxLines = 1, color = Loam)
                        Text("Edit", fontSize = 11.sp, color = Muted, modifier = Modifier.clickable { onEdit(a) })
                    }
                }
            }
        }
        val pulled = accounts.filter { it.use and 1 != 0 }.mapNotNull { a -> Notify.chats[a.id]?.takeIf { it.isNotEmpty() }?.let { a to it } }
        if (pulled.isNotEmpty()) item {
            Text("Your chats and projects", fontWeight = FontWeight.SemiBold, color = Loam)
            Text("Read from each AI you opened. Tap one to open it.", color = Muted, fontSize = 12.sp)
            pulled.forEach { (a, list) ->
                Text(Notify.titleOf(a), color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                list.take(6).forEach { (t, h) -> Text(t, color = Loam, fontSize = 14.sp, maxLines = 1, modifier = Modifier.fillMaxWidth().clickable { onChat(a.id, h) }.padding(vertical = 6.dp)) }
            }
        }
        item { Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Bone).clickable { showAll = !showAll }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (accounts.isEmpty()) "Pick an AI" else "Add another AI", Modifier.weight(1f), fontWeight = FontWeight.SemiBold, color = Loam); Text(if (showAll) "\u25B4" else "\u25BE", color = Loam) } }
        if (showAll) item { OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Search ${all.size} AIs") }, shape = RoundedCornerShape(14.dp)) }
        if (showAll) item { LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { items(groups) { g -> Chip(g, g == group) { group = g } } } }
        if (showAll) items(shown.chunked(2)) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { ai ->
                    val n = accounts.count { it.aiId == ai.id }
                    Card(Modifier.weight(1f).clickable { onPick(ai) }, colors = CardDefaults.cardColors(containerColor = Bone), shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.padding(14.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(44.dp).clip(CircleShape).background(Color.White), Alignment.Center) { Text(ai.name.take(2), fontWeight = FontWeight.Bold, color = Loam) }
                            Text(ai.name, fontWeight = FontWeight.SemiBold, color = Loam, fontSize = 14.sp, maxLines = 1, modifier = Modifier.padding(top = 8.dp))
                            Text(if (n == 0) ai.group else "$n account" + if (n > 1) "s" else "", color = Muted, fontSize = 12.sp)
                            if (n > 0) Text("+ Add account", color = Loam, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp).clickable { onAddAccount(ai) })
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        if (showAll) item {
            OutlinedButton(onClick = { custom = true }, Modifier.fillMaxWidth(), border = BorderStroke(1.dp, Loam)) { Text("Any other AI: add it by link", color = Loam) }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (custom) CustomAiDialog(onDismiss = { custom = false }, onAdd = { custom = false; onAddAccount(it) })
}

@Composable
fun PickerDialog(all: List<AI>, accounts: List<Account>, onDismiss: () -> Unit, onAi: (AI) -> Unit, onAccount: (String) -> Unit = {}) {
    var q by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = Loam) } }, title = { Text("Open an AI") }, text = {
        Column {
            OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Search") })
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                val mine = accounts.filter { q.isBlank() || it.title.contains(q, true) || it.aiName.contains(q, true) }
                if (mine.isNotEmpty()) {
                    item { Text("Already logged in", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)) }
                    items(mine) { a -> Row(Modifier.fillMaxWidth().clickable { onAccount(a.id) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(a, 32.dp); Column(Modifier.weight(1f).padding(start = 10.dp)) { Text(a.title, color = Loam); Text(a.aiName, color = Muted, fontSize = 11.sp) } } }
                    item { Text("Not logged in yet", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp)) }
                }
                items(Catalog.filter(all, q, "All").filter { ai -> accounts.none { it.aiId == ai.id } }) { ai ->
                    Row(Modifier.fillMaxWidth().clickable { onAi(ai) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(ai.name, Modifier.weight(1f), color = Loam)
                        val n = accounts.count { it.aiId == ai.id }; if (n > 0) Text("$n", color = Muted)
                    }
                }
            }
        }
    })
}

private val COLORS = listOf(0xA8C256L, 0x16120EL, 0xF0EAE0L, 0xE5484DL, 0xF5A524L, 0xF2D14BL, 0x2FBF71L, 0x2BB7C8L, 0x3E7BFAL, 0x7C5CFAL, 0xD64FB0L, 0x8B6B4AL)
private val QUICK = listOf("\uD83D\uDE00", "\uD83D\uDCA1", "\uD83D\uDE80", "\uD83C\uDF31", "\uD83D\uDCBC", "\uD83C\uDFE0", "\uD83C\uDFA8", "\uD83D\uDCDA", "\u2B50", "\uD83D\uDD25")

@Composable
fun AccountDialog(title: String, initial: Account, onDismiss: () -> Unit, onSave: (Account) -> Unit, onDelete: (() -> Unit)?, onPhoto: () -> Unit = {}) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var color by remember { mutableStateOf(initial.color) }
    var pv by remember { mutableStateOf(0) }
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u -> if (u != null) { Photos.save(ctx, initial.id, u); pv++; onPhoto() } }
    var nick by remember { mutableStateOf(initial.nickname) }
    var emoji by remember { mutableStateOf(initial.emoji) }
    var note by remember { mutableStateOf(initial.note) }
    var nf by remember { mutableStateOf(initial.nf) }
    var use by remember { mutableStateOf(initial.use) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        confirmButton = { TextButton(onClick = { onSave(initial.copy(nickname = nick.trim(), emoji = emoji.trim(), note = note.trim(), color = color, nf = nf, use = use)) }) { Text("Save", color = Loam) } },
        dismissButton = { Row { if (onDelete != null) TextButton(onClick = onDelete) { Text("Remove", color = Color(0xFFB3261E)) }; TextButton(onClick = onDismiss) { Text("Cancel", color = Loam) } } },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(nick, { nick = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Nickname (like Work or Personal)") })
            Text("Pick an emoji", color = Muted, fontSize = 12.sp)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(QUICK) { e -> Box(Modifier.size(38.dp).clip(CircleShape).background(if (emoji == e) Sprout else Bone).clickable { emoji = e }, Alignment.Center) { Text(e, fontSize = 18.sp) } } }
            OutlinedTextField(emoji, { emoji = it.take(4) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Or type your own") })
            OutlinedTextField(note, { note = it.take(300) }, Modifier.fillMaxWidth(), label = { Text("Description (optional)") })
            Text("Color", color = Muted, fontSize = 12.sp)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(COLORS) { c -> Box(Modifier.size(34.dp).clip(CircleShape).background(Color(0xFF000000 or c)).border(if (color == c) 3.dp else 1.dp, if (color == c) Loam else Muted.copy(alpha = .3f), CircleShape).clickable { color = c }) } }
            var hex by remember(color) { mutableStateOf(if (color == 0L) "" else "%06X".format(color)) }
            OutlinedTextField(hex, { v -> hex = v.filter { it.isLetterOrDigit() }.take(6).uppercase(); if (hex.length == 6) hex.toLongOrNull(16)?.let { color = it } }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Any color (hex, like 3E7BFA)") })
            Text("Alerts", color = Muted, fontSize = 12.sp)
            listOf(NF_DONE to "When it finishes a job", NF_NEEDS to "When it needs me", NF_MSGS to "When a new message comes").forEach { (bit, label) ->
                Row(Modifier.fillMaxWidth().clickable { nf = nf xor bit }, verticalAlignment = Alignment.CenterVertically) { Checkbox(nf and bit != 0, { nf = nf xor bit }); Text(label, fontSize = 14.sp) }
            }
            Text("What NeedsMe does with this AI", color = Muted, fontSize = 12.sp)
            listOf(1 to "Pull my chats and projects", 2 to "Use for Needs Me (tell me when it needs me)", 4 to "Show its updates").forEach { (bit, label) ->
                Row(Modifier.fillMaxWidth().clickable { use = use xor bit }, verticalAlignment = Alignment.CenterVertically) { Switch(use and bit != 0, { use = use xor bit }); Text(label, fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(initial.copy(color = color, emoji = emoji), 44.dp, pv)
                OutlinedButton(onClick = { photo.launch("image/*") }) { Text("Use a photo", color = Loam) }
                if (Photos.load(ctx, initial.id) != null) TextButton(onClick = { Photos.clear(ctx, initial.id); pv++; onPhoto() }) { Text("Remove photo", color = Muted) }
            }
        } })
}

@Composable
fun CustomAiDialog(onDismiss: () -> Unit, onAdd: (AI) -> Unit) {
    var name by remember { mutableStateOf("") }; var url by remember { mutableStateOf("https://") }
    val ok = name.isNotBlank() && Regex("^https://[^\\s/]+\\.[^\\s]+").matches(url.trim())
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Add any AI") },
        confirmButton = { TextButton(enabled = ok, onClick = { onAdd(AI("custom-" + System.currentTimeMillis(), name.trim(), url.trim(), "Any")) }) { Text("Add", color = Loam) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Loam) } },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Name") })
            OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Link (https://...)") })
        } })
}

@Composable
fun SettingsDialog(user: User, sync: String, onDismiss: () -> Unit, onLogout: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    fun web(u: String) = CustomTabsIntent.Builder().build().launchUrl(ctx, Uri.parse(u))
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Settings") }, confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = Loam) } }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(user.email.ifBlank { user.name.ifBlank { "Signed in" } }, fontWeight = FontWeight.SemiBold, color = Loam)
            Text("Sync: $sync", color = Muted, fontSize = 13.sp)
            Text("Your AI logins stay on this phone. We never see or store them.", color = Muted, fontSize = 13.sp)
            TextButton(onClick = { web("https://support897.github.io/needsme/privacy.html") }) { Text("Privacy policy", color = Loam) }
            TextButton(onClick = { web("https://support897.github.io/needsme/terms.html") }) { Text("Terms of use", color = Loam) }
            TextButton(onClick = { web("https://support897.github.io/needsme/support.html") }) { Text("Help and support", color = Loam) }
            TextButton(onClick = { web(APP_URL) }) { Text("Delete my account (opens your account page)", color = Loam) }
            TextButton(onClick = onLogout) { Text("Log out", color = Color(0xFFB3261E)) }
        }
    })
}
