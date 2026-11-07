package com.xxxx.emby_tv.ui.player

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import com.xxxx.emby_tv.data.model.MediaStreamDto

/**
 * 字幕配置构建器
 * 
 * 负责构建 ExoPlayer 的字幕配置
 */
object SubtitleConfigBuilder {
    private const val TAG = "SubtitleConfigBuilder"

    /**
     * 构建字幕配置列表
     * 
     * @param subtitleTracks Emby 返回的字幕轨道列表
     * @param serverUrl 服务器地址
     * @param mediaId 媒体ID
     * @param mediaSourceId 媒体源ID
     * @param apiKey API密钥
     * @param selectedSubtitleIndex 当前选中的字幕索引
     * @param serverDeliveredIndices 需要 Emby 服务器提取外挂的字幕索引集合
     *        （如内嵌轨被 zlib 压缩，media3 无法解析，只能改走 Stream.srt）
     * @return 字幕配置列表
     */
    fun buildSubtitleConfigs(
        subtitleTracks: List<MediaStreamDto>,
        serverUrl: String,
        mediaId: String,
        mediaSourceId: String,
        apiKey: String,
        selectedSubtitleIndex: Int,
        serverDeliveredIndices: Set<Int> = emptySet(),
        /**
         * 本地下载的字幕（在线搜索而来），与 Emby 的轨道并列。
         * 每条是「文件 + 显示名 + 语言」，索引用负数，避免和服务器的 index 冲突。
         */
        localSubtitles: List<LocalSubtitle> = emptyList()
    ): List<MediaItem.SubtitleConfiguration> {
        val subtitleConfigs = mutableListOf<MediaItem.SubtitleConfiguration>()

        // 本地下载的字幕先加入：文件就在本机，media3 直接读取，无需服务器参与
        localSubtitles.forEachIndexed { position, local ->
            val config = MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(local.file))
                .setId(LOCAL_ID_PREFIX + position)
                .setMimeType(MimeTypes.APPLICATION_SUBRIP)
                .setLanguage(local.language)
                .setLabel("${local.label} [本地]")
                .setSelectionFlags(if (local.selected) C.SELECTION_FLAG_DEFAULT else 0)
                .build()
            subtitleConfigs.add(config)
        }

//        Log.d(TAG, "=== Building Subtitle Configurations ===")
//        Log.d(TAG, "Total subtitleTracks: ${subtitleTracks.size}")

        subtitleTracks.forEach { track ->
            val index = track.index ?: 0
            val codec = track.codec?.lowercase() ?: "srt"
            val label = track.displayTitle ?: "Subtitle"
            val lang = track.language ?: "und"

            // 判断是否可以通过URL加载（外置文件，或内嵌轨因压缩等原因需服务器提取）
            val isLoadableSubtitle = track.isExternal == true || index in serverDeliveredIndices

//            Log.d(TAG, "Track[$index]: Codec=$codec, IsExternal=${track.isExternal}, SupportsExternal=${track.supportsExternalStream}, Loadable=$isLoadableSubtitle")

            if (isLoadableSubtitle) {
                // 对于 ASS/SSA/SUBRIP 请求 SRT 格式，让 Emby 转码
                val requestFormat = when {
                    codec.contains("ass") || codec.contains("ssa") || codec.contains("subrip") -> "srt"
                    codec.contains("vtt") -> "vtt"
                    else -> "srt"
                }

                val subUrl = "${serverUrl}/emby/Videos/$mediaId/$mediaSourceId/Subtitles/$index/Stream.$requestFormat?api_key=$apiKey"

                // 根据请求格式确定 MIME 类型
                val mimeType = when (requestFormat) {
                    "vtt" -> MimeTypes.TEXT_VTT
                    else -> MimeTypes.APPLICATION_SUBRIP
                }

                // 使用 "Label [Index]" 格式作为唯一标识，防止 ExoPlayer 自动去重
                val uniqueLabel = "$label [$index]"

                val config = MediaItem.SubtitleConfiguration.Builder(Uri.parse(subUrl))
                    .setId(index.toString())
                    .setMimeType(mimeType)
                    .setLanguage(lang)
                    .setLabel(uniqueLabel)
                    .setSelectionFlags(if (index == selectedSubtitleIndex) C.SELECTION_FLAG_DEFAULT else 0)
                    .build()

                subtitleConfigs.add(config)
//                Log.d(TAG, "Added subtitle config: Index=$index, URL=$subUrl, UniqueLabel=$uniqueLabel")
            }
        }

//        Log.d(TAG, "Total subtitle configs added: ${subtitleConfigs.size}")
        return subtitleConfigs
    }

    /**
     * 获取字幕的 MIME 类型
     */
    fun getMimeType(codec: String): String {
        return when {
            codec.contains("ass") || codec.contains("ssa") -> MimeTypes.TEXT_SSA
            codec.contains("vtt") || codec.contains("webvtt") -> MimeTypes.TEXT_VTT
            codec.contains("srt") || codec.contains("subrip") -> MimeTypes.APPLICATION_SUBRIP
            codec.contains("pgs") || codec.contains("hdmv_pgs") -> MimeTypes.APPLICATION_PGS
            codec.contains("dvb") || codec.contains("dvbsub") -> MimeTypes.APPLICATION_DVBSUBS
            else -> MimeTypes.APPLICATION_SUBRIP
        }
    }
    /** 本地字幕的 id 前缀，和服务器的数字 index 区分开。 */
    const val LOCAL_ID_PREFIX = "local-"
}

/**
 * 本地字幕：从网上搜索下载到本机的 SRT 文件。
 * [label] 显示在字幕菜单里（通常含片名和语言），[language] 是媒体语言标记。
 */
data class LocalSubtitle(
    val file: java.io.File,
    val label: String,
    val language: String,
    val selected: Boolean = false
)
