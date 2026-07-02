package com.zaknong.airus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zaknong.airus.R
import com.zaknong.airus.database.entity.Song
import com.zaknong.airus.ui.component.SongItemRow
import com.zaknong.airus.ui.component.PlaylistItemRow
import com.zaknong.airus.ui.viewmodels.HomeViewModel

@Composable
fun HomeScreen(
    onSongClick: (List<Song>, Int) -> Unit,
    onPlaylistClick: (com.zaknong.airus.database.entity.Playlist) -> Unit,
    onMoreClick: (String) -> Unit = {},
    viewModel: HomeViewModel = viewModel()
) {
    val recentlyAdded by viewModel.recentlyAdded.collectAsState(initial = emptyList())
    val favorites by viewModel.favorites.collectAsState(initial = emptyList())
    val topPlayed by viewModel.topPlayed.collectAsState(initial = emptyList())
    val playlists by viewModel.playlists.collectAsState(initial = emptyList())

    val color1 = MaterialTheme.colorScheme.primary
    val color2 = MaterialTheme.colorScheme.secondary
    val color3 = MaterialTheme.colorScheme.tertiary
    val surfaceColor = MaterialTheme.colorScheme.surface

    Box(modifier = Modifier.fillMaxSize().background(surfaceColor)) {
        // Mesh Gradient Background
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.7f)
                .align(Alignment.TopCenter)
                .zIndex(-1f)
                .drawWithCache {
                    val width = size.width
                    val height = size.height

                    val brush1 = Brush.radialGradient(
                        colors = listOf(color1.copy(alpha = 0.38f), color1.copy(alpha = 0.14f), Color.Transparent),
                        center = Offset(width * 0.15f, height * 0.1f),
                        radius = width * 0.55f
                    )
                    val brush2 = Brush.radialGradient(
                        colors = listOf(color2.copy(alpha = 0.34f), color2.copy(alpha = 0.11f), Color.Transparent),
                        center = Offset(width * 0.85f, height * 0.2f),
                        radius = width * 0.65f
                    )
                    val brush3 = Brush.radialGradient(
                        colors = listOf(color3.copy(alpha = 0.3f), color3.copy(alpha = 0.09f), Color.Transparent),
                        center = Offset(width * 0.3f, height * 0.45f),
                        radius = width * 0.6f
                    )
                    val overlayBrush = Brush.verticalGradient(
                        colors = listOf(Color.Transparent, surfaceColor.copy(alpha = 0.5f), surfaceColor),
                        startY = height * 0.4f,
                        endY = height
                    )

                    onDrawBehind {
                        drawRect(brush = brush1)
                        drawRect(brush = brush2)
                        drawRect(brush = brush3)
                        drawRect(brush = overlayBrush)
                    }
                }
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 80.dp, bottom = 140.dp)
        ) {
            item {
                HomeHeader()
            }

            // 1. Playlists (Horizontal LazyRow)
            item {
                HomeSectionTitle("Playlists", onMoreClick = null) // Remove More
                if (playlists.isEmpty()) {
                    Text(
                        text = "Belum ada playlist",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                    )
                } else {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                    ) {
                        this@LazyRow.items(
                            items = playlists,
                            key = { playlist -> "playlist_home_${playlist.id}" }
                        ) { playlist ->
                            Column(
                                modifier = Modifier
                                    .width(100.dp)
                                    .clickable { onPlaylistClick(playlist) },
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                com.zaknong.airus.ui.component.PlaylistGridCover(
                                    playlistId = playlist.id,
                                    modifier = Modifier.size(100.dp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = playlist.name ?: "Unnamed",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${playlist.songCount} lagu",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.6f),
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }

            // 2. Favorites (Maksimal 5)
            item { HomeSectionTitle("Favorites", onMoreClick = { onMoreClick("Favorites") }) }
            if (favorites.isEmpty()) {
                item {
                    Text(
                        text = "Belum ada musik",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                    )
                }
            } else {
                val favoritesToShow = favorites.take(5)
                items(
                    count = favoritesToShow.size,
                    key = { index -> "fav_${favoritesToShow[index].id}" }
                ) { index ->
                    val song = favoritesToShow[index]
                    SongItemRow(song, onClick = { onSongClick(favoritesToShow, index) })
                }
            }

            // 3. Top Played (Maksimal 5)
            item { HomeSectionTitle("Top Played", onMoreClick = { onMoreClick("All Songs") }) }
            if (topPlayed.isEmpty()) {
                item {
                    Text(
                        text = "Belum ada musik",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                    )
                }
            } else {
                val topPlayedToShow = topPlayed.take(5)
                items(
                    count = topPlayedToShow.size,
                    key = { index -> "top_${topPlayedToShow[index].id}" }
                ) { index ->
                    val song = topPlayedToShow[index]
                    SongItemRow(song, onClick = { onSongClick(topPlayedToShow, index) })
                }
            }

            // 4. Recently Added (Maksimal 5)
            item { HomeSectionTitle("Recently Added", onMoreClick = { onMoreClick("Recent") }) }
            if (recentlyAdded.isEmpty()) {
                item {
                    Text(
                        text = "Belum ada musik",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                    )
                }
            } else {
                val recentlyAddedToShow = recentlyAdded.take(5)
                items(
                    count = recentlyAddedToShow.size,
                    key = { index -> "recent_${recentlyAddedToShow[index].id}" }
                ) { index ->
                    val song = recentlyAddedToShow[index]
                    SongItemRow(song, onClick = { onSongClick(recentlyAddedToShow, index) })
                }
            }
        }
    }
}

@Composable
fun HomeStatChip(label: String, iconRes: Int) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        shape = androidx.compose.foundation.shape.CircleShape,
        modifier = Modifier.height(32.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = androidx.compose.ui.res.painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(text = label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
fun HomeHeader() {
    Column(modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 32.dp, bottom = 16.dp)) {
        Text(
            text = "Welcome back,",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
        )
        Text(
            text = "Your Daily High-Res.",
            style = MaterialTheme.typography.headlineLarge.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 34.sp,
                letterSpacing = (-1).sp
            ),
            color = MaterialTheme.colorScheme.onBackground
        )
        
        Spacer(modifier = Modifier.height(4.dp))
        
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HomeStatChip("Hi-Res", com.zaknong.airus.R.drawable.ic_bitperfect_active)
            HomeStatChip("Bit-Perfect", com.zaknong.airus.R.drawable.ic_eq)
        }
    }
}

@Composable
fun HomeSectionTitle(title: String, onMoreClick: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground
        )
        if (onMoreClick != null) {
            Text(
                text = "More",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { onMoreClick() }
            )
        }
    }
}
