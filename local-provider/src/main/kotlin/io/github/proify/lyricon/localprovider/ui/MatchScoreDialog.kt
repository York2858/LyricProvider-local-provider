package io.github.proify.lyricon.localprovider.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.proify.lyricon.localprovider.search.MatchPart
import io.github.proify.lyricon.localprovider.search.MatchRules
import io.github.proify.lyricon.localprovider.search.MatchScorer
import io.github.proify.lyricon.localprovider.search.MatchTarget
import io.github.proify.lyricon.localprovider.search.SearchCandidate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Compact score summary with one comparison or rule explanation open at a time. */
object MatchScoreDialog {
    fun show(context: Context, reference: MatchTarget, candidate: SearchCandidate,
             source: String, rules: MatchRules): AlertDialog =
        Content(context, reference, candidate, source, rules).show()

    private class Content(private val context: Context, private val reference: MatchTarget,
                          private val candidate: SearchCandidate, private val source: String,
                          rules: MatchRules) {
        private val palette = ProviderTheme.colors(context)
        private val rules = rules.takeIf { it.valid() } ?: MatchRules()
        private val score = MatchScorer.evaluate(reference, candidate.target, this.rules)
        private val activeWeight = score.parts.filter { it.similarity != null }.sumOf { it.weight }
        private val sections = mutableListOf<Section>()
        private data class Section(val header: View, val body: View, val arrow: ImageView)

        private fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()
        private fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        private fun row() = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        private fun full(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(top); bottomMargin = dp(bottom)
        }
        private fun text(value: String, size: Int = 14, color: Int = palette.text) = TextView(context).apply {
            text = value; textSize = size.toFloat(); setTextColor(color)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        private fun number(value: Double): String = if (value == value.toInt().toDouble()) value.toInt().toString()
            else String.format(Locale.getDefault(), "%.1f", value)
        private fun time(value: Long) = if (value <= 0) "未知" else String.format(Locale.getDefault(),
            "%02d:%02d", value / 60000, value / 1000 % 60)
        private fun seconds(value: Long) = number(value / 1000.0) + " 秒"
        private fun maximum(part: MatchPart) = if (part.similarity == null || activeWeight == 0) 0.0
            else part.weight * 100.0 / activeWeight
        private fun divider(parent: LinearLayout) {
            parent.addView(View(context).apply { setBackgroundColor(palette.line); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
                LinearLayout.LayoutParams(-1, dp(1)))
        }
        private fun note(value: String) = text(value, 12, palette.muted)
        private fun metadata(value: String?) = value?.takeIf { it.isNotBlank() } ?: "未知"
        private fun pair(parent: LinearLayout, label: String, value: String) {
            parent.addView(column().apply {
                addView(note(label), full())
                addView(text(value, 13), full(3))
            }, full(8))
        }

        fun show(): AlertDialog {
            val requiredFields = listOf(rules.requireTitle, rules.requireArtist, rules.requireAlbum, rules.requireDuration)
            val requiredSummary = when {
                requiredFields.none { it } -> "未设置必需项"
                score.parts.indices.any { requiredFields[it] && score.parts[it].similarity == null } -> "已知必需项已通过"
                else -> "必需项已通过"
            }
            val body = column().apply {
                tag = "matchScoreContent"
                setPadding(dp(24), dp(24), dp(24), 0)
                addView(text("匹配 ${score.total} 分", 20), full(bottom = 12))
                addView(text(if (score.eligible) "满足自动匹配条件" else "未满足自动匹配条件", 14,
                    palette.primary), full())
                addView(note(if (score.eligible) "最低要求 ${rules.minimumScore} 分 · $requiredSummary"
                    else score.reasons.joinToString("\n")), full(3))
                if (!score.eligible) addView(note("仍可手动预览和选择"), full(3))
                addView(text(listOfNotNull(candidate.song.name?.takeIf { it.isNotBlank() },
                    candidate.song.artist?.takeIf { it.isNotBlank() }).joinToString(" · ").ifBlank { "歌曲信息未知" }), full(14))
                addView(note(listOf(source, candidate.album.ifBlank { "专辑未知" },
                    if (candidate.song.duration > 0) time(candidate.song.duration) else "时长未知")
                    .joinToString(" · ")), full(3, 14))
                divider(this)
                addView(text("分项得分", 13), full(12))
                addView(note("点击展开对比 · 得分 / 本项满分"), full(3, 2))
                score.parts.forEachIndexed { index, part -> addPart(this, index, part) }
                val explanation = column().apply {
                    tag = "matchScoreRulesBody"
                    pair(this, "计分字段", "${score.parts.count { it.similarity != null && it.weight > 0 }} 项 · 有效权重 $activeWeight")
                    pair(this, "最低分", "${score.total} ${if (score.total >= rules.minimumScore) "≥" else "<"} ${rules.minimumScore}")
                    pair(this, "必需条件", buildList {
                        if (rules.requireTitle) add("标题相似度 ≥ 60%")
                        if (rules.requireArtist) add("艺术家相似度 ≥ 60%")
                        if (rules.requireAlbum) add("专辑相似度 ≥ 60%")
                        if (rules.requireDuration) add("时长相差不超过 ${seconds(rules.durationToleranceMs)}")
                    }.joinToString("\n").ifBlank { "未开启" })
                    addView(note("未知的参考字段不参与计分，其余项按有效权重归一到 100 分。参考已知但结果缺失时，该项计 0 分。权重为 0 的项不贡献分数，但已开启的必需条件仍会检查。"), full(10))
                    addView(note("分项得分 = 相似度 × 权重 ÷ 有效权重 × 100；总分由未舍入的分项求和后四舍五入。"), full(8))
                    addView(note("评分比较歌曲信息，不评价歌词内容或时间轴质量。满足条件表示可参与自动匹配，不代表一定选中。"), full(8, 8))
                }
                addSection(this, "rules", "评分与自动匹配规则", "", null, explanation)
            }
            val scroll = object : ScrollView(context) {
                override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                    // Leave room for system bars, dialog insets and the always-visible dismiss button.
                    val cap = (resources.displayMetrics.heightPixels * .68f).toInt()
                    val available = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) cap
                        else minOf(cap, MeasureSpec.getSize(heightMeasureSpec))
                    super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST))
                }
            }.apply {
                isFillViewport = false; isVerticalScrollBarEnabled = true
                addView(body, ViewGroup.LayoutParams(-1, -2))
            }
            val layout = column().apply {
                // Shrink the scroll area first when the window cannot fit the entire dialog.
                addView(scroll, LinearLayout.LayoutParams(-1, -2, 1f))
            }
            val dialog = ProviderDialogBuilder(context)
                .setBackground(ProviderGlass.panel(context,radius=24))
                .setView(layout).create()
            layout.addView(row().apply {
                gravity = Gravity.END
                setPadding(dp(24), dp(8), dp(24), dp(16))
                addView(MaterialButton(context).apply {
                    text = "知道了"; textSize = 14f; isAllCaps = false
                    cornerRadius = dp(22); minHeight = dp(44); minimumHeight = dp(44)
                    insetTop = 0; insetBottom = 0
                    backgroundTintList = ColorStateList.valueOf(palette.primary)
                    setTextColor(palette.onPrimary); setOnClickListener { dialog.dismiss() }
                })
            }, full())
            dialog.show()
            dialog.window?.setLayout(minOf(context.resources.displayMetrics.widthPixels, dp(420)), ViewGroup.LayoutParams.WRAP_CONTENT)
            return dialog
        }

        private fun addPart(parent: LinearLayout, index: Int, part: MatchPart) {
            val target = candidate.target
            val referenceValue = when (index) { 0 -> metadata(reference.title); 1 -> metadata(reference.artist)
                2 -> metadata(reference.album); else -> time(reference.durationMs) }
            val resultValue = when (index) { 0 -> metadata(target.title); 1 -> metadata(target.artist)
                2 -> metadata(target.album); else -> time(target.durationMs) }
            val resultMissing = when (index) { 0 -> target.title.isNullOrBlank(); 1 -> target.artist.isNullOrBlank()
                2 -> target.album.isNullOrBlank(); else -> target.durationMs <= 0 }
            val summary = when {
                part.similarity == null -> "参考未知"
                part.weight == 0 -> "权重为 0"
                index == 3 && target.durationMs > 0 -> "差 ${seconds(abs(reference.durationMs - target.durationMs))} · ${percent(part)}"
                resultMissing -> "结果缺失 · 0%"
                else -> "${if (part.similarity == 1.0) "一致" else "不一致"} · ${percent(part)}"
            }
            val content = column().apply {
                tag = "matchScorePartBody:$index"
                setPadding(0, 0, 0, dp(12))
                pair(this, "参考信息", referenceValue)
                pair(this, "搜索结果", resultValue)
                if (part.similarity != null && part.weight > 0) addView(ProgressBar(context, null,
                    android.R.attr.progressBarStyleHorizontal).apply {
                    max = 1000; progress = (part.similarity * 1000).roundToInt()
                    progressTintList = ColorStateList.valueOf(palette.primary)
                    progressBackgroundTintList = ColorStateList.valueOf(palette.line)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(-1, dp(4)).apply { topMargin = dp(10) })
                val mode = when (index) { 0 -> rules.titleMode; 1 -> rules.artistMode; else -> rules.albumMode }
                val method = when (mode) { "exact" -> "完全一致"; "normalized" -> "忽略大小写与标点"; else -> "相似度匹配" }
                val required = when (index) { 0 -> rules.requireTitle; 1 -> rules.requireArtist
                    2 -> rules.requireAlbum; else -> rules.requireDuration }
                val passed = part.similarity == null || if (index == 3) target.durationMs > 0 &&
                    abs(reference.durationMs - target.durationMs) <= rules.durationToleranceMs else part.similarity >= .6
                val condition = when {
                    !required -> "非必需项"
                    part.similarity == null -> "参考未知，跳过必需检查"
                    passed -> "必需项已通过"
                    else -> "必需项未通过"
                }
                val explanation = when {
                    part.similarity == null -> "参考信息未知，不参与计分。"
                    part.weight == 0 -> "权重为 0，不贡献分数。相似度 ${percent(part)}。"
                    else -> "权重 ${part.weight} · 相似度 ${percent(part)} · 扣 ${number(maximum(part) - part.points)} 分。"
                }
                addView(note(explanation), full(8))
                addView(note(if (index == 3) "相差不超过 1 秒得满分，之后逐步扣分；容差 ${seconds(rules.durationToleranceMs)}。"
                    else method + if (index == 0 && mode != "exact") "；评分会移除括号内的非版本别名，保留现场、混音等版本信息。" else "。"), full(4))
                addView(note(condition), full(4))
            }
            val points = if (part.similarity == null || part.weight == 0) "—"
                else "${number(part.points)} / ${number(maximum(part))}"
            addSection(parent, index.toString(), part.label, summary, points, content)
            divider(parent)
        }

        private fun percent(part: MatchPart) = "${((part.similarity ?: 0.0) * 100).toInt()}%"

        private fun addSection(parent: LinearLayout, key: String, label: String, summary: String,
                               points: String?, body: LinearLayout) {
            val arrow = ImageView(context).apply {
                setImageDrawable(ProviderIcon("down", palette.muted))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val header = row().apply {
                tag = "matchScoreSection:$key"
                minimumHeight = dp(48); isClickable = true; isFocusable = true
                setPadding(0, dp(8), 0, dp(8))
                val attrs = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
                background = attrs.getDrawable(0); attrs.recycle()
                addView(column().apply {
                    addView(text(label, if (points == null) 13 else 14,
                        if (points == null) palette.muted else palette.text), full())
                    if (summary.isNotEmpty()) addView(note(summary), full(3))
                }, LinearLayout.LayoutParams(0, -2, 1f))
                if (points != null) addView(text(points, 13).apply {
                    setSingleLine(); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
                addView(arrow, LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginStart = dp(8) })
                for (i in 0 until childCount) getChildAt(i).importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
            val description = listOfNotNull(label, summary.takeIf { it.isNotBlank() }, points).joinToString("，")
            header.contentDescription = description
            ViewCompat.setStateDescription(header, "已收起")
            body.visibility = View.GONE
            val section = Section(header, body, arrow)
            sections += section
            header.setOnClickListener {
                val expand = body.visibility != View.VISIBLE
                sections.forEach { item ->
                    val open = item === section && expand
                    item.body.visibility = if (open) View.VISIBLE else View.GONE
                    item.arrow.rotation = if (open) 180f else 0f
                    ViewCompat.setStateDescription(item.header, if (open) "已展开" else "已收起")
                }
            }
            parent.addView(header, full())
            parent.addView(body, full())
        }
    }
}
