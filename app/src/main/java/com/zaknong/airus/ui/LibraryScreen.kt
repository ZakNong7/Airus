package com.zaknong.airus.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.List
import androidx.compose.foundation.combinedClickable
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.zaknong.airus.R
import com.zaknong.airus.database.AppDatabase
import com.zaknong.airus.database.entity.Song
import com.zaknong.airus.database.entity.Playlist
import com.zaknong.airus.ui.component.SongItemRow
import com.zaknong.airus.ui.viewmodels.LibraryViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun LibraryScreen(
    onSongClick: (List<Song>, Int) -> Unit,
    onNavigateToFolders: () -> Unit,
    initialCategory: String? = null,
    initialPlaylist: Playlist? = null,
    viewModel: LibraryViewModel = viewModel()
) {
    var selectedCategory by remember { mutableStateOf(initialCategory) }
    var selectedPlaylistById by remember { mutableStateOf(initialPlaylist) }
    
    // Reset selectedCategory when initialCategory changes
    LaunchedEffect(initialCategory, initialPlaylist) {
        if (initialCategory != null) {
            selectedCategory = initialCategory
        }
        if (initialPlaylist != null) {
            selectedCategory = "Playlists"
            selectedPlaylistById = initialPlaylist
        }
    }
    
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Spacer(modifier = Modifier.height(64.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selectedCategory != null) {
                    IconButton(onClick = { selectedCategory = null }) {
                        Icon(painterResource(R.drawable.ic_chevron_right), null, modifier = Modifier.size(32.dp).rotateDegrees(180f))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = selectedCategory ?: "Library",
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 32.sp
                    ),
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            IconButton(onClick = onNavigateToFolders) {
                Icon(
                    painter = painterResource(R.drawable.ic_folder),
                    contentDescription = "Scan Folders",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        AnimatedContent(targetState = selectedCategory, label = "LibraryContent") { category ->
            if (category == null) {
                LibraryCategories(onCategoryClick = { selectedCategory = it })
            } else {
                CategoryDetail(category, viewModel, onSongClick, selectedPlaylistById)
            }
        }
    }
}

@Composable
fun LibraryCategories(onCategoryClick: (String) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 120.dp)
    ) {
        val categories = listOf("All Songs", "Recent", "Favorites", "Albums", "Artists", "Playlists")
        items(categories) { category ->
            CategoryItemRow(category, onClick = { onCategoryClick(category) })
        }
    }
}

@Composable
fun CategoryItemRow(name: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val icon = when (name) {
                "All Songs" -> R.drawable.ic_library
                "Recent" -> R.drawable.ic_refresh
                "Favorites" -> R.drawable.ic_favorite_on
                "Albums" -> R.drawable.ic_album
                "Artists" -> Icons.Default.Person
                "Playlists" -> R.drawable.ic_playlist
                else -> R.drawable.ic_library
            }
            if (icon is Int) {
                Icon(painterResource(icon), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            } else {
                Icon(icon as androidx.compose.ui.graphics.vector.ImageVector, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            }
            Spacer(modifier = Modifier.width(20.dp))
            Text(text = name, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
fun CategoryDetail(
    category: String,
    viewModel: LibraryViewModel,
    onSongClick: (List<Song>, Int) -> Unit,
    initialPlaylist: Playlist? = null
) {
    when (category) {
        "All Songs" -> SongList(viewModel.allSongs, onSongClick)
        "Recent" -> SongList(viewModel.recentSongs, onSongClick)
        "Favorites" -> SongList(viewModel.favoriteSongs, onSongClick)
        "Albums" -> AlbumList(viewModel.albums, viewModel, onSongClick)
        "Artists" -> ArtistList(viewModel.artists, viewModel, onSongClick)
        "Playlists" -> PlaylistList(viewModel.playlists, onSongClick, initialPlaylist)
    }
}

@Composable
fun SongList(songsFlow: kotlinx.coroutines.flow.Flow<List<Song>>, onSongClick: (List<Song>, Int) -> Unit) {
    val songs by songsFlow.collectAsState(initial = emptyList())
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 120.dp)
    ) {
        items(
            count = songs.size,
            key = { index -> songs[index].id }
        ) { index ->
            val song = songs[index]
            SongItemRow(song, onClick = { onSongClick(songs, index) })
        }
    }
}

@Composable
fun AlbumList(
    albumsFlow: kotlinx.coroutines.flow.Flow<List<com.zaknong.airus.database.dao.SongDao.AlbumInfo>>,
    viewModel: LibraryViewModel,
    onSongClick: (List<Song>, Int) -> Unit
) {
    val albums by albumsFlow.collectAsState(initial = emptyList())
    var selectedAlbum by remember { mutableStateOf<com.zaknong.airus.database.dao.SongDao.AlbumInfo?>(null) }

    if (selectedAlbum != null) {
        val albumSongs by viewModel.getSongsByAlbum(selectedAlbum!!.album ?: "").collectAsState(initial = emptyList())
        DetailListView(
            title = selectedAlbum!!.album ?: "Unknown Album",
            subtitle = selectedAlbum!!.artist ?: "Unknown Artist",
            songs = albumSongs,
            onBack = { selectedAlbum = null },
            onSongClick = onSongClick
        )
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 140.dp)
        ) {
            items(albums) { album ->
                AlbumItemRow(album, onClick = { selectedAlbum = album })
            }
        }
    }
}

@Composable
fun ArtistList(
    artistsFlow: kotlinx.coroutines.flow.Flow<List<String>>,
    viewModel: LibraryViewModel,
    onSongClick: (List<Song>, Int) -> Unit
) {
    val artists by artistsFlow.collectAsState(initial = emptyList())
    var selectedArtist by remember { mutableStateOf<String?>(null) }

    if (selectedArtist != null) {
        val artistSongs by viewModel.getSongsByArtist(selectedArtist!!).collectAsState(initial = emptyList())
        DetailListView(
            title = selectedArtist!!,
            subtitle = "Artist",
            songs = artistSongs,
            onBack = { selectedArtist = null },
            onSongClick = onSongClick
        )
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 140.dp)
        ) {
            items(artists) { artist ->
                ArtistItemRow(artist, onClick = { selectedArtist = artist })
            }
        }
    }
}

