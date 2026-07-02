package com.zaknong.airus.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.preference.PreferenceManager
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.zaknong.airus.permissions.PermissionManager
import com.zaknong.airus.scanner.MediaScanner
import com.zaknong.airus.scanner.TagEnricher
import com.zaknong.airus.service.PlayerService
import com.zaknong.airus.service.PlayerState
import com.zaknong.airus.ui.component.COLLAPSED_ANCHOR
import com.zaknong.airus.ui.component.DISMISSED_ANCHOR
import com.zaknong.airus.ui.component.rememberBottomSheetState
import com.zaknong.airus.ui.theme.AirusTheme
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private var playerService by mutableStateOf<PlayerService?>(null)
    private var serviceBound by mutableStateOf(false)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as PlayerService.LocalBinder
            playerService = localBinder.getService()
            serviceBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceBound = false
            playerService = null
        }
    }

    private lateinit var permissionManager: PermissionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        startPlayerService()

        permissionManager = PermissionManager(this) { granted ->
            if (granted) {
                // startMediaScan() // Remove auto-scan on permission grant in onCreate
            }
        }

        setContent {
            AirusTheme {
                MainScreenContent(
                    playerService = playerService,
                    onShowEq = {
                        val eqFragment = EqFragment()
                        eqFragment.show(supportFragmentManager, "eq")
                    }
                )
            }
        }

        // permissionManager.requestStoragePermission() // Remove auto-request in onCreate
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, PlayerService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }

    private fun startPlayerService() {
        val intent = Intent(this, PlayerService::class.java)
        startForegroundService(intent)
    }

    private fun startMediaScan() {
        val scanner = MediaScanner(this)
        val enricher = TagEnricher(this)

        scanner.scanProgress.observe(this) { progress ->
            if (progress.isFinished) {
                enricher.enrichAll()
            }
        }
        scanner.startScan()
    }
}

