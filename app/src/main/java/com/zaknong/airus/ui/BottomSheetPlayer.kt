package com.zaknong.airus.ui

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.livedata.observeAsState
import coil3.compose.AsyncImage
import com.zaknong.airus.R
import com.zaknong.airus.database.entity.Song
import com.zaknong.airus.service.PlayerState
import com.zaknong.airus.ui.component.BottomSheet
import com.zaknong.airus.ui.component.BottomSheetState

@Composable
fun BottomSheetPlayer(
    state: BottomSheetState,
    currentSong: Song?,
    playbackState: PlayerState.State,
    position: Long,
    duration: Long,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onToggleRepeat: () -> Unit,
    onToggleFavorite: () -> Unit,
    onShowEq: () -> Unit,
    onSkipToQueueItem: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val isPlaying = playbackState == PlayerState.State.PLAYING
    var showQueue by remember { mutableStateOf(false) }

    BottomSheet(
        state = state,
        modifier = modifier,
        backgroundColor = MaterialTheme.colorScheme.surface,
        collapsedContent = {
            MiniPlayerContent(
                song = currentSong,
                isPlaying = isPlaying,
                onPlayPause = onPlayPause
            )
        },
        content = {
            FullPlayerContent(
                song = currentSong,
                isPlaying = isPlaying,
                position = position,
                duration = duration,
                onPlayPause = onPlayPause,
                onNext = onNext,
                onPrevious = onPrevious,
                onSeek = onSeek,
                onToggleShuffle = onToggleShuffle,
                onToggleRepeat = onToggleRepeat,
                onToggleFavorite = onToggleFavorite,
                onShowEq = onShowEq,
                onShowQueue = { showQueue = true },
                onCollapse = { state.collapse() }
            )
        }
    )

    if (showQueue && currentSong != null) {
        val queueState by PlayerState.getInstance().getQueue().observeAsState(emptyList())
        val queueIndex by PlayerState.getInstance().queueIndex.observeAsState(0)

        QueueScreen(
            queue = queueState,
            currentIndex = queueIndex,
            onSongClick = { 
                onSkipToQueueItem(it)
                showQueue = false
            },
            onClose = { showQueue = false }
        )
    }
}

