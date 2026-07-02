package com.zaknong.airus.ui.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.asFlow
import com.zaknong.airus.database.AppDatabase
import com.zaknong.airus.database.entity.Song
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest

class SearchViewModel(application: Application) : AndroidViewModel(application) {
    private val songDao = AppDatabase.getInstance(application).songDao()
    val query = MutableStateFlow("")

    @OptIn(ExperimentalCoroutinesApi::class)
    val searchResults: Flow<List<Song>> = query.flatMapLatest { q ->
        if (q.isBlank()) {
            kotlinx.coroutines.flow.flowOf(emptyList())
        } else {
            songDao.searchSongs(q).asFlow()
        }
    }
}
