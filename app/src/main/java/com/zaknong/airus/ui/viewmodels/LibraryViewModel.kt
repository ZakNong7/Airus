package com.zaknong.airus.ui.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.asFlow
import com.zaknong.airus.database.AppDatabase
import com.zaknong.airus.database.entity.Song
import kotlinx.coroutines.flow.Flow

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AppDatabase.getInstance(application)
    private val songDao = db.songDao()
    private val playlistDao = db.playlistDao()

    val allSongs: Flow<List<Song>> = songDao.allSongsAlpha.asFlow()
    val recentSongs: Flow<List<Song>> = songDao.allSongsRecent.asFlow()
    val favoriteSongs: Flow<List<Song>> = songDao.favoriteSongs.asFlow()
    val albums: Flow<List<com.zaknong.airus.database.dao.SongDao.AlbumInfo>> = songDao.allAlbums.asFlow()
    val artists: Flow<List<String>> = songDao.allArtists.asFlow()
    val playlists: Flow<List<com.zaknong.airus.database.entity.Playlist>> = playlistDao.allPlaylists.asFlow()

    fun getSongsByAlbum(album: String): Flow<List<Song>> = songDao.getSongsByAlbumName(album).asFlow()
    fun getSongsByArtist(artist: String): Flow<List<Song>> = songDao.getSongsByArtist(artist).asFlow()
}
