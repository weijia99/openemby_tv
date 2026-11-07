package com.xxxx.emby_tv

import Emby_tvTheme
import com.xxxx.emby_tv.ui.LibraryScreen
import com.xxxx.emby_tv.ui.LoginScreen
import com.xxxx.emby_tv.ui.PlayerScreen

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.xxxx.emby_tv.ui.AccountScreen
import com.xxxx.emby_tv.ui.MediaDetailScreen
import com.xxxx.emby_tv.ui.HomeScreen
import com.xxxx.emby_tv.ui.UpdateScreen
import com.xxxx.emby_tv.ui.PlaybackSettingsScreen
import com.xxxx.emby_tv.ui.ProxySettingsScreen
import com.xxxx.emby_tv.ui.SearchScreen
import com.xxxx.emby_tv.ui.components.BuildGradientBackground
import com.xxxx.emby_tv.ui.components.Loading
import com.xxxx.emby_tv.ui.theme.ThemeColorManager
import com.xxxx.emby_tv.ui.viewmodel.MainViewModel
import com.xxxx.emby_tv.ui.viewmodel.HomeViewModel
import com.xxxx.emby_tv.ui.viewmodel.LoginViewModel
import com.xxxx.emby_tv.ui.viewmodel.LibraryViewModel
import com.xxxx.emby_tv.ui.viewmodel.DetailViewModel
import com.xxxx.emby_tv.ui.viewmodel.PlayerViewModel
import com.xxxx.emby_tv.ui.viewmodel.SearchViewModel


class MainActivity : ComponentActivity() {

    /** Emitted at most once per intent; consumed by EmbyTvApp to deep-link into the player. */
    private val openPlayerRequest = mutableStateOf<OpenPlayerRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 强制设置为横屏模式
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

        enableEdgeToEdge()

        // Boot hook for the embedded MCP server; it also starts on app open (see below).
        com.xxxx.emby_tv.mcp.McpServer.start(this)
        openPlayerRequest.value = readOpenPlayerRequest(intent)

        setContent {
            EmbyTvApp(openPlayerRequest)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readOpenPlayerRequest(intent)?.let { openPlayerRequest.value = it }
    }

    /**
     * An MCP caller (or a shell) asks for a specific item to be opened. The request
     * is delivered as intent extras so no separate IPC surface is exposed.
     */
    private fun readOpenPlayerRequest(intent: Intent?): OpenPlayerRequest? {
        if (intent == null) return null
        val mediaId = intent.getStringExtra(EXTRA_MEDIA_ID) ?: return null
        return OpenPlayerRequest(
            mediaId = mediaId,
            positionTicks = intent.getLongExtra(EXTRA_POSITION_TICKS, 0L),
            audioIndex = intent.getIntExtra(EXTRA_AUDIO_INDEX, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE },
            subtitleIndex = intent.getIntExtra(EXTRA_SUBTITLE_INDEX, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE },
            requestId = intent.getStringExtra(EXTRA_REQUEST_ID) ?: mediaId
        )
    }

    companion object {
        const val ACTION_OPEN_PLAYER = "com.xxxx.emby_tv.action.OPEN_PLAYER"
        const val EXTRA_MEDIA_ID = "com.xxxx.emby_tv.extra.MEDIA_ID"
        const val EXTRA_POSITION_TICKS = "com.xxxx.emby_tv.extra.POSITION_TICKS"
        const val EXTRA_AUDIO_INDEX = "com.xxxx.emby_tv.extra.AUDIO_INDEX"
        const val EXTRA_SUBTITLE_INDEX = "com.xxxx.emby_tv.extra.SUBTITLE_INDEX"
        const val EXTRA_REQUEST_ID = "com.xxxx.emby_tv.extra.REQUEST_ID"
    }
}

/** Route/argument marker meaning "no explicit track requested". */
const val UNSPECIFIED_TRACK: Int = Int.MIN_VALUE

/** A request to open the player on [mediaId], consumed once by EmbyTvApp. */
data class OpenPlayerRequest(
    val mediaId: String,
    val positionTicks: Long,
    val audioIndex: Int?,
    val subtitleIndex: Int?,
    val requestId: String
)

