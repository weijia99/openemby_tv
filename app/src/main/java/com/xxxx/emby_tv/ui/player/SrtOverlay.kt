package com.xxxx.emby_tv.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/**
 * 由应用自己绘制的字幕
 *
 * SRT 在这里解析成时间轴，播放时按当前进度取对应的一行，交给界面用 Compose 浮层画在视频上。
 *
 * 之所以不用 Media3 自带的字幕链路（MediaItem 的 SubtitleConfiguration / 轨道选择 / TextRenderer）：
 * 中途加入字幕必须重建 MediaItem，那会让播放从头缓冲；而重建后的轨道选择又容易落到服务器的
 * 旧轨道上，实测两者都不可靠。这里完全不碰播放器，只读播放位置，所以字幕即时出现、
 * 换一条也即时生效，播放不中断。
 */
object SrtOverlay {
    private const val TAG = "SrtOverlay"

    /** 一条字幕：[startMs] 到 [endMs]，时间是视频自身的时间轴。 */
    data class Cue(val startMs: Long, val endMs: Long, val text: String)

    /** 当前应显示的文本；null 表示没有字幕或处在两条之间。 */
    var current by mutableStateOf<String?>(null)
        private set

    /** 已载入的提示，界面用来判断是否要显示设置项。 */
    var loadedLabel by mutableStateOf<String?>(null)
        private set

    private var cues: List<Cue> = emptyList()

    /**
     * 字幕时间偏移（毫秒）：正值让字幕延后，负值提前。
     * 机翻字幕的时间轴常与视频对不上，靠这个微调而不必改文件。
     */
    var offsetMs: Long = 0

    /**
     * 读入 [file]（[SubtitleCat] 保存的 SRT）并解析。解析不出任何一条时保留原有字幕不动，
     * 返回 false —— 一个空文件不应该把正在用的字幕清掉。
     */
    fun load(file: File, label: String? = null): Boolean {
        val parsed = try {
            parse(file.readText(Charsets.UTF_8))
        } catch (error: Exception) {
            android.util.Log.w(TAG, "读取 ${file.name} 失败: ${error.message}")
            emptyList()
        }
        if (parsed.isEmpty()) {
            android.util.Log.w(TAG, "${file.name} 里没有可解析的字幕")
            return false
        }
        cues = parsed
        loadedLabel = label ?: file.name
        current = null
        android.util.Log.i(TAG, "已载入 ${file.name}：${cues.size} 条，首条 ${cues.first().startMs}ms")
        return true
    }

    /** 清除当前字幕。 */
    fun clear() {
        cues = emptyList()
        loadedLabel = null
        current = null
    }

    /**
     * 取 [positionMs] 处要显示的一行，没有则 null。界面在播放中反复调用它，
     * 文本变化时才更新 `current` 以触发重组。
     */
    fun lineFor(positionMs: Long): String? {
        val at = positionMs + offsetMs
        val line = cues.firstOrNull { at in it.startMs..it.endMs }?.text
        if (line != current) current = line
        return line
    }

    /** SubRip 文本转时间轴；兼容 CRLF/LF，最后一块可以没有空行。 */
    internal fun parse(srt: String): List<Cue> {
        val timeLine = Regex(
            """(\d{2}):(\d{2}):(\d{2})[,.](\d{1,3})\s*-->\s*(\d{2}):(\d{2}):(\d{2})[,.](\d{1,3})"""
        )
        val cues = mutableListOf<Cue>()
        for (block in srt.replace("\r\n", "\n").split(Regex("\n\\s*\n"))) {
            val lines = block.trim().split('\n')
            val at = lines.indexOfFirst { timeLine.containsMatchIn(it) }
            if (at < 0) continue
            val match = timeLine.find(lines[at]) ?: continue
            val (h1, m1, s1, ms1, h2, m2, s2, ms2) = match.destructured
            val start = h1.toLong() * 3_600_000 + m1.toLong() * 60_000 + s1.toLong() * 1_000 +
                ms1.padEnd(3, '0').toLong()
            val end = h2.toLong() * 3_600_000 + m2.toLong() * 60_000 + s2.toLong() * 1_000 +
                ms2.padEnd(3, '0').toLong()
            val text = lines.drop(at + 1).joinToString("\n").trim()
            if (text.isNotEmpty() && end > start) cues.add(Cue(start, end, text))
        }
        return cues.sortedBy { it.startMs }
    }
}
