/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lrckit

import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine

/**
 * 增强型歌词解析器。
 * 兼容标准 LRC、逐字时间轴及多角色标签（支持行内前缀与状态继承式的独立角色行）。
 * 具备幕后制作人员名单过滤机制，防止角色状态机被污染。
 */
object EnhanceLrcParser {

    /** 严格匹配行首的一个或多个时间戳标签，如 [00:12.34][00:15.00] */
    private val TIME_PREFIX_REGEX = Regex("""^(\[(\d{1,3})[ :.](\d{2})(?:[ :.](\d{1,3}))?])+""")

    /** 匹配单个时间戳标签，用于从前缀字符串中提取具体数值 */
    private val TAG_REGEX = Regex("""\[(\d{1,3})[ :.](\d{2})(?:[ :.](\d{1,3}))?]""")

    /** 匹配逐字时间标签，如 <00:12.34> */
    private val WORD_REGEX = Regex("""<(\d{1,3})[ :.](\d{2})(?:[ :.](\d{1,3}))?>""")

    // 匹配行尾的一个或多个时间戳标签
    private val TIME_SUFFIX_REGEX = Regex("""(\[(\d{1,3})[ :.](\d{2})(?:[ :.](\d{1,3}))?])+$""")

    /** 匹配元数据标签，如 [ar:歌手名] */
    private val META_REGEX = Regex("""^\[(\w+)\s*:\s*(.*)]$""")

    private val CJK_REGEX = Regex("[\u3040-\u30ff\u3400-\u4dbf\u4e00-\u9fff\u1100-\u11ff\u3130-\u318f\uac00-\ud7af]")
    private val LATIN_REGEX = Regex("[A-Za-z]")

    // 角色识别正则
    private val INVISIBLE_ROLE_REGEX = Regex("""^(v\d+|bg|合)[:：]\s*(.+)$""", RegexOption.IGNORE_CASE)
    private val TIMED_INVISIBLE_ROLE_REGEX = Regex(
        """^((?:\s*<\d{1,3}[ :.]\d{2}(?:[ :.]\d{1,3})?>\s*)+)(v\d+|bg|合)[:：]\s*(.*)$""",
        RegexOption.IGNORE_CASE
    )
    private val VISIBLE_ROLE_REGEX = Regex("""^([^\s:：]{1,15})\s*[:：]""")

    // 制作信息黑名单
    private val STAFF_ROLE_EXACT = setOf(
        // 原有简体与已存在的英文/繁体
        "词", "曲", "鼓", "后期", "视觉", "录音", "混音", "混缩", "母带", "企划", "统筹", "出品", "发行", 
        "人声", "vocal", "vocals", "mix", "master", "a&r", "staff", "合音",
        "program", "打击乐", "和声", "箱琴", "电吉它", "笛子", "缩混", "古筝", "笛箫", "双簧管", "口琴",
        "鼓手", "长笛", "萨克斯", "长号", "小号", "詞", "和音", "pgm",

        // 新增繁体
        "後期", "視覺", "錄音", "混縮", "母帶", "企劃", "統籌", "發行", "人聲", "打擊樂", "和聲", 
        "電吉他", "電吉它", "縮混", "古箏", "笛簫", "雙簧管", "長笛", "薩克斯", "長號", "小號",

        // 新增英文
        "words", "melody", "drums", "post-production", "visual", "visuals", "recording", 
        "mixdown", "distribution", "release", "voices", "backing vocal", "chorus", 
        "percussion", "harmony", "acoustic guitar", "electric guitar", "flute", 
        "bamboo flute", "guzheng", "zither", "oboe", "harmonica", "drummer", 
        "saxophone", "sax", "trombone", "trumpet"
    )
    
