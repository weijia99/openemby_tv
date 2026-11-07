package com.xxxx.emby_tv.ui.player

import android.util.Log

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 在线字幕搜索与下载（subtitlecat.com）
 *
 * 站点没有 API：搜索是一页链接（`subs/<组>/<名称>.html`），每个结果页列出该字幕的所有语言
 * （`subs/<组>/<名称>-<语言>.srt`）。这里把两页当文本解析，因此可以在播放中按需获取字幕。
 *
 * 下载的 SRT 存到应用私有目录，再交给 [SubtitleConfigBuilder] 作为外挂字幕加载。
 */
object SubtitleCat {
    private const val TAG = "SubtitleCat"
    private const val BASE = "https://www.subtitlecat.com"
    private const val TIMEOUT_MS = 12_000
    private const val MAX_BYTES = 4 * 1024 * 1024
    private const val MAX_RESULTS = 40

    /** 一条搜索结果：[page] 打开它的详情页，[id] 唯一。 */
    data class Hit(val name: String, val page: String, val id: String)

    /** 详情页提供的一种语言：[lang] 是站点代码（"zh-CN"、"en" 等）。 */
    data class Language(val lang: String, val label: String, val url: String)

    /**
     * 搜索 [query]（通常是番号或片名）能匹配到的字幕。
     * 阻塞调用，需在工作线程执行；站点无结果或不可达时返回空列表。
     */
    fun search(query: String): List<Hit> {
        val term = query.trim()
        if (term.isEmpty()) return emptyList()
        val url = "$BASE/index.php?search=" + URLEncoder.encode(term, "UTF-8")
        val html = try {
            fetchText(url)
        } catch (error: Exception) {
            Log.w(TAG, "搜索 '$term' 失败: ${error.message}")
            return emptyList()
        }
        val pattern = Regex("""href="(/?subs/(\d+)/([^"]+)\.html)"""")
        val seen = HashSet<String>()
        return pattern.findAll(html)
            .mapNotNull { match ->
                val page = match.groupValues[1].removePrefix("/")
                val id = match.groupValues[2] + "/" + match.groupValues[3]
                val name = match.groupValues[3]
                    .replace("%20", " ")
                    .replace("%5B", "[").replace("%5D", "]")
                    .replace("%28", "(").replace("%29", ")")
                if (seen.add(id)) Hit(name, page, id) else null
            }
            .take(MAX_RESULTS)
            .toList()
            .also { Log.i(TAG, "'$term': ${it.size} 条字幕") }
    }

    /**
     * [hit] 的详情页提供的语言，中文优先（电视机上最可能的选择），其次英文，其余在后。
     * 阻塞调用，需在工作线程执行。
     */
    fun languages(hit: Hit): List<Language> {
        val html = try {
            fetchText("$BASE/" + hit.page)
        } catch (error: Exception) {
            Log.w(TAG, "详情页 '${hit.name}' 失败: ${error.message}")
            return emptyList()
        }
        val pattern = Regex("""href="(/?subs/\d+/[^"]+?-([A-Za-z]{2}(?:-[A-Za-z]{2})?)\.srt)"""")
        val seen = HashSet<String>()
        val found = pattern.findAll(html).mapNotNull { match ->
            val path = match.groupValues[1].removePrefix("/")
            val lang = match.groupValues[2]
            if (!seen.add(lang)) null else Language(
                lang = lang,
                label = languageName(lang),
                url = "$BASE/" + path.replace(" ", "%20")
            )
        }.toList()
        val rank = { language: Language ->
            when {
                language.lang.startsWith("zh", true) && language.lang.endsWith("CN", true) -> 0
                language.lang.startsWith("zh", true) -> 1
                language.lang.equals("en", true) -> 2
                else -> 3
            }
        }
        return found.sortedWith(compareBy(rank, { it.lang }))
            .also { Log.i(TAG, "'${hit.name}': ${it.size} 种语言") }
    }

    /**
     * 下载 [url] 到本地文件（按 UTF-8 保存，已修正编码），返回该文件。
     * 文件名由 URL 哈希生成，同一语言重复下载会覆盖自身，不会堆副本。
     * 阻塞调用，需在工作线程执行；失败返回 null。
     */
    fun download(context: Context, url: String): File? = try {
        val text = decode(fetchBytes(url))
        // 文件名由 URL 哈希生成：同一语言重复下会覆盖自身，不同语言各自一份，不会堆副本
        val name = "subtitle-" + url.hashCode().toLong().let { if (it < 0) -it else it } + ".srt"
        val file = File(context.filesDir, name)
        file.writeText(text, Charsets.UTF_8)
        Log.i(TAG, "已保存 ${file.name}（${text.length} 字符）")
        file
    } catch (error: Exception) {
        Log.w(TAG, "下载失败: ${error.message}")
        null
    }

    /**
     * 下载并立即显示：存到本地后交给 [SrtOverlay] 解析，由界面自绘在视频上。
     * 全程不碰播放器，所以字幕即时出现，播放不中断、不重新缓冲。
     */
    fun downloadAndShow(context: Context, url: String, label: String): Boolean {
        val file = download(context, url) ?: return false
        return SrtOverlay.load(file, label).also { ok ->
            Log.i(TAG, if (ok) "已加载字幕：$label" else "字幕解析失败：$label")
        }
    }

    /** "zh-CN" → "中文（简体）"；未知代码原样返回。 */
    fun languageName(code: String): String = when (code.lowercase()) {
        "zh-cn" -> "中文（简体）"
        "zh-tw" -> "中文（繁體）"
        "zh" -> "中文"
        "en" -> "English"
        "ja" -> "日本語"
        "ko" -> "한국어"
        "fr" -> "Français"
        "de" -> "Deutsch"
        "es" -> "Español"
        "ru" -> "Русский"
        "pt" -> "Português"
        "it" -> "Italiano"
        "ar" -> "العربية"
        "th" -> "ไทย"
        "vi" -> "Tiếng Việt"
        "id" -> "Indonesia"
        "ms" -> "Melayu"
        "tr" -> "Türkçe"
        "pl" -> "Polski"
        "nl" -> "Nederlands"
        "hi" -> "हिन्दी"
        else -> code
    }

    /**
     * 按 BOM 判断 UTF-8/UTF-16；否则试着按 UTF-8 解码，失败回退 GB18030（GBK 的超集）。
     * 中文 SRT 常见 GBK，直接按 UTF-8 读会乱码。
     */
    internal fun decode(bytes: ByteArray): String {
        fun startsWith(vararg mark: Int) =
            bytes.size >= mark.size && mark.indices.all { bytes[it] == mark[it].toByte() }
        return when {
            startsWith(0xEF, 0xBB, 0xBF) -> String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            startsWith(0xFF, 0xFE) -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            startsWith(0xFE, 0xFF) -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            else -> try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString()
            } catch (notUtf8: CharacterCodingException) {
                String(bytes, Charset.forName("GB18030"))
            }
        }
    }

    private fun fetchText(url: String): String = String(fetchBytes(url), Charsets.UTF_8)

    private fun fetchBytes(url: String): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            // 站点会拒绝没有 User-Agent 的客户端
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) OpenEmby")
            connection.instanceFollowRedirects = true
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw java.io.IOException("HTTP ${connection.responseCode}")
            }
            connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (out.size() < MAX_BYTES) {
                    val read = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES - out.size()))
                    if (read < 0) break
                    out.write(buffer, 0, read)
                }
                if (out.size() >= MAX_BYTES) throw java.io.IOException("超过 ${MAX_BYTES / 1024} KB")
                out.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }
}
