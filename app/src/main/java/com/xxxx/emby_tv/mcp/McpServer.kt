package com.xxxx.emby_tv.mcp

import android.content.Context
import android.content.Intent
import android.util.Log
import com.xxxx.emby_tv.MainActivity
import com.xxxx.emby_tv.data.model.BaseItemDto
import com.xxxx.emby_tv.data.model.MediaStreamDto
import com.xxxx.emby_tv.data.repository.EmbyRepository
import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MCP (Model Context Protocol) server embedded in OpenEmby TV.
 *
 * JSON-RPC 2.0 over HTTP POST /mcp, one response per request (the
 * "streamable HTTP" transport without a server-initiated stream). Every tool
 * maps onto the app's own Emby data layer and player route, so playback keeps
 * the app's subtitle / audio-track / quality handling instead of duplicating it.
 */
object McpServer {
    private const val TAG = "EmbyTvMcp"
    const val PATH = "/mcp"
    const val DEFAULT_PORT = 8765

    private val started = AtomicBoolean(false)

    @Volatile
    private var context: Context? = null

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    var port: Int = -1
        private set

    fun start(appContext: Context, preferredPort: Int = DEFAULT_PORT) {
        context = appContext.applicationContext
        if (!started.compareAndSet(false, true)) return
        Thread({
            bind(if (preferredPort > 0) preferredPort else DEFAULT_PORT)
        }, "embytv-mcp").apply { isDaemon = true }.start()
    }