    private val STAFF_ROLE_KEYWORDS = listOf(
        // 原有简体
        "作词", "作曲", "编曲", "制作", "吉他", "贝斯", "贝司", "键盘", "钢琴", "合成器",
        "监制", "口哨", "midi", "棚", "室", "弦乐", "管乐", "提琴", "程序", "编写",
        "演唱", "原唱", "翻唱", "文案", "设计", "封面", "总监", "声乐", "指导",
        "混音", "混缩", "母带", "企划", "出品", "发行", "统筹", "策划", "伴奏",
        "人声", "录音", "营销", "录制", "公司", "乐器", "编程", "编辑", "著作",
        "项目", "经纪", "混录", "合作", "推广", "原曲", "顾问", "艺人", "版权", "特别", "合唱团",

        // 原有英文
        "lyricist", "composer", "compose", "arranger", "arrange", "producer", "produce",
        "guitar", "bass", "drum", "keyboard", "piano", "synth", "strings",
        "mixing", "mastering", "record", "studio", "engineer", "director",
        "writer", "written", "instrumental", "programmed", "programming", "op", "sp",
        "music", "lyric", "lyrics",

        // 新增繁体
        "作詞", "編曲", "製作", "貝斯", "貝司", "鍵盤", "鋼琴", "監製", "監制", "弦樂", "管樂", 
        "編寫", "設計", "總監", "聲樂", "指導", "混縮", "母帶", "企劃", "發行", "統籌", "策劃", 
        "人聲", "錄音", "營銷", "錄製", "樂器", "編程", "編輯", "項目", "經紀", "混錄", "推廣", 
        "顧問", "藝人", "版權", "特別", "合唱團",

        // 新增英文
        "arrangement", "production", "keyboards", "executive producer", "whistle", 
        "brass", "winds", "violin", "cello", "singer", "cover", "copywriter", "design", 
        "designer", "art", "coach", "guide", "present", "publisher", "publish", "planner", 
        "accompaniment", "backing track", "marketing", "instrument", "instruments", "edit", 
        "editing", "project", "manager", "management", "feat", "featuring", "promotion", 
        "consultant", "advisor", "artist", "choir"
    )

    // 供外部（如本地 JSON 文件）注入的自定义黑名单
    val customStaffRoleExact = mutableSetOf<String>()
    val customStaffRoleKeywords = mutableListOf<String>()

    private fun isStaffRole(roleName: String): Boolean {
        if (roleName in STAFF_ROLE_EXACT || roleName in customStaffRoleExact) return true
        if (STAFF_ROLE_KEYWORDS.any { roleName.contains(it) }) return true
        if (customStaffRoleKeywords.any { roleName.contains(it) }) return true
        return false
    }

    fun matchesStaffHeaderLine(text: String?): Boolean {
        val value = text?.trim().orEmpty()
        if (value.isBlank()) return false
        val roleName = extractVisibleRoleName(value) ?: return false
        if (roleName.toLongOrNull() != null) return false
        return isStaffRole(roleName.lowercase())
    }

    private fun isLikelyRomaText(text: String?): Boolean {
        val value = text?.trim().orEmpty()
        if (value.isBlank()) return false
        return LATIN_REGEX.containsMatchIn(value) && !CJK_REGEX.containsMatchIn(value)
    }

    private fun mergeAuxiliaryLine(
        target: RichLyricLine,
        text: String?,
        words: List<LyricWord>? = null
    ) {
        val value = text?.trim().orEmpty()
        if (value.isBlank()) return

        if (target.secondary?.trim() == value) {
            if (target.secondaryWords.isNullOrEmpty() && !words.isNullOrEmpty()) {
                target.secondaryWords = words
            }
            return
        }
        if (target.translation?.trim() == value) {
            if (target.translationWords.isNullOrEmpty() && !words.isNullOrEmpty()) {
                target.translationWords = words
            }
            return
        }
        if (target.roma?.trim() == value) return

        if (target.secondary.isNullOrBlank() && target.secondaryWords.isNullOrEmpty()) {
            target.secondary = value
            target.secondaryWords = words
            return
        }

        if (target.translation.isNullOrBlank() && target.translationWords.isNullOrEmpty()) {
            val incomingIsRoma = isLikelyRomaText(value)
            val secondaryIsRoma = isLikelyRomaText(target.secondary)
            if (incomingIsRoma && !secondaryIsRoma) {
                target.translation = target.secondary
                target.translationWords = target.secondaryWords
                target.secondary = value
                target.secondaryWords = words
            } else {
                target.translation = value
                target.translationWords = words
            }
            return
        }

        if (target.roma.isNullOrBlank()) {
            target.roma = value
        }
    }

    private class ParseState {
        var currentRole: String? = null
        val primaryRoles = mutableListOf<String>()
    }

    private data class InvisibleRoleMatch(
        val role: String,
        val content: String
    )