@Composable
fun DetailListView(
    title: String,
    subtitle: String,
    songs: List<Song>,
    onBack: () -> Unit,
    onSongClick: (List<Song>, Int) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.Close, null, tint = Color.White)
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(text = title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(text = subtitle, style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
            }
        }
        
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 140.dp)) {
            items(count = songs.size) { index ->
                SongItemRow(songs[index], onClick = { onSongClick(songs, index) })
            }
        }
    }
}

@Composable
fun PlaylistList(
    playlistsFlow: kotlinx.coroutines.flow.Flow<List<Playlist>>,
    onSongClick: (List<Song>, Int) -> Unit,
    initialPlaylist: Playlist? = null
) {
    val playlists by playlistsFlow.collectAsState(initial = emptyList())
    val context = LocalContext.current
    val db = AppDatabase.getInstance(context)
    val scope = rememberCoroutineScope()
    var showCreateDialog by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    
    // State untuk playlist yang terpilih
    var selectedPlaylist by remember { mutableStateOf<Playlist?>(initialPlaylist) }

    LaunchedEffect(initialPlaylist) {
        if (initialPlaylist != null) {
            selectedPlaylist = initialPlaylist
        }
    }

    if (selectedPlaylist != null) {
        // Render Detail View jika ada playlist terpilih
        PlaylistDetailView(
            playlist = selectedPlaylist!!,
            onBack = { selectedPlaylist = null },
            onSongClick = onSongClick,
            db = db
        )
        return
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("New Playlist") },
            containerColor = Color(0xFF1A1A1A),
            text = {
                OutlinedTextField(
                    value = newPlaylistName,
                    onValueChange = { newPlaylistName = it },
                    placeholder = { Text("Playlist Name") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (newPlaylistName.isNotBlank()) {
                        scope.launch(Dispatchers.IO) {
                            val playlist = Playlist()
                            playlist.name = newPlaylistName
                            playlist.dateCreated = System.currentTimeMillis()
                            playlist.dateModified = playlist.dateCreated
                            db.playlistDao().insertPlaylist(playlist)
                        }
                        newPlaylistName = ""
                        showCreateDialog = false
                    }
                }) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
        columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 140.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(3) }) {
            PlaylistAddRow(onClick = { showCreateDialog = true })
        }
        items(
            count = playlists.size,
            key = { index -> playlists[index].id }
        ) { index ->
            val playlist = playlists[index]
            var showOptionsDialog by remember { mutableStateOf(false) }
            var showRenameDialog by remember { mutableStateOf(false) }
            var editedName by remember { mutableStateOf(playlist.name ?: "") }

            if (showOptionsDialog) {
                AlertDialog(
                    onDismissRequest = { showOptionsDialog = false },
                    title = { Text(playlist.name ?: "Playlist Options") },
                    confirmButton = {},
                    dismissButton = { TextButton(onClick = { showOptionsDialog = false }) { Text("Cancel") } },
                    text = {
                        Column {
                            TextButton(
                                onClick = {
                                    showOptionsDialog = false
                                    showRenameDialog = true
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Rename Playlist", color = Color.White)
                            }
                            TextButton(
                                onClick = {
                                    showOptionsDialog = false
                                    scope.launch(Dispatchers.IO) { db.playlistDao().deletePlaylist(playlist) }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Delete Playlist", color = Color.Red)
                            }
                        }
                    }
                )
            }

            if (showRenameDialog) {
                AlertDialog(
                    onDismissRequest = { showRenameDialog = false },
                    title = { Text("Rename Playlist") },
                    text = {
                        OutlinedTextField(
                            value = editedName,
                            onValueChange = { editedName = it },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                        )
                    },
                    confirmButton = {
                        Button(onClick = {
                            if (editedName.isNotBlank()) {
                                scope.launch(Dispatchers.IO) {
                                    playlist.name = editedName
                                    db.playlistDao().updatePlaylist(playlist)
                                }
                                showRenameDialog = false
                            }
                        }) { Text("Save") }
                    },
                    dismissButton = { TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") } }
                )
            }

            @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = { selectedPlaylist = playlist },
                        onLongClick = { showOptionsDialog = true }
                    ),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                com.zaknong.airus.ui.component.PlaylistGridCover(
                    playlistId = playlist.id,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f)
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

@Composable
fun PlaylistAddRow(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = androidx.compose.material.icons.Icons.Default.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.width(20.dp))
            Text(text = "Create New Playlist", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
fun PlaylistItemRow(playlist: Playlist, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 24.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(painterResource(R.drawable.ic_playlist), null, tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(text = playlist.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                Text(text = "${playlist.songCount} songs", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
        }
    }
}

@Composable
fun PlaylistDetailView(
    playlist: Playlist,
    onBack: () -> Unit,
    onSongClick: (List<Song>, Int) -> Unit,
    db: AppDatabase
) {
    val scope = rememberCoroutineScope()
    val songs by db.playlistDao().getSongsInPlaylist(playlist.id).observeAsState(emptyList())
    var showAddSongsDialog by remember { mutableStateOf(false) }

    var isManageMode by remember { mutableStateOf(false) }

    if (showAddSongsDialog) {
        AddSongsToPlaylistBottomSheet(
            playlistId = playlist.id,
            existingSongIds = songs.map { it.id },
            onDismiss = { showAddSongsDialog = false },
            db = db
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = playlist.name ?: "Playlist",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${songs.size} songs",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            
            IconButton(onClick = { isManageMode = !isManageMode }) {
                Icon(
                    imageVector = if (isManageMode) Icons.Default.Check else Icons.Default.Add,
                    contentDescription = "Manage",
                    tint = if (isManageMode) MaterialTheme.colorScheme.primary else Color.White
                )
            }

            if (isManageMode) {
                Button(
                    onClick = { showAddSongsDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text("Add", fontSize = 12.sp)
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 140.dp)
        ) {
            if (songs.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 80.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "Playlist is empty",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }
            } else {
                items(
                    count = songs.size,
                    key = { index -> songs[index].id }
                ) { index ->
                    val song = songs[index]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSongClick(songs, index) }
                            .padding(horizontal = 24.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AsyncImage(
                            model = song.albumArtPath ?: R.drawable.ic_default_album_art,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.Gray.copy(alpha = 0.2f))
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = song.title,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                            Text(
                                text = song.artist,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                maxLines = 1
                            )
                        }
                        
                        if (isManageMode) {
                            IconButton(
                                onClick = {
                                    scope.launch(Dispatchers.IO) {
                                        val crossRef = com.zaknong.airus.database.entity.PlaylistSongCrossRef()
                                        crossRef.playlistId = playlist.id
                                        crossRef.songId = song.id
                                        db.playlistDao().removeSongFromPlaylist(crossRef)
                                        db.playlistDao().refreshPlaylistCount(playlist.id)
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Remove",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSongsToPlaylistBottomSheet(
    playlistId: Long,
    existingSongIds: List<Long>,
    onDismiss: () -> Unit,
    db: AppDatabase
) {
    val allSongs by db.songDao().getAllSongsAlpha().observeAsState(emptyList())
    val scope = rememberCoroutineScope()
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }

    val sortedSongs = remember(allSongs) {
        allSongs.sortedBy { it.title.lowercase() }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color.Black.copy(alpha = 0.95f),
        dragHandle = { BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = 0.5f)) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Add Songs",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                )
                Button(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            for (songId in selectedIds) {
                                val crossRef = com.zaknong.airus.database.entity.PlaylistSongCrossRef()
                                crossRef.playlistId = playlistId
                                crossRef.songId = songId
                                db.playlistDao().addSongToPlaylist(crossRef)
                            }
                            db.playlistDao().refreshPlaylistCount(playlistId)
                        }
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("Save (${selectedIds.size})")
                }
            }

            if (sortedSongs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No songs in library", color = Color.White.copy(alpha = 0.6f))
                }
            } else {
                androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                    columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(3),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        count = sortedSongs.size,
                        key = { index -> sortedSongs[index].id }
                    ) { index ->
                        val song = sortedSongs[index]
                        val isChecked = selectedIds.contains(song.id) || existingSongIds.contains(song.id)
                        val isEnabled = !existingSongIds.contains(song.id)

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(0.75f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isChecked) Color.White.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.05f))
                                .clickable(enabled = isEnabled) {
                                    selectedIds = if (isChecked) {
                                        selectedIds - song.id
                                    } else {
                                        selectedIds + song.id
                                    }
                                }
                                .padding(8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                ) {
                                    AsyncImage(
                                        model = song.albumArtPath ?: R.drawable.ic_default_album_art,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    if (isChecked) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = androidx.compose.material.icons.Icons.Default.Check,
                                                contentDescription = null,
                                                tint = Color.White,
                                                modifier = Modifier.size(32.dp)
                                            )
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = song.title,
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                                    color = if (isEnabled) Color.White else Color.White.copy(alpha = 0.4f),
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                                Text(
                                    text = song.artist,
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 9.sp),
                                    color = Color.White.copy(alpha = 0.6f),
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AlbumItemRow(album: com.zaknong.airus.database.dao.SongDao.AlbumInfo, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = album.album_art_path ?: R.drawable.ic_default_album_art,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Gray.copy(alpha = 0.1f))
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(text = album.album ?: "Unknown Album", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(text = album.artist ?: "Unknown Artist", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), maxLines = 1)
        }
    }
}

@Composable
fun ArtistItemRow(artist: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(Color.Gray.copy(alpha = 0.2f), shape = CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(painterResource(R.drawable.ic_library), null, tint = Color.Gray, modifier = Modifier.size(24.dp))
        }
        Spacer(modifier = Modifier.width(16.dp))
        Text(text = artist, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
    }
}

private fun Modifier.rotateDegrees(degrees: Float) = this.then(
    Modifier.rotate(degrees)
)
