package com.zaknong.airus.ui.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.asFlow
import com.zaknong.airus.database.AppDatabase
import com.zaknong.airus.database.entity.Song
import kotlinx.coroutines.flow.Flow

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AppDatabase.getInstance(application)
    private val songDao = db.songDao()
    private val playlistDao = db.playlistDao()

    val recentlyAdded: Flow<List<Song>> = songDao.allSongsRecent.asFlow()
    val favorites: Flow<List<Song>> = songDao.favoriteSongs.asFlow()
    val topPlayed: Flow<List<Song>> = songDao.getMostPlayedSongs(10).asFlow()
    val playlists: Flow<List<com.zaknong.airus.database.entity.Playlist>> = playlistDao.allPlaylists.asFlow()
}