    fun parse(raw: String?, duration: Long = 0): EnhanceLrcDocument {
        if (raw.isNullOrBlank()) return EnhanceLrcDocument(emptyMap(), emptyList())

        val lines = mutableListOf<RichLyricLine>()
        val meta = mutableMapOf<String, String>()
        val state = ParseState()
        val parsedTimeGroups = mutableSetOf<String>()

        raw.lineSequence().forEach { rawLine ->
            val trimmed = rawLine.trim()
            if (!trimmed.startsWith("[")) return@forEach

            val timeMatch = TIME_PREFIX_REGEX.find(trimmed)
            if (timeMatch != null) {
                var content = trimmed.substring(timeMatch.range.last + 1).trim()
                val timeTags = timeMatch.value

                var explicitEndTime: Long? = null
                val suffixMatch = TIME_SUFFIX_REGEX.find(content)
                if (suffixMatch != null) {
                    content = content.substring(0, suffixMatch.range.first).trim()
                    val lastTagMatch = TAG_REGEX.findAll(suffixMatch.value).lastOrNull()
                    if (lastTagMatch != null) {
                        explicitEndTime = toMs(
                            lastTagMatch.groupValues[1],
                            lastTagMatch.groupValues[2],
                            lastTagMatch.groupValues.getOrNull(3)
                        )
                    }
                }

                val allowRoleDetection = parsedTimeGroups.add(buildTimeGroupKey(timeTags))
                parseStandardLine(
                    timeTags,
                    content,
                    state,
                    explicitEndTime,
                    allowRoleDetection
                ).forEach { cur ->
                    mergeLines(lines, cur)
                }
            } else {
                handleMeta(trimmed, meta, lines)
            }
        }

        val offset = meta["offset"]?.toLongOrNull() ?: 0L
        val finalizedLines = finalize(lines, duration)

        return EnhanceLrcDocument(meta, finalizedLines).run {
            if (offset != 0L) applyOffset(offset) else this
        }
    }

    private fun mergeLines(lines: MutableList<RichLyricLine>, cur: RichLyricLine) {
        val last = lines.lastOrNull()
        if (last != null && last.begin == cur.begin) {
            mergeAuxiliaryLine(last, cur.text, cur.words)
            if (cur.isAlignedRight) last.isAlignedRight = true
        } else {
            lines.add(cur)
        }
    }

    private fun parseStandardLine(
        timeTags: String,
        content: String,
        state: ParseState,
        explicitEndTime: Long? = null,
        allowRoleDetection: Boolean = true
    ): List<RichLyricLine> {
        var textToParse = content

        extractInvisibleRole(content)?.let {
            val role = it.role
            if (allowRoleDetection) {
                state.currentRole = role
            }
            textToParse = it.content
        }

        // 用 0L 作为模拟行首时间来提取纯文本，避免被真实时间轴干扰
        val wordsTemplate = parseWords(textToParse, 0L, explicitEndTime)
        val plainText = if (wordsTemplate.isNotEmpty()) {
            wordsTemplate.toDisplayText(textToParse)
        } else {
            textToParse
        }

        if (allowRoleDetection) {
            VISIBLE_ROLE_REGEX.find(plainText)?.let { match ->
                val roleName = match.groupValues[1].lowercase()
                if (roleName.toLongOrNull() == null && !isStaffRole(roleName)) {
                    state.currentRole = roleName
                }
            }
        }

        // --- 极简排雷日志：直接写入本地文件 ---
        if (!allowRoleDetection) {
            return TAG_REGEX.findAll(timeTags).map { m ->
                val ms = toMs(m.groupValues[1], m.groupValues[2], m.groupValues.getOrNull(3))
                val words = parseWords(textToParse, ms, explicitEndTime)

                RichLyricLine(
                    begin = words.firstOrNull()?.begin ?: ms,
                    end = explicitEndTime ?: words.lastOrNull()?.end ?: ms,
                    text = plainText,
                    words = words.takeIf { it.isNotEmpty() },
                    isAlignedRight = false
                )
            }.toList()
        }

        val isRight = when (val role = state.currentRole) {
            null, "合", "合唱", "both", "all", "together" -> false
            "bg", "和声", "和音", "伴唱", "chorus", "harmony", "backing", "bvo" -> {
                logToFile("对唱识别标签为: $role")
                true
            }
            else -> {
                if (state.primaryRoles.isEmpty()) {
                    state.primaryRoles.add(role)
                    false
                } else {
                    val right = role != state.primaryRoles.first()
                    if (right) {
                        logToFile("对唱识别标签为: ${state.primaryRoles.first()}、$role")
                    }
                    right
                }
            }
        }

        return TAG_REGEX.findAll(timeTags).map { m ->
            val ms = toMs(m.groupValues[1], m.groupValues[2], m.groupValues.getOrNull(3))
            
            // 携带真正的行首时间 ms 重新解析出绝对准确的 word-level 时间轴
            val words = parseWords(textToParse, ms, explicitEndTime)
            
            RichLyricLine(
                begin = words.firstOrNull()?.begin ?: ms,
                end = explicitEndTime ?: words.lastOrNull()?.end ?: ms,
                text = plainText,
                words = words.takeIf { it.isNotEmpty() },
                isAlignedRight = isRight
            )
        }.toList()
    }

