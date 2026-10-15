package cn.taprunner

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import cn.taprunner.engine.*
import cn.taprunner.model.*
import cn.taprunner.model.Target
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID

class MainActivity: ComponentActivity() {
    private val app get()=application as TapApp
    private var refreshed by mutableIntStateOf(0)
    private val importFile=registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { persist({
        java.io.BufferedInputStream(contentResolver.openInputStream(it)!!).use { stream ->
            stream.mark(4); val a=stream.read(); val b=stream.read(); stream.reset()
            if(a==80&&b==75) ScriptBundle.import(app,stream) else {
                val bytes=stream.readBounded(2_000_001);require(bytes.size<=2_000_000){"文件不能超过 2MB"}
                val script=ScriptJson.format.decodeFromString<Script>(bytes.toString(Charsets.UTF_8));Validator.validate(script)
                app.store.save(script.copy(id=UUID.randomUUID().toString()))
            }
        }
    }) { toast("导入成功") } } }
    private val exportFile=registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let { val script=app.store.current() ?: return@let;persist({contentResolver.openOutputStream(it)!!.use {out -> out.write(ScriptJson.format.encodeToString(script).toByteArray())}}) {toast("已导出")} } }
    private val exportBundle=registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> uri?.let {val script=app.store.current() ?: return@let;persist({ScriptBundle.export(app,script,contentResolver.openOutputStream(it)!!)}) {toast("脚本与模板已打包导出")} } }
    private val notifyPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()) {refreshed++}
    override fun onCreate(savedInstanceState:Bundle?) {super.onCreate(savedInstanceState);enableEdgeToEdge();setContent {TapRunnerTheme {AppScreen()}}}
    override fun onResume() {super.onResume();refreshed++}
    private fun toast(text:String)=Toast.makeText(this,text,Toast.LENGTH_LONG).show()
    private fun safe(action:()->Unit) {runCatching(action).onFailure {toast(it.message ?: "操作失败")}}
    private fun persist(action:()->Unit,done:()->Unit={}) {app.scope.launch {runCatching {withContext(Dispatchers.IO){action()}}.onSuccess {done()}.onFailure {toast(it.message ?: "操作失败")}}}
    private fun service(action:String)=safe {startForegroundService(Intent(this,RunnerService::class.java).setAction(action))}
    private fun selectScript(id:String) {if(app.overlayDraft.current()?.id!=id)app.overlayDraft.select(id)else app.store.selected.value=id}

    @Composable private fun AppScreen() {
        val scripts by app.store.scripts.collectAsState();val selected by app.store.selected.collectAsState()
        val connected by TapAccessibilityService.connected.collectAsState();val advancedConnected by TapAccessibilityService.advancedConnected.collectAsState()
        val draft by app.overlayDraft.state.collectAsState();val appearance by app.appearance.state.collectAsState()
        var page by rememberSaveable {mutableStateOf("home")};var advanced by rememberSaveable {mutableStateOf(false)}
        val homeScroll=rememberLazyListState();val libraryScroll=rememberLazyListState();val settingsScroll=rememberLazyListState()
        var search by rememberSaveable {mutableStateOf("")};var sort by rememberSaveable {mutableStateOf(ScriptSort.NAME_ASC)};var sortMenu by remember {mutableStateOf(false)}
        var addStep by remember {mutableStateOf(false)};var editStep by remember {mutableStateOf<Step?>(null)}
        var settings by remember {mutableStateOf(false)};var jsonEdit by remember {mutableStateOf(false)};var logs by remember {mutableStateOf(false)}
        var renameScript by remember {mutableStateOf<Script?>(null)};var deleteScript by remember {mutableStateOf<Script?>(null)}
        var templateDialog by remember {mutableStateOf(false)};var accentDialog by remember {mutableStateOf(false)};var clearSteps by remember {mutableStateOf(false)}
        var accepted by remember {mutableStateOf(getPreferences(0).getBoolean("accepted",false))}
        val visible=remember(scripts,search,sort){ScriptLibrary.visible(scripts,search,sort)}
        val script=remember(scripts,selected){scripts.firstOrNull {it.id==selected}}
        val colors=MaterialTheme.colorScheme
        val dark=AppearanceStore.palette(appearance, androidx.compose.foundation.isSystemInDarkTheme()).dark
        SideEffect {WindowCompat.getInsetsController(window,window.decorView).apply {isAppearanceLightStatusBars=!dark;isAppearanceLightNavigationBars=!dark}}
        fun open(action:String) {if(Settings.canDrawOverlays(this@MainActivity))service(action)else{page="settings";toast("请先开启悬浮窗")}}
        fun copy(saved:Script) {persist({app.store.duplicate(saved.id)}) {toast("已复制已保存的脚本，未保存草稿保持不变")}}
        fun load(saved:Script) {safe {selectScript(saved.id);open("overlay")}}
        Scaffold(containerColor=colors.background,
            topBar={Column(Modifier.statusBarsPadding().padding(horizontal=20.dp,vertical=12.dp)) {Row(verticalAlignment=Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).background(colors.primary,CircleShape),contentAlignment=Alignment.Center){Icon(painterResource(R.drawable.ic_brand_tap),null,tint=colors.onPrimary,modifier=Modifier.size(27.dp))}
                Spacer(Modifier.width(12.dp));Column(Modifier.weight(1f)){Text("TapRunner",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold);Text("让重复操作更轻松",style=MaterialTheme.typography.labelMedium,color=colors.onSurfaceVariant)}
                TextButton(onClick={page="settings"}){Text("外观")}
            }}},
            bottomBar={NavigationBar(containerColor=colors.surface,tonalElevation=0.dp) {
                listOf(Triple("home","首页",R.drawable.ic_home),Triple("library","脚本",R.drawable.ic_library),Triple("settings","设置",R.drawable.ic_settings)).forEach {(id,title,icon) ->
                    NavigationBarItem(selected=page==id,onClick={page=id},icon={Icon(painterResource(icon),null,Modifier.size(23.dp))},label={Text(title)})
                }
            }}
        ) {insets ->
            LazyColumn(Modifier.fillMaxSize().padding(insets),state=when(page){"home"->homeScroll;"library"->libraryScroll;else->settingsScroll},contentPadding=PaddingValues(start=20.dp,end=20.dp,top=6.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                if(page=="home") {
                    item("run-status") {RuntimeCard(connected)}
                    item("quick-heading") {SectionTitle("快速开始","在目标应用上设置点位")}
                    item("quick-modes") {Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        QuickCard("单点连点","重复点击同一处","01",Modifier.weight(1f)){open("quickSingle")}
                        QuickCard("多点连点","按步骤顺序执行","02",Modifier.weight(1f)){open("quickMulti")}
                    }}
                    item("quick-guide") {Card(colors=CardDefaults.cardColors(containerColor=colors.surfaceVariant)) {Column(Modifier.padding(16.dp)) {
                        Text("从一个点开始",fontWeight=FontWeight.SemiBold);Spacer(Modifier.height(6.dp))
                        Text("切到目标应用 → 点悬浮栏＋ → 点击屏幕建点 → 设置间隔并保存。",style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant)
                        if(script!=null||draft!=null)TextButton(onClick={open("overlay")}) {Text(if(draft?.dirty==true)"继续编辑未保存点位"else"打开悬浮控制")}
                    }}}
                    item("saved-heading") {SectionTitle("已保存脚本","${scripts.size} 个脚本") {page="library"}}
                    if(scripts.isEmpty())item("empty-home"){EmptyCard("还没有脚本","保存好点位后，就能在这里再次加载。")}
                    items(scripts.take(3),key={"home-${it.id}"}) {saved -> ScriptCard(saved,saved.id==selected,{safe{selectScript(saved.id)}},{load(saved)},{renameScript=saved},{copy(saved)},{deleteScript=saved})}
                } else if(page=="library") {
                    item("library-title"){SectionTitle("脚本库","整理点位与自动化步骤")}
                    item("library-actions"){Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        Button(onClick={persist({app.store.create()})},modifier=Modifier.weight(1f)){Text("新建脚本")}
                        OutlinedButton(onClick={importFile.launch(arrayOf("application/json","text/plain","application/octet-stream","application/zip"))},modifier=Modifier.weight(1f)){Text("导入")}
                    }}
                    item("library-search"){OutlinedTextField(search,{search=it},label={Text("搜索脚本名称")},singleLine=true,modifier=Modifier.fillMaxWidth(),trailingIcon={if(search.isNotEmpty())TextButton(onClick={search=""}){Text("清除")}})}
                    item("library-controls"){Row(verticalAlignment=Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)){TextButton(onClick={sortMenu=true}){Text(when(sort){ScriptSort.NAME_ASC->"名称升序";ScriptSort.NAME_DESC->"名称降序";ScriptSort.STEP_COUNT->"动作最多"})};DropdownMenu(sortMenu,{sortMenu=false}) {ScriptSort.entries.forEach {option -> DropdownMenuItem(text={Text(when(option){ScriptSort.NAME_ASC->"名称升序";ScriptSort.NAME_DESC->"名称降序";ScriptSort.STEP_COUNT->"动作最多"})},onClick={sort=option;sortMenu=false})}}}
                        FilterChip(selected=advanced,onClick={advanced=!advanced},label={Text("步骤编辑")})
                    }}
                    if(visible.isEmpty())item("empty-search"){EmptyCard(if(scripts.isEmpty())"创建第一个脚本"else"没有匹配的脚本",if(scripts.isEmpty())"从首页设置点位，或在这里新建步骤。"else"修改或清除搜索后再试。")}
                    items(visible,key={"script-${it.id}"}) {saved -> ScriptCard(saved,saved.id==selected,{safe{selectScript(saved.id)}},{load(saved)},{renameScript=saved},{copy(saved)},{deleteScript=saved})}
                    if(advanced && script!=null) {
                        item("editor-heading"){SectionTitle("编辑 · ${script.name}","${script.steps.size} 个步骤")}
                        item("editor-actions"){Card {Column(Modifier.padding(14.dp)) {
                            Row {TextButton(onClick={settings=true}){Text("设置")};TextButton(onClick={jsonEdit=true}){Text("JSON")};TextButton(onClick={exportFile.launch("${script.name}.json")}){Text("导出")};TextButton(onClick={logs=true}){Text("日志")}}
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Button(onClick={if(!connected){page="settings";toast("请先开启无障碍服务")}else service("run")}){Text("运行")};OutlinedButton(onClick={open("edit")}){Text("悬浮编辑")}}
                            Row {TextButton(onClick={addStep=true}){Text("添加步骤")};TextButton(onClick={open("record")}){Text("准星标点")};TextButton(onClick={clearSteps=true}){Text("清空")}}
                            TextButton(onClick={templateDialog=true}){Text("截取图像模板")};TextButton(onClick={exportBundle.launch("${script.name}.zip")}){Text("打包导出脚本与模板")}
                        }}}
                        itemsIndexed(script.steps,key={_,step -> "step-${script.id}-${step.id}"}) {index,step -> Card(colors=CardDefaults.cardColors(containerColor=colors.surface)) {Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(30.dp).background(colors.primaryContainer,CircleShape),contentAlignment=Alignment.Center){Text("${index+1}",color=colors.primary,style=MaterialTheme.typography.labelLarge)};Spacer(Modifier.width(10.dp));Text("${label(step.type)} ${step.note}",fontWeight=FontWeight.SemiBold)}
                            Spacer(Modifier.height(8.dp));Text(detail(step),style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant)
                            Row {TextButton(onClick={editStep=step;addStep=true}){Text("编辑")};TextButton(onClick={val updated=script.steps.toMutableList();if(index>0){updated.removeAt(index);updated.add(index-1,step);persist({app.store.save(script.copy(steps=updated))})}}){Text("上移")};TextButton(onClick={persist({app.store.save(script.copy(steps=script.steps.filter{it.id!=step.id}))})}){Text("删除",color=colors.error)}}
                        }}}
                    }
                } else {
                    item("appearance-title"){SectionTitle("外观与体验","选择适合你的明暗与色彩")}
                    item("appearance-panel"){Card {Column(Modifier.padding(18.dp)) {
                        Text("明暗模式",fontWeight=FontWeight.SemiBold);Spacer(Modifier.height(10.dp));Row(horizontalArrangement=Arrangement.spacedBy(7.dp)){ThemeMode.entries.forEach {mode -> FilterChip(selected=appearance.mode==mode,onClick={app.appearance.update(mode=mode)},label={Text(mode.title,fontSize=12.sp)})}}
                        Spacer(Modifier.height(16.dp));Text("强调色",fontWeight=FontWeight.SemiBold);Spacer(Modifier.height(14.dp));Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){AppearanceStore.accents.forEach {(title,accent) -> Column(horizontalAlignment=Alignment.CenterHorizontally,modifier=Modifier.clickable{app.appearance.update(accent=accent)}) {Box(Modifier.size(38.dp).background(Color(accent),CircleShape).then(if(appearance.accent==accent)Modifier.border(3.dp,colors.onSurface,CircleShape)else Modifier));Spacer(Modifier.height(6.dp));Text(title,style=MaterialTheme.typography.labelSmall)}}}
                        TextButton(onClick={accentDialog=true}){Text("自定义颜色")}
                    }}}
                    item("permissions-title"){SectionTitle("运行准备","权限由你在系统设置中开启")}
                    item("permission-coordinate") {PermissionCard("坐标连点",connected,"基础点击、长按与滑动；不读取页面内容。") {safe {startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))}}}
                    item("permission-advanced") {PermissionCard("高级识别",advancedConnected,"文字、控件与图像等待；还提供音量键急停。") {safe {startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))}}}
                    item("permission-overlay") {refreshed;PermissionCard("悬浮窗",Settings.canDrawOverlays(this@MainActivity),"在其他应用上编辑点位和控制执行。") {safe{startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,"package:$packageName".toUri()))}}}
                    item("permission-notification") {PermissionCard("通知",android.os.Build.VERSION.SDK_INT<33||checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==android.content.pm.PackageManager.PERMISSION_GRANTED,"显示运行状态与停止按钮。") {if(android.os.Build.VERSION.SDK_INT>=33)notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)}}
                    item("permission-battery") {val exempt=(getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName);PermissionCard("后台与电池",exempt,"可在系统的电池优化列表中调整 TapRunner；不同厂商入口可能不同。") {safe {startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))}}}
                    item("vendor-help"){Text("若无障碍被系统关闭，请检查受限制设置、后台耗电管理和自启动。修改后重新开启对应服务；应用不会自动续跑。",style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant)}
                    item("tools"){Row {TextButton(onClick={logs=true}){Text("运行日志")};TextButton(onClick={startActivity(Intent(this@MainActivity,TestActivity::class.java))}){Text("真机测试台")}}}
                    item("about"){Text("TapRunner ${BuildConfig.VERSION_NAME} · 无广告 · 数据留在本机",style=MaterialTheme.typography.labelMedium,color=colors.onSurfaceVariant)}
                }
            }
        }
        if(!accepted)AlertDialog(onDismissRequest={},title={Text("每一次运行都由你掌控")},text={Text("脚本、模板和日志保存在本机，应用不联网。请仅用于你有权操作的任务。可从悬浮栏或通知停止，锁屏也会停止；音量键急停需要高级识别服务。")},confirmButton={TextButton(onClick={accepted=true;getPreferences(0).edit{putBoolean("accepted",true)};page="settings"}){Text("了解并开始")}})
        if(addStep&&script!=null)StepDialog(editStep,{addStep=false;editStep=null}) {step -> val updated=script.copy(steps=if(editStep==null)script.steps+step else script.steps.map{if(it.id==step.id)step else it});persist({app.store.save(updated)}){addStep=false;editStep=null}}
        if(settings&&script!=null)SettingsDialog(script,{settings=false}) {updated -> persist({app.store.save(updated)}){settings=false}}
        if(jsonEdit&&script!=null)JsonDialog(ScriptJson.format.encodeToString(script),{jsonEdit=false}) {text -> persist({val updated=ScriptJson.format.decodeFromString<Script>(text);Validator.validate(updated);app.store.save(updated.copy(id=script.id))}){jsonEdit=false}}
        if(logs){val logText by produceState("读取日志…"){value=withContext(Dispatchers.IO){app.store.logs().ifBlank{"暂无日志"}}};AlertDialog(onDismissRequest={logs=false},title={Text("运行日志")},text={Text(logText,modifier=Modifier.heightIn(max=450.dp).verticalScroll(rememberScrollState()),fontSize=12.sp)},confirmButton={TextButton(onClick={logs=false}){Text("关闭")}})}
        renameScript?.let {renaming -> RenameDialog(renaming,{renameScript=null}) {name -> safe {
            val current=app.overlayDraft.state.value;require(current?.script?.id!=renaming.id||current.dirty!=true){"请先保存或作废此脚本的未保存草稿"}
            persist({app.store.rename(renaming.id,name)}) {if(current?.script?.id==renaming.id&&app.overlayDraft.state.value==current)app.overlayDraft.discard();renameScript=null}
        }}}
        deleteScript?.let {deleting -> AlertDialog(onDismissRequest={deleteScript=null},title={Text("删除脚本？")},text={Text("${deleting.name}\n确认后删除此脚本及它的未保存草稿。")},confirmButton={TextButton(onClick={safe{app.engine.stop("删除脚本，已停止");if(app.overlayDraft.current()?.id==deleting.id)app.overlayDraft.discard();persist({app.store.delete(deleting.id)}){deleteScript=null}}}){Text("确认删除",color=colors.error)}},dismissButton={TextButton(onClick={deleteScript=null}){Text("取消")}})}
        if(clearSteps&&script!=null)AlertDialog(onDismissRequest={clearSteps=false},title={Text("清空全部步骤？")},text={Text("将保留脚本名称和设置。")},confirmButton={TextButton(onClick={persist({app.store.save(script.copy(steps=emptyList()))}){clearSteps=false}}){Text("确认清空")}},dismissButton={TextButton(onClick={clearSteps=false}){Text("取消")}})
        if(accentDialog)AccentDialog(appearance.accent,{accentDialog=false}) {accent -> app.appearance.update(accent=accent);accentDialog=false}
        if(templateDialog&&script!=null)TemplateDialog({templateDialog=false}) {roi -> templateDialog=false;app.scope.launch {try {
            toast("3 秒后截取，请切到目标应用");delay(3000);val bitmap=app.runtime.screenshot();val fileName="${UUID.randomUUID()}.png"
            withContext(Dispatchers.IO){val base=File(filesDir,"templates").apply{mkdirs()};try {val x=(roi[0]*bitmap.width).toInt();val y=(roi[1]*bitmap.height).toInt();val width=((roi[2]-roi[0])*bitmap.width).toInt().coerceAtLeast(2);val height=((roi[3]-roi[1])*bitmap.height).toInt().coerceAtLeast(2);val crop=android.graphics.Bitmap.createBitmap(bitmap,x,y,minOf(width,bitmap.width-x),minOf(height,bitmap.height-y));File(base,fileName).outputStream().use{crop.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};if(crop!==bitmap)crop.recycle();File(base,"$fileName.width").writeText(bitmap.width.toString())}finally{bitmap.recycle()};app.store.save(script.copy(steps=script.steps+Step("s_${UUID.randomUUID().toString().take(8)}","waitImage",template=fileName,roi=roi,note="本地图像模板")))}
            toast("已添加图像等待步骤")
        }catch(e:Exception){toast(e.message?:"截图失败")}}}
    }

    @Composable private fun RuntimeCard(connected:Boolean) {
        val progress by app.displayProgress.collectAsState()
        val colors=MaterialTheme.colorScheme
        val running=progress.status in listOf(Status.RUNNING,Status.PAUSED)
        Card(colors=CardDefaults.cardColors(containerColor=colors.primary),modifier=Modifier.fillMaxWidth()) {
            Column(Modifier.background(Brush.linearGradient(listOf(colors.primary,lerp(colors.primary,if(colors.onPrimary==Color.Black)Color.White else Color.Black,.15f)))).padding(22.dp)) {
                Text(if(running)"执行控制"else"准备好，开始下一次",style=MaterialTheme.typography.labelMedium,color=colors.onPrimary)
                Text(if(connected)"无障碍 · 已连接"else"无障碍 · 等待连接",style=MaterialTheme.typography.labelSmall,color=colors.onPrimary)
                Spacer(Modifier.height(10.dp));Text(when(progress.status){Status.RUNNING->"正在执行 · 第 ${progress.round+1} 轮";Status.PAUSED->"已暂停";Status.FAULT->"上次运行已停止";else->if(connected)"点位由你来决定"else"先完成权限设置"},style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold,color=colors.onPrimary)
                Spacer(Modifier.height(9.dp));Text(if(running)"已完成 ${progress.gestures} 次手势"else if(progress.status==Status.FAULT)progress.message else "点击、滑动与等待，按你的步骤执行。",style=MaterialTheme.typography.bodyMedium,color=colors.onPrimary)
                if(running)Row {TextButton(onClick={if(progress.status==Status.PAUSED)app.engine.resume()else app.engine.pause()}){Text(if(progress.status==Status.PAUSED)"继续"else"暂停",color=colors.onPrimary)};TextButton(onClick={app.engine.stop("主界面停止")}){Text("立即停止",color=colors.onPrimary)}}
            }
        }
    }
    @Composable private fun QuickCard(title:String,description:String,number:String,modifier:Modifier,action:()->Unit) {val colors=MaterialTheme.colorScheme;Card(onClick=action,modifier=modifier,colors=CardDefaults.cardColors(containerColor=colors.surface)){Column(Modifier.padding(18.dp)){Text(number,color=colors.primary,style=MaterialTheme.typography.labelLarge);Spacer(Modifier.height(20.dp));Text(title,fontWeight=FontWeight.Bold,style=MaterialTheme.typography.titleMedium);Spacer(Modifier.height(6.dp));Text(description,color=colors.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)}}}
    @Composable private fun SectionTitle(title:String,subtitle:String,more:(()->Unit)?=null){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(title,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold);Text(subtitle,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};if(more!=null)TextButton(onClick=more){Text("全部")}}}
    @Composable private fun EmptyCard(title:String,subtitle:String){Card {Column(Modifier.fillMaxWidth().padding(22.dp)){Text(title,fontWeight=FontWeight.SemiBold);Spacer(Modifier.height(6.dp));Text(subtitle,color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)}}}
    @Composable private fun ScriptCard(script:Script,selected:Boolean,select:()->Unit,load:()->Unit,rename:()->Unit,copy:()->Unit,delete:()->Unit){var menu by remember(script.id){mutableStateOf(false)};val colors=MaterialTheme.colorScheme;Card(onClick=select,modifier=Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=if(selected)colors.primaryContainer else colors.surface)){Column(Modifier.padding(16.dp)){Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(script.name,fontWeight=FontWeight.SemiBold,style=MaterialTheme.typography.titleMedium);Text("${script.steps.size} 个动作 · ${script.meta.repeatCount} 轮",color=colors.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)};Box{TextButton(onClick={menu=true}){Text("更多")};DropdownMenu(menu,{menu=false}){DropdownMenuItem(text={Text("重命名")},onClick={menu=false;rename()});DropdownMenuItem(text={Text("复制")},onClick={menu=false;copy()});DropdownMenuItem(text={Text("删除",color=colors.error)},onClick={menu=false;delete()})}}};Spacer(Modifier.height(6.dp));Row(verticalAlignment=Alignment.CenterVertically){Text(if(selected)"当前脚本"else"本地保存",color=colors.primary,style=MaterialTheme.typography.labelSmall,modifier=Modifier.weight(1f));TextButton(onClick=load){Text("加载点位")}}}}}
    @Composable private fun PermissionCard(name:String,ready:Boolean,description:String,action:()->Unit){Card {Column(Modifier.padding(16.dp)){Row(verticalAlignment=Alignment.CenterVertically){Text(name,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f));Text(if(ready)"已就绪"else"待设置",color=if(ready)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.labelMedium)};Spacer(Modifier.height(6.dp));Text(description,color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall);TextButton(onClick=action){Text(if(ready)"查看设置"else"去设置")}}}}
    @Composable private fun AccentDialog(initial:Int,dismiss:()->Unit,save:(Int)->Unit){var text by remember {mutableStateOf("#%06X".format(initial and 0xFFFFFF))};val parsed=AppearanceStore.parseAccent(text);AlertDialog(onDismissRequest=dismiss,title={Text("自定义强调色")},text={Column{Text("输入六位颜色值，例如 #5367E8。",style=MaterialTheme.typography.bodySmall);OutlinedTextField(text,{text=it},singleLine=true,label={Text("颜色值")},isError=parsed==null,modifier=Modifier.fillMaxWidth())}},confirmButton={TextButton(onClick={parsed?.let(save)},enabled=parsed!=null){Text("应用")}},dismissButton={TextButton(onClick=dismiss){Text("取消")}})}

    private fun label(type:String)=mapOf("click" to "点击","longPress" to "长按","swipe" to "滑动","delay" to "等待","waitText" to "等待文字/控件","waitImage" to "等待图像","loop" to "有限循环","key" to "系统按键","inputText" to "输入文字","log" to "日志","stop" to "停止","launchApp" to "打开应用","if" to "条件分支")[type]?:type
    private fun detail(s:Step)=when(s.type){"click","longPress"->"${s.target?.let{"控件 ${it.find}: ${it.value}"}?:"坐标 %.3f, %.3f".format(s.x,s.y)} · ${s.holdMs}ms";"delay"->"${s.ms}ms";"loop"->"${s.times}轮 · ${s.body.joinToString()}";"waitText"->"${s.target?.value?:s.text} · 超时 ${s.timeoutMs}ms";else->s.id}
    @Composable private fun JsonDialog(initial:String,dismiss:()->Unit,save:(String)->Unit) {var text by remember{mutableStateOf(initial)};AlertDialog(onDismissRequest=dismiss,title={Text("高级脚本 JSON")},text={OutlinedTextField(text,{text=it},modifier=Modifier.height(450.dp),textStyle=LocalTextStyle.current.copy(fontSize=12.sp))},confirmButton={TextButton(onClick={save(text)}){Text("校验并保存")}},dismissButton={TextButton(onClick=dismiss){Text("取消")}})}
    @Composable private fun SettingsDialog(s:Script,dismiss:()->Unit,save:(Script)->Unit) {var name by remember{mutableStateOf(s.name)};var interval by remember{mutableStateOf(s.meta.minIntervalMs.toString())};var expected by remember{mutableStateOf(s.meta.expectedPkg)};AlertDialog(onDismissRequest=dismiss,title={Text("脚本设置")},text={Column{Field("名称",name){name=it};Field("点击开始到下次点击间隔 ms",interval){interval=it};Field("目标包名（可空）",expected){expected=it}}},confirmButton={TextButton(onClick={safe{save(s.copy(name=name,meta=s.meta.copy(minIntervalMs=interval.toLong(),expectedPkg=expected)))}}){Text("保存")}},dismissButton={TextButton(onClick=dismiss){Text("取消")}})}
    @Composable private fun Field(label:String,value:String,change:(String)->Unit){OutlinedTextField(value,change,label={Text(label)},modifier=Modifier.fillMaxWidth().padding(vertical=3.dp),singleLine=true)}
    @Composable private fun RenameDialog(script:Script,dismiss:()->Unit,save:(String)->Unit) {
        var name by remember(script.id){mutableStateOf(script.name)}
        val error=runCatching{ScriptLibrary.rename(script,name)}.exceptionOrNull()?.message
        AlertDialog(onDismissRequest=dismiss,title={Text("重命名脚本")},text={Column{
            OutlinedTextField(name,{name=it},label={Text("名称")},singleLine=true,isError=error!=null,modifier=Modifier.fillMaxWidth())
            if(error!=null)Text(error,color=MaterialTheme.colorScheme.error,fontSize=12.sp)
        }},confirmButton={TextButton(onClick={save(name)},enabled=error==null){Text("保存")}},dismissButton={TextButton(onClick=dismiss){Text("取消")}})
    }
    @Composable private fun TemplateDialog(dismiss:()->Unit,save:(List<Double>)->Unit) {
        var left by remember{mutableStateOf("0.25")};var top by remember{mutableStateOf("0.25")};var right by remember{mutableStateOf("0.75")};var bottom by remember{mutableStateOf("0.5")}
        AlertDialog(onDismissRequest=dismiss,title={Text("本地图像模板")},text={Column{Text("输入模板区域的归一化边界。确认后 3 秒截图，请切到目标应用。优先使用控件等待，仅在节点不可用时使用图像。",fontSize=12.sp);Field("左 0～1",left){left=it};Field("上 0～1",top){top=it};Field("右 0～1",right){right=it};Field("下 0～1",bottom){bottom=it}}},confirmButton={TextButton(onClick={safe{val roi=listOf(left.toDouble(),top.toDouble(),right.toDouble(),bottom.toDouble());require(roi.all{it.isFinite()&&it in 0.0..1.0}&&roi[2]>roi[0]&&roi[3]>roi[1]){"区域无效"};save(roi)}}){Text("开始倒计时")}},dismissButton={TextButton(onClick=dismiss){Text("取消")}})
    }
    @Composable private fun StepDialog(original:Step?,dismiss:()->Unit,save:(Step)->Unit) {
        val types=listOf("click","longPress","swipe","delay","waitText","loop","key","inputText","launchApp","log","stop")
        var type by remember{mutableStateOf(original?.type?:"click")};var menu by remember{mutableStateOf(false)}
        var x by remember{mutableStateOf((original?.x?:.5).toString())};var y by remember{mutableStateOf((original?.y?:.5).toString())}
        var x2 by remember{mutableStateOf((original?.to?.get(0)?:.5).toString())};var y2 by remember{mutableStateOf((original?.to?.get(1)?:.25).toString())}
        var ms by remember{mutableStateOf((original?.let{if(it.type=="delay")it.ms else if(it.type=="swipe")it.durationMs else if(it.type=="waitText")it.timeoutMs else it.holdMs}?:500).toString())}
        var value by remember{mutableStateOf(original?.let{when(it.type){"loop"->it.body.joinToString(",");"key"->it.key;"launchApp"->it.pkg;"log"->it.message;"inputText"->it.text;else->it.target?.value?:it.text}}?:"")}
        var targetValue by remember{mutableStateOf(original?.target?.value?:"")};var find by remember{mutableStateOf(original?.target?.find?:"text")}
        var times by remember{mutableStateOf((original?.times?:10).toString())};var note by remember{mutableStateOf(original?.note?:"")}
        AlertDialog(onDismissRequest=dismiss,title={Text(if(original==null)"添加步骤" else "编辑 ${original.id}")},text={Column(Modifier.heightIn(max=450.dp).verticalScroll(rememberScrollState())) {
            Box {OutlinedButton(onClick={menu=true}){Text("类型：${label(type)}")};DropdownMenu(menu,{menu=false}){types.forEach{t->DropdownMenuItem(text={Text(label(t))},onClick={type=t;menu=false})}}}
            Field("备注",note){note=it}
            when(type){
                "click","longPress","swipe"->{Field("X（0～1）",x){x=it};Field("Y（0～1）",y){y=it};if(type=="swipe"){Field("终点 X",x2){x2=it};Field("终点 Y",y2){y2=it}};Field("手势时长 ms",ms){ms=it}}
                "delay"->Field("等待 ms",ms){ms=it}
                "waitText"->{Field("等待文字",value){value=it};Field("超时 ms",ms){ms=it}}
                "loop"->{Field("循环次数（有限）",times){times=it};Field("子步骤 ID，逗号分隔",value){value=it};Text("被循环引用的步骤只在循环中运行。可在高级 JSON 查看完整 ID。",fontSize=12.sp)}
                "key"->Field("back / home / recents",value){value=it}
                "inputText"->{Field("目标类型 text/id/desc",find){find=it};Field("目标控件",targetValue){targetValue=it};Field("输入文字",value){value=it}}
                "launchApp"->Field("应用包名",value){value=it}
                "log"->Field("日志消息",value){value=it}
            }
        }},confirmButton={TextButton(onClick={safe{
            val base=original?:Step("s_${UUID.randomUUID().toString().take(8)}")
            val st=when(type){"click","longPress"->base.copy(type=type,x=x.toDouble(),y=y.toDouble(),fallback=if(base.target!=null)Point(x.toDouble(),y.toDouble())else null,holdMs=ms.toLong(),note=note);"swipe"->base.copy(type=type,from=listOf(x.toDouble(),y.toDouble()),to=listOf(x2.toDouble(),y2.toDouble()),durationMs=ms.toLong(),note=note);"delay"->base.copy(type=type,ms=ms.toLong(),note=note);"waitText"->base.copy(type=type,text=value,target=null,timeoutMs=ms.toLong(),note=note);"loop"->base.copy(type=type,times=times.toInt(),body=value.split(',').map{it.trim()}.filter{it.isNotEmpty()},note=note);"key"->base.copy(type=type,key=value,note=note);"inputText"->base.copy(type=type,target=Target(find,targetValue),text=value,note=note);"launchApp"->base.copy(type=type,pkg=value,note=note);"log"->base.copy(type=type,message=value,note=note);else->base.copy(type=type,note=note)}
            save(st)
        }}){Text("保存")}},dismissButton={TextButton(onClick=dismiss){Text("取消")}})
    }
}