@Composable
fun MainScreenContent(
    playerService: PlayerService?,
    onShowEq: () -> Unit
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val playerState = PlayerState.getInstance()
    val currentSong by playerState.currentSong.observeAsState()
    val playbackState by playerState.playbackState.observeAsState(PlayerState.State.IDLE)
    val position by playerState.position.observeAsState(0L)
    val duration by playerState.duration.observeAsState(0L)

    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }
    val isFirstLaunch = remember { mutableStateOf(prefs.getBoolean("first_launch", true)) }

    val scanManager = remember { com.zaknong.airus.scanner.ScanManager.getInstance(context) }
    val isScanning by scanManager.getIsScanning().observeAsState(false)
    val scanProgressText by scanManager.getScanProgressText().observeAsState("")

    val pagerState = rememberPagerState(pageCount = { Screens.MainScreens.size })
    val scope = rememberCoroutineScope()
    var libraryCategory by remember { mutableStateOf<String?>(null) }
    var initialPlaylist by remember { mutableStateOf<com.zaknong.airus.database.entity.Playlist?>(null) }

    val isMainScreen = currentRoute == "main" || currentRoute == null

    // First Launch Logic
    LaunchedEffect(isFirstLaunch.value) {
        if (isFirstLaunch.value) {
            navController.navigate("scan_folders")
            prefs.edit().putBoolean("first_launch", false).apply()
            isFirstLaunch.value = false
        }
    }

    // Back Navigation Logic
    BackHandler(enabled = true) {
        if (libraryCategory != null) {
            libraryCategory = null
        } else if (pagerState.currentPage != 0) {
            scope.launch { pagerState.animateScrollToPage(0) }
        } else {
            (context as? android.app.Activity)?.finish()
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val screenHeight = maxHeight
        val bottomSheetState = rememberBottomSheetState(
            dismissedBound = 0.dp,
            collapsedBound = 80.dp,
            expandedBound = screenHeight,
            initialAnchor = if (currentSong != null) COLLAPSED_ANCHOR else DISMISSED_ANCHOR
        )

        LaunchedEffect(currentSong) {
            if (currentSong != null && bottomSheetState.isDismissed) {
                bottomSheetState.collapse()
            }
        }

        Scaffold(
            bottomBar = {
                if (isMainScreen) {
                    NavigationBar(
                        containerColor = Color.Black,
                        contentColor = Color.White,
                        tonalElevation = 0.dp
                    ) {
                        Screens.MainScreens.forEachIndexed { index, screen ->
                            NavigationBarItem(
                                icon = {
                                    Icon(
                                        painter = painterResource(if (pagerState.currentPage == index) screen.iconIdActive else screen.iconIdInactive),
                                        contentDescription = stringResource(screen.titleId),
                                        modifier = Modifier.size(26.dp)
                                    )
                                },
                                label = null,
                                selected = pagerState.currentPage == index,
                                onClick = {
                                    scope.launch { pagerState.animateScrollToPage(index) }
                                },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    unselectedIconColor = Color.Gray,
                                    indicatorColor = Color.Transparent
                                )
                            )
                        }
                    }
                }
            },
            containerColor = Color.Black
        ) { padding ->
            Box(modifier = Modifier.padding(padding)) {
                NavHost(
                    navController = navController,
                    startDestination = "main",
                    modifier = Modifier.fillMaxSize()
                ) {
                    composable("main") {
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.fillMaxSize(),
                            beyondViewportPageCount = 2
                        ) { page ->
                            when (Screens.MainScreens[page].route) {
                                "home" -> HomeScreen(
                                    onSongClick = { list, index ->
                                        playerService?.playQueue(list, index)
                                    },
                                    onPlaylistClick = { playlist ->
                                        initialPlaylist = playlist
                                        scope.launch { pagerState.animateScrollToPage(2) }
                                    },
                                    onMoreClick = { category ->
                                        libraryCategory = category
                                        initialPlaylist = null
                                        scope.launch { pagerState.animateScrollToPage(2) }
                                    }
                                )
                                "search" -> SearchScreen(onSongClick = { list, index ->
                                    playerService?.playQueue(list, index)
                                })
                                "library" -> LibraryScreen(
                                    onSongClick = { list, index ->
                                        playerService?.playQueue(list, index)
                                    },
                                    onNavigateToFolders = { navController.navigate("scan_folders") },
                                    initialCategory = libraryCategory,
                                    initialPlaylist = initialPlaylist
                                )
                            }
                        }
                    }
                    composable("scan_folders") { ScanFoldersScreen(onBack = { navController.popBackStack() }) }
                }
            }
        }

        if (isMainScreen) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.BottomCenter
            ) {
                // Background overlay for expanded state
                if (bottomSheetState.progress > 0.01f) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.6f * bottomSheetState.progress))
                            .clickable(enabled = bottomSheetState.progress > 0.9f) {}
                    )
                }

                val offsetY = 80.dp * (1f - bottomSheetState.progress)

                BottomSheetPlayer(
                    state = bottomSheetState,
                    currentSong = currentSong,
                    playbackState = playbackState,
                    position = position,
                    duration = duration,
                    onPlayPause = { if (playbackState == PlayerState.State.PLAYING) playerService?.pause() else playerService?.play() },
                    onNext = { playerService?.skipToNext() },
                    onPrevious = { playerService?.skipToPrevious() },
                    onSeek = { playerService?.seekTo(it) },
                    onToggleShuffle = { playerService?.toggleShuffle() },
                    onToggleRepeat = { playerService?.cycleRepeatMode() },
                    onToggleFavorite = { playerService?.toggleFavorite() },
                    onShowEq = onShowEq,
                    onSkipToQueueItem = { playerService?.skipToQueueItem(it) },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .offset(y = -offsetY)
                )
            }
        }

        if (isScanning) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.8f))
                    .clickable(enabled = false) {}, // Blokir interaksi
                contentAlignment = Alignment.Center
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.padding(32.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 4.dp,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Text(
                            text = "Memindai Musik",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = scanProgressText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}
