package com.heartline.wear.ui

import android.text.format.DateUtils
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.heartline.shared.model.RecordSummary
import com.heartline.wear.data.WatchRecordDao
import com.heartline.wear.ui.screens.HistoryItem
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import com.heartline.shared.model.RecordMeta
import com.heartline.shared.sync.Protocol

class HistoryViewModel(dao: WatchRecordDao, private val now: () -> Long = System::currentTimeMillis) : ViewModel() {
    val items: StateFlow<List<HistoryItem>> = dao.recent().map { rows ->
        rows.map { row ->
            val meta = Protocol.json.decodeFromString<RecordMeta>(row.metaJson)
            HistoryItem(
                id = meta.id,
                metric = meta.kind.metric,
                result = (meta.summary as? RecordSummary.Ecg)?.result,
                value = LauncherViewModel.lastValue(meta),
                time = DateUtils.getRelativeTimeSpanString(meta.startedAtMs, now(), DateUtils.MINUTE_IN_MILLIS).toString(),
                synced = row.delivered,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