@Composable
fun MiniPlayerContent(
    song: Song?,
    isPlaying: Boolean,
    onPlayPause: () -> Unit
) {
    Surface(
        color = Color.Black.copy(alpha = 0.95f),
        modifier = Modifier.fillMaxWidth().height(80.dp)
    ) {
        Column {
            LinearProgressIndicator(
                progress = { 0f }, // Will add actual progress in next step if needed
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.Transparent
            )
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = song?.albumArtPath ?: R.drawable.ic_default_album_art,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Gray.copy(alpha = 0.2f))
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = song?.title ?: "Not Playing",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        ),
                        maxLines = 1,
                        modifier = Modifier.basicMarquee()
                    )
                    Text(
                        text = song?.artist ?: "Unknown Artist",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.6f),
                        maxLines = 1,
                        modifier = Modifier.basicMarquee()
                    )
                }
                IconButton(onClick = onPlayPause) {
                    Icon(
                        painter = painterResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun FullPlayerContent(
    song: Song?,
    isPlaying: Boolean,
    position: Long,
    duration: Long,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onToggleRepeat: () -> Unit,
    onToggleFavorite: () -> Unit,
    onShowEq: () -> Unit,
    onShowQueue: () -> Unit,
    onCollapse: () -> Unit
) {
    val playerState = PlayerState.getInstance()
    val shuffleMode by playerState.shuffleMode.observeAsState(PlayerState.ShuffleMode.OFF)
    val repeatMode by playerState.repeatMode.observeAsState(PlayerState.RepeatMode.OFF)

    Box(modifier = Modifier.fillMaxSize()) {
        // Backdrop Blur
        AsyncImage(
            model = song?.albumArtPath ?: R.drawable.ic_default_album_art,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .blur(50.dp)
                .background(Color.Black.copy(alpha = 0.4f))
        )
        
        Box(modifier = Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))
            )
        ))

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(24.dp))
            
            // Drag handle / Collapse
            IconButton(onClick = onCollapse, modifier = Modifier.align(Alignment.Start)) {
                Icon(painterResource(R.drawable.ic_chevron_right), null, tint = Color.White, modifier = Modifier.size(32.dp).rotate(90f))
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Album Art
            AsyncImage(
                model = song?.albumArtPath ?: R.drawable.ic_default_album_art,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.White.copy(alpha = 0.05f))
            )
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // Title & Artist
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = song?.title ?: "Unknown Title",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 28.sp
                    ),
                    color = Color.White,
                    maxLines = 1,
                    modifier = Modifier.basicMarquee()
                )
                Text(
                    text = song?.artist ?: "Unknown Artist",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1
                )
            }
            
            Spacer(modifier = Modifier.weight(1f))
            
            // SeekBar
            PlayerProgressSlider(
                position = position,
                duration = duration,
                onSeek = onSeek
            )
            
            Spacer(modifier = Modifier.height(24.dp))

            // Extra Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val accentColor = MaterialTheme.colorScheme.primary
                
                IconButton(onClick = onToggleShuffle) { 
                    Icon(
                        painterResource(R.drawable.ic_shuffle), 
                        null, 
                        tint = if (shuffleMode == PlayerState.ShuffleMode.ON) accentColor else Color.White.copy(alpha = 0.6f)
                    ) 
                }
                IconButton(onClick = onToggleFavorite) { 
                    Icon(
                        painterResource(if (song?.isFavorite == true) R.drawable.ic_favorite_on else R.drawable.ic_favorite_off), 
                        null, 
                        tint = if (song?.isFavorite == true) Color.Red else Color.White.copy(alpha = 0.6f)
                    ) 
                }
                IconButton(onClick = onShowQueue) { Icon(painterResource(R.drawable.ic_playlist), null, tint = Color.White.copy(alpha = 0.6f)) }
                IconButton(onClick = onShowEq) { Icon(painterResource(R.drawable.ic_eq), null, tint = Color.White.copy(alpha = 0.6f)) }
                IconButton(onClick = onToggleRepeat) { 
                    Icon(
                        painterResource(when(repeatMode) {
                            PlayerState.RepeatMode.ONE -> R.drawable.ic_repeat_one
                            PlayerState.RepeatMode.ALL -> R.drawable.ic_repeat_all
                            else -> R.drawable.ic_repeat_off
                        }), 
                        null, 
                        tint = if (repeatMode != PlayerState.RepeatMode.OFF) accentColor else Color.White.copy(alpha = 0.6f)
                    ) 
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
            
            // Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onPrevious, modifier = Modifier.size(56.dp)) {
                    Icon(painterResource(R.drawable.ic_previous), null, tint = Color.White, modifier = Modifier.size(32.dp))
                }
                
                Surface(
                    onClick = onPlayPause,
                    shape = CircleShape,
                    color = Color.White,
                    modifier = Modifier.size(80.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                }
                
                IconButton(onClick = onNext, modifier = Modifier.size(56.dp)) {
                    Icon(painterResource(R.drawable.ic_next), null, tint = Color.White, modifier = Modifier.size(32.dp))
                }
            }
            
            Spacer(modifier = Modifier.height(64.dp))
        }
    }
}

@Composable
fun PlayerProgressSlider(
    position: Long,
    duration: Long,
    onSeek: (Long) -> Unit
) {
    var isDragging by remember { mutableStateOf(false) }
    var dragPosition by remember { mutableLongStateOf(0L) }

    Column {
        Slider(
            value = if (isDragging) (if (duration > 0) dragPosition.toFloat() / duration else 0f) 
                    else (if (duration > 0) position.toFloat() / duration else 0f),
            onValueChange = { 
                isDragging = true
                dragPosition = (it * duration).toLong()
            },
            onValueChangeFinished = {
                onSeek(dragPosition)
                isDragging = false
            },
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color.White.copy(alpha = 0.24f)
            )
        )
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = formatTime(if (isDragging) dragPosition else position), color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall)
            Text(text = formatTime(duration), color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return java.util.Locale.getDefault().let { locale ->
        String.format(locale, "%d:%02d", min, sec)
    }
}
