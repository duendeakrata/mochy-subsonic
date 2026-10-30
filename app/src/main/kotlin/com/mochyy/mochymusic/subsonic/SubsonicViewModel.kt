package com.mochyy.mochymusic.subsonic

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SubsonicViewModel @Inject constructor(
    private val sync: SubsonicSync,
) : ViewModel() {
    val progress: StateFlow<SubsonicSync.Progress?> = sync.progress

    private var job: Job? = null

    fun startSync() {
        if (job?.isActive == true) return
        job = viewModelScope.launch { sync.sync() }
    }
}
