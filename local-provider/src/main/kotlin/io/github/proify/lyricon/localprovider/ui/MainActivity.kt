package io.github.proify.lyricon.localprovider.ui

import io.github.proify.lyricon.localprovider.lyrics.LyricTiming
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.database.ContentObserver
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.InputType
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.doOnLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton

import io.github.proify.cloudlyric.provider.LyricsConfig
import io.github.proify.lyricon.localprovider.BuildConfig
import io.github.proify.lyricon.localprovider.R
import io.github.proify.lyricon.localprovider.data.*
import io.github.proify.lyricon.lyric.model.Song
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import io.github.proify.lyricon.localprovider.search.*
import io.github.proify.lyricon.localprovider.artwork.ArtworkLoader
import io.github.proify.lyricon.localprovider.preview.PreviewTimeline
import kotlinx.coroutines.*
import java.io.File

class MainActivity : AppCompatActivity() {
    private val store by lazy { ProviderStore(this) }
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var heading: TextView
    private lateinit var subtitle: TextView
    private lateinit var back: ImageButton
    private lateinit var nav: LinearLayout
    private lateinit var footerTools: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var pageHeader: LinearLayout
    private lateinit var headerAction: ImageButton
    private var tab = "home"
    private var page = "home"
    private val history = ArrayDeque<String>()
    private var files = emptyList<LyricFile>()
    private var scanning = false
    private var query = ""
    private var filter = "全部"
    private var sort = "最近更新"
    private var selected: LyricFile? = null
    private var detailSong: Song? = null
    private var detailOffset = 0L
    private var originalOnly = false
    private var choosing = false
    private val searchScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var searchJob: Job? = null
    private var onlineQuery = ""
    private var searchReference = MatchTarget()
    private var searchSongKey = ""
    private var searchRange = "全部"
    private var searchFilter = "全部"
    private var searchSource = "全部来源"
    private var searchFiltersExpanded = false
    private var onlineResults = emptyList<SearchCandidate>()
    private val searchErrors = linkedMapOf<String, String>()
    private var searchedProviders = 0
    private var totalProviders = 0
    private var onlineSession = OnlineSearch(searchScope)
    private var previewLoadJob: Job? = null
    private var previewLoading = false
    private var previewLoadVersion = 0
    private var activeProviderIds = emptyList<String>()
    private val searchPageSize = 6
    private var detectionStopped = false
    private var searchRunning = false
    private var searchGeneration = 0
    private var searchPerformed = false
    private var searchedQuery = ""
    private val expandedGroups = mutableSetOf<String>()
    private var candidate: SearchCandidate? = null
    private var rememberChoice = true
    private var showRoma = false
    private var showTranslation = false
    private var savingSelection = false
    private var foreground = false
    private var previewTrack = ""
    private var previewPlayer = ""
    private var previewRuntime = RuntimeSnapshot()
    private var followPlayback = true
    private var previewTimeline: io.github.proify.lyricon.localprovider.preview.PreviewTimeline? = null
    private val previewRows = mutableListOf<Pair<View, TextView>>()
    private var previewPosition: io.github.proify.lyricon.localprovider.preview.PreviewTimeline.Position? = null
    private var previewVisualLine = -1
    private var forcePreviewScroll = false
    private var followButton: MaterialButton? = null
    private val previewOwner = java.util.UUID.randomUUID().toString()
    private var previewSubscribed = false
    private val previewLease = object : Runnable {
        override fun run() {
            if (!foreground || page != "detail") return
            store.previewDemand(previewOwner, true)
            main.postDelayed(this, 10_000)
        }
    }
    private fun syncPreviewDemand() {
        val active = foreground && page == "detail"
        if (active == previewSubscribed) return
        previewSubscribed = active
        main.removeCallbacks(previewLease)
        if (active) previewLease.run() else store.previewDemand(previewOwner, false)
    }
    private val previewTick = object : Runnable {
        override fun run() {
            if (!foreground || page != "detail") return
            updatePreviewPosition()
            val playback = previewPlayback() ?: return
            val duration = previewRuntime.song?.duration ?: 0
            val position = playback.positionAt(android.os.SystemClock.elapsedRealtime(), duration)
            if (duration > 0 && position >= duration) return
            val delay = previewTimeline?.nextDelay(position, if (candidate != null) detailOffset else 0,
                playback.playing, playback.speed) ?: return
            main.postDelayed(this, delay)
        }
    }
    private val receiver by lazy { ReceiverCheck(this) { updateReceiverSection() } }
    private val observer = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) = onChange(selfChange, null)
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            if (uri == ProviderStore.PLAYBACK_URI) {
                if (page == "detail") restartPreviewTick()
                return
            }
            if (page == "detail") { previewRuntime = store.snapshot(); restartPreviewTick() }
            if (page in setOf("home", "logs", "diagnosis", "players")) render()
            else if (page == "search") {
                val snapshot = store.snapshot()
                content.findViewWithTag<View>("playing:${trackKey(snapshot.song?.name, snapshot.song?.artist)}")
                    ?.findViewWithTag<ImageView>("songCover")?.let {
                        ArtworkLoader.bind(it, playingArtwork(snapshot))
                    }
            }
        }
    }
    private val palette get() = ProviderTheme.colors(this)
    private val ink get() = palette.text
    private val muted get() = palette.muted
    private val purple get() = palette.primary
    private val surface get() = palette.surface
    private val tint get() = palette.tint
    private val bg get() = palette.background
    private val green get() = palette.primary
    private var themeSignature = ""
    private val titles = mapOf("home" to "当前歌词", "library" to "歌词库", "settings" to "设置",
        "folders" to "歌词目录", "detail" to "歌词预览", "diagnosis" to "匹配详情",
        "players" to "播放器管理", "cloud" to "云端歌词源", "logs" to "运行记录", "rules" to "歌词过滤",
        "search" to "搜索歌词", "matching" to "匹配评分规则", "appearance" to "外观与布局", "saving" to "保存规则")

    override fun onCreate(savedInstanceState: Bundle?) {
        delegate.localNightMode = ProviderTheme.nightMode(this)
        super.onCreate(savedInstanceState)
        ProviderTheme.install(this)
        themeSignature = ProviderTheme.signature(this)
        tab = savedInstanceState?.getString("tab") ?: "home"
        page = savedInstanceState?.getString("page") ?: tab
        savedInstanceState?.getStringArrayList("history")?.let { history.addAll(it) }
        root = column().apply { background = ProviderGlass.page(this@MainActivity) }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor=android.graphics.Color.TRANSPARENT; window.navigationBarColor=android.graphics.Color.TRANSPARENT
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        val header = row().apply { setPadding(dp(12), dp(4), dp(16), dp(6)) }; pageHeader = header
        back = iconButton("back", "返回") { goBack() }
        header.addView(back)
        heading = text("当前歌词", 20).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
        subtitle = text("本地歌词支持", 12, muted)
        header.addView(column().apply {
            addView(heading); addView(subtitle, full(4))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        headerAction=iconButton("info","歌词信息") { if(page=="detail") fileInfo() else open("diagnosis") }
        header.addView(headerAction)
        root.addView(header)
        scroll = ScrollView(this).apply { isFillViewport = false; clipToPadding = false }
        content = column().apply { setPadding(dp(16), 0, dp(16), dp(16)) }
        scroll.setOnTouchListener { _, event ->
            if (page == "detail" && event.actionMasked == android.view.MotionEvent.ACTION_MOVE && followPlayback) {
                followPlayback = false; updateFollowButton()
            }
            false
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        footerTools=column()
        root.addView(footerTools,LinearLayout.LayoutParams(-1,-2).apply { setMargins(dp(16),dp(4),dp(16),0) })
        nav = row().apply { setPadding(dp(5), dp(4), dp(5), dp(4)); ProviderGlass.surface(this,18) }
        root.addView(nav, LinearLayout.LayoutParams(-1,-2).apply { setMargins(dp(16),dp(4),dp(16),dp(8)) })
        setContentView(root)
        updateSystemBars()
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (page == tab && tab == "home") finish() else goBack() }
        })
        store.connectPlayers()
        if (tab == "search") prepareSearch()
        render()
        scan(false)
    }
    private fun updateSystemBars() {
        val light=androidx.core.graphics.ColorUtils.calculateLuminance(palette.background)>.3
        WindowCompat.getInsetsController(window,root).apply { isAppearanceLightStatusBars=light; isAppearanceLightNavigationBars=light }
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if(hasFocus && ::root.isInitialized) updateSystemBars()
    }
    override fun onStart() { super.onStart(); foreground = true; previewRuntime = store.snapshot(); syncSearchWork(); if (page == "search" || page == "detail") render(); restartPreviewTick(); contentResolver.registerContentObserver(ProviderStore.URI, true, observer); if (page == "diagnosis") receiver.start() }
    override fun onStop() { foreground = false; syncPreviewDemand(); syncSearchWork(); main.removeCallbacks(previewTick); receiver.stop(); contentResolver.unregisterContentObserver(observer); super.onStop() }
    override fun onDestroy() { onlineSession.close(); searchScope.cancel(); io.shutdownNow(); main.removeCallbacksAndMessages(null); super.onDestroy() }
    override fun onResume() {
        super.onResume()
        if (themeSignature.isNotBlank() && themeSignature != ProviderTheme.signature(this)) recreate()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("tab", tab); outState.putString("page", page)
        outState.putStringArrayList("history", ArrayList(history))
        super.onSaveInstanceState(outState)
    }
    private fun open(next: String) { history.addLast(page); page = next; render(); scroll.scrollTo(0, 0) }
    private fun goBack() {
        page = if (history.isNotEmpty()) history.removeLast() else if (page != tab) tab else "home"
        if (page == "home") tab = "home"
        render(); scroll.scrollTo(0, 0)
    }
    private fun render() {
        if (isDestroyed) return
        updateSystemBars()
        syncSearchWork()
        if (page != "detail" && previewLoadJob?.isActive == true) {
            previewLoadVersion++; previewLoadJob?.cancel(); previewLoading = false
        }
        heading.text = titles[page] ?: "本地歌词支持"
        subtitle.visibility = if(page=="library") View.VISIBLE else View.GONE
        heading.gravity=if(page in setOf("home","search","library","settings")) Gravity.START else Gravity.CENTER
        val summary=(SavedLyrics(this).files()+files).distinctBy { it.uri }
        subtitle.text="${summary.groupBy { trackKey(it.song.name,it.song.artist) }.size} 首歌曲 · ${summary.size} 份歌词"
        headerAction.visibility=when { page=="detail" || page=="home" -> View.VISIBLE
            page !in setOf("home","search","library","settings") -> View.INVISIBLE; else -> View.GONE }
        headerAction.setImageDrawable(ProviderIcon(if(page=="home") "levels" else "info",ink))
        headerAction.contentDescription=if(page=="detail") "歌词信息" else "匹配诊断"
        back.visibility = if (page in setOf("home", "search", "library", "settings")) View.GONE else View.VISIBLE
        if (page != "diagnosis") receiver.stop()
        main.removeCallbacks(previewTick)
        previewRows.clear(); previewTimeline = null; previewPosition = null; previewVisualLine = -1; followButton = null
        content.removeAllViews(); footerTools.removeAllViews(); footerTools.visibility=View.GONE
        when (page) {
            "home" -> home(); "library" -> library(); "settings" -> settings(); "folders" -> folders()
            "detail" -> detail(); "diagnosis" -> diagnosis(); "players" -> players(); "cloud" -> cloud()
            "rules" -> rules(); "logs" -> logs()
            "search" -> searchPage(); "matching" -> matchingPage(); "appearance" -> appearance(); "saving" -> savingPage()
        }
        restartPreviewTick()
        if (page == "detail") content.doOnLayout { if (foreground && page == "detail") restartPreviewTick() }
        nav.removeAllViews()
        if (page == "detail") {
            nav.visibility = View.VISIBLE; nav.orientation = LinearLayout.VERTICAL
            renderDetailActions()
            footerTools.visibility=if(footerTools.childCount>0) View.VISIBLE else View.GONE
            return
        }
        nav.setPadding(dp(5),dp(4),dp(5),dp(4))
        nav.orientation = LinearLayout.HORIZONTAL
        nav.visibility = if (page in setOf("home", "search", "library", "settings")) View.VISIBLE else View.GONE
        val tabContainer=nav
        if(page=="library") {
            footerTools.visibility=View.VISIBLE
            footerTools.addView(row().apply {
                listOf(Triple("导入歌词","upload",{ importFile() }),Triple("目录","folder",{ open("folders") }),
                    Triple(if(scanning) "扫描中…" else "扫描","refresh",{ scan(true) })).forEach { (label,icon,callback) ->
                    addView(action(label,false,icon,callback),LinearLayout.LayoutParams(0,dp(42),1f).apply { setMargins(dp(2),0,dp(2),dp(2)) })
                }
            })
        }
        listOf(Triple("home", "当前", "home"), Triple("search", "搜索", "search"), Triple("library", "歌词库", "library"), Triple("settings", "设置", "settings")).forEach { (id,title,icon) ->
            val active=page==id
            val b=Button(this).apply {
                stateListAnimator=null; elevation=0f; translationZ=0f
                text=title; textSize=11f; isAllCaps=false; includeFontPadding=false; gravity=Gravity.CENTER
                setTextColor(if(active) purple else ink); minHeight=dp(50); minimumHeight=dp(50); minWidth=0; minimumWidth=0
                background=ProviderGlass.solid(this@MainActivity,if(active) tint else android.graphics.Color.TRANSPARENT,18,false)
                val drawable=ProviderIcon(icon,if(active) purple else ink).apply { setBounds(0,0,dp(22),dp(22)) }
                setCompoundDrawables(null,drawable,null,null); compoundDrawablePadding=dp(3); setPadding(dp(2),dp(4),dp(2),dp(4))
                contentDescription=title; isSelected=active
                ProviderGlass.feedback(this,18)
                setOnClickListener { if(id=="search") prepareSearch(); tab=id; page=id; choosing=false; history.clear(); render(); scroll.scrollTo(0,0) }
            }
            tabContainer.addView(b,LinearLayout.LayoutParams(0,-2,1f).apply { setMargins(dp(2),0,dp(2),0) })
        }
    }

    private fun home() {
        val current = store.snapshot()
        val song = current.song
        content.addView(text(if (current.updatedAt > 0) "●  已连接 · ${playerName(current.player)}" else "等待播放器连接", 12,
            if (current.updatedAt > 0) 0xFF27BA7C.toInt() else muted), full(2, 12))
        if (song?.name.isNullOrBlank()) {
            card {
                addView(text("播放一首歌，开始匹配歌词",20),full(12,10))
                addView(text("接入播放器后，当前歌词和匹配状态会显示在这里。",13,muted),full(0,12))
                addView(action("管理播放器",false,"player") { open("players") },full(0,8))
                addView(action("搜索歌词",true,"search") { enterSearch() },full(0,10))
            }
            return
        }
        val line = song.lyrics.orEmpty().firstOrNull { !it.roma.isNullOrBlank() && !it.translation.isNullOrBlank() }
            ?: song.lyrics.orEmpty().firstOrNull { !it.translation.isNullOrBlank() || !it.roma.isNullOrBlank() }
            ?: song.lyrics.orEmpty().firstOrNull()
        content.addView(row().apply {
            tag="currentSongHero"; ProviderGlass.surface(this,16); setPadding(dp(12),dp(14),dp(12),dp(14))
            addView(ImageView(this@MainActivity).apply {
                tag="songCover"; clipToOutline=true; background=shape(tint,10)
                ArtworkLoader.bind(this,playingArtwork(current))
            },LinearLayout.LayoutParams(dp(112),dp(152)).apply { marginEnd=dp(16) })
            addView(column().apply {
                addView(text(song!!.name.orEmpty(),17).apply {
                    tag="songTitle"; setSingleLine(); ellipsize=android.text.TextUtils.TruncateAt.END
                    typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
                },full())
                addView(text(listOf(song.artist.orEmpty(),current.album).filter { it.isNotBlank() }.joinToString(" · "),12,muted).apply {
                    setSingleLine(); ellipsize=android.text.TextUtils.TruncateAt.END
                },full(5))
                addView(text(line?.text ?: current.message,17,ink).apply { maxLines=2 },full(14))
                line?.translation?.takeIf { it.isNotBlank() }?.let { addView(text(it,16,muted).apply { maxLines=2 },full(5)) }
                line?.roma?.takeIf { it.isNotBlank() }?.let { addView(text(it,11,muted).apply { maxLines=2 },full(8)) }
                addView(row().apply {
                    val capabilities=ProviderSongMetadata.capabilities(song)
                    if(capabilities.isNotEmpty()) addView(ProviderSongMetadata.features(this@MainActivity,capabilities),LinearLayout.LayoutParams(-2,-2).apply { marginEnd=dp(5) })
                    if(current.source.isNotBlank()) addView(ProviderSongMetadata.source(this@MainActivity,sourceLabel(current.source)),LinearLayout.LayoutParams(-2,-2))
                },full(9))
            },LinearLayout.LayoutParams(0,-2,1f))
        },full(0,12))
        content.addView(row().apply {
            addView(action("搜索 / 换歌词",true,"search") { enterSearch() },LinearLayout.LayoutParams(0,dp(46),1.12f).apply { marginEnd=dp(8) })
            addView(action("查看歌词",false,"library") { openCurrent() },LinearLayout.LayoutParams(0,dp(46),1f))
        },full(0,10))
        content.addView(settingsItem("匹配详情",current.message.ifBlank { "查看本次歌词匹配状态" },"list") { open("diagnosis") }.apply {
            ProviderGlass.surface(this,14); setPadding(dp(12),dp(8),dp(10),dp(8))
        },full())
        section("最近使用")
        val saved=SavedLyrics(this).list().take(2)
        if(saved.isNotEmpty()) saved.forEach { entry ->
            content.addView(SongResultCard(this,entry.song,entry.album,sourceLabel(entry.source),artwork=entry.artworkUrl,layoutStyle=SongResultCard.LayoutStyle.RECENT,
                onPreview={ openCandidate(SearchCandidate(entry.id,entry.source,entry.song,entry.album,"saved:${entry.id}",entry.artworkUrl)) }),full(6))
        } else emptyState(content,"选用的歌词会保留在歌词库")
        if (store.choices().containsKey(trackKey(song.name, song.artist))) {
            content.addView(action("恢复自动匹配", false, "refresh") {
                store.clearChoice(trackKey(song.name, song.artist)); toast("已恢复自动匹配")
            }, full(12))
        }
    }

    private fun library() {
        val search = EditText(this).apply {
            hint = "搜索歌名、歌手"; textSize = 14f;
            setCompoundDrawablesRelative(ProviderIcon("search",muted).apply { setBounds(0,0,dp(18),dp(18)) },null,null,null); compoundDrawablePadding=dp(8); setSingleLine(); setText(query)
            setPadding(dp(14), dp(9), dp(14), dp(9)); ProviderGlass.surface(this,16)
            contentDescription = "搜索本地歌词"
        }
        content.addView(search, full())
        val tools = row()
        listOf("全部", "逐字", "三行").forEach { value ->
            tools.addView(chip(value, value == filter) { filter = value; render() },LinearLayout.LayoutParams(0,-2,1f).apply { marginEnd=dp(7) })
        }
        val sortButton = action(sort, false, "sort") { }
        sortButton.setOnClickListener {
            val options=listOf("最近更新","歌名 A–Z","歌手 A–Z")
            ProviderDialogBuilder(this).setTitle("歌词排序").setSingleChoiceItems(options.toTypedArray(),options.indexOf(sort)) { d,i ->
                sort=options[i]; sortButton.text=sort; renderLibraryRows(); d.dismiss()
            }.setNegativeButton("取消",null).show()
        }
        tools.addView(sortButton.apply { minHeight=dp(36); minimumHeight=dp(36); textSize=11f; iconSize=dp(14); setPadding(dp(8),0,dp(8),0) })
        content.addView(tools, full(8))
        content.addView(column().apply { tag = "libraryRows" }, full(2))
        renderLibraryRows()
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { query = s.toString(); renderLibraryRows() }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun renderLibraryRows() {
        val target = content.findViewWithTag<LinearLayout>("libraryRows") ?: return
        target.removeAllViews()
        var list = (SavedLyrics(this).files() + files).distinctBy { it.uri }
            .filter { (it.song.name.orEmpty() + " " + it.song.artist.orEmpty()).contains(query, true) }
            .filter { filter == "全部" || filter == "逐字" && it.wordLevel || filter == "三行" && it.romanization && it.translated }
        list = when (sort) { "歌名 A–Z" -> list.sortedBy { it.song.name }; "歌手 A–Z" -> list.sortedBy { it.song.artist }; else -> list.sortedByDescending { it.modified } }
        val groups = list.groupBy { trackKey(it.song.name, it.song.artist) }
        subtitle.text="${groups.size} 首歌曲 · ${list.size} 份歌词"
        if (groups.isEmpty()) emptyState(target,if(scanning) "正在扫描…" else "暂无歌词，导入文件或搜索在线歌词")
        groups.values.take(150).forEach { versions ->
            val song = versions.first().song
            val expanded = column().apply { tag="libraryVersions"; visibility=View.GONE; ProviderGlass.surface(this,12); setPadding(dp(12),dp(4),dp(12),dp(4)) }
            val group=column()
            val first=versions.first()
            val saved=SavedLyrics(this).get(first.uri)
            val entry=SongResultCard(this,song,saved?.album.orEmpty(),"",artwork=saved?.artworkUrl.orEmpty(),
                layoutStyle=SongResultCard.LayoutStyle.LIBRARY,versionCount=versions.size,
                capabilityLabels=buildList {
                    add(if(first.wordLevel) "逐字" else if(first.romanization && first.translated) "三行" else "逐行")
                    if(first.translated) add("翻译") else if(first.romanization) add("罗马音")
                },onPreview={ expanded.visibility=if(expanded.visibility==View.GONE) View.VISIBLE else View.GONE })
            group.addView(entry)
            versions.forEach { file -> expanded.addView(item(file.filename,file.directory,"预览") { openCandidate(localCandidate(file)) }) }
            group.addView(expanded,full(4)); target.addView(group,full(6))
        }
        if (groups.size > 150) target.addView(text("请搜索以查看其他歌曲", 12, muted), full(12))
    }

    private fun openCurrent() {
        previewLoadVersion++; previewLoadJob?.cancel(); previewLoading = false
        val current = store.snapshot()
        selected = null; candidate = null; detailSong = current.song; detailOffset = 0
        previewTrack = trackKey(current.song?.name, current.song?.artist); previewPlayer = current.player
        followPlayback = true; forcePreviewScroll = true
        showRoma = false; showTranslation = false; open("detail")
    }
    private fun playingArtwork(snapshot: RuntimeSnapshot): String = snapshot.artwork.ifBlank {
        val uri = store.choices()[trackKey(snapshot.song?.name, snapshot.song?.artist)]?.uri.orEmpty()
        SavedLyrics(this).get(uri)?.artworkUrl.orEmpty()
    }
    private fun localCandidate(file: LyricFile): SearchCandidate {
        val saved = SavedLyrics(this).get(file.uri)
        return if (saved == null) SearchCandidate.local(file).copy(song = file.song.copy(duration = 0))
        else SearchCandidate(saved.id, saved.source, saved.song, saved.album, file.uri, saved.artworkUrl)
    }
    private fun prepareSearch() {
        val snapshot = store.snapshot()
        val key = trackKey(snapshot.song?.name, snapshot.song?.artist)
        if (key != searchSongKey || onlineQuery.isBlank()) {
            searchJob?.cancel(); searchGeneration++; searchRunning = false
            onlineSession.close(); onlineSession = OnlineSearch(searchScope); activeProviderIds = emptyList()
            searchSongKey = key; onlineQuery = snapshot.song?.name.orEmpty()
            searchReference = MatchTarget(snapshot.song?.name, snapshot.song?.artist, snapshot.album, snapshot.song?.duration ?: 0)
            onlineResults = emptyList(); searchPerformed = false; expandedGroups.clear()
            searchErrors.clear(); searchedProviders = 0; totalProviders = 0
        }
    }
    private fun enterSearch() { prepareSearch(); tab = "search"; open("search") }
    private fun openCandidate(value: SearchCandidate) {
        if (searchSongKey.isBlank() || page == "library" || page == "home") prepareSearch()
        val current = store.snapshot()
        previewTrack = searchSongKey; previewPlayer = current.player
        // Library entries are followed only when they are the currently playing song.
        if (page == "library" && trackKey(value.song.name, value.song.artist) != previewTrack) previewTrack = ""
        followPlayback = true; forcePreviewScroll = true
        previewLoadVersion++; previewLoadJob?.cancel(); previewLoading = false
        val latest = SearchVersionGroups.find(onlineResults, value.id) ?: value
        candidate = latest; selected = files.firstOrNull { it.uri == latest.localUri }
        detailSong = latest.song; detailOffset = 0; rememberChoice = true; showRoma = false; showTranslation = false
        open("detail")
        if (latest.remote != null && latest.lyricState != LyricState.READY) loadPreview(latest)
    }
    private fun searchPage() {
        val snapshot = store.snapshot()
        val input = EditText(this).apply {
            hint = "搜索歌名 / 艺术家"; setSingleLine(); setText(onlineQuery); textSize = 15f
            minHeight = dp(48)
            setPadding(dp(2), dp(8), dp(2), dp(8)); background=null
            setTextColor(ink); setHintTextColor(muted)
            contentDescription = "搜索在线或本地歌词"; imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { onlineQuery = s.toString() }
                override fun afterTextChanged(s: Editable?) {}
            })
            setOnEditorActionListener { _, action, _ -> if (action == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) { runSearch(false); true } else false }
        }
        content.addView(row().apply {
            tag="searchField"; ProviderGlass.surface(this,20)
            addView(iconButton("search","搜索歌词") { runSearch(false) })
            addView(input,LinearLayout.LayoutParams(0,dp(46),1f))
            addView(iconButton("close","清空搜索") { input.setText("") })
        },full(0,10))
        referenceSongCard(snapshot)
        content.addView(row().apply {
            tag="searchControls"
            listOf("全部","在线","本地").forEach { label ->
                addView(chip(label,searchRange==label) { searchRange=label; render() },LinearLayout.LayoutParams(0,-2,1f).apply { marginEnd=dp(8) })
            }
            addView(iconButton("sliders",if(searchFiltersExpanded) "收起筛选" else "筛选") {
                searchFiltersExpanded=!searchFiltersExpanded; render()
            }.apply { ProviderGlass.surface(this,20) },LinearLayout.LayoutParams(dp(40),dp(36)))
        },full(0,2))
        if (searchFiltersExpanded) {
            content.addView(buttonFlow().apply {
            listOf("全部", "逐字", "罗马音").forEach { label -> addView(chip(label, searchFilter == label) {
                searchFilter = label; render()
            }) }
            val button = action(searchSource, false, "down") {}
            button.setOnClickListener {
                val sources=listOf("全部来源","本地")+(LyricsConfig.ALL_PROVIDERS.map { it.id }+"AppleMusic")
                ProviderDialogBuilder(this@MainActivity).setTitle("歌词来源").setSingleChoiceItems(sources.map(::sourceLabel).toTypedArray(),sources.indexOf(searchSource)) { d,i ->
                    searchSource=sources[i]; d.dismiss(); render()
                }.setNegativeButton("取消",null).show()
            }
            button.text = sourceLabel(searchSource); addView(button)
            }, full(4))
            content.addView(buttonFlow().apply {
                addView(textAction("修改搜索信息") { editSearchReference() })
            }, full(2, 8))
        } else if (searchFilter != "全部" || searchSource != "全部来源") {
            content.addView(text("${searchFilter} · ${sourceLabel(searchSource)}", 12, purple), full(0, 8))
        }
        content.addView(column().apply { tag = "searchResults" }, full())
        renderSearchResults()
    }
    private fun renderSearchResults() {
        val holder = content.findViewWithTag<LinearLayout>("searchResults") ?: return
        val previousScroll = scroll.scrollY
        holder.removeAllViews()
        val queryTokens = onlineQuery.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val local = (SavedLyrics(this).files() + files).distinctBy { it.uri }.filter { file ->
            queryTokens.all { (file.song.name.orEmpty() + " " + file.song.artist.orEmpty()).contains(it, true) }
        }.map(::localCandidate)
        val list = SearchCandidate.distinct((if (searchRange == "本地") emptyList() else onlineResults) +
            (if (searchRange == "在线") emptyList() else local)).filter { row ->
                (searchSource == "全部来源" || searchSource == "本地" && row.localUri != null || searchSource == row.source) &&
                    (searchFilter == "全部" || searchFilter == "逐字" && row.wordLevel || searchFilter == "罗马音" && row.roma)
            }.sortedWith(SearchCandidate.ranking(searchReference, store.settings().matchRules))
        holder.addView(row().apply {
            addView(text(if(searchRunning) "正在搜索 $searchedProviders / $totalProviders 个来源 · ${list.size} 份歌词" else "已找到 ${list.size} 份歌词 · ${list.map { it.source }.distinct().size} 个来源",11,muted),LinearLayout.LayoutParams(0,-2,1f))
            addView(textAction("评分规则","info") { open("matching") }.apply { textSize=11f; minHeight=dp(36); minimumHeight=dp(36); iconSize=dp(15) })
        },full())
        if(searchRunning) holder.addView(action("停止搜索",false) { detectionStopped=true; syncSearchWork(); renderSearchResults() })
        val groups=SearchVersionGroups.groups(list,searchReference,store.settings().matchRules)
        groups.forEach { group ->
            val key = group.parent.id
            val expanded = key in expandedGroups
            val shown = listOf(group.parent) + if (expanded) group.versions else emptyList()
            shown.forEachIndexed { index, result ->
                val score = MatchScorer.evaluate(searchReference, result.target, store.settings().matchRules)
                holder.addView(SongResultCard(this, result.song, result.album, sourceLabel(result.source),
                    score = score.total, moreVersions = if (index == 0) group.versions.size else 0,
                    expanded = expanded, artwork = result.artworkUrl, onPreview = { openCandidate(result) },
                    lyricStatus = lyricStatus(result),
                    onScore = { scoreDetails(result) }, onToggleVersions = {
                        if (expandedGroups.add(key)) {
                            val session = onlineSession
                            session.detect(group.versions) { resolved ->
                                withContext(Dispatchers.Main.immediate) { updateSearchCandidate(session, resolved) }
                            }
                        } else expandedGroups.remove(key)
                        renderSearchResults()
                    }), full(6).apply { if (index > 0) marginStart = dp(16) })
            }
        }
        if (list.isEmpty()) emptyState(holder,if(searchPerformed) "没有找到歌曲，试试修改关键词或来源" else "点击搜索查找歌词，本地结果会直接显示")
        if (searchPerformed && !searchRunning && searchRange != "本地") {
            val selectedIds = activeProviderIds.filter { searchSource == "全部来源" || it == searchSource }
            if (onlineSession.canLoadMore(selectedIds)) {
                holder.addView(action("加载更多", false, "search") { runSearch(true) }, full(12))
            } else if (onlineResults.isNotEmpty()) holder.addView(text("已加载全部结果", 12, muted), full(12))
            if (searchErrors.isNotEmpty()) holder.addView(action("${searchErrors.size} 个来源失败 · 查看 / 重试", false) {
                ProviderDialogBuilder(this).setTitle("来源状态").setMessage(searchErrors.entries.joinToString("\n") { "${sourceLabel(it.key)}：${it.value}" })
                    .setNegativeButton("关闭", null).setPositiveButton("重试") { _, _ -> runSearch(true, failedOnly = true) }.show()
            }, full(4))
        }
        if (foreground && page == "search" && !detectionStopped) {
            val visible = groups.flatMap { group -> listOf(group.parent) + if (group.parent.id in expandedGroups) group.versions else emptyList() }
            val session = onlineSession
            session.detect(visible) { resolved -> withContext(Dispatchers.Main.immediate) { updateSearchCandidate(session, resolved) } }
        }
        if (page == "search") scroll.post {
            if (page == "search" && content.findViewWithTag<LinearLayout>("searchResults") === holder) scroll.scrollTo(0, previousScroll)
        }
    }
    private fun lyricStatus(value: SearchCandidate): String = when (value.lyricState) {
        LyricState.UNKNOWN -> "待检测"
        LyricState.CHECKING -> "检测中…"
        LyricState.READY -> ""
        LyricState.NONE -> "暂无歌词"
        LyricState.ERROR -> "获取失败 · 点击重试"
    }
    private fun updateSearchCandidate(session: OnlineSearch, value: SearchCandidate) {
        if (session !== onlineSession || isDestroyed) return
        onlineResults = SearchVersionGroups.update(onlineResults, value)
        if (foreground && page == "search") renderSearchResults()
    }
    private fun loadPreview(value: SearchCandidate) {
        val session = onlineSession
        val version = ++previewLoadVersion
        previewLoadJob?.cancel(); previewLoading = true
        candidate = value.copy(lyricState = LyricState.CHECKING); render()
        previewLoadJob = searchScope.launch {
            try {
                val resolved = session.resolve(value, retry = value.lyricState in setOf(LyricState.ERROR, LyricState.NONE))
                updateSearchCandidate(session, resolved)
                if (session === onlineSession && candidate?.id == value.id) {
                    candidate = resolved; detailSong = resolved.song
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            finally {
                if (version == previewLoadVersion) {
                    previewLoading = false
                    if (page == "detail" && candidate?.id == value.id && !isDestroyed) render()
                }
            }
        }
    }
    private fun syncSearchWork() {
        if (foreground && page == "search" && !detectionStopped) {
            onlineSession.resumeDetection()
        } else {
            searchJob?.cancel(); searchGeneration++; searchRunning = false
            onlineSession.pauseDetection()
            fun reset(item: SearchCandidate): SearchCandidate = item.copy(
                lyricState = if (item.lyricState == LyricState.CHECKING) LyricState.UNKNOWN else item.lyricState,
                versions = item.versions.map(::reset))
            onlineResults = onlineResults.map(::reset)
        }
    }
    private fun runSearch(more: Boolean, failedOnly: Boolean = false) {
        if (onlineQuery.isBlank()) { toast("请输入歌名或艺术家"); return }
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(content.windowToken, 0)
        detectionStopped = false
        searchJob?.cancel(); val generation = ++searchGeneration
        val query = onlineQuery.trim()
        val append = more && searchedQuery == query
        val retryIds = searchErrors.keys.toSet()
        if (!append) {
            onlineSession.close(); onlineSession = OnlineSearch(searchScope)
            onlineResults = emptyList(); expandedGroups.clear(); activeProviderIds = emptyList()
        }
        val session = onlineSession
        syncSearchWork()
        searchedQuery = query
        searchPerformed = true; searchedProviders = 0
        if (searchRange == "本地" || searchSource == "本地") { searchRunning = false; renderSearchResults(); return }
        val providers = SearchProviders.create(store.settings()).filter {
            (searchSource == "全部来源" || it.id == searchSource) && (!failedOnly || it.id in retryIds)
        }
        activeProviderIds = (activeProviderIds + providers.map { it.id }).distinct()
        if (!append) searchErrors.clear()
        totalProviders = providers.size; searchRunning = providers.isNotEmpty(); renderSearchResults()
        if (providers.isEmpty()) { toast("没有启用的在线来源，请到设置中管理歌词源"); return }
        searchJob = searchScope.launch {
            try {
                session.search(providers, query, searchPageSize, more = append) { batch ->
                    withContext(Dispatchers.Main.immediate) {
                        if (generation != searchGeneration || session !== onlineSession || isDestroyed) return@withContext
                        searchedProviders++
                        if (batch.error != null) searchErrors[batch.provider] = batch.error else searchErrors.remove(batch.provider)
                        onlineResults = SearchCandidate.distinct(onlineResults + batch.results)
                        if (page == "search") renderSearchResults()
                        session.detect(batch.results) { resolved ->
                            withContext(Dispatchers.Main.immediate) { updateSearchCandidate(session, resolved) }
                        }
                    }
                }
            } finally {
                if (generation == searchGeneration && !isDestroyed) { searchRunning = false; if (page == "search") renderSearchResults() }
            }
        }
    }
    private fun editSearchReference(): androidx.appcompat.app.AlertDialog {
        val form = column().apply { setPadding(dp(22), dp(6), dp(22), 0) }
        fun field(label: String, value: String) = EditText(this).apply {
            hint = label; setText(value); setSingleLine(); form.addView(text(label, 12, muted)); form.addView(this)
        }
        val title = field("标题", searchReference.title.orEmpty())
        val artist = field("艺术家", searchReference.artist.orEmpty())
        val album = field("专辑", searchReference.album.orEmpty())
        val duration = field("时长（秒，留空表示未知）", searchReference.durationMs.takeIf { it > 0 }?.div(1000.0)?.toString().orEmpty())
        duration.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        return ProviderDialogBuilder(this).setTitle("搜索与评分参考信息").setView(form).setNegativeButton("取消", null)
            .setPositiveButton("搜索") { _, _ ->
                searchReference = MatchTarget(title.text.toString(), artist.text.toString(), album.text.toString(),
                    duration.text.toString().toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 && it < 86400 }?.times(1000)?.toLong() ?: 0)
                onlineQuery = listOf(title.text.toString(), artist.text.toString()).filter { it.isNotBlank() }.joinToString(" ")
                render(); runSearch(false)
            }.show()
    }
    private fun scoreDetails(value: SearchCandidate) {
        MatchScoreDialog.show(this, searchReference, value, sourceLabel(value.source), store.settings().matchRules)
    }
    private fun renderDetailActions() {
        val value = candidate ?: run {
            nav.addView(action("搜索 / 换歌词",true,"search") { enterSearch() },full())
            return
        }
        if (value.lyricState != LyricState.READY || value.song.lyrics.isNullOrEmpty()) {
            nav.addView(action(if (previewLoading) "正在获取歌词…" else "重新获取歌词", true) { loadPreview(value) }
                .apply { isEnabled = !previewLoading && value.remote != null }, full())
            return
        }
        val amount = text("${detailOffset} ms", 12).apply { gravity=Gravity.CENTER; background=ProviderGlass.solid(this@MainActivity,tint,12); minimumHeight=dp(36) }
        nav.setPadding(dp(12),dp(10),dp(12),dp(10))
        nav.addView(row().apply {
            addView(action("− 0.1 秒",false) { detailOffset=(detailOffset-100).coerceAtLeast(-30000); amount.text="$detailOffset ms" }
                .apply { minHeight=dp(38); minimumHeight=dp(38); minWidth=0; minimumWidth=0 },LinearLayout.LayoutParams(0,-2,1f))
            addView(amount,LinearLayout.LayoutParams(0,-2,.7f).apply { setMargins(dp(10),0,dp(10),0) })
            addView(action("＋ 0.1 秒",false) { detailOffset=(detailOffset+100).coerceAtMost(30000); amount.text="$detailOffset ms" }
                .apply { minHeight=dp(38); minimumHeight=dp(38); minWidth=0; minimumWidth=0 },LinearLayout.LayoutParams(0,-2,1f))
        })
        nav.addView(toggle("记住这首歌", "", rememberChoice) { rememberChoice=it }.apply { minimumHeight=dp(44); setPadding(dp(2),0,dp(2),0) })
        nav.addView(action(if (savingSelection) "正在保存…" else "使用这份歌词", true) { applyCandidate(value) }
            .apply { isEnabled = !savingSelection }, full())
    }
    private fun applyCandidate(value: SearchCandidate) {
        if (value.lyricState != LyricState.READY || value.song.lyrics.isNullOrEmpty()) { toast("请先获取可用歌词"); return }
        val current = store.snapshot().song
        if (current?.name.isNullOrBlank()) { toast("请先播放歌曲"); return }
        if (trackKey(current?.name, current?.artist) != searchSongKey) { toast("播放歌曲已变化，请返回当前歌曲重新搜索"); return }
        savingSelection = true; render()
        val offset = detailOffset; val temporary = !rememberChoice; val key = searchSongKey
        searchScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val uri = value.localUri ?: SavedLyrics(this@MainActivity).save(value)
                    store.choose(key, SongChoice(uri, offset, temporary))
                }
                toast("已保存选择，正在发送歌词到词幕")
                candidate = null; tab = "home"; page = "home"; history.clear()
                scan(false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { toast("保存失败：${error.javaClass.simpleName}，请重试") }
            finally { savingSelection = false; if (!isDestroyed) render() }
        }
    }
    private fun durationLabel(value: Long) = if (value <= 0) "时长未知" else "%02d:%02d".format(value / 60000, value / 1000 % 60)
    private fun matchingPage() {
        var draft = store.settings().matchRules
        content.addView(text("用于搜索排序和自动匹配。权重会按可用字段归一到 100 分。", 12, muted), full(0, 10))
        fun weight(label: String, initial: Int, change: (Int) -> Unit) {
            val value = text("$label · $initial", 14)
            content.addView(value, full(9))
            content.addView(SeekBar(this).apply {
                max = 100; progress = initial; contentDescription = "$label 权重"
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (fromUser) { value.text = "$label · $progress"; change(progress) }
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                })
            }, full())
        }
        section("字段权重")
        weight("标题", draft.titleWeight) { draft = draft.copy(titleWeight = it) }
        weight("艺术家", draft.artistWeight) { draft = draft.copy(artistWeight = it) }
        weight("专辑", draft.albumWeight) { draft = draft.copy(albumWeight = it) }
        weight("时长", draft.durationWeight) { draft = draft.copy(durationWeight = it) }
        section("文字比较方式")
        fun mode(label: String, initial: String, change: (String) -> Unit) {
            val modes = linkedMapOf("exact" to "完全一致", "normalized" to "忽略大小写与标点", "fuzzy" to "相似度匹配")
            val button = action(modes[initial].orEmpty(), false, "down") {}
            button.setOnClickListener {
                val keys=modes.keys.toList()
                ProviderDialogBuilder(this).setTitle("$label 比较方式").setItems(modes.values.toTypedArray()) { _,i ->
                    change(keys[i]); button.text=modes[keys[i]]
                }.setNegativeButton("取消",null).show()
            }
            content.addView(row().apply { addView(text(label), LinearLayout.LayoutParams(0, -2, 1f)); addView(button) })
        }
        mode("标题", draft.titleMode) { draft = draft.copy(titleMode = it) }
        mode("艺术家", draft.artistMode) { draft = draft.copy(artistMode = it) }
        mode("专辑", draft.albumMode) { draft = draft.copy(albumMode = it) }
        section("自动匹配条件")
        weight("最低匹配分", draft.minimumScore) { draft = draft.copy(minimumScore = it) }
        val duration = EditText(this).apply {
            hint = "时长容差（1–120 秒）"; setSingleLine(); setText((draft.durationToleranceMs / 1000.0).toString())
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            contentDescription = "时长容差（秒）"
        }
        content.addView(text("时长容差（秒）", 13, muted), full(11)); content.addView(duration)
        content.addView(text("时长相差不超过 1 秒得满分，之后逐步扣分。未知的参考字段不参与评分。", 12, muted), full(5, 8))
        content.addView(toggle("标题必须匹配", "", draft.requireTitle) { draft = draft.copy(requireTitle = it) })
        content.addView(toggle("艺术家必须匹配", "", draft.requireArtist) { draft = draft.copy(requireArtist = it) })
        content.addView(toggle("专辑必须匹配", "", draft.requireAlbum) { draft = draft.copy(requireAlbum = it) })
        content.addView(toggle("排除时长超差的结果", "", draft.requireDuration) { draft = draft.copy(requireDuration = it) })
        content.addView(action("保存评分规则", true) {
            val seconds = duration.text.toString().toDoubleOrNull()
            if (seconds == null || !seconds.isFinite() || seconds !in 1.0..120.0) {
                duration.error = "请输入 1–120 秒"; return@action
            }
            draft = draft.copy(durationToleranceMs = (seconds * 1000).toLong())
            if (!draft.valid()) { toast("四项权重不能全部为 0"); return@action }
            store.update { it.copy(matchRules = draft) }
            toast("已保存，搜索排序与自动匹配已更新"); goBack()
        }, full(14))
        content.addView(action("恢复默认", false) { store.update { it.copy(matchRules = MatchRules()) }; render() }, full(5))
    }

    private fun settings() {
        val s=store.settings()
        section("外观")
        card { addView(settingsItem("外观与布局","主题 · 配色 · 液态玻璃","palette") { open("appearance") }) }
        section("接入")
        card {
            addView(settingsItem("播放器管理","","player") { open("players") })
            addView(settingsItem("歌词目录","","folder") { open("folders") })
            addView(settingsItem("云端歌词源","","cloud") { open("cloud") })
        }
        section("匹配与保存")
        card {
            addView(settingsItem("匹配评分规则","","sliders") { open("matching") })
            addView(settingsToggle("逐字优先","本地逐行时继续查找逐字","info",s.wordFirst) { v -> update { it.copy(wordFirst=v) } })
            addView(settingsToggle("保存云端歌词","沿用音乐文件名","cloud",s.saveCloud) { v -> update { it.copy(saveCloud=v) } })
        }
        section("工具")
        card {
            addView(settingsItem("歌词过滤","","filter") { open("rules") })
            addView(settingsItem("运行记录与诊断","","list") { open("logs") })
        }
        content.addView(text("LyricProvider  ${BuildConfig.VERSION_NAME}",11,muted).apply { gravity=Gravity.CENTER; setPadding(0,dp(12),0,dp(6)) })
    }
    private fun settingsToggle(title: String,subtitle: String,icon: String,checked: Boolean,callback: (Boolean)->Unit): View =
        toggle(title,subtitle,checked,callback).apply {
            addView(ImageView(this@MainActivity).apply { setImageDrawable(ProviderIcon(icon,ink)); importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO },0,
                LinearLayout.LayoutParams(dp(21),dp(21)).apply { marginEnd=dp(10) })
        }

    private fun savingPage() {
        val s=store.settings()
        card {
            addView(toggle("保存云端歌词","沿用音乐文件名",s.saveCloud) { v -> update { it.copy(saveCloud=v) } })
            addView(toggle("覆盖同名歌词","关闭时保留已有文件",s.overwriteCloud) { v -> update { it.copy(overwriteCloud=v) } })
        }
        card {
            addView(text("保存位置",14))
            addView(text("使用现有音乐同目录保存规则；文件访问权限不足时会提示。原歌词与目录授权继续保留。",12,muted),full(6,6))
        }
    }

    private fun settingsItem(title: String,subtitle: String,icon: String,onClick: () -> Unit): LinearLayout {
        val view=item(title,subtitle,callback=onClick)
        view.addView(ImageView(this).apply { setImageDrawable(ProviderIcon(icon,ink)); importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO },0,
            LinearLayout.LayoutParams(dp(22),dp(22)).apply { marginEnd=dp(12) })
        return view
    }

    private fun appearance() {
        section("外观预览")
        content.addView(row().apply {
            listOf(false to "普通",true to "液态玻璃").forEach { (glass,label) ->
                val tile=column().apply {
                    tag=if(glass) "glassPreview" else "plainPreview"
                    setPadding(dp(8),dp(5),dp(8),dp(8)); minimumHeight=dp(86)
                    background=android.graphics.drawable.LayerDrawable(arrayOf(ProviderGlass.panel(this@MainActivity,this,14,glass),
                        GradientDrawable().apply {
                            cornerRadius=dp(14)*ProviderAppearance.corner(this@MainActivity)/16f
                            setColor(android.graphics.Color.TRANSPARENT)
                            if(ProviderAppearance.glass(this@MainActivity)==glass) setStroke(dp(1),purple)
                        }))
                    addView(text(label,11,if(ProviderAppearance.glass(this@MainActivity)==glass) purple else muted).apply { gravity=Gravity.CENTER },full(0,7))
                    addView(row().apply {
                        background=ProviderGlass.panel(this@MainActivity,this,10,glass); setPadding(dp(6),dp(6),dp(6),dp(6))
                        addView(ImageView(this@MainActivity).apply {
                            background=shape(tint,7); clipToOutline=true
                            ArtworkLoader.bind(this,playingArtwork(store.snapshot()))
                        },LinearLayout.LayoutParams(dp(40),dp(40)).apply { marginEnd=dp(8) })
                        addView(column().apply {
                            addView(text("示例歌曲",12)); addView(text("示例歌手",10,muted),full(4))
                        },LinearLayout.LayoutParams(0,-2,1f))
                        addView(ImageView(this@MainActivity).apply { setImageDrawable(ProviderIcon("chevron",muted)) },LinearLayout.LayoutParams(dp(14),dp(14)))
                    })
                    isClickable=true; isFocusable=true; contentDescription="$label 外观预览"
                    ProviderGlass.feedback(this,14)
                    setOnClickListener { ProviderAppearance.setGlass(this@MainActivity,glass); recreate() }
                }
                addView(tile,LinearLayout.LayoutParams(0,-2,1f).apply { if(!glass) marginEnd=dp(10) })
            }
        },full())
        section("主题与配色")
        card {
            val mode=ProviderTheme.mode(this@MainActivity)
            addView(settingsItem("主题模式","","sun") {
                val modes=listOf("auto","light","dark")
                ProviderDialogBuilder(this@MainActivity).setTitle("主题模式").setSingleChoiceItems(arrayOf("跟随系统","浅色","深色"),modes.indexOf(mode)) { d,i ->
                    ProviderTheme.setMode(this@MainActivity,modes[i]); d.dismiss(); recreate()
                }.setNegativeButton("取消",null).show()
            }.apply {
                addView(text(when(mode) { "light"->"浅色"; "dark"->"深色"; else->"跟随系统" },11,muted),childCount-1)
            })
            addView(settingsToggle("莫奈取色","","palette",ProviderTheme.monet(this@MainActivity)) { ProviderTheme.setMonet(this@MainActivity,it); recreate() })
            addView(row().apply {
                minimumHeight=dp(40)
                addView(ImageView(this@MainActivity).apply { setImageDrawable(ProviderIcon("palette",ink)) },LinearLayout.LayoutParams(dp(22),dp(22)).apply { marginEnd=dp(12) })
                addView(text("主题色",15).apply {
                    minimumHeight=dp(40); isClickable=true; isFocusable=true; contentDescription="自定义主题色"; ProviderGlass.feedback(this,10)
                    setOnClickListener { customColor("theme","主题色") }
                },LinearLayout.LayoutParams(0,-2,1f))
                listOf("蓝色","绿色","紫色","粉色").forEach { label ->
                    val color=ProviderTheme.seeds.getValue(label)
                    addView(TextView(this@MainActivity).apply {
                        tag="themeSwatch:$label"; text=if(ProviderTheme.seed(this@MainActivity)==color || ProviderTheme.seed(this@MainActivity)==0 && label=="蓝色") "✓" else ""
                        setTextColor(android.graphics.Color.WHITE); gravity=Gravity.CENTER; textSize=14f
                        background=android.graphics.drawable.InsetDrawable(shape(color,99),dp(6),dp(9),dp(6),dp(9))
                        contentDescription="$label 主题色"; isClickable=true; isFocusable=true; ProviderGlass.feedback(this,20)
                        setOnClickListener { ProviderTheme.setSeed(this@MainActivity,color); ProviderTheme.setMonet(this@MainActivity,false); recreate() }
                    },LinearLayout.LayoutParams(dp(36),dp(40)))
                }
            })
            addView(colorItem("全局背景色","background"))
            addView(colorItem("控件边框颜色","border"))
        }
        section("材质")
        card {
            addView(settingsToggle("液态玻璃","默认关闭 · 所有页面统一","drop",ProviderAppearance.glass(this@MainActivity)) {
                ProviderAppearance.setGlass(this@MainActivity,it); recreate()
            })
            addView(settingsItem("模糊与透明度","","blur") { materialControls() })
            addView(settingsItem("圆角与布局","","corners") { cornerControls() })
        }
        content.addView(action("恢复外观默认值",false,"refresh") {
            ProviderDialogBuilder(this).setTitle("恢复外观默认值？").setMessage("仅恢复主题、配色、玻璃和圆角。歌词、目录授权、播放器、账号与匹配规则都会保留。")
                .setNegativeButton("取消",null).setPositiveButton("恢复") { _,_ -> ProviderAppearance.reset(this); recreate() }.show()
        }.apply { setTextColor(purple); icon=ProviderIcon("refresh",purple); iconTint=ColorStateList.valueOf(purple) },full(12))
    }

    private fun colorItem(title: String,key: String): View {
        val value=if(key=="background") ProviderAppearance.background(this) else ProviderAppearance.border(this)
        val view=item(title,end=value?.let { String.format("#%06X",it and 0xFFFFFF) } ?: if(androidx.core.graphics.ColorUtils.calculateLuminance(bg)<.3) "深蓝" else "浅蓝") { customColor(key,title) }
        view.addView(if(key=="background") ImageView(this).apply { setImageDrawable(ProviderIcon("image",ink)) }
            else View(this).apply { background=ProviderGlass.solid(this@MainActivity,value ?: palette.line,99) },0,
            LinearLayout.LayoutParams(dp(20),dp(20)).apply { marginEnd=dp(12) })
        return view
    }

    private fun customColor(key: String,title: String) {
        val current=when(key) { "theme" -> ProviderTheme.seed(this); "background" -> ProviderAppearance.background(this); else -> ProviderAppearance.border(this) }
        val input=EditText(this).apply {
            hint="#RRGGBB"; setSingleLine(); setText(current?.takeIf { it!=0 }?.let { String.format("#%06X",it and 0xFFFFFF) }.orEmpty()); contentDescription="$title 自定义颜色"
        }
        val body=column().apply {
            setPadding(dp(20),dp(8),dp(20),0)
            addView(text("选择预设或输入颜色值",12,muted)); addView(input,full(8))
        }
        val dialog=ProviderDialogBuilder(this).setTitle(title).setView(body).setNegativeButton("取消",null).setPositiveButton("应用",null)
            .setNeutralButton("跟随主题") { _,_ -> if(key=="theme") ProviderTheme.setSeed(this,0) else ProviderAppearance.setColor(this,key,null); recreate() }.create()
        body.addView(buttonFlow().apply {
            ProviderTheme.seeds.filter { it.value!=0 }.forEach { (label,color) -> addView(chip(label,false) {
                input.setText(String.format("#%06X",color and 0xFFFFFF))
            }) }
        },full(8))
        dialog.setOnShowListener { dialog.getButton(-1).setOnClickListener {
            val raw=input.text.toString().trim().removePrefix("#")
            if(!Regex("[a-fA-F0-9]{6}").matches(raw)) { input.error="请输入 6 位颜色值，例如 #C9E1F5"; return@setOnClickListener }
            val color=android.graphics.Color.parseColor("#$raw")
            if(key=="theme") { ProviderTheme.setSeed(this,color); ProviderTheme.setMonet(this,true) } else ProviderAppearance.setColor(this,key,color)
            dialog.dismiss(); recreate()
        } }
        dialog.show()
    }

    private fun materialControls() {
        var blur=ProviderAppearance.blur(this); var opacity=ProviderAppearance.opacity(this)
        val body=column().apply { setPadding(dp(20),dp(6),dp(20),0) }
        fun slider(title: String,min: Int,max: Int,initial: Int,change: (Int) -> Unit) {
            val label=text("$title · $initial",13); body.addView(label,full(10))
            body.addView(SeekBar(this).apply {
                this.max=max-min; progress=initial-min; contentDescription=title
                setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(v: SeekBar?,p: Int,user: Boolean) { label.text="$title · ${p+min}"; change(p+min) }
                    override fun onStartTrackingTouch(v: SeekBar?) {}
                    override fun onStopTrackingTouch(v: SeekBar?) {}
                })
            })
        }
        slider("模糊",0,32,blur) { blur=it }; slider("透明度",25,90,opacity) { opacity=it }
        body.addView(text("调整统一应用于卡片、导航和弹窗。",12,muted),full(10))
        ProviderDialogBuilder(this).setTitle("模糊与透明度").setView(body).setNegativeButton("取消",null).setPositiveButton("应用") { _,_ ->
            ProviderAppearance.setBlur(this,blur); ProviderAppearance.setOpacity(this,opacity); recreate()
        }.show()
    }

    private fun cornerControls() {
        val values=listOf(12,16,20,24)
        ProviderDialogBuilder(this).setTitle("圆角与布局").setSingleChoiceItems(values.map { "$it dp" }.toTypedArray(),values.indexOf(ProviderAppearance.corner(this))) { d,i ->
            ProviderAppearance.setCorner(this,values[i]); d.dismiss(); recreate()
        }.setNegativeButton("取消",null).show()
    }

    private fun referenceSongCard(snapshot: RuntimeSnapshot) {
        val reference=searchReference
        val box=row().apply { ProviderGlass.surface(this,16); setPadding(dp(12),dp(12),dp(8),dp(12)); minimumHeight=dp(84) }
        box.addView(ImageView(this).apply { background=shape(tint,10); clipToOutline=true; ArtworkLoader.bind(this,playingArtwork(snapshot)) },
            LinearLayout.LayoutParams(dp(52),dp(52)).apply { marginEnd=dp(12) })
        box.addView(column().apply {
            listOf(reference.title.orEmpty().ifBlank { "参考歌曲" },listOf(reference.artist.orEmpty().ifBlank { "歌手未提供" },reference.album.orEmpty()).filter { it.isNotBlank() }.joinToString(" · "),"专辑 · ${reference.album.orEmpty().ifBlank { "未提供" }}").forEachIndexed { index,value ->
                addView(text(value,if(index==0) 15 else 12,if(index==0) ink else muted).apply { setSingleLine(); ellipsize=android.text.TextUtils.TruncateAt.END },full(if(index==0) 0 else 4))
            }
        },LinearLayout.LayoutParams(0,-2,1f))
        box.addView(action("修改信息",false,"chevron") { editSearchReference() }.apply {
            textSize=11f; iconSize=dp(12); iconGravity=MaterialButton.ICON_GRAVITY_TEXT_END
            setTextColor(purple); icon=ProviderIcon("chevron",purple); iconTint=ColorStateList.valueOf(purple)
            setPadding(dp(8),0,dp(6),0); minHeight=dp(40); minimumHeight=dp(40); minWidth=0; minimumWidth=0
            background=android.graphics.drawable.InsetDrawable(ProviderGlass.panel(this@MainActivity,this,14),0,dp(7),0,dp(7))
        })
        content.addView(box,full(0,10))
    }

    private fun folders() {
        content.addView(text("支持 LRC、TTML 和音乐内嵌歌词；从所选目录向下搜索最多 ${LocalScan.MAX_DIRECTORY_DEPTH} 层子目录。", 12, muted), full(4, 15))
        store.settings().folders.forEach { folder -> card {
            val readable = if (folder.uri.startsWith("content:")) contentResolver.persistedUriPermissions.any { it.uri.toString() == folder.uri && it.isReadPermission }
                else java.io.File(folder.uri).canRead()
            val displayPath=if(folder.uri.startsWith("content:")) runCatching {
                DocumentsContract.getTreeDocumentId(Uri.parse(folder.uri)).replace("primary:","内部存储 / ")
            }.getOrDefault(folder.name) else folder.uri
            addView(item(folder.name,"${if(readable) "已授权" else "需要重新授权"} · $displayPath",if(readable) "管理" else "授权") {
                ProviderDialogBuilder(this@MainActivity).setTitle(folder.name)
                    .setItems(arrayOf("重新授权", "移除目录")) { _, index ->
                        if (index == 0) selectFolder() else {
                            update { it.copy(folders = it.folders.filterNot { item -> item.uri == folder.uri }) }
                            releaseGrantIfUnused(folder.uri); scan(true); render()
                        }
                    }.show()
            }.apply {
                addView(ImageView(this@MainActivity).apply { setImageDrawable(ProviderIcon("folder",ink)) },0,
                    LinearLayout.LayoutParams(dp(28),dp(28)).apply { marginEnd=dp(12) })
            })
        } }
        content.addView(action("添加歌词目录", true, "folder") { selectFolder() }, full(10))
        val imported = store.settings().files
        if (imported.isNotEmpty()) {
            section("导入的歌词")
            card { imported.forEach { file -> addView(item(file.name, "单独授权的歌词文件", "移除") {
                ProviderDialogBuilder(this@MainActivity).setTitle("移除这份歌词？")
                    .setMessage("仅移除插件中的引用，原文件会保留。")
                    .setNegativeButton("取消", null).setPositiveButton("移除") { _, _ ->
                        update { it.copy(files = it.files.filterNot { item -> item.uri == file.uri }) }
                        releaseGrantIfUnused(file.uri); scan(true); render()
                    }.show()
            }) } }
        }
    }
    private fun detail() {
        previewRuntime = store.snapshot()
        val song = detailSong
        val currentCandidate=candidate
        content.addView(SongResultCard(this,song ?: Song(),currentCandidate?.album ?: previewRuntime.album,
            currentCandidate?.source?.let(::sourceLabel).orEmpty(),heroHeader=true,
            score=currentCandidate?.let { MatchScorer.evaluate(searchReference,it.target,store.settings().matchRules).total },
            artwork=currentCandidate?.artworkUrl.orEmpty().ifBlank { previewRuntime.artwork },onPreview={ fileInfo() },
            onScore=currentCandidate?.let { value -> { scoreDetails(value) } }),full(0,10))
        if (song?.lyrics.isNullOrEmpty()) {
            val state = candidate?.lyricState
            emptyState(content,when {
                previewLoading || state == LyricState.CHECKING -> "正在获取歌词…"
                state == LyricState.ERROR -> "歌词获取失败，可点击下方重试"
                state == LyricState.NONE -> "该版本暂未提供可用歌词，可重试或选择其他版本"
                else -> "当前没有可预览的歌词"
            })
            return
        }
        content.addView(row().apply {
            tag="previewModes"; ProviderGlass.surface(this,20); setPadding(dp(2),dp(2),dp(2),dp(2))
            listOf("原文","罗马音","翻译").forEachIndexed { i,label ->
                val active=when(i) { 1->showRoma; 2->showTranslation; else->!showRoma && !showTranslation }
                addView(chip(label,active) {
                    showRoma=i==1; showTranslation=i==2; render()
                }.apply {
                    if(!active) background=ProviderGlass.solid(this@MainActivity,android.graphics.Color.TRANSPARENT,20,false)
                },LinearLayout.LayoutParams(0,dp(36),1f))
            }
        },full(0,18))
        footerTools.addView(row().apply {
            gravity=Gravity.END
            followButton=chip(if(followPlayback) "定位当前行 · 跟随中" else "定位当前行 · 跟随暂停",false) { locatePreview() }.apply {
                textSize=11f; setTextColor(purple); icon=ProviderIcon("locate",purple); iconSize=dp(14); iconPadding=dp(4)
                contentDescription="定位当前行，跟随播放"; minimumHeight=dp(34); minHeight=dp(34)
            }
            addView(followButton)
        },full(0,4))
        val timeline = PreviewTimeline(song.lyrics.orEmpty())
        previewTimeline = timeline
        timeline.lines.forEachIndexed { index, line ->
            val original = text(timeline.display[index].text, 23, muted)
            val lyricRow = column().apply {
                setPadding(dp(6), dp(12), dp(6), dp(12))
                addView(original, full(4))
                if (showRoma) line.roma?.takeIf { it.isNotBlank() }?.let { addView(text(it, 13, muted), full(5)) }
                if (showTranslation) line.translation?.takeIf { it.isNotBlank() }?.let { addView(text(it, 14, muted), full(4)) }
            }
            previewRows += lyricRow to original
            content.addView(lyricRow)
        }
    }

    private fun restartPreviewTick() {
        syncPreviewDemand()
        main.removeCallbacks(previewTick)
        if (foreground && page == "detail") main.post(previewTick)
    }
    private fun updateFollowButton() {
        followButton?.text = if (followPlayback) "定位当前行 · 跟随中" else "定位当前行 · 跟随暂停"
    }
    private fun previewPlayback(): io.github.proify.lyricon.localprovider.preview.PlaybackSnapshot? {
        val playing = store.playback()
        val current = previewRuntime
        return playing.takeIf { previewTrack.isNotBlank() && it.track == previewTrack && it.player == previewPlayer &&
            current.player == previewPlayer && trackKey(current.song?.name, current.song?.artist) == previewTrack && it.positionMs >= 0 }
    }
    private fun locatePreview() {
        if (previewPlayback() == null) { toast("暂无这首歌的播放进度，请在播放器中播放后重试"); return }
        followPlayback = true; forcePreviewScroll = true; updateFollowButton(); updatePreviewPosition()
    }
    private fun updatePreviewPosition() {
        val timeline = previewTimeline ?: return
        val playback = previewPlayback()
        val playbackMs = playback?.positionAt(android.os.SystemClock.elapsedRealtime(), previewRuntime.song?.duration ?: 0) ?: -1
        val offset = if (candidate != null) detailOffset else 0
        val next = if (playback != null) timeline.locate(playbackMs, offset) else null
        val old = previewPosition
        if (next != old) {
            old?.let { previous -> previewRows.getOrNull(previous.line)?.let { (row, text) ->
                row.background = null; text.text = timeline.display[previous.line].text; text.setTextColor(muted)
            } }
            next?.let { position -> previewRows.getOrNull(position.line)?.let { (row, text) ->
                row.background = null; text.setTextColor(if (position.singing) purple else muted)
                val value = android.text.SpannableString(timeline.display[position.line].text)
                timeline.display[position.line].words.getOrNull(position.word)?.let { word ->
                    value.setSpan(android.text.style.BackgroundColorSpan(purple), word.start, word.end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    value.setSpan(android.text.style.ForegroundColorSpan(palette.onPrimary), word.start, word.end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                text.text = value
            } }
            previewPosition = next
        }
        if (!followPlayback || next == null) return
        val (row, original) = previewRows.getOrNull(next.line) ?: return
        val layout = original.layout ?: return
        val start = timeline.anchorOffset(next, playbackMs, offset)
        val visualLine = layout.getLineForOffset(start.coerceIn(0, original.text.length))
        if (forcePreviewScroll || next.line != old?.line || visualLine != previewVisualLine) {
            val y = content.top + row.top + original.top + original.totalPaddingTop + layout.getLineTop(visualLine)
            scroll.smoothScrollTo(0, (y - scroll.height / 3).coerceAtLeast(0))
            forcePreviewScroll = false; previewVisualLine = visualLine
        }
    }

    private fun fileInfo() {
        val file = selected
        val song = detailSong
        ProviderDialogBuilder(this).setTitle("歌词信息").setMessage(buildString {
            appendLine(file?.filename ?: candidate?.song?.name ?: "播放器当前歌词")
            candidate?.let {
                appendLine("来源：${sourceLabel(it.source)}")
                if (it.album.isNotBlank()) appendLine("专辑：${it.album}")
                appendLine("时长：${durationLabel(it.song.duration)}")
            }
            file?.let { appendLine(it.directory) }
            appendLine("共 ${song?.lyrics?.size ?: 0} 行")
            appendLine("罗马音：${song?.lyrics.orEmpty().count { !it.roma.isNullOrBlank() }} 行")
            append("翻译：${song?.lyrics.orEmpty().count { !it.translation.isNullOrBlank() }} 行")
        }).setPositiveButton("关闭", null).show()
    }
    private fun chooseCurrent(file: LyricFile) {
        val current = store.snapshot().song
        if (current?.name.isNullOrBlank()) { toast("请先在播放器中播放一首歌"); return }
        ProviderDialogBuilder(this).setTitle("用于当前歌曲？")
            .setMessage("${file.filename}\n→ ${current?.name} · ${current?.artist.orEmpty()}\n偏移 ${detailOffset} ms")
            .setNegativeButton("取消", null).setPositiveButton("使用") { _, _ ->
                store.choose(trackKey(current?.name, current?.artist), SongChoice(file.uri, detailOffset))
                toast("已保存选择，正在重新匹配")
                page = "home"; tab = "home"; history.clear(); render()
            }.show()
    }
    private fun diagnosis() {
        val value = store.snapshot(); val song = value.song
        content.addView(text(song?.name.orEmpty().ifBlank { "暂无歌曲" }, 21))
        section("本次匹配")
        card {
            addView(text(value.message, 15))
            addView(text("播放器：${playerName(value.player)}", 13, muted), full(12))
            addView(text("来源：${sourceLabel(value.source).ifBlank { "尚未命中" }}", 13, muted), full(6))
            value.path?.let { addView(text("音乐：$it", 12, muted), full(6)) }
            if (song != null) { space(this, 15); addView(badges(song)) }
            addView(text("更新时间：${formatDate(value.updatedAt)}", 12, muted), full(13))
        }
        card {
            addView(text("发送字段", 14))
            val lines = song?.lyrics.orEmpty()
            addView(text("原文 text：${lines.count { !it.text.isNullOrBlank() }} 行", 13, muted), full(9))
            addView(text("罗马音 roma：${lines.count { !it.roma.isNullOrBlank() }} 行", 13, purple), full(5))
            addView(text("翻译 translation：${lines.count { !it.translation.isNullOrBlank() }} 行", 13, muted), full(5))
        }
        section("词幕接收核对")
        cardIn(content).apply { tag = "receiverRows" }
        updateReceiverSection()
        receiver.start()
        content.addView(action("复制诊断信息", false) { copyDiagnostic() }, full(10))
        content.addView(action("重新匹配", true, "refresh") { retry() }, full(8))
        content.addView(text("核对只读取词幕当前数据，不调用 AI。", 12, muted), full(15))
    }
    private fun updateReceiverSection() {
        if (page != "diagnosis" || !::content.isInitialized) return
        val box = content.findViewWithTag<LinearLayout>("receiverRows") ?: return
        val received = receiver.state
        box.removeAllViews()
        val result = if (received.connected) TransferVerifier.compare(store.snapshot(), received.song, received.provider, received.player)
            else received.message
        box.addView(text(result, 14, if (result.startsWith("字段一致")) green else ink))
        if (received.provider.isNotBlank()) {
            box.addView(text("来源：${if (received.provider == TransferVerifier.PACKAGE) "本地歌词支持" else received.provider}", 12, muted), full(10))
            box.addView(text("播放器：${playerName(received.player)}", 12, muted), full(4))
        }
        val lines = received.song?.lyrics.orEmpty()
        if (lines.isNotEmpty()) {
            box.addView(text("实际收到：原文 ${lines.count { !it.text.isNullOrBlank() }} 行 · 罗马音 ${lines.count { !it.roma.isNullOrBlank() }} 行 · 翻译 ${lines.count { !it.translation.isNullOrBlank() }} 行", 12, muted), full(9))
            lines.firstOrNull { !it.roma.isNullOrBlank() }?.let { box.addView(text("罗马音示例：${it.roma}", 12, purple), full(7)) }
            if (received.romanizationVisible == false) box.addView(text("当前来源关闭了罗马音显示", 12, muted), full(6))
        }
        if (received.connected && received.provider.isNotBlank() && received.provider != TransferVerifier.PACKAGE)
            box.addView(text("请确认插件作用域已勾选，并关闭播放器自带的词幕输出，避免来源冲突。", 12, muted), full(9))
        box.addView(action("重新核对", false) { receiver.stop(); receiver.start(); updateReceiverSection() }, full(8))
    }
    private fun players() {
        val s = store.settings(); val current = store.snapshot()
        s.knownPlayers.sortedBy { playerName(it) }.forEach { pkg ->
            val installed = runCatching { packageManager.getApplicationInfo(pkg, 0) }.isSuccess
            if (installed || pkg in s.players && pkg !in ProviderSettings.DEFAULT_PLAYERS) card {
                addView(toggle(playerName(pkg), if (current.player == pkg) "最近接入 ${formatDate(current.updatedAt)}" else "等待播放器接入", pkg in s.players) { enabled ->
                    update { it.copy(players = if (enabled) it.players + pkg else it.players - pkg) }
                }.apply {
                    contentDescription="${playerName(pkg)}，$pkg"
                    addView(ImageView(this@MainActivity).apply {
                        tag="playerIcon"; background=ProviderGlass.solid(this@MainActivity,tint,8,false); clipToOutline=true
                        val appIcon=runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull()
                        if(appIcon==null) { setImageDrawable(ProviderIcon("player",ink)); setPadding(dp(7),dp(7),dp(7),dp(7)) }
                        else setImageDrawable(appIcon)
                    },0,LinearLayout.LayoutParams(dp(36),dp(36)).apply { marginEnd=dp(12) })
                })
            }
        }
        content.addView(action("添加其他播放器", false) { addPlayer() }, full(10))
        content.addView(action("管理模块作用域", true) { openModuleManager() }, full(8))
        content.addView(text("这里控制是否提供歌词；首次接入还需勾选对应作用域，并重新打开播放器。", 12, muted), full(15))
    }
    private fun addPlayer() {
        val input = EditText(this).apply { hint = "例如 com.example.music"; inputType = InputType.TYPE_CLASS_TEXT; setSingleLine() }
        ProviderDialogBuilder(this).setTitle("添加播放器").setView(input).setNegativeButton("取消", null)
            .setPositiveButton("添加", null).create().apply {
                setOnShowListener { getButton(-1).setOnClickListener {
                    val pkg = input.text.toString().trim()
                    if (!Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+").matches(pkg)) { input.error = "请输入有效包名"; return@setOnClickListener }
                    update { it.copy(players = it.players + pkg) }; dismiss(); render(); toast("已添加，请在作用域中勾选此播放器")
                } }; show()
            }
    }
    private fun cloud() {
        content.addView(settingsItem("保存规则","保存位置与同名文件处理","save") { open("saving") },full(0,8))
        val settings = store.settings()
        card { addView(toggle("启用云端补全", "本地缺少逐字时继续搜索", settings.cloudEnabled) { value -> update { it.copy(cloudEnabled = value) } }) }
        section("歌词源")
        val labels = mapOf("QQMusicProvider" to "QQ 音乐", "NetEase" to "网易云音乐", "KuGou" to "酷狗音乐", "Soda" to "汽水音乐", "LrcLib" to "LRCLIB")
        card {
            LyricsConfig.ALL_PROVIDERS.forEach { source -> addView(toggle(labels[source.id] ?: source.id, "", source.id !in settings.disabledSources) { enabled ->
                update { it.copy(disabledSources = if (enabled) it.disabledSources - source.id else it.disabledSources + source.id) }
            }.apply { addView(ProviderSourceIcons.view(this@MainActivity,labels[source.id] ?: source.id),0,LinearLayout.LayoutParams(dp(24),dp(24)).apply { marginEnd=dp(12) }) }) }
        }
        section("Apple Music")
        card {
            addView(toggle("参与搜索", "", settings.appleEnabled) { value -> update { it.copy(appleEnabled = value) } })
            addView(item("账号配置", if (settings.appleConfigured) "已保存到插件私有空间" else "可配置，或沿用已有配置文件") { appleConfig() })
        }
    }
    private fun appleConfig() {
        val s = store.settings(); val form = column().apply { setPadding(dp(22), dp(6), dp(22), 0) }
        fun field(label: String, value: String, secret: Boolean = false): EditText {
            form.addView(text(label, 12, muted))
            return EditText(this).apply {
                setText(value); textSize = 14f; setSingleLine()
                if (secret) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                form.addView(this, full(0, 8))
            }
        }
        val region = field("地区代码", s.appleStorefront)
        val language = field("语言", s.appleLanguage)
        val user = field("Media User Token", s.appleUserToken, true)
        val developer = field("Developer Token（可选）", s.appleDeveloperToken, true)
        val dialog = ProviderDialogBuilder(this).setTitle("Apple Music").setView(form)
            .setNegativeButton("取消", null).setNeutralButton("沿用配置文件") { _, _ -> update { it.copy(appleConfigured = false) }; render() }
            .setPositiveButton("保存", null).create()
        dialog.setOnShowListener { dialog.getButton(-1).setOnClickListener {
            if (user.text.isNullOrBlank()) { user.error = "请填写 Media User Token"; return@setOnClickListener }
            if (!Regex("[A-Za-z]{2}").matches(region.text.toString().trim())) { region.error = "请填写两位地区代码"; return@setOnClickListener }
            update { it.copy(appleConfigured = true, appleStorefront = region.text.toString().trim().lowercase(Locale.ROOT),
                appleLanguage = language.text.toString().trim().ifBlank { "zh-Hans-CN" },
                appleUserToken = user.text.toString().trim(), appleDeveloperToken = developer.text.toString().trim()) }
            dialog.dismiss(); render(); toast("配置已保存")
        } }; dialog.show()
    }
    private fun rules() {
        val s = store.settings()
        content.addView(text("内置过滤仍然保留；以下规则用于补充。", 12, muted), full(4))
        section("精确匹配")
        card {
            if (s.exactRules.isEmpty()) addView(text("暂无自定义规则", 13, muted))
            s.exactRules.forEach { rule -> addView(item(rule, "", "移除") { update { it.copy(exactRules = it.exactRules - rule) }; render() }) }
            addView(action("添加精确词", false) { addRule(false) })
        }
        section("包含匹配")
        card {
            if (s.keywordRules.isEmpty()) addView(text("暂无自定义规则", 13, muted))
            s.keywordRules.forEach { rule -> addView(item(rule, "", "移除") { update { it.copy(keywordRules = it.keywordRules - rule) }; render() }) }
            addView(action("添加包含词", false) { addRule(true) })
        }
    }
    private fun addRule(keyword: Boolean) {
        val input = EditText(this).apply { hint = "需要过滤的词"; setSingleLine() }
        ProviderDialogBuilder(this).setTitle(if (keyword) "添加包含词" else "添加精确词")
            .setView(input).setNegativeButton("取消", null).setPositiveButton("添加") { _, _ ->
                val value = input.text.toString().trim()
                if (value.isNotEmpty()) update { if (keyword) it.copy(keywordRules = (it.keywordRules + value).distinct()) else it.copy(exactRules = (it.exactRules + value).distinct()) }
                render()
            }.show()
    }
    private fun logs() {
        content.addView(settingsItem("匹配诊断","核对歌词发送与词幕接收","info") { open("diagnosis") },full(0,8))
        val events = store.events()
        content.addView(buttonFlow().apply {
            addView(action("复制诊断", false) { copyDiagnostic() })
            addView(action("清空记录", false) {
                ProviderDialogBuilder(this@MainActivity).setTitle("清空运行记录？").setNegativeButton("取消", null)
                    .setPositiveButton("清空") { _, _ -> store.clearEvents(); render() }.show()
            })
        })
        if (events.isEmpty()) emptyState(content,"暂无运行记录") else card {
            events.forEach { event ->
                addView(text("${formatDate(event.time)} · ${playerName(event.player)}", 11, muted), full(12))
                if (event.title.isNotBlank()) addView(text(event.title, 14), full(3))
                addView(text(event.message, 13, muted), full(3, 12)); divider(this)
            }
        }
    }
    private fun copyDiagnostic() {
        val snapshot = store.snapshot()
        val value = buildString {
            appendLine("本地歌词支持 ${BuildConfig.VERSION_NAME}")
            appendLine("播放器：${snapshot.player}")
            appendLine("歌曲：${snapshot.song?.name} / ${snapshot.song?.artist}")
            appendLine("来源：${snapshot.source}")
            appendLine("状态：${snapshot.message}")
            if (page == "diagnosis") {
                appendLine("词幕来源：${receiver.state.provider}")
                appendLine("接收核对：${TransferVerifier.compare(snapshot, receiver.state.song, receiver.state.provider, receiver.state.player)}")
            }
            appendLine("原文=${snapshot.song?.lyrics.orEmpty().count { !it.text.isNullOrBlank() }} " +
                "罗马音=${snapshot.song?.lyrics.orEmpty().count { !it.roma.isNullOrBlank() }} " +
                "翻译=${snapshot.song?.lyrics.orEmpty().count { !it.translation.isNullOrBlank() }}")
            store.events().take(15).forEach { appendLine("${formatDate(it.time)} ${it.message}") }
        }
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("本地歌词诊断", value))
        toast("已复制诊断信息")
    }
    private fun retry() {
        if (store.snapshot().song?.name.isNullOrBlank()) { toast("请先播放歌曲并启用播放器作用域"); return }
        Catalog.invalidate(); store.requestRetry(); toast("已请求重新匹配")
    }
    private fun scan(force: Boolean) {
        if (scanning) return
        scanning = true
        io.execute {
            val result = runCatching { Catalog.scan(this, force) }
            main.post {
                if (isDestroyed) return@post
                scanning = false; files = result.getOrDefault(emptyList())
                if (page == "library") render()
                else if (page == "search") renderSearchResults()
                if (force) toast(result.fold({ "找到 ${it.size} 份歌词" }, { "扫描失败，请检查目录授权" }))
            }
        }
    }
    private fun selectFolder() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), 41)
    }
    private fun importFile() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), 42)
    }
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode !in setOf(41, 42) || resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val name = if (requestCode == 41) DocumentsContract.getTreeDocumentId(uri).substringAfterLast('/').substringAfter(':').ifBlank { "内部存储" }
                else contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "歌词文件"
            if (requestCode == 42 && !name.endsWith(".lrc", true) && !name.endsWith(".ttml", true)) { toast("请选择 LRC 或 TTML 文件"); return }
            val entry = LyricFolder(uri.toString(), name)
            update { s -> if (requestCode == 41) {
                val documentId = DocumentsContract.getTreeDocumentId(uri)
                val absolutePath = if (documentId.startsWith("primary:")) "/storage/emulated/0/" + documentId.substringAfter(':') else null
                s.copy(folders = s.folders.filterNot { it.uri == entry.uri || it.uri == absolutePath } + entry)
            } else s.copy(files = s.files.filterNot { it.uri == entry.uri } + entry) }
            scan(true); render(); toast("已取得读取授权")
        }.onFailure { toast("未能保留授权，请重新选择目录或文件") }
    }
    private fun releaseGrantIfUnused(value: String) {
        if (!value.startsWith("content:") || (store.settings().folders + store.settings().files).any { it.uri == value }) return
        runCatching { contentResolver.releasePersistableUriPermission(Uri.parse(value), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }
    private fun update(change: (ProviderSettings) -> ProviderSettings) { store.update(change) }
    private fun openModuleManager() {
        val intent = packageManager.getLaunchIntentForPackage("org.lsposed.manager")
        if (intent != null) runCatching { startActivity(intent) }.onFailure { toast("请在 LSPosed 中管理插件作用域") }
        else ProviderDialogBuilder(this).setTitle("启用播放器作用域")
            .setMessage("打开 LSPosed → 模块 → 本地歌词支持，勾选对应播放器，然后重新打开播放器。")
            .setPositiveButton("知道了", null).show()
    }
    private fun playerName(pkg: String): String = when (pkg) {
        "" -> "未连接播放器"; "yos.music.player" -> "Yos"; "com.neutroncode.mp" -> "Neutron"
        "com.maxmpz.audioplayer" -> "Poweramp"; "ink.trantor.coneplayer", "ink.trantor.coneplayer.gp" -> "光锥音乐"
        else -> runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
    }
    private fun emptyState(parent: LinearLayout,message: String) {
        parent.addView(column().apply { ProviderGlass.surface(this,16); setPadding(dp(16),dp(20),dp(16),dp(20)); addView(text(message,13,muted)) },full(8))
    }
    private fun sourceLabel(value: String): String = when (value) {
        "Embedded" -> "音乐内嵌歌词"; "ExternalLrc" -> "同目录歌词 / LRC"; "QQMusicProvider" -> "QQ 音乐"
        "NetEase" -> "网易云音乐"; "KuGou" -> "酷狗音乐"; "Soda" -> "汽水音乐"; "AppleMusic" -> "Apple Music"; "LrcLib" -> "LRCLIB"; else -> value
    }
    private fun badges(song: Song): LinearLayout = ProviderSongMetadata.features(this,ProviderSongMetadata.capabilities(song))
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun buttonFlow() = SpacedButtonLayout(this)
    private fun text(value: String, size: Int = 15, color: Int = ink) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(color); gravity = Gravity.CENTER_VERTICAL; includeFontPadding=false
        typeface = Typeface.create("sans-serif", Typeface.NORMAL); setLineSpacing(dp(2).toFloat(), 1f)
    }
    private fun action(title: String, primary: Boolean, icon: String? = null, callback: () -> Unit): MaterialButton =
        MaterialButton(this).apply {
            text = title; isAllCaps = false; textSize = 13f; stateListAnimator=null; elevation=0f; translationZ=0f
            iconGravity=MaterialButton.ICON_GRAVITY_TEXT_START
            cornerRadius = (dp(14)*ProviderAppearance.corner(this@MainActivity)/16f).toInt()
            insetTop = 0; insetBottom = 0; minHeight = dp(44); minimumHeight = dp(44)
            setPadding(dp(12), dp(4), dp(12), dp(4))
            backgroundTintList = ColorStateList.valueOf(if (primary) purple else surface)
            setTextColor(if (primary) palette.onPrimary else ink)
            strokeWidth=dp(1); strokeColor=ColorStateList.valueOf(ProviderAppearance.border(this@MainActivity) ?: if(primary) purple else palette.line)
            rippleColor=ColorStateList.valueOf(androidx.core.graphics.ColorUtils.setAlphaComponent(if(primary) palette.onPrimary else purple,28))
            if (icon != null) { this.icon = ProviderIcon(icon, if (primary) palette.onPrimary else ink); iconSize = dp(17); iconPadding = dp(6); iconTint = ColorStateList.valueOf(if (primary) palette.onPrimary else ink) }
            setOnClickListener { callback() }
            if(!primary) { backgroundTintList=null; background=ProviderGlass.panel(this@MainActivity,this,14); ProviderGlass.feedback(this,14) }
        }
    private fun textAction(title: String, icon: String? = null, callback: () -> Unit) = action(title, false, icon, callback).apply {
        backgroundTintList = ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
        background=ProviderGlass.solid(this@MainActivity,android.graphics.Color.TRANSPARENT,18,false)
        setTextColor(purple); icon?.let { this.icon=ProviderIcon(it,purple) }; iconTint=ColorStateList.valueOf(purple)
        setPadding(dp(8), dp(4), dp(8), dp(4)); minWidth = 0; minimumWidth = 0
    }
    private fun chip(title: String, chosen: Boolean, callback: () -> Unit): MaterialButton = action(title, false, callback = callback).apply {
        backgroundTintList = ColorStateList.valueOf(if (chosen) tint else androidx.core.graphics.ColorUtils.setAlphaComponent(palette.muted,10)); cornerRadius = dp(20)
        setTextColor(if (chosen) palette.onPrimary else muted); isSelected = chosen
        backgroundTintList=null
        background=ProviderGlass.solid(this@MainActivity,if(chosen) purple else androidx.core.graphics.ColorUtils.setAlphaComponent(surface,110),20,true)
        ProviderGlass.feedback(this,20)
        setPadding(dp(12), dp(3), dp(12), dp(3)); minWidth = 0; minimumWidth = 0; minHeight=dp(36); minimumHeight=dp(36); strokeWidth=0
    }
    private fun iconButton(icon: String, label: String, callback: () -> Unit) = ImageButton(this).apply {
        setImageDrawable(ProviderIcon(icon, muted)); background = ProviderGlass.solid(this@MainActivity,android.graphics.Color.TRANSPARENT,24,false); ProviderGlass.feedback(this,24); contentDescription = label
        setPadding(dp(12), dp(12), dp(12), dp(12)); layoutParams = LinearLayout.LayoutParams(dp(46), dp(46)); setOnClickListener { callback() }
        if(icon=="close") {
            setPadding(dp(17),dp(17),dp(17),dp(17))
            background=android.graphics.drawable.InsetDrawable(ProviderGlass.solid(this@MainActivity,tint,99,false),dp(12))
        }
    }
    private fun item(title: String, subtitle: String = "", end: String = "", callback: () -> Unit): LinearLayout = row().apply {
        setPadding(dp(2), dp(7), dp(2), dp(7)); minimumHeight = dp(if(subtitle.isBlank()) 38 else 52)
        background=ProviderGlass.solid(this@MainActivity,android.graphics.Color.TRANSPARENT,10,false); ProviderGlass.feedback(this,10)
        addView(column().apply {
            addView(text(title,15).apply { maxLines=2; ellipsize=android.text.TextUtils.TruncateAt.END })
            if(subtitle.isNotBlank()) addView(text(subtitle,12,muted).apply { maxLines=2; ellipsize=android.text.TextUtils.TruncateAt.END; setPadding(0,dp(4),dp(5),0) })
        },LinearLayout.LayoutParams(0,-2,1f))
        if (end.isNotBlank()) addView(text(end, 12, purple).apply { setPadding(dp(8), 0, dp(5), 0) })
        addView(ImageView(this@MainActivity).apply { setImageDrawable(ProviderIcon("chevron", muted)) }, LinearLayout.LayoutParams(dp(18), dp(18)))
        isClickable = true; isFocusable = true; setOnClickListener { callback() }; contentDescription = "$title${if (subtitle.isNotBlank()) ", $subtitle" else ""}"
    }
    private fun toggle(title: String, subtitle: String, checked: Boolean, callback: (Boolean) -> Unit): LinearLayout = row().apply {
        minimumHeight = dp(if(subtitle.isBlank()) 42 else 50); setPadding(dp(2), dp(2), dp(2), dp(2))
        addView(column().apply { addView(text(title)); if (subtitle.isNotBlank()) addView(text(subtitle, 12, muted).apply { setPadding(0, dp(3), dp(7), 0) }) }, LinearLayout.LayoutParams(0, -2, 1f))
        val control = CapsuleSwitch(this@MainActivity).apply {
            isChecked=checked; contentDescription=title
            setOnCheckedChangeListener { _,value -> callback(value) }
        }
        background=ProviderGlass.solid(this@MainActivity,android.graphics.Color.TRANSPARENT,10,false); ProviderGlass.feedback(this,10)
        addView(control); isClickable = true; setOnClickListener { control.isChecked = !control.isChecked }
    }
    private fun card(block: LinearLayout.() -> Unit) { cardIn(content).apply(block) }
    private fun cardIn(parent: LinearLayout) = column().apply {
        ProviderGlass.surface(this,14)
        val verticalPadding=if(page in setOf("settings","appearance","players","cloud","folders")) 2 else 12
        setPadding(dp(12),dp(verticalPadding),dp(12),dp(verticalPadding))
        if(page in setOf("settings","appearance","players","cloud")) {
            showDividers=LinearLayout.SHOW_DIVIDER_MIDDLE
            dividerDrawable=GradientDrawable().apply { setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(palette.line,95)); setSize(1,dp(1)) }
        }
        parent.addView(this, full(3))
    }
    private fun section(title: String, action: String? = null, callback: (() -> Unit)? = null) {
        content.addView(row().apply {
            addView(text(title, 13, if(page=="home") ink else muted).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }, LinearLayout.LayoutParams(0, dp(24), 1f))
            if (action != null && callback != null) addView(textAction(action, callback = callback))
        }, full(6))
    }
    private fun shape(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun divider(parent: LinearLayout) { parent.addView(View(this).apply { setBackgroundColor(palette.line) }, LinearLayout.LayoutParams(-1, dp(1))) }
    private fun full(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top); bottomMargin = dp(bottom) }
    private fun space(parent: LinearLayout, height: Int) { parent.addView(Space(this), LinearLayout.LayoutParams(1, dp(height))) }
    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()
    private fun toast(value: String) { Toast.makeText(this, value, Toast.LENGTH_SHORT).show() }
    private fun formatDate(time: Long): String = if (time <= 0) "暂无" else SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(time))
    private fun formatTime(time: Long): String = String.format(Locale.ROOT, "%02d:%02d.%03d", time / 60_000, time / 1000 % 60, time % 1000)
}
