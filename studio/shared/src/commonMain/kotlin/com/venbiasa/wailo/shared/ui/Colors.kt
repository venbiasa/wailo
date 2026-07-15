package com.venbiasa.wailo.shared.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.venbiasa.wailo.shared.format.StatusKind
import com.venbiasa.wailo.shared.theme.LocalWailoColors

@Composable
internal fun statusColor(kind: StatusKind): Color {
    val colors = LocalWailoColors.current
    return when (kind) {
        StatusKind.Success -> colors.success
        StatusKind.Redirect -> colors.info
        StatusKind.ClientError -> colors.warning
        StatusKind.ServerError -> MaterialTheme.colorScheme.error
        StatusKind.Failed -> MaterialTheme.colorScheme.error
        StatusKind.Pending -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}