@Composable
fun EmbyTvApp(openPlayerRequest: MutableState<OpenPlayerRequest?>? = null) {
    val navController = rememberNavController()

    // 使用新的 MainViewModel
    val mainViewModel: MainViewModel = viewModel()

    // 响应式监听状态变化
    val isLoaded = mainViewModel.isLoaded
    val isLoggedIn = mainViewModel.isLoggedIn

    // 初始化加载
    LaunchedEffect(Unit) {
        mainViewModel.initialize()
    }

    // 监听状态变化并自动导航
    LaunchedEffect(isLoaded, isLoggedIn) {
        when {
            !isLoaded -> {
                if (navController.currentDestination?.route != "loading") {
                    navController.navigate("loading") {
                        popUpTo(0) { inclusive = true }
                    }
                }
            }

            isLoggedIn -> {
                if (navController.currentDestination?.route != "home") {
                    navController.navigate("home") {
                        popUpTo(0) { inclusive = true }
                    }
                }
            }

            else -> {
                if (navController.currentDestination?.route != "login") {
                    navController.navigate("login") {
                        popUpTo(0) { inclusive = true }
                    }
                }
            }
        }
    }

    // MCP / intent deep link: jump straight to the player once signed in.
    val pendingOpen = openPlayerRequest?.value
    LaunchedEffect(pendingOpen, isLoggedIn, isLoaded) {
        if (pendingOpen == null || !isLoaded || !isLoggedIn) return@LaunchedEffect
        openPlayerRequest!!.value = null
        navController.navigate(
            "player/${pendingOpen.mediaId}?position=${pendingOpen.positionTicks}" +
                "&audio=${pendingOpen.audioIndex ?: UNSPECIFIED_TRACK}" +
                "&subtitle=${pendingOpen.subtitleIndex ?: UNSPECIFIED_TRACK}"
        ) {
            launchSingleTop = true
        }
    }

    // 获取当前主题色
    val context = LocalContext.current
    val currentThemeColor =
        ThemeColorManager.getThemeColorById(context, mainViewModel.currentThemeId)

    Emby_tvTheme(themeColor = currentThemeColor) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            BuildGradientBackground(context = context, themeColor = currentThemeColor) {
                NavHost(
                    navController = navController,
                    startDestination = "loading"
                ) {
                    // 加载页面
                    composable("loading") {
                        Loading()
                    }

                    // 登录页面
                    composable("login") {
                        val loginViewModel: LoginViewModel = viewModel()
                        LoginScreen(
                            loginViewModel = loginViewModel,
                            mainViewModel = mainViewModel,
                            navController = navController
                        )
                    }

                    // 主页面
                    composable("home") { backStackEntry ->
                        val homeViewModel: HomeViewModel = viewModel()

                        // 使用 rememberSaveable 保存是否已经初始加载过
                        var isInitialLoaded by rememberSaveable { mutableStateOf(false) }

                        // 监听路由返回，延迟刷新
                        LaunchedEffect(backStackEntry) {
                            // 如果不是首次加载（说明是从其他页面返回），则刷新数据
                            if (isInitialLoaded) {
                                homeViewModel.loadData()
                            } else {
                                // 首次加载，不刷新
                                isInitialLoaded = true
                            }
                        }

                        HomeScreen(
                            homeViewModel = homeViewModel,
                            mainViewModel = mainViewModel,
                            navController = navController,
                            onSwitchAccount = {
                                navController.navigate("account")
                            }
                        )
                    }

                    // 账号管理页面
                    composable("account") {
                        val loginViewModel: LoginViewModel = viewModel()
                        AccountScreen(
                            mainViewModel = mainViewModel,
                            savedAccounts = loginViewModel.savedAccounts,
                            currentAccountId = loginViewModel.currentAccountId,
                            onSwitchAccount = { accountId ->
                                loginViewModel.switchAccount(
                                    accountId = accountId,
                                    onSuccess = {
                                        // 切换成功后回到首页并刷新
                                        navController.navigate("home") {
                                            popUpTo(0) { inclusive = true }
                                        }
                                    },
                                    onError = { /* 可选：显示错误提示 */ }
                                )
                            },
                            onDeleteAccount = { accountId ->
                                loginViewModel.removeAccount(accountId)
                            },
                            onAddAccount = {
                                // 退出当前账号，进入登录页面添加新账号
                                mainViewModel.logout()
                            }
                        )
                    }

                    // 播放设置页面
                    composable("playback_settings") {
                        PlaybackSettingsScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }

                    // 代理设置页面
                    composable("proxy_settings") {
                        ProxySettingsScreen(
                            mainViewModel = mainViewModel,
                            onBack = { navController.popBackStack() }
                        )
                    }

                    // 更新页面
                    composable("update") {
                        UpdateScreen(
                            mainViewModel = mainViewModel
                        )
                    }

                    // 搜索页面
                    composable("search") {
                        val searchViewModel: SearchViewModel = viewModel()
                        val loginViewModel: LoginViewModel = viewModel()
                        SearchScreen(
                            searchViewModel = searchViewModel,
                            loginViewModel = loginViewModel,
                            mainViewModel = mainViewModel,
                            onNavigateToSeries = { seriesId ->
                                navController.navigate("series/$seriesId")
                            }
                        )
                    }

                    // 媒体库页面
                    composable(
                        "library/{libraryId}?libraryName={libraryName}&type={type}",
                        arguments = listOf(
                            navArgument("libraryId") { type = NavType.StringType },
                            navArgument("libraryName") {
                                type = NavType.StringType
                                defaultValue = ""
                            },
                            navArgument("type") {
                                type = NavType.StringType
                                defaultValue = ""
                            }
                        )
                    ) { backStackEntry ->
                        val libraryId = backStackEntry.arguments?.getString("libraryId") ?: ""
                        val libraryName = backStackEntry.arguments?.getString("libraryName") ?: ""
                        val type = backStackEntry.arguments?.getString("type") ?: ""
                        val libraryViewModel: LibraryViewModel = viewModel()

                        LibraryScreen(
                            parentId = libraryId,
                            title = libraryName,
                            type = type,
                            libraryViewModel = libraryViewModel,
                            onNavigateToSeries = { seriesId ->
                                navController.navigate("series/$seriesId")
                            }
                        )
                    }

                    // 剧集详情页面
                    composable(
                        "series/{seriesId}",
                        arguments = listOf(navArgument("seriesId") { type = NavType.StringType })
                    ) { backStackEntry ->
                        val seriesId = backStackEntry.arguments?.getString("seriesId") ?: ""
                        val detailViewModel: DetailViewModel = viewModel()
                        MediaDetailScreen(
                            seriesId = seriesId,
                            detailViewModel = detailViewModel,
                            onNavigateToSeries = { nestedSeriesId ->
                                navController.navigate("series/$nestedSeriesId")
                            },
                            onNavigateToPlayer = { mediaItem ->
                                val id = mediaItem.id ?: ""
                                val position = mediaItem.userData?.playbackPositionTicks ?: 0L
                                navController.navigate("player/$id?position=$position")
                            }
                        )
                    }

                    // 通用媒体详情页面
                    composable(
                        "media/{mediaId}",
                        arguments = listOf(navArgument("mediaId") { type = NavType.StringType })
                    ) { backStackEntry ->
                        val mediaId = backStackEntry.arguments?.getString("mediaId") ?: ""
                        val detailViewModel: DetailViewModel = viewModel()

                        MediaDetailScreen(
                            seriesId = mediaId,
                            detailViewModel = detailViewModel,
                            onNavigateToSeries = { nestedSeriesId ->
                                navController.navigate("series/$nestedSeriesId")
                            },
                            onNavigateToPlayer = { mediaItem ->
                                val id = mediaItem.id ?: ""
                                val position = mediaItem.userData?.playbackPositionTicks ?: 0L
                                navController.navigate("player/$id?position=$position")
                            }
                        )
                    }

                    // 播放器页面
                    composable(
                        "player/{mediaId}?position={position}&audio={audio}&subtitle={subtitle}",
                        arguments = listOf(
                            navArgument("mediaId") { type = NavType.StringType },
                            navArgument("position") {
                                type = NavType.LongType
                                defaultValue = 0L
                            },
                            navArgument("audio") {
                                type = NavType.IntType
                                defaultValue = UNSPECIFIED_TRACK
                            },
                            navArgument("subtitle") {
                                type = NavType.IntType
                                defaultValue = UNSPECIFIED_TRACK
                            }
                        )
                    ) { backStackEntry ->
                        val mediaId = backStackEntry.arguments?.getString("mediaId") ?: ""
                        val playbackPositionTicks =
                            backStackEntry.arguments?.getLong("position") ?: 0L
                        val requestedAudio =
                            backStackEntry.arguments?.getInt("audio") ?: UNSPECIFIED_TRACK
                        val requestedSubtitle =
                            backStackEntry.arguments?.getInt("subtitle") ?: UNSPECIFIED_TRACK
                        val playerViewModel: PlayerViewModel = viewModel()

                        PlayerScreen(
                            mediaId = mediaId,
                            playbackPositionTicks = playbackPositionTicks,
                            initialAudioIndex = requestedAudio.takeIf { it != UNSPECIFIED_TRACK },
                            initialSubtitleIndex = requestedSubtitle.takeIf { it != UNSPECIFIED_TRACK },
                            playerViewModel = playerViewModel,
                            onNavigateToPlayer = { nextItem ->
                                val nextId = nextItem.id ?: ""
                                val nextPos = nextItem.userData?.playbackPositionTicks ?: 0L
                                navController.popBackStack()
                                navController.navigate("player/$nextId?position=$nextPos")
                            },
                            onRePlayer = { nextItem, newPosition ->
                                val nextId = nextItem.id ?: ""
                                val nextPos = newPosition * 10000
                                navController.popBackStack()
                                navController.navigate("player/$nextId?position=$nextPos")
                            }
                        )
                    }
                }
            }
        }
    }
}
