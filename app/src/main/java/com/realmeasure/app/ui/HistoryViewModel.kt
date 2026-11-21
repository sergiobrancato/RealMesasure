package com.realmeasure.app.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.realmeasure.app.cv.FastenerSpecResult
import com.realmeasure.app.data.ScanHistoryItem
import com.realmeasure.app.data.ScanHistoryRepository
import com.realmeasure.app.model.ScanMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * State holder for the "My Hardware" tab: loads the JSON history, saves new
 * scans and deletes entries (with their JPEG files).
 */
class HistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ScanHistoryRepository(application)

    private val _items = MutableStateFlow<List<ScanHistoryItem>>(emptyList())
    val items: StateFlow<List<ScanHistoryItem>> = _items.asStateFlow()

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            _items.value = runCatching { repository.loadAll() }
                .getOrDefault(_items.value)
        }
    }

    /** Persist the result modal's crop + metadata, then refresh the list. */
    fun save(
        result: FastenerSpecResult,
        mode: ScanMode,
        bitmap: Bitmap,
        onSaved: () -> Unit = {},
    ) {
        if (_saving.value) return
        _saving.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                runCatching { repository.save(result, mode, bitmap) }
                    .onSuccess {
                        _items.value = repository.loadAll()
                        withContext(Dispatchers.Main) { onSaved() }
                    }
            } finally {
                _saving.value = false
            }
        }
    }

    fun delete(item: ScanHistoryItem) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.delete(item) }
            _items.value = runCatching { repository.loadAll() }.getOrDefault(emptyList())
        }
    }
}
