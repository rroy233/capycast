package com.roy233.tvcast.ui.tv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.roy233.tvcast.ui.model.TvUiState

/**
 * 持有电视界面根状态，作为 Compose 重组的唯一可变来源。
 *
 * 首轮保留 [TvUiState] 的整体形状与全部字段，调用方仍以整体 `copy` 的方式更新状态；
 * 后续阶段再按功能切片，避免一次性改变状态结构。
 */
class TvStateHolder(initial: TvUiState = TvUiState()) {

    /** 当前界面状态；写入即触发 Compose 重组。 */
    var state: TvUiState by mutableStateOf(initial)
        private set

    /** 以整体替换的方式提交新状态。 */
    fun set(value: TvUiState) {
        state = value
    }

    /** 读取当前状态。 */
    fun read(): TvUiState = state
}