    /**
     * 双模逐字解析器：支持 `<time>字` 和 `字[time]` 两种穿插格式
     */
    private fun extractInvisibleRole(content: String): InvisibleRoleMatch? {
        INVISIBLE_ROLE_REGEX.matchEntire(content)?.let {
            return InvisibleRoleMatch(
                role = it.groupValues[1].lowercase(),
                content = it.groupValues[2]
            )
        }
        TIMED_INVISIBLE_ROLE_REGEX.matchEntire(content)?.let {
            return InvisibleRoleMatch(
                role = it.groupValues[2].lowercase(),
                content = it.groupValues[1] + it.groupValues[3]
            )
        }
        return null
    }

    private fun extractVisibleRoleName(text: String): String? {
        return VISIBLE_ROLE_REGEX.find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun parseWords(content: String, defaultStartTime: Long, explicitEndTime: Long?): List<LyricWord> {
        // 模式 A：尝试匹配标准的 <mm:ss.xx> 格式
        val angleMatches = WORD_REGEX.findAll(content).toList()
        if (angleMatches.isNotEmpty()) {
            return angleMatches.mapIndexedNotNull { i, m ->
                val start = toMs(m.groupValues[1], m.groupValues[2], m.groupValues.getOrNull(3))
                val nextMatch = angleMatches.getOrNull(i + 1)
                val text = content.substring(m.range.last + 1, nextMatch?.range?.first ?: content.length)
                
                if (nextMatch == null && text.trim().isEmpty()) {
                    return@mapIndexedNotNull null
                }
                
                LyricWord(begin = start, text = text).apply {
                    if (nextMatch != null) {
                        end = toMs(nextMatch.groupValues[1], nextMatch.groupValues[2], nextMatch.groupValues.getOrNull(3))
                    } else if (explicitEndTime != null) {
                        end = explicitEndTime
                    }
                    duration = (end - begin).coerceAtLeast(0)
                }
            }
        }

        // 模式 B：尝试匹配被 [mm:ss.xx] 截断穿插的非标格式 (如：清[00:00.436]空[00:00.872])
        val squareMatches = TAG_REGEX.findAll(content).toList()
        if (squareMatches.isNotEmpty()) {
            val words = mutableListOf<LyricWord>()
            var currentStartTime = defaultStartTime
            var currentTextStart = 0

            for (match in squareMatches) {
                val text = content.substring(currentTextStart, match.range.first)
                val nextTime = toMs(match.groupValues[1], match.groupValues[2], match.groupValues.getOrNull(3))
                
                if (text.isNotEmpty() || words.isNotEmpty()) {
                    words.add(LyricWord(begin = currentStartTime, text = text).apply {
                        end = nextTime
                        duration = (end - begin).coerceAtLeast(0)
                    })
                }
                currentStartTime = nextTime
                currentTextStart = match.range.last + 1
            }
            
            val lastText = content.substring(currentTextStart)
            if (lastText.isNotEmpty()) {
                words.add(LyricWord(begin = currentStartTime, text = lastText).apply {
                    if (explicitEndTime != null) {
                        end = explicitEndTime
                        duration = (end - begin).coerceAtLeast(0)
                    }
                })
            }
            return words
        }

        return emptyList()
    }

    private fun toMs(mStr: String, sStr: String, fStr: String?): Long {
        val m = mStr.toLongOrNull() ?: 0L
        val s = sStr.toLongOrNull() ?: 0L
        val ms = when (fStr?.length) {
            1 -> fStr.toLong() * 100
            2 -> fStr.toLong() * 10
            3 -> fStr.toLong()
            else -> 0L
        }
        return m * 60000 + s * 1000 + ms
    }

    private fun buildTimeGroupKey(timeTags: String): String {
        return TAG_REGEX.findAll(timeTags)
            .joinToString(separator = "|") { match ->
                toMs(
                    match.groupValues[1],
                    match.groupValues[2],
                    match.groupValues.getOrNull(3)
                ).toString()
            }
    }

    @Suppress("unused")
    private fun handleMeta(
        line: String,
        meta: MutableMap<String, String>,
        lines: MutableList<RichLyricLine>
    ) {
        META_REGEX.matchEntire(line)?.let { m ->
            val tag = m.groupValues[1].lowercase()
            val value = m.groupValues[2].trim()

            if (tag == "bg" && lines.isNotEmpty()) {
                val words = parseWords(value, lines.last().begin, null)
                lines.last().apply {
                    secondary = if (words.isNotEmpty()) words.toDisplayText(value) else value
                    secondaryWords = words.takeIf { it.isNotEmpty() }
                }
            } else {
                meta[tag] = value
            }
        }
    }

    private fun List<LyricWord>.toDisplayText(default: String? = null): String {
        val joined = joinToString(separator = "") { it.text.orEmpty() }
        if (joined.isBlank()) return default.orEmpty()

        val hasCjk = CJK_REGEX.containsMatchIn(joined)
        val hasLatin = LATIN_REGEX.containsMatchIn(joined)
        return if (hasLatin && !hasCjk) {
            buildString {
                this@toDisplayText.forEach { word ->
                    val text = word.text.orEmpty()
                    if (text.isEmpty()) return@forEach
                    if (isNotEmpty() && !last().isWhitespace() && !text.first().isWhitespace()) {
                        append(' ')
                    }
                    append(text)
                }
            }.trim()
        } else {
            joined
        }
    }

    private fun finalize(lines: List<RichLyricLine>, totalDur: Long): List<RichLyricLine> {
        val sorted = mergeByTimestamp(lines).sortedBy { it.begin }
        sorted.forEachIndexed { i, cur ->
            if (cur.end <= cur.begin) {
                val nextBegin = sorted.getOrNull(i + 1)?.begin
                cur.end = nextBegin ?: if (totalDur > cur.begin) totalDur else cur.begin + 5000L
            }
            cur.duration = (cur.end - cur.begin).coerceAtLeast(0)
        }
        return sorted
    }

    private fun mergeByTimestamp(lines: List<RichLyricLine>): List<RichLyricLine> {
        if (lines.isEmpty()) return emptyList()
        val merged = linkedMapOf<Long, RichLyricLine>()
        lines.forEach { incoming ->
            val existing = merged[incoming.begin]
            if (existing == null) {
                merged[incoming.begin] = incoming
                return@forEach
            }
            if (existing.text.isNullOrBlank() && !incoming.text.isNullOrBlank()) {
                existing.text = incoming.text
            }
            if (existing.words.isNullOrEmpty() && !incoming.words.isNullOrEmpty()) {
                existing.words = incoming.words
            }

            mergeAuxiliaryLine(existing, incoming.text, incoming.words)
            mergeAuxiliaryLine(existing, incoming.secondary, incoming.secondaryWords)
            mergeAuxiliaryLine(existing, incoming.translation, incoming.translationWords)
            if (existing.roma.isNullOrBlank() && !incoming.roma.isNullOrBlank()) {
                existing.roma = incoming.roma
            }
            existing.end = maxOf(existing.end, incoming.end)
            existing.duration = maxOf(existing.duration, incoming.duration)
            existing.isAlignedRight = existing.isAlignedRight || incoming.isAlignedRight
        }
        return merged.values.toList()
    }

    // --- 直接写文件的小工具 ---
    private fun logToFile(msg: String) {
        try {
            val logFile = java.io.File("/storage/emulated/0/Lyrics/lyricon_role_debug.txt")
            if (!logFile.exists()) {
                logFile.parentFile?.mkdirs()
                logFile.createNewFile()
            }
            val time = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
            logFile.appendText("[$time] $msg\n")
        } catch (e: Exception) {
            // 没有存储权限时安静忽略
        }
    }
}
