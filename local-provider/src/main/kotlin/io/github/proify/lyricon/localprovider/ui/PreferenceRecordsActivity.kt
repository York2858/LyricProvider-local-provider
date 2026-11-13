package io.github.proify.lyricon.localprovider.ui

import android.app.Activity
import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.database.ContentObserver
import android.graphics.Typeface
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.text.TextUtils
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
    private lateinit var filters: LinearLayout
    private lateinit var delete: MaterialButton
    private lateinit var sortButton: MaterialButton
    private lateinit var multiButton: MaterialButton
    private var query = ""
    private var statusFilter = "全部"
    private var kindFilter = "全部操作"
    private var ascending = false
    private var multiMode = false
    private var visibleLimit = 50
    private val artworkCache = android.util.LruCache<String, android.graphics.Bitmap>(24)
    private val artworkMisses = mutableSetOf<String>()
    private var lastRendered: List<PreferenceRecord>? = null
    private var updatingSelection=false
    private var pickingId: String? = null
    private var writing = false
    private var collectionJob: Job? = null
    private val collectionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val palette get():ProviderPalette {
        val theme=ProviderTheme.colors(this)
        val dark=resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        return if(dark)theme.copy(background=0xFF15171C.toInt(),surface=0xFF202329.toInt(),popup=0xFF272B32.toInt(),text=0xFFF0F1F4.toInt(),muted=0xFFA1A7B2.toInt(),line=0xFF383D47.toInt())
            else theme.copy(background=0xFFF6F7FA.toInt(),surface=Color.WHITE,popup=Color.WHITE,text=0xFF222833.toInt(),muted=0xFF7C8490.toInt(),line=0xFFD9DDE5.toInt())
    }
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { if(store.records()!=lastRendered)renderRows() }
    }
    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = pickingId
        pickingId = null
        if(uri != null && id != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            store.choose(id, PreferenceFile(uri=uri.toString(), source="手动确认"))
            selected.add(id); multiMode=true;renderRows()
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
        query=savedInstanceState?.getString("query").orEmpty()
        statusFilter=savedInstanceState?.getString("filter") ?: "全部"
        kindFilter=savedInstanceState?.getString("kind") ?: "全部操作"
        ascending=savedInstanceState?.getBoolean("ascending") ?: false
        multiMode=savedInstanceState?.getBoolean("multi") ?: selected.isNotEmpty()
        val root=column().apply { setBackgroundColor(palette.background) }
        WindowCompat.setDecorFitsSystemWindows(window,false)
        window.statusBarColor=Color.TRANSPARENT;window.navigationBarColor=Color.TRANSPARENT
        ViewCompat.setOnApplyWindowInsetsListener(root) { view,insets ->
            val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars());view.setPadding(bars.left,bars.top,bars.right,bars.bottom);insets
        }
        root.addView(row().apply {
            setPadding(dp(8),dp(8),dp(12),dp(8))
            addView(iconButton("back","返回") { finish() },LinearLayout.LayoutParams(dp(44),dp(44)))
            addView(text("收藏与评分",22).apply { typeface=Typeface.create("sans-serif-medium",0) },LinearLayout.LayoutParams(0,-2,1f))
            addView(iconButton("more","更多记录操作") { showOptions() },LinearLayout.LayoutParams(dp(44),dp(44)))
        })
        val body=column().apply { setPadding(dp(16),dp(8),dp(16),dp(16)) }
        body.addView(row().apply {
            setPadding(dp(14),dp(16),dp(12),dp(16));surface(this,20)
            addView(ImageView(this@PreferenceRecordsActivity).apply {
                setImageDrawable(ProviderIcon("heart",palette.primary));setPadding(dp(9),dp(9),dp(9),dp(9))
                background=ProviderGlass.solid(this@PreferenceRecordsActivity,soft(palette.primary),12,false)
            },LinearLayout.LayoutParams(dp(44),dp(44)).apply {marginEnd=dp(12)})
            addView(column().apply {
                addView(text("记录播放器操作",16).apply {typeface=Typeface.DEFAULT_BOLD})
                addView(text("收藏与星级会保存在这里",11,palette.muted),full(5))
            },LinearLayout.LayoutParams(0,-2,1f))
            addView(CapsuleSwitch(this@PreferenceRecordsActivity).apply {
                isChecked=store.enabled();contentDescription="记录播放器操作"
                setOnCheckedChangeListener { _,value -> collectionScope.launch { withContext(Dispatchers.IO) { store.setEnabled(value) } } }
            })
        },full(0,16))
        body.addView(row().apply {
            surface(this,18);setPadding(dp(12),dp(2),dp(4),dp(2))
            addView(ImageView(this@PreferenceRecordsActivity).apply {setImageDrawable(ProviderIcon("search",palette.muted))},LinearLayout.LayoutParams(dp(22),dp(22)))
            addView(EditText(this@PreferenceRecordsActivity).apply {
                hint="搜索歌曲、歌手";contentDescription="搜索歌曲、歌手";textSize=14f;setSingleLine();background=null
                setTextColor(palette.text);setHintTextColor(palette.muted);setPadding(dp(10),dp(10),dp(4),dp(10));setText(query)
                addTextChangedListener(object:TextWatcher {
                    override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
                    override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) {query=s.toString();visibleLimit=50;renderRows()}
                    override fun afterTextChanged(s:Editable?) {}
                })
            },LinearLayout.LayoutParams(0,dp(48),1f))
            addView(iconButton("sliders","筛选操作类型") { showKindFilter() },LinearLayout.LayoutParams(dp(44),dp(44)))
        },full(0,12))
        filters=row()
        body.addView(HorizontalScrollView(this).apply {isHorizontalScrollBarEnabled=false;addView(filters)},full(0,12))
        body.addView(row().apply {
            addView(text("最近记录",18).apply {typeface=Typeface.DEFAULT_BOLD},LinearLayout.LayoutParams(0,-2,1f))
            sortButton=button("时间 ↓",false) { ascending=!ascending;renderRows() }.apply {backgroundTintList=ColorStateList.valueOf(Color.TRANSPARENT)};addView(sortButton)
            multiButton=button(if(multiMode)"取消多选" else "多选",false) {
                multiMode=!multiMode;if(!multiMode)selected.clear();renderRows()
            }.apply {backgroundTintList=ColorStateList.valueOf(Color.TRANSPARENT);setTextColor(palette.primary)};addView(multiButton)
        },full(0,10))
        rows=column();body.addView(rows)
        root.addView(ScrollView(this).apply {isFillViewport=true;addView(body)},LinearLayout.LayoutParams(-1,0,1f))
        root.addView(column().apply {
            setPadding(dp(16),dp(8),dp(16),dp(10));surface(this,20)
            addView(row().apply {
                selectAll=CheckBox(this@PreferenceRecordsActivity).apply {
                    text="全选";textSize=13f;setTextColor(palette.text);buttonTintList=ColorStateList.valueOf(palette.primary)
                    contentDescription="全选当前筛选记录"
                    setOnCheckedChangeListener { _,checked ->
                        if(updatingSelection)return@setOnCheckedChangeListener
                        multiMode=true
                        val ids=visibleRecords().map { it.id }.toSet()
                        if(checked)selected.addAll(ids)else selected.removeAll(ids)
                        renderRows()
                    }
                };addView(selectAll)
                count=text("",12,palette.muted);addView(count,LinearLayout.LayoutParams(0,-2,1f).apply {marginStart=dp(12)})
            },full(0,4))
            addView(row().apply {
                delete=button("删除记录",false) { confirmDelete(selected.toSet()) }
                delete.strokeWidth=dp(1);delete.strokeColor=ColorStateList.valueOf(palette.line)
                addView(delete,LinearLayout.LayoutParams(0,dp(46),1f).apply {marginEnd=dp(10)})
                write=button("写入标签",true) { preview() };write.contentDescription="调用 Lyrico 写入标签"
                addView(write,LinearLayout.LayoutParams(0,dp(46),1.6f))
            })
            addView(text("由 Lyrico 写入，保留原始音频",10,palette.muted).apply {gravity=Gravity.CENTER},full(7))
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
    override fun onSaveInstanceState(outState: Bundle){outState.putString("query",query);outState.putString("filter",statusFilter);outState.putString("kind",kindFilter);outState.putBoolean("ascending",ascending);outState.putBoolean("multi",multiMode);outState.putStringArrayList("selected",ArrayList(selected));outState.putString("picking",pickingId);outState.putBoolean("writing",writing);super.onSaveInstanceState(outState)}
    private fun visibleRecords(): List<PreferenceRecord> = store.records().filter { it.hasChanges }
        .filter { record ->
            (statusFilter=="全部" || state(record)==statusFilter) &&
                (query.isBlank() || listOf(record.title,record.artist,record.album,record.file?.path.orEmpty()).any { it.contains(query.trim(),true) }) &&
                when(kindFilter) { "收藏操作" -> record.favorite!=null; "评分操作" -> record.rating!=null; else -> true }
        }.let { if(ascending)it.sortedBy { r->r.updatedAt }else it.sortedByDescending { r->r.updatedAt } }
    private fun state(r:PreferenceRecord)=when {r.status=="written"->"已写入";!r.ready->"待确认";r.status=="failed"->"未完成";else->"待写入"}
    private fun stateColor(r:PreferenceRecord)=when(state(r)) {"已写入"->0xFF278268.toInt();"待确认"->0xFFAC701A.toInt();"未完成"->0xFFBD4F49.toInt();else->palette.primary}
    private fun renderRows() {
        if(!::rows.isInitialized)return
        val all=store.records();lastRendered=all
        selected.retainAll(all.filter {it.hasChanges}.map {it.id}.toSet())
        val records=visibleRecords()
        filters.removeAllViews()
        listOf("全部","待写入","待确认","已写入","未完成").forEach { label ->
            val total=all.count {it.hasChanges&&(label=="全部"||state(it)==label)}
            filters.addView(button("$label $total",false) {statusFilter=label;visibleLimit=50;renderRows()}.apply {
                contentDescription="筛选$label";isSelected=statusFilter==label
                backgroundTintList=ColorStateList.valueOf(if(isSelected)soft(palette.primary)else palette.surface)
                setTextColor(if(isSelected)palette.primary else palette.muted)
                setPadding(dp(14),0,dp(14),0)
            },LinearLayout.LayoutParams(-2,dp(42)).apply {marginEnd=dp(6)})
        }
        sortButton.text=if(ascending)"时间 ↑" else "时间 ↓"
        sortButton.contentDescription=if(ascending)"时间从早到晚" else "时间从晚到早"
        multiButton.text=if(multiMode)"取消多选"else "多选"
        rows.removeAllViews()
        if(records.isEmpty()) rows.addView(column().apply {
            setPadding(dp(24),dp(40),dp(24),dp(40));surface(this,20)
            addView(text(if(all.none {it.hasChanges})"还没有操作记录"else"没有符合条件的记录",17).apply {gravity=Gravity.CENTER})
            addView(text(if(all.none {it.hasChanges})"在已启用的播放器中收藏歌曲或修改评分，\n记录会保存在这里，随后可选择写入文件。"else"试试其他关键词或切换筛选条件",13,palette.muted).apply {gravity=Gravity.CENTER},full(10))
        },full(4))
        records.take(visibleLimit).forEach { r -> rows.addView(recordCard(r),full(0,10)) }
        if(records.size>visibleLimit) rows.addView(button("加载更多 · 还有 ${records.size-visibleLimit} 条",false) {visibleLimit+=50;renderRows()},full())
        updateCount()
    }
    private fun recordCard(r:PreferenceRecord):LinearLayout = column().apply {
        surface(this,20);setPadding(dp(12),dp(14),dp(12),dp(12))
        addView(row().apply {
            if(multiMode)addView(CheckBox(this@PreferenceRecordsActivity).apply {
                isEnabled=!writing;isChecked=r.id in selected;buttonTintList=ColorStateList.valueOf(palette.primary)
                contentDescription="选择 ${r.title}"
                setOnCheckedChangeListener { _,checked->if(checked)selected.add(r.id)else selected.remove(r.id);updateCount() }
            },LinearLayout.LayoutParams(dp(34),dp(44)))
            addView(ImageView(this@PreferenceRecordsActivity).apply {
                background=ProviderGlass.solid(this@PreferenceRecordsActivity,soft(palette.primary),12,false);clipToOutline=true
                contentDescription="${r.title} 封面";setImageDrawable(ProviderIcon("music",palette.muted));setPadding(dp(14),dp(14),dp(14),dp(14))
                loadCover(this,r)
            },LinearLayout.LayoutParams(dp(58),dp(64)).apply {marginEnd=dp(12)})
            addView(column().apply {
                addView(text(r.title,16).apply {typeface=Typeface.DEFAULT_BOLD;maxLines=2;ellipsize=TextUtils.TruncateAt.END})
                addView(text(listOf(r.artist,r.album).filter {it.isNotBlank()}.joinToString(" · ").ifBlank {"未知歌手"},12,palette.muted).apply {maxLines=1;ellipsize=TextUtils.TruncateAt.END},full(4))
                addView(text(listOfNotNull(r.favorite?.let {if(it)"♡ 已收藏"else "已取消收藏"},r.rating?.let {"评分 $it"}).joinToString("  ·  "),12,palette.primary),full(7))
            },LinearLayout.LayoutParams(0,-2,1f))
            addView(text(state(r),10,stateColor(r)).apply {
                background=ProviderGlass.solid(this@PreferenceRecordsActivity,soft(stateColor(r)),12,false)
                setPadding(dp(8),dp(5),dp(8),dp(5))
            },LinearLayout.LayoutParams(-2,-2).apply {gravity=Gravity.TOP;marginStart=dp(4)})
        })
        addView(row().apply {
            setPadding(dp(10),dp(5),dp(8),dp(5));minimumHeight=dp(38)
            background=ProviderGlass.solid(this@PreferenceRecordsActivity,if(r.ready)soft(palette.muted)else soft(stateColor(r)),10,false)
            addView(ImageView(this@PreferenceRecordsActivity).apply {setImageDrawable(ProviderIcon(if(r.ready)"folder"else"info",if(r.ready)palette.muted else stateColor(r)))},LinearLayout.LayoutParams(dp(17),dp(17)).apply {marginEnd=dp(7)})
            addView(text(if(r.ready)r.file!!.path.removePrefix("/storage/emulated/0/").ifBlank {"已确认歌曲文件"}else "请确认歌曲文件",11,if(r.ready)palette.muted else stateColor(r)).apply {maxLines=1;ellipsize=TextUtils.TruncateAt.MIDDLE},LinearLayout.LayoutParams(0,-2,1f))
            addView(text(if(r.ready)"›"else "确认文件 →",12,palette.primary))
            contentDescription=if(r.ready)"查看 ${r.title} 的文件"else"确认 ${r.title} 的文件"
            isEnabled=!writing;setOnClickListener {if(r.ready)details(r)else choose(r)}
        },full(11))
        addView(text("${SimpleDateFormat("MM-dd HH:mm",Locale.ROOT).format(Date(r.updatedAt))} · ${playerName(r.player)}",11,palette.muted),full(7))
        if(r.message.isNotBlank())addView(text(r.message,11,if(r.status=="failed")stateColor(r)else palette.muted).apply {maxLines=2;ellipsize=TextUtils.TruncateAt.END},full(5))
        setOnClickListener {if(multiMode&&!writing) {if(!selected.add(r.id))selected.remove(r.id);renderRows()} else details(r)}
        setOnLongClickListener {if(!writing) {multiMode=true;selected.add(r.id);multiMode=true;renderRows()};true}
    }
    private fun loadCover(view:ImageView,r:PreferenceRecord) {
        val file=r.file?:return
        val key=file.path.ifBlank {file.uri}
        fun show(bitmap:android.graphics.Bitmap) {view.setPadding(0,0,0,0);view.scaleType=ImageView.ScaleType.CENTER_CROP;view.setImageBitmap(bitmap)}
        artworkCache.get(key)?.let {show(it);return}
        if(key in artworkMisses)return
        collectionScope.launch {
            val bitmap=withContext(Dispatchers.IO) {
                runCatching {
                    val retriever=android.media.MediaMetadataRetriever()
                    try {
                        if(file.uri.isNotBlank())retriever.setDataSource(this@PreferenceRecordsActivity,Uri.parse(file.uri))else retriever.setDataSource(file.path)
                        retriever.embeddedPicture?.let { bytes ->
                            val bounds=android.graphics.BitmapFactory.Options().apply {inJustDecodeBounds=true}
                            android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
                            val options=android.graphics.BitmapFactory.Options().apply {inSampleSize=(maxOf(bounds.outWidth,bounds.outHeight)/180).coerceAtLeast(1)}
                            android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
                        }
                    }finally {retriever.release()}
                }.getOrNull()
            }
            if(bitmap!=null) {artworkCache.put(key,bitmap);if(view.isAttachedToWindow)show(bitmap)}else artworkMisses.add(key)
        }
    }
    private fun confirmDelete(ids:Set<String>) {
        if(writing||ids.isEmpty())return
        ProviderDialogBuilder(this).setTitle("删除 ${ids.size} 条记录？")
            .setMessage("仅删除操作记录，歌曲文件和已写入的标签保持原样。")
            .setNegativeButton("取消",null).setPositiveButton("删除") {_,_->store.delete(ids);selected.removeAll(ids);renderRows()}.show()
    }
    private fun updateCount() {
        val chosen=store.records().filter {it.hasChanges&&it.id in selected}
        val ready=chosen.count {it.ready&&it.status!="written"}
        val unresolved=chosen.count {!it.ready&&it.status!="written"}
        count.text="已选 ${chosen.size} 首"+when {writing->"\n等待 Lyrico 返回结果…";unresolved>0->"\n${unresolved} 首需确认文件";chosen.any {it.status=="written"}->"\n已写入记录可删除";else->"\n$ready 首可写入"}
        write.isEnabled=ready>0&&!writing;write.alpha=if(write.isEnabled)1f else .45f
        write.text=if(writing)"写入中…"else "写入标签 · ${minOf(ready,100)} 首"
        delete.isEnabled=chosen.isNotEmpty()&&!writing
        val visible=visibleRecords()
        updatingSelection=true
        selectAll.isChecked=visible.isNotEmpty()&&visible.all {it.id in selected}
        selectAll.isEnabled=visible.isNotEmpty()&&!writing
        updatingSelection=false
        multiButton.isEnabled=!writing
    }
    private fun showKindFilter() {
        val values=arrayOf("全部操作","收藏操作","评分操作")
        ProviderDialogBuilder(this).setTitle("筛选操作类型").setSingleChoiceItems(values,values.indexOf(kindFilter)) {dialog,index->kindFilter=values[index];visibleLimit=50;dialog.dismiss();renderRows()}.setNegativeButton("取消",null).show()
    }
    private fun showOptions() {
        ProviderDialogBuilder(this).setTitle("记录管理").setItems(arrayOf("刷新播放器记录","全选可写入","清除筛选条件","删除已写入记录")) {_,index->
            when(index) {
                0->collectionScope.launch {PlayerPreferenceClient.collect(this@PreferenceRecordsActivity);renderRows();toast("记录已刷新")}
                1->{multiMode=true;selected.addAll(visibleRecords().filter {it.ready&&it.status!="written"}.map {it.id});renderRows()}
                2->{kindFilter="全部操作";statusFilter="全部";query="";recreate()}
                3->confirmDelete(store.records().filter {it.hasChanges&&it.status=="written"}.map {it.id}.toSet())
            }
        }.show()
    }
    private fun soft(color:Int)=androidx.core.graphics.ColorUtils.blendARGB(palette.surface,color,.09f)
    private fun surface(view:View,radius:Int) {view.background=ProviderGlass.solid(this,palette.surface,radius,false)}
    private fun iconButton(icon:String,label:String,click:()->Unit)=ImageButton(this).apply {background=null;setImageDrawable(ProviderIcon(icon,palette.primary));contentDescription=label;setOnClickListener {click()}}
    private fun details(r: PreferenceRecord){
        val body=column()
        val dialog=sheet("记录详情",body)
        body.apply {
            addView(text(r.title,17));addView(text("${r.artist} · ${r.album}",12,palette.muted),full(5))
            r.favorite?.let{addView(text("MOOD：${if(it)r.favoriteTime else "清除收藏时间"}",13),full(14))}
            r.rating?.let{addView(text("RATING：$it",13),full(7))}
            addView(text(r.file?.let{it.path.ifBlank{it.uri}} ?: "尚未确认文件",12,palette.muted).apply {setTextIsSelectable(true)},full(14))
            addView(button("重新确认文件",false){dialog.dismiss();choose(r)}.apply {isEnabled=!writing},full(14))
            addView(button("删除此记录",false){dialog.dismiss();confirmDelete(setOf(r.id))}.apply {isEnabled=!writing},full(8))
        }
    }
    private fun choose(r: PreferenceRecord){
        if(writing)return
        if(r.candidates.size>1){
            val names=r.candidates.map{it.path.ifBlank{it.uri}}.toTypedArray()
            ProviderDialogBuilder(this).setTitle("确认 ${r.title} 的文件").setItems(names){_,which->store.choose(r.id,r.candidates[which]);selected.add(r.id);multiMode=true;renderRows()}
                .setNeutralButton("选择其他文件"){_,_->pickingId=r.id;pickFile.launch(arrayOf("audio/*"))}.setNegativeButton("取消",null).show()
        }else{pickingId=r.id;pickFile.launch(arrayOf("audio/*"))}
    }
    private fun preview(){
        val eligible=store.records().filter{it.id in selected&&it.hasChanges&&it.ready&&it.status!="written"}
        val records=eligible.take(100)
        if(records.isEmpty())return
        val body=column()
        if(eligible.size>100)body.addView(text("每批最多写入 100 首，其余 ${eligible.size-100} 首会保留选择，可在本批完成后继续。",12,palette.muted),full(8))
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
        try{writing=true;renderRows();writer.launch(intent)}catch(e:Exception){writing=false;updateCount();toast("无法调用 Lyrico，请安装新版后重试")}
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
    private fun playerName(pkg:String)=when(pkg){"com.neutroncode.mp"->"Neutron";"ink.trantor.coneplayer.gp","ink.trantor.coneplayer"->"光锥音乐";"com.maxmpz.audioplayer"->"Poweramp";"yos.music.player"->"Yos";else->pkg.substringAfterLast('.')}
    private fun column()=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
    private fun row()=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
    private fun text(value:String,size:Int,color:Int=palette.text)=TextView(this).apply{text=value;textSize=size.toFloat();setTextColor(color);includeFontPadding=false;setLineSpacing(dp(2).toFloat(),1f)}
    private fun button(value:String,primary:Boolean,click:()->Unit)=MaterialButton(this).apply{
        text=value;isAllCaps=false;textSize=13f;minWidth=0;minimumWidth=0;setPadding(dp(12),0,dp(12),0);stateListAnimator=null;elevation=0f;translationZ=0f;cornerRadius=dp(16);insetTop=0;insetBottom=0;minHeight=dp(44)
        backgroundTintList=ColorStateList.valueOf(if(primary)palette.primary else palette.surface);setTextColor(if(primary)palette.onPrimary else palette.text);setOnClickListener{click()}
    }
    private fun full(top:Int=0,bottom:Int=0)=LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(top);bottomMargin=dp(bottom)}
    private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
    private fun toast(message:String){Toast.makeText(this,message,Toast.LENGTH_LONG).show()}
}
