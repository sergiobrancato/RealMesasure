package com.realmeasure.app.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.realmeasure.app.data.ScanRecord
import com.realmeasure.app.data.ScanRepository
import com.realmeasure.app.model.ObjectGeometry
import com.realmeasure.app.model.ReferenceMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** State holder for the "My Scans" tab. */
class ScansViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ScanRepository(application)

    private val _records = MutableStateFlow<List<ScanRecord>>(emptyList())
    val records: StateFlow<List<ScanRecord>> = _records.asStateFlow()

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            _records.value = runCatching { repository.loadAll() }
                .getOrDefault(_records.value)
        }
    }

    fun save(
        mode: ReferenceMode,
        objectGeometry: ObjectGeometry,
        annotatedBitmap: Bitmap,
        onSaved: (ScanRecord) -> Unit = {},
    ) {
        if (_saving.value) return
        _saving.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                runCatching {
                    repository.save(mode, objectGeometry, annotatedBitmap)
                }.onSuccess { record ->
                    _records.value = repository.loadAll()
                    withContext(Dispatchers.Main) { onSaved(record) }
                }
            } finally {
                _saving.value = false
            }
        }
    }

    fun delete(record: ScanRecord) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.delete(record) }
            _records.value = runCatching { repository.loadAll() }.getOrDefault(emptyList())
        }
    }
}
