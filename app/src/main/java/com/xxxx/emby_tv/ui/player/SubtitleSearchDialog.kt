package com.xxxx.emby_tv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 深色底 + 高对比字色：电视上远看也清楚。 */
private val PanelTop = Color(0xFF1C1A26)
private val PanelBottom = Color(0xFF15131D)
private val TextPrimary = Color(0xFFF2F0FF)
private val TextSecondary = Color(0xFFA9A3C0)
private val Accent = Color(0xFF9B87F5)
private val AccentBusy = Color(0xFF7E6BD8)

/**
 * 在线字幕搜索对话框
 *
 * 两级：按片名/番号搜索 → 选中一条结果后选语言。每行都给出足够信息（名称、语言、
 * 语言数量），避免只看到一行代码无所适从。下载到本机后经 [onDownloaded] 交回播放页
 * 作为外挂字幕加载。
 *
 * 界面为深色高对比，遥控器方向键可走遍所有行，OK 进入下一级，Back（[onDismiss]）退出。
 */
@Composable
fun SubtitleSearchDialog(
    initialQuery: String,
    onDownloaded: (File?, String) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf(initialQuery) }
    var results by remember { mutableStateOf<List<SubtitleCat.Hit>>(emptyList()) }
    var expanded by remember { mutableStateOf<SubtitleCat.Hit?>(null) }
    var languages by remember { mutableStateOf<List<SubtitleCat.Language>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf<String?>(null) }
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext

    // 打开即搜索。searchTrigger 每次进入都是 0，但 LaunchedEffect 会随本组合首次执行；
    // 另外靠 query 变化重搜（输入框改词）。
    LaunchedEffect(query) {
        if (query.isBlank()) return@LaunchedEffect
        busy = true
        searched = false
        results = withContext(Dispatchers.IO) { SubtitleCat.search(query) }
        busy = false
        searched = true
        android.util.Log.i("SubtitleCat", "搜索 '$query' → ${results.size} 条")
    }

    LaunchedEffect(expanded) {
        val hit = expanded ?: return@LaunchedEffect
        busy = true
        languages = withContext(Dispatchers.IO) { SubtitleCat.languages(hit) }
        busy = false
        android.util.Log.i("SubtitleCat", "'${hit.name}' → ${languages.size} 种语言")
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = true)
    ) {
        Box(
            modifier = Modifier
                .width(920.dp)
                .heightIn(max = 820.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Brush.verticalGradient(listOf(PanelTop, PanelBottom)))
                .padding(32.dp)
        ) {
            Column(Modifier.fillMaxWidth()) {
                // 标题行
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (expanded == null) "搜索字幕" else "选择语言",
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Spacer(Modifier.width(12.dp))
                    if (busy) {
                        CircularProgressIndicator(
                            color = AccentBusy, strokeWidth = 2.dp,
                            modifier = Modifier.width(20.dp).height(20.dp)
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (expanded == null) "字幕来源 subtitlecat.com"
                    else "来自「${expanded?.name.orEmpty()}」",
                    fontSize = 14.sp,
                    color = TextSecondary
                )
                Spacer(Modifier.height(20.dp))

                if (expanded == null) {
                    // 输入框：当前搜索词，可用遥控器方向键聚焦后改
                    var focused by remember { mutableStateOf(false) }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF2A2740))
                            .onFocusChanged { focused = it.isFocused }
                            .padding(horizontal = 16.dp, vertical = 14.dp)
                    ) {
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            textStyle = TextStyle(color = TextPrimary, fontSize = 18.sp),
                            cursorBrush = SolidColor(Accent),
                            modifier = Modifier.fillMaxWidth(),
                            decorationBox = { inner ->
                                if (query.isEmpty()) {
                                    Text("片名或番号，如 abf-349", fontSize = 18.sp, color = TextSecondary)
                                }
                                inner()
                            }
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "共 ${results.size} 条结果 · 方向键选择，OK 查看语言",
                        fontSize = 13.sp,
                        color = TextSecondary
                    )
                    Spacer(Modifier.height(12.dp))
                    if (searched && results.isEmpty() && !busy) {
                        Box(Modifier.fillMaxWidth().padding(36.dp), contentAlignment = Alignment.Center) {
                            Text("没有找到，换个名字试试（例如只用番号）", color = TextSecondary, fontSize = 16.sp)
                        }
                    } else {
                        LazyColumn(Modifier.heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(results) { hit ->
                                OptionRow(
                                    title = hit.name,
                                    subtitle = hit.id,
                                    trailing = "查看语言",
                                    initialFocus = false
                                ) { expanded = hit }
                            }
                        }
                    }
                } else {
                    Text(
                        "共 ${languages.size} 种语言 · 中文已排在最前",
                        fontSize = 13.sp,
                        color = TextSecondary
                    )
                    Spacer(Modifier.height(12.dp))
                    if (languages.isEmpty() && !busy) {
                        Box(Modifier.fillMaxWidth().padding(36.dp), contentAlignment = Alignment.Center) {
                            Text("这条字幕没有可用语言", color = TextSecondary, fontSize = 16.sp)
                        }
                    } else {
                        LazyColumn(Modifier.heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(languages) { language ->
                                OptionRow(
                                    title = language.label,
                                    subtitle = "下载后立即显示，无需重启",
                                    trailing = language.lang,
                                    initialFocus = language === languages.firstOrNull()
                                ) {
                                    if (downloading == null) {
                                        downloading = language.label
                                        Thread {
                                            // 下载后直接交给自绘字幕层：不重建 MediaItem，
                                            // 所以播放不中断、不重新缓冲，字幕立即出现
                                            val ok = SubtitleCat.downloadAndShow(
                                                appContext, language.url, language.label
                                            )
                                            if (ok) {
                                                onDownloaded(null, language.label)
                                                onDismiss()
                                            } else {
                                                downloading = null
                                            }
                                        }.start()
                                    }
                                }
                            }
                        }
                    }
                }

                if (downloading != null) {
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            color = AccentBusy, strokeWidth = 2.dp,
                            modifier = Modifier.width(18.dp).height(18.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text("正在下载「$downloading」…", color = Accent, fontSize = 15.sp)
                    }
                }
            }
        }
    }
}

/**
 * 列表行：主标题、次要说明、右侧标签。选中时整行高亮为强调色，
 * 文字转深色，电视上对比明确。
 */
@Composable
private fun OptionRow(
    title: String,
    subtitle: String?,
    trailing: String?,
    initialFocus: Boolean = false,
    onClick: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(initialFocus) }
    LaunchedEffect(initialFocus) {
        if (initialFocus) runCatching { focusRequester.requestFocus() }
    }
    // 用 Box + clickable 而不是 tv-material3 的 Surface：
    // Surface 在未聚焦时会把 contentColor 压得很暗，导致"只有选中才看得见"。
    // 这里自己管理底色与字色，未聚焦时也用高对比的白字。
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(12.dp))
            .background(if (focused) Accent else Color(0xFF252236))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (focused) Color(0xFF12101A) else Color(0xFFF2F0FF),
                    maxLines = 2
                )
                if (!subtitle.isNullOrBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = subtitle,
                        fontSize = 12.sp,
                        color = if (focused) Color(0xFF12101A).copy(alpha = 0.75f) else Color(0xFFA9A3C0),
                        maxLines = 1
                    )
                }
            }
            if (!trailing.isNullOrBlank()) {
                Spacer(Modifier.width(16.dp))
                Text(
                    text = trailing,
                    fontSize = 14.sp,
                    color = if (focused) Color(0xFF12101A).copy(alpha = 0.85f) else Accent
                )
            }
        }
    }
}
