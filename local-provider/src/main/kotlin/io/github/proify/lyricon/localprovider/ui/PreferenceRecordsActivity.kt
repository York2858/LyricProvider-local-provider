package io.github.proify.lyricon.localprovider.ui

import android.app.Activity
import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.database.ContentObserver
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import io.github.proify.lyricon.localprovider.data.*
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.*

/** Durable, manual hand-off: capture never writes audio files. */
class PreferenceRecordsActivity : AppCompatActivity() {
    private val store by lazy { PreferenceRecordStore(this) }
    private val selected = linkedSetOf<String>()
    private lateinit var rows: LinearLayout
    private lateinit var count: TextView
    private lateinit var write: MaterialButton
    private lateinit var selectAll: CheckBox
    private var updatingSelection=false
    private var pickingId: String? = null
    private var writing = false
    private var collectionJob: Job? = null
    private val collectionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val palette get() = ProviderTheme.colors(this)
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { renderRows() }
    }
    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = pickingId
        pickingId = null
        if(uri != null && id != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            store.choose(id, PreferenceFile(uri=uri.toString(), source="手动确认"))
            selected.add(id); renderRows()
        }
    }
    private val writer = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        writing = false
        val raw = result.data?.getStringExtra("results")
        if(result.resultCode == Activity.RESULT_OK && raw != null) {
            runCatching {
                val all = JSONArray(raw)
                var succeeded=0
                for(i in 0 until all.length()) {
                    val item=all.getJSONObject(i)
                    store.applyResult(item.getString("id"),item.getLong("revision"),item.getBoolean("success"),item.optString("message"))
                    if(item.getBoolean("success")) { succeeded++; selected.remove(item.getString("id")) }
                }
                toast("Lyrico 已返回：$succeeded 首成功，${all.length()-succeeded} 首未完成")
            }.onFailure { toast("写入结果无法读取，记录已保留，可重试") }
        } else toast("写入已取消，记录已保留")
        renderRows()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        delegate.localNightMode=ProviderTheme.nightMode(this)
        super.onCreate(savedInstanceState)
        ProviderTheme.install(this)
        selected.addAll(savedInstanceState?.getStringArrayList("selected").orEmpty())
        pickingId=savedInstanceState?.getString("picking")
        writing=savedInstanceState?.getBoolean("writing") ?: false
        val root=column().apply { background=ProviderGlass.page(this@PreferenceRecordsActivity) }
        WindowCompat.setDecorFitsSystemWindows(window,false)
        window.statusBarColor=android.graphics.Color.TRANSPARENT;window.navigationBarColor=android.graphics.Color.TRANSPARENT
        ViewCompat.setOnApplyWindowInsetsListener(root) { view,insets ->
            val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars());view.setPadding(bars.left,bars.top,bars.right,bars.bottom);insets
        }
        root.addView(row().apply {
            setPadding(dp(10),dp(6),dp(16),dp(6))
            addView(ImageButton(this@PreferenceRecordsActivity).apply {
                background=null;setImageDrawable(ProviderIcon("back",palette.text));contentDescription="返回"
                setOnClickListener { finish() }
            },LinearLayout.LayoutParams(dp(44),dp(44)))
            addView(text("收藏与评分",21).apply { typeface=Typeface.create("sans-serif-medium",0) })
        })
        val body=column().apply {setPadding(dp(16),dp(8),dp(16),dp(12))}
        val capture=row().apply {setPadding(dp(14),dp(12),dp(14),dp(12));ProviderGlass.surface(this,17)}
        capture.addView(column().apply {
            addView(text("记录播放器操作",15));addView(text("只记录，写入文件由你决定",12,palette.muted),full(4))
        },LinearLayout.LayoutParams(0,-2,1f))
        capture.addView(CapsuleSwitch(this).apply {
            isChecked=store.enabled();contentDescription="记录播放器操作"
            setOnCheckedChangeListener { _,value -> store.setEnabled(value) }
        });body.addView(capture,full(0,16))
        body.addView(row().apply {
            addView(text("操作记录",13,palette.muted),LinearLayout.LayoutParams(0,-2,1f))
            selectAll=CheckBox(this@PreferenceRecordsActivity).apply {
                text="全选可写入";textSize=12f;setTextColor(palette.muted);buttonTintList=ColorStateList.valueOf(palette.primary)
                contentDescription="全选可写入的记录"
                setOnCheckedChangeListener { _,checked ->
                    if(updatingSelection)return@setOnCheckedChangeListener
                    if(checked)selected.addAll(store.records().filter { it.ready&&it.hasChanges&&it.status!="written" }.map { it.id }) else selected.clear()
                    renderRows()
                }
            };addView(selectAll)
            addView(button("删除记录",false) { confirmDelete(store.records().filter { it.hasChanges }.map { it.id }.toSet()) })
        })
        rows=column();body.addView(rows)
        root.addView(ScrollView(this).apply {addView(body)},LinearLayout.LayoutParams(-1,0,1f))
        root.addView(column().apply {
            setPadding(dp(16),dp(12),dp(16),dp(14));ProviderGlass.surface(this,18)
            count=text("",12,palette.muted);addView(count,full(0,8))
            write=button("调用 Lyrico 写入标签",true) { preview() };write.contentDescription="调用 Lyrico 写入标签"
            addView(write,full())
        })
        setContentView(root)
        WindowCompat.getInsetsController(window,root).apply {
            val light=androidx.core.graphics.ColorUtils.calculateLuminance(palette.background)>.3
            isAppearanceLightStatusBars=light;isAppearanceLightNavigationBars=light
        }
        renderRows()
    }
    override fun onStart(){
        super.onStart();contentResolver.registerContentObserver(ProviderStore.URI,true,observer);renderRows()
        collectionJob = collectionScope.launch {
            while (isActive) { PlayerPreferenceClient.collect(this@PreferenceRecordsActivity); delay(2000) }
        }
    }
    override fun onStop(){collectionJob?.cancel();collectionJob=null;contentResolver.unregisterContentObserver(observer);super.onStop()}
    override fun onDestroy(){collectionScope.cancel();super.onDestroy()}
    override fun onSaveInstanceState(outState: Bundle){outState.putStringArrayList("selected",ArrayList(selected));outState.putString("picking",pickingId);outState.putBoolean("writing",writing);super.onSaveInstanceState(outState)}
    private fun renderRows() {
        if(!::rows.isInitialized)return
        val records=store.records().filter { it.hasChanges }
        selected.retainAll(records.filter {it.ready&&it.status!="written"}.map {it.id}.toSet())
        rows.removeAllViews()
        if(records.isEmpty())rows.addView(text("在已启用的播放器中收藏歌曲或修改星级，记录会显示在这里。",14,palette.muted),full(20))
        records.forEach { r ->
            rows.addView(column().apply {
                ProviderGlass.surface(this,17);setPadding(dp(13),dp(12),dp(13),dp(12))
                addView(row().apply {
                    addView(CheckBox(this@PreferenceRecordsActivity).apply {
                        isEnabled=r.ready&&r.status!="written"&&!writing;isChecked=r.id in selected
                        buttonTintList=ColorStateList.valueOf(palette.primary);contentDescription="选择 ${r.title}"
                        setOnCheckedChangeListener { _,checked ->if(checked)selected.add(r.id)else selected.remove(r.id);updateCount()}
                    },LinearLayout.LayoutParams(dp(36),dp(44)))
                    addView(ImageView(this@PreferenceRecordsActivity).apply {
                        setImageDrawable(ProviderIcon("player",palette.primary));setPadding(dp(8),dp(8),dp(8),dp(8));ProviderGlass.surface(this,10)
                    },LinearLayout.LayoutParams(dp(42),dp(42)).apply {marginEnd=dp(10)})
                    addView(column().apply {
                        addView(text(r.title,15).apply {typeface=Typeface.create("sans-serif-medium",0);maxLines=2})
                        addView(text(r.artist,12,palette.muted),full(3))
                    },LinearLayout.LayoutParams(0,-2,1f))
                    addView(text(when {r.status=="written"->"已写入";!r.ready->"待确认";r.status=="failed"->"未完成";else->"待写入"},11,palette.primary))
                })
                r.favorite?.let { addView(text("MOOD   ${if(it)r.favoriteTime.orEmpty() else "清除收藏时间"}",12),full(10)) }
                r.rating?.let { addView(text("RATING   $it   ·   ${it/20.0} 星",12),full(7)) }
                if(r.ready) addView(button(fileName(r.file!!),false) { details(r) }.apply {textSize=11f;minHeight=dp(34);minimumHeight=dp(34)},full(7))
                else addView(button(if(r.candidates.size>1)"找到 ${r.candidates.size} 个文件 · 确认文件" else "确认歌曲文件",false) { choose(r) },full(7))
                addView(text("${playerName(r.player)} · ${r.file?.source ?: "路径待确认"} · ${SimpleDateFormat("MM-dd HH:mm:ss",Locale.ROOT).format(Date(r.updatedAt))}",11,palette.muted),full(6))
                if(r.message.isNotBlank()) addView(text(r.message,12,palette.muted),full(5))
                addView(button("删除此记录",false) { confirmDelete(setOf(r.id)) }.apply { isEnabled = !writing },full(6))
                setOnLongClickListener {details(r);true}
            },full(0,10))
        }
        updateCount()
    }
    private fun confirmDelete(ids: Set<String>) {
        if (writing || ids.isEmpty()) return
        val targets = selected.intersect(ids).ifEmpty { ids }
        ProviderDialogBuilder(this).setTitle("删除 ${targets.size} 条记录？")
            .setMessage("仅删除操作记录，歌曲文件和已写入的标签保持原样。")
            .setNegativeButton("取消",null).setPositiveButton("删除") { _,_ ->
                store.delete(targets); selected.removeAll(targets); renderRows()
            }.show()
    }
    private fun updateCount(){
        val pending=store.records().count {it.hasChanges&&!it.ready}
        count.text="已选 ${selected.size} 首"+(if(pending>0)"  ·  $pending 首待确认文件" else "")
        write.isEnabled=selected.isNotEmpty()&&!writing
        write.alpha=if(write.isEnabled)1f else .45f
        write.text=if(writing)"等待 Lyrico 返回结果…" else "调用 Lyrico 写入标签"
        val ready=store.records().filter{it.hasChanges&&it.ready&&it.status!="written"}
        updatingSelection=true
        selectAll.isChecked=ready.isNotEmpty()&&ready.all{it.id in selected}
        selectAll.isEnabled=ready.isNotEmpty()&&!writing
        updatingSelection=false
    }
    private fun details(r: PreferenceRecord){
        sheet("记录详情",column().apply {
            addView(text(r.title,17));addView(text("${r.artist} · ${r.album}",12,palette.muted),full(5))
            r.favorite?.let{addView(text("MOOD：${if(it)r.favoriteTime else "清除收藏时间"}",13),full(14))}
            r.rating?.let{addView(text("RATING：$it",13),full(7))}
            addView(text(r.file?.let{it.path.ifBlank{it.uri}} ?: "尚未确认文件",12,palette.muted).apply {setTextIsSelectable(true)},full(14))
            addView(button("重新确认文件",false){choose(r)},full(14))
        })
    }
    private fun choose(r: PreferenceRecord){
        if(r.candidates.size>1){
            val names=r.candidates.map{it.path.ifBlank{it.uri}}.toTypedArray()
            ProviderDialogBuilder(this).setTitle("确认 ${r.title} 的文件").setItems(names){_,which->store.choose(r.id,r.candidates[which]);selected.add(r.id);renderRows()}
                .setNeutralButton("选择其他文件"){_,_->pickingId=r.id;pickFile.launch(arrayOf("audio/*"))}.setNegativeButton("取消",null).show()
        }else{pickingId=r.id;pickFile.launch(arrayOf("audio/*"))}
    }
    private fun preview(){
        val records=store.records().filter{it.id in selected&&it.ready&&it.status!="written"}.take(100)
        if(records.isEmpty())return
        val body=column()
        records.forEach {r->body.addView(text(r.title,15),full(10));r.favorite?.let{body.addView(text("MOOD → ${if(it)r.favoriteTime else "清除收藏时间"}",12,palette.muted),full(4))};r.rating?.let{body.addView(text("RATING → $it",12,palette.muted),full(4))};body.addView(text(r.file!!.path.ifBlank{r.file.uri},11,palette.muted),full(4,8))}
        body.addView(text("仅修改列出的标签，收藏时间使用记录时的时间。",12,palette.muted),full(12))
        val dialog=sheet("写入预览 · ${records.size} 首",body)
        body.addView(button("交给 Lyrico 写入",true){dialog.dismiss();launchWriter(records)},full(16))
    }
    private fun launchWriter(records: List<PreferenceRecord>){
        val packages=listOf("com.lonx.lyrico","com.lonx.lyrico.debug")
        val target=packages.firstOrNull {runCatching{packageManager.getApplicationInfo(it,0)}.isSuccess}
        if(target==null){toast("请先安装支持标签同步的 Lyrico");return}
        val payload=JSONArray()
        records.forEach{r->payload.put(JSONObject().apply{
            put("id",r.id);put("revision",r.revision);put("title",r.title);put("artist",r.artist);put("album",r.album);put("duration",r.duration)
            put("path",r.file!!.path);put("uri",r.file.uri);put("manual",r.file.source=="手动确认")
            r.favorite?.let{put("favorite",it);if(it)put("favoriteTime",r.favoriteTime)};r.rating?.let{put("rating",it)}
        })}
        val intent=Intent("com.lonx.lyrico.action.WRITE_PLAYBACK_PREFERENCES").setComponent(ComponentName(target,"com.lonx.lyrico.playbacktags.PlaybackPreferenceWriteActivity"))
            .putExtra("records",payload.toString())
        // MediaStore URIs supplied by a hooked player are not grants owned by this module.
        // Lyrico obtains its own media permission. Only transfer grants actually held here.
        val owned=contentResolver.persistedUriPermissions.filter{it.isReadPermission}.associateBy{it.uri.toString()}
        fun ownProvider(raw:String)=runCatching { packageManager.resolveContentProvider(Uri.parse(raw).authority ?: "",0)?.applicationInfo?.uid==android.os.Process.myUid() }.getOrDefault(false)
        val granted=records.mapNotNull{it.file?.uri?.takeIf{raw->raw.startsWith("content://")&&(raw in owned || ownProvider(raw))}}.distinct()
        if(granted.isNotEmpty())intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if(granted.isNotEmpty()&&granted.all{owned[it]?.isWritePermission==true || ownProvider(it)})intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        granted.forEach{raw->
            val uri=Uri.parse(raw)
            if(intent.clipData==null)intent.clipData=ClipData.newRawUri("歌曲文件",uri)else intent.clipData!!.addItem(ClipData.Item(uri))
        }
        try{writing=true;updateCount();writer.launch(intent)}catch(e:Exception){writing=false;updateCount();toast("无法调用 Lyrico，请安装新版后重试")}
    }
    private fun sheet(title:String,body:LinearLayout):BottomSheetDialog {
        val dialog=BottomSheetDialog(this)
        val panel=column().apply{setPadding(dp(18),dp(18),dp(18),dp(24));background=ProviderGlass.solid(this@PreferenceRecordsActivity,palette.popup,24,false)
            addView(row().apply{addView(text(title,19),LinearLayout.LayoutParams(0,-2,1f));addView(button("关闭",false){dialog.dismiss()})},full(0,12))
            addView(ScrollView(this@PreferenceRecordsActivity).apply{addView(body)},LinearLayout.LayoutParams(-1,-2))}
        dialog.setContentView(panel);dialog.show()
        dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.background=null
        dialog.behavior.maxHeight=resources.displayMetrics.heightPixels*4/5
        dialog.behavior.state=com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        return dialog
    }
    private fun fileName(file:PreferenceFile)=file.path.substringAfterLast('/').ifBlank{runCatching{contentResolver.query(Uri.parse(file.uri),arrayOf("_display_name"),null,null,null)?.use{if(it.moveToFirst())it.getString(0)else null}}.getOrNull()?:"查看文件位置"}
    private fun playerName(pkg:String)=when(pkg){"com.neutroncode.mp"->"Neutron";"ink.trantor.coneplayer.gp","ink.trantor.coneplayer"->"光锥音乐";"com.maxmpz.audioplayer"->"Poweramp";else->pkg}
    private fun column()=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
    private fun row()=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
    private fun text(value:String,size:Int,color:Int=palette.text)=TextView(this).apply{text=value;textSize=size.toFloat();setTextColor(color);includeFontPadding=false;setLineSpacing(dp(2).toFloat(),1f)}
    private fun button(value:String,primary:Boolean,click:()->Unit)=MaterialButton(this).apply{
        text=value;isAllCaps=false;textSize=13f;stateListAnimator=null;elevation=0f;translationZ=0f;cornerRadius=dp(22);insetTop=0;insetBottom=0;minHeight=dp(44)
        backgroundTintList=ColorStateList.valueOf(if(primary)palette.primary else palette.surface);setTextColor(if(primary)palette.onPrimary else palette.text);setOnClickListener{click()}
    }
    private fun full(top:Int=0,bottom:Int=0)=LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(top);bottomMargin=dp(bottom)}
    private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
    private fun toast(message:String){Toast.makeText(this,message,Toast.LENGTH_LONG).show()}
}