    private fun bind(preferred: Int) {
        val candidates = if (preferred == DEFAULT_PORT) intArrayOf(DEFAULT_PORT, 8766, 8767, 0) else intArrayOf(preferred, 0)
        for (candidate in candidates) {
            try {
                val socket = ServerSocket(candidate, 16, InetAddress.getByName("0.0.0.0"))
                server = socket
                port = socket.localPort
                Log.i(TAG, "MCP listening on 0.0.0.0:$port$PATH")
                acceptLoop(socket)
                return
            } catch (e: IOException) {
                Log.w(TAG, "bind $candidate failed: ${e.message}")
            }
        }
        Log.e(TAG, "MCP could not bind any port")
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            try {
                val client = socket.accept()
                Thread({ serve(client) }, "embytv-mcp-conn").apply { isDaemon = true }.start()
            } catch (e: IOException) {
                if (socket.isClosed) return
                Log.w(TAG, "accept: ${e.message}")
            }
        }
    }

    private fun serve(client: Socket) {
        var responded = false
        try {
            // Tool calls that hit a slow Emby server (cold index, first search after a
            // site switch) can legitimately take a while; a short read timeout here
            // closed the socket before the reply was ready.
            client.soTimeout = 180000
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            var contentLength = 0
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0 && line.substring(0, colon).trim().equals("Content-Length", true)) {
                    contentLength = line.substring(colon + 1).trim().toIntOrNull() ?: 0
                }
            }

            val out = client.getOutputStream()
            val parts = requestLine.split(" ")
            val method = parts.getOrElse(0) { "" }
            val target = parts.getOrElse(1) { "" }
            val path = target.substringBefore('?').ifEmpty { "/" }

            if (method == "GET" && (path == "/" || path == "/health")) {
                responded = true
                respond(out, 200, """{"ok":true,"server":"openemby-tv-mcp","port":$port}""")
                return
            }
            if (path != PATH) {
                responded = true
                respond(out, 404, """{"error":"not found; POST JSON-RPC to $PATH"}""")
                return
            }
            if (method == "GET") {
                responded = true
                respond(out, 405, """{"error":"this MCP endpoint is POST-only (no server-initiated stream)"}""")
                return
            }
            if (method == "OPTIONS") {
                responded = true
                respond(out, 200, "{}")
                return
            }
            if (method != "POST") {
                responded = true
                respond(out, 405, """{"error":"method not allowed"}""")
                return
            }
            if (contentLength > 4 * 1024 * 1024) {
                responded = true
                respond(out, 413, """{"jsonrpc":"2.0","id":null,"error":{"code":-32600,"message":"body too large"}}""")
                return
            }

            val body = CharArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = reader.read(body, read, contentLength - read)
                if (n < 0) break
                read += n
            }
            responded = true
            respond(out, 200, handle(String(body, 0, read)))
        } catch (e: Throwable) {
            Log.w(TAG, "serve: $e")
            if (!responded) {
                try {
                    respond(client.getOutputStream(), 500,
                        """{"jsonrpc":"2.0","id":null,"error":{"code":-32603,"message":${Json.write(e.toString())}}}""")
                } catch (_: Throwable) {
                }
            }
        } finally {
            try { client.close() } catch (_: IOException) {}
        }
    }

    private fun respond(out: OutputStream, status: Int, json: String) {
        val payload = json.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 $status ${reason(status)}\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ${payload.size}\r\n")
            append("Connection: close\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Headers: content-type, mcp-protocol-version, mcp-session-id\r\n")
            append("Access-Control-Allow-Methods: POST, GET, OPTIONS\r\n")
            append("\r\n")
        }
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(payload)
        out.flush()
    }

    private fun reason(status: Int) = when (status) {
        200 -> "OK"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        413 -> "Payload Too Large"
        else -> "Error"
    }

    // ------------------------------------------------------------------ JSON-RPC

    private fun handle(body: String): String {
        val parsed = try {
            Json.parse(body)
        } catch (e: Json.ParseError) {
            return """{"jsonrpc":"2.0","id":null,"error":{"code":-32700,"message":${Json.write("parse error: ${e.message}")}}}"""
        }
        if (parsed is List<*>) {
            val responses = parsed.mapNotNull { handleOne(it) }
            return if (responses.isEmpty()) "" else Json.write(responses)
        }
        return handleOne(parsed)?.let { Json.write(it) } ?: ""
    }

    private fun handleOne(request: Any?): Map<String, Any?>? {
        if (request !is Map<*, *>) return error(null, -32600, "request must be an object")
        val id = request["id"]
        val method = request["method"]?.toString() ?: return null
        @Suppress("UNCHECKED_CAST")
        val params = (request["params"] as? Map<String, Any?>) ?: emptyMap()

        return try {
            when (method) {
                "initialize" -> result(id, initializeResult())
                "notifications/initialized" -> null
                "ping" -> result(id, emptyMap<String, Any?>())
                "tools/list" -> result(id, toolsList())
                "tools/call" -> result(id, callTool(params))
                "resources/list" -> result(id, mapOf("resources" to emptyList<Any>()))
                "prompts/list" -> result(id, mapOf("prompts" to emptyList<Any>()))
                else -> error(id, -32601, "method not found: $method")
            }
        } catch (e: ToolError) {
            error(id, -32602, e.message ?: "invalid arguments")
        } catch (e: Throwable) {
            Log.w(TAG, "tool $method failed: $e")
            error(id, -32603, e.toString())
        }
    }

    private fun result(id: Any?, payload: Any?): Map<String, Any?> =
        mapOf("jsonrpc" to "2.0", "id" to id, "result" to payload)

    private fun error(id: Any?, code: Int, message: String): Map<String, Any?> =
        mapOf("jsonrpc" to "2.0", "id" to id, "error" to mapOf("code" to code, "message" to message))

    private fun initializeResult(): Map<String, Any?> = mapOf(
        "protocolVersion" to "2025-06-18",
        "capabilities" to mapOf("tools" to emptyMap<String, Any?>()),
        "serverInfo" to mapOf("name" to "openemby-tv", "version" to "1.0.0"),
        "instructions" to
            "Controls OpenEmby TV: browse the signed-in Emby server, then open the app's player page " +
            "for an item. Playback keeps the app's own subtitle, audio-track and quality handling."
    )

    // ------------------------------------------------------------------ tools

    private fun toolsList(): Map<String, Any?> = mapOf(
        "tools" to listOf(
            tool(
                "list_libraries",
                "List the media libraries (views) on the signed-in Emby server.",
                schema(emptyMap(), emptyList())
            ),
            tool(
                "list_items",
                "List items in a library, or the children of a folder/series. Items and folders both " +
                "come back with their Emby id; pass a returned id to open_item.",
                schema(
                    mapOf(
                        "parent_id" to prop("string", "Library or folder id. Omit to list the top-level libraries."),
                        "item_type" to prop("string", "Optional filter: Series, Movie, Episode, Folder."),
                        "limit" to prop("number", "Maximum items to return (default 30)."),
                        "start_index" to prop("number", "Offset for paging (default 0)."),
                        "search" to prop("string", "Optional name filter applied by the server.")
                    ),
                    emptyList()
                )
            ),
            tool(
                "search",
                "Search the whole library by name.",
                schema(
                    mapOf(
                        "query" to prop("string", "Search text."),
                        "limit" to prop("number", "Maximum results (default 20).")
                    ),
                    listOf("query")
                )
            ),
            tool(
                "get_item",
                "Read one item's details: name, type, overview, runtime, and its playable streams " +
                "(video, audio tracks, subtitles) with indexes.",
                schema(
                    mapOf("item_id" to prop("string", "Emby item id.")),
                    listOf("item_id")
                )
            ),
            tool(
                "open_item",
                "Open the app's player page for an item, exactly as selecting it in the UI would. " +
                "Pass audio_index / subtitle_index to preselect a track (index -1 disables subtitles).",
                schema(
                    mapOf(
                        "item_id" to prop("string", "Emby item id."),
                        "position_ticks" to prop("number", "Resume position in 100-ns ticks (default 0)."),
                        "audio_index" to prop("number", "Preferred audio stream index."),
                        "subtitle_index" to prop("number", "Preferred subtitle stream index; -1 turns subtitles off.")
                    ),
                    listOf("item_id")
                )
            ),
            tool(
                "list_resume",
                "Items the server says are partially watched, newest activity first.",
                schema(emptyMap(), emptyList())
            ),
            tool(
                "now_playing",
                "Sessions currently playing on the server, with what they are playing and how far in.",
                schema(emptyMap(), emptyList())
            ),
            tool(
                "list_sites",
                "List the Emby servers this app is signed in to, and which one is active. " +
                "Pass an id to use_site, or the name/host to search and open_item.",
                schema(emptyMap(), emptyList())
            ),
            tool(
                "use_site",
                "Switch the app to another signed-in Emby server. Every later search/open_item targets it.",
                schema(
                    mapOf("site" to prop("string", "Site id, or a substring of its display name or host.")),
                    listOf("site")
                )
            ),
            tool(
                "play",
                "One-shot: pick the site (optional), search by name, and open the app's player page for " +
                "the best match. Use this when the person asks to watch something by title.",
                schema(
                    mapOf(
                        "query" to prop("string", "Title to look for."),
                        "site" to prop("string", "Optional site id/name/host; defaults to the active site."),
                        "audio_index" to prop("number", "Preferred audio stream index."),
                        "subtitle_index" to prop("number", "Preferred subtitle stream index; -1 turns subtitles off.")
                    ),
                    listOf("query")
                )
            ),
            tool(
                "status",
                "Report the MCP server port, URL list, sign-in state and server address.",
                schema(emptyMap(), emptyList())
            )
        )
    )

    private fun tool(name: String, description: String, inputSchema: Map<String, Any?>) =
        mapOf("name" to name, "description" to description, "inputSchema" to inputSchema)

    private fun prop(type: String, description: String) =
        mapOf("type" to type, "description" to description)

    private fun schema(properties: Map<String, Any?>, required: List<String>) = mapOf(
        "type" to "object",
        "properties" to properties,
        "additionalProperties" to false,
        "required" to required
    )

    private fun callTool(params: Map<String, Any?>): Map<String, Any?> {
        val name = params["name"]?.toString() ?: throw ToolError("name is required")
        @Suppress("UNCHECKED_CAST")
        val args = (params["arguments"] as? Map<String, Any?>) ?: emptyMap()
        return textResult(
            when (name) {
                "list_libraries" -> listLibraries()
                "list_items" -> listItems(args)
                "search" -> search(args)
                "get_item" -> getItem(args)
                "open_item" -> openItem(args)
                "list_resume" -> listResume()
                "now_playing" -> nowPlaying()
                "list_sites" -> listSites()
                "use_site" -> useSite(args)
                "play" -> play(args)
                "status" -> status()
                else -> throw ToolError("unknown tool: $name")
            }
        )
    }

    private fun textResult(text: String) = mapOf(
        "content" to listOf(mapOf("type" to "text", "text" to text)),
        "isError" to false
    )

    private class ToolError(message: String) : Exception(message)

    // ------------------------------------------------------------------ data

    private fun repo(): EmbyRepository {
        val ctx = context ?: throw ToolError("the app is not initialised yet")
        return EmbyRepository.getInstance(ctx)
    }

    private fun requireLogin(): EmbyRepository {
        val repository = repo()
        if (repository.serverUrl.isNullOrEmpty() || repository.apiKey.isNullOrEmpty()) {
            throw ToolError("not signed in: open the app and sign in to an Emby server first")
        }
        return repository
    }

    private fun itemJson(item: BaseItemDto): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        map["id"] = item.id
        map["name"] = item.name ?: item.seriesName
        map["type"] = item.type
        item.seriesName?.takeIf { it != item.name }?.let { map["series"] = it }
        item.productionYear?.let { map["year"] = it.toDouble() }
        item.runTimeTicks?.let { map["runtime_minutes"] = it / 600000000.0 }
        item.userData?.let { ud ->
            map["played"] = ud.played
            if (ud.playbackPositionTicks > 0) {
                map["resume_ticks"] = ud.playbackPositionTicks.toDouble()
            }
            if (ud.isFavorite) map["favorite"] = true
        }
        return map
    }

    private fun streamJson(stream: MediaStreamDto): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        map["index"] = stream.index?.toDouble()
        map["type"] = stream.type
        map["codec"] = stream.codec
        map["language"] = stream.language
        map["display"] = stream.displayTitle ?: stream.title
        stream.channels?.let { map["channels"] = it.toDouble() }
        stream.bitRate?.let { map["bitrate"] = it.toDouble() }
        stream.height?.let { map["height"] = it.toDouble() }
        stream.width?.let { map["width"] = it.toDouble() }
        stream.isDefault?.let { map["default"] = it }
        stream.isForced?.let { map["forced"] = it }
        return map
    }

    private fun listLibraries(): String {
        val repository = requireLogin()
        // Views() is the library list; /Items without a parent returns the library contents.
        val views = runBlocking { repository.getViews() }
        return Json.write(mapOf("libraries" to views.map { itemJson(it) }))
    }

    private fun listItems(args: Map<String, Any?>): String {
        val repository = requireLogin()
        val parentId = args["parent_id"]?.toString().orEmpty()
        val type = args["item_type"]?.toString().orEmpty()
        val limit = (args["limit"] as? Number)?.toInt() ?: 30
        val startIndex = (args["start_index"] as? Number)?.toInt() ?: 0

        if (parentId.isEmpty()) return listLibraries()

        val (items, total) = runBlocking { repository.getLibraryList(parentId, type, startIndex, limit) }
        return Json.write(
            mapOf(
                "parent_id" to parentId,
                "total" to total,
                "items" to items.map { itemJson(it) }
            )
        )
    }

    private fun search(args: Map<String, Any?>): String {
        val repository = requireLogin()
        val query = args["query"]?.toString()?.trim().orEmpty()
        if (query.isEmpty()) throw ToolError("query is required")
        val limit = (args["limit"] as? Number)?.toInt() ?: 20
        val (items, total) = runBlocking { repository.searchItems(query, 0, limit) }
        return Json.write(
            mapOf(
                "query" to query,
                "total" to total,
                "items" to items.map { itemJson(it) }
            )
        )
    }

    private fun getItem(args: Map<String, Any?>): String {
        val repository = requireLogin()
        val itemId = args["item_id"]?.toString().orEmpty()
        if (itemId.isEmpty()) throw ToolError("item_id is required")
        val item = runBlocking { repository.getMediaInfo(itemId) }
        val detail = LinkedHashMap<String, Any?>(itemJson(item))
        // BaseItemDto deliberately excludes MediaSources, so the streams come from PlaybackInfo.
        val source = runBlocking { repository.getPlaybackInfo(itemId, 0L) }.mediaSources?.firstOrNull()
        if (source != null) {
            detail["container"] = source.container
            detail["bitrate"] = source.bitrate?.toDouble()
            detail["media_source_id"] = source.id
            val streams = source.mediaStreams ?: emptyList()
            detail["video"] = streams.filter { it.type == "Video" }.map { streamJson(it) }
            detail["audio"] = streams.filter { it.type == "Audio" }.map { streamJson(it) }
            detail["subtitle"] = streams.filter { it.type == "Subtitle" }.map { streamJson(it) }
        }
        return Json.write(detail)
    }

    private fun listResume(): String {
        val repository = requireLogin()
        val items = runBlocking { repository.getResumeItems() }
        return Json.write(mapOf("items" to items.map { itemJson(it) }))
    }

    private fun nowPlaying(): String {
        val repository = requireLogin()
        val sessions = runBlocking { repository.getPlayingSessions() }
        return Json.write(
            mapOf(
                "sessions" to sessions.map { session ->
                    mapOf(
                        "user" to session.userName,
                        "device" to session.deviceName,
                        "client" to session.client,
                        "item_id" to session.nowPlayingItem?.id,
                        "title" to session.nowPlayingItem?.name,
                        "position_ticks" to session.playState?.positionTicks?.toDouble()
                    )
                }
            )
        )
    }

    private fun status(): String {
        val ctx = context
        val repository = if (ctx != null) runCatching { EmbyRepository.getInstance(ctx) }.getOrNull() else null
        return Json.write(
            mapOf(
                "ok" to true,
                "port" to port.toDouble(),
                "path" to PATH,
                "urls" to addresses(),
                "signed_in" to (repository?.isLoggedIn ?: false),
                "server" to repository?.serverUrl
            )
        )
    }

    private fun addresses(): List<String> {
        val out = ArrayList<String>()
        try {
            for (nif in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in Collections.list(nif.inetAddresses)) {
                    if (addr.isLoopbackAddress) continue
                    val host = addr.hostAddress?.substringBefore('%') ?: continue
                    out.add("http://$host:$port$PATH")
                }
            }
        } catch (_: Exception) {
        }
        if (out.isEmpty()) out.add("http://127.0.0.1:$port$PATH")
        return out
    }

    // ------------------------------------------------------------------ player

    private fun openItem(args: Map<String, Any?>): String {
        // A named site is switched before anything is resolved, so the item id and the
        // session that will play it always belong to the same server.
        args["site"]?.toString()?.takeIf { it.isNotBlank() }?.let { selectSite(it) }
        val repository = requireLogin()
        val itemId = args["item_id"]?.toString().orEmpty()
        if (itemId.isEmpty()) throw ToolError("item_id is required")
        val ctx = context ?: throw ToolError("the app is not initialised yet")

        val positionTicks = (args["position_ticks"] as? Number)?.toLong() ?: 0L
        val audioIndex = (args["audio_index"] as? Number)?.toInt()
        val subtitleIndex = (args["subtitle_index"] as? Number)?.toInt()

        // Resolve the title first so a bad id fails here rather than on a blank screen.
        // Only item metadata is fetched: PlaybackInfo is the player screen's job and is
        // the slowest call on a cold server, so asking for it here stalled "play".
        val item = runBlocking { repository.getMediaInfo(itemId) }

        val intent = Intent(ctx, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_PLAYER
            putExtra(MainActivity.EXTRA_MEDIA_ID, itemId)
            putExtra(MainActivity.EXTRA_POSITION_TICKS, positionTicks)
            audioIndex?.let { putExtra(MainActivity.EXTRA_AUDIO_INDEX, it) }
            subtitleIndex?.let { putExtra(MainActivity.EXTRA_SUBTITLE_INDEX, it) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        ctx.startActivity(intent)

        return Json.write(
            mapOf(
                "ok" to true,
                "item_id" to itemId,
                "name" to (item.name ?: item.seriesName),
                "position_ticks" to positionTicks.toDouble(),
                "audio_index" to audioIndex?.toDouble(),
                "subtitle_index" to subtitleIndex?.toDouble()
            )
        )
    }

    // ------------------------------------------------------------------ sites

    private data class SiteRef(val id: String, val label: String, val url: String, val active: Boolean)

    private fun sites(): List<SiteRef> {
        val repository = repo()
        val active = repository.currentAccountId
        return repository.savedAccounts.map { account ->
            SiteRef(
                id = account.id,
                label = account.displayName.ifEmpty { account.username },
                url = account.serverUrl,
                active = account.id == active
            )
        }
    }

    private fun listSites(): String {
        val all = sites()
        return Json.write(
            mapOf(
                "sites" to all.map {
                    mapOf("id" to it.id, "name" to it.label, "url" to it.url, "active" to it.active)
                }
            )
        )
    }

    private fun useSite(args: Map<String, Any?>): String {
        val wanted = args["site"]?.toString().orEmpty()
        if (wanted.isBlank()) throw ToolError("site is required")
        val site = selectSite(wanted)
        return Json.write(mapOf("ok" to true, "active" to site.label, "id" to site.id, "url" to site.url))
    }

    /**
     * Resolve a site by exact id, then by exact label, then by case-insensitive
     * substring of the label or host. Switching only rewrites session state; every
     * request afterwards reads the new serverUrl, so no restart is needed.
     */
    private fun selectSite(wanted: String): SiteRef {
        val all = sites()
        if (all.isEmpty()) throw ToolError("no saved sites; sign in to an Emby server in the app first")
        val needle = wanted.trim()
        val match = all.firstOrNull { it.id == needle }
            ?: all.firstOrNull { it.label.equals(needle, true) }
            ?: all.firstOrNull { it.label.contains(needle, true) }
            ?: all.firstOrNull { it.url.contains(needle, true) }
            ?: throw ToolError(
                "no site matches \"$needle\"; known sites: " +
                    all.joinToString(", ") { "${it.label} (${it.url})" }
            )
        if (!match.active) {
            if (!repo().switchAccount(match.id)) throw ToolError("could not switch to ${match.label}")
        }
        return match.copy(active = true)
    }

    // ------------------------------------------------------------------ one-shot play

    private fun play(args: Map<String, Any?>): String {
        val query = args["query"]?.toString()?.trim().orEmpty()
        if (query.isEmpty()) throw ToolError("query is required")

        args["site"]?.toString()?.takeIf { it.isNotBlank() }?.let { selectSite(it) }
        val repository = requireLogin()

        // No retry here: a slow server would pay the timeout twice, and the caller
        // (an agent) can simply ask again. The app's own read timeout bounds this.
        val results = runBlocking { repository.searchItems(query, 0, 10) }
        val items = results.first
        if (items.isEmpty()) {
            throw ToolError("no results for \"$query\" on ${repository.serverUrl}")
        }

        // Prefer a title that starts with the query, then an exact name, else the first hit.
        val best = items.firstOrNull { it.name.equals(query, ignoreCase = true) }
            ?: items.firstOrNull { (it.name ?: "").startsWith(query, ignoreCase = true) }
            ?: items.first()

        val itemId = best.id ?: throw ToolError("the best match has no id")
        val openArgs = LinkedHashMap<String, Any?>()
        openArgs["item_id"] = itemId
        args["audio_index"]?.let { openArgs["audio_index"] = it }
        args["subtitle_index"]?.let { openArgs["subtitle_index"] = it }
        val opened = runCatching { openItem(openArgs) }.getOrElse { error ->
            // The search already proved the id; failing to re-read it must not hide the result.
            Log.w(TAG, "openItem after play search failed: $error")
            Json.write(mapOf("ok" to true, "item_id" to itemId, "deeplink" to "sent"))
        }

        return Json.write(
            mapOf(
                "ok" to true,
                "site" to repository.serverUrl,
                "query" to query,
                "picked" to itemJson(best),
                "candidates" to items.take(5).map { itemJson(it) },
                "opened" to opened
            )
        )
    }



}
