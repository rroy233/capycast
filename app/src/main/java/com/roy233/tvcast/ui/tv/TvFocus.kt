package com.roy233.tvcast.ui.tv

import androidx.compose.foundation.focusGroup
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer

/** Groups related controls and restores the last focused child when focus returns. */
fun Modifier.tvFocusRegion(): Modifier = focusGroup().focusRestorer()
