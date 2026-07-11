package com.venbiasa.wailo.shared.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.venbiasa.wailo.shared.format.MethodKind
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
        StatusKind.Pending -> colors.onSurfaceDisabled
    }
}

@Composable
internal fun onStatusColor(kind: StatusKind): Color {
    val colors = LocalWailoColors.current
    return when (kind) {
        StatusKind.Success -> colors.onSuccess
        StatusKind.Redirect -> colors.onInfo
        StatusKind.ClientError -> colors.onWarning
        StatusKind.ServerError -> MaterialTheme.colorScheme.onError
        StatusKind.Failed -> MaterialTheme.colorScheme.onError
        StatusKind.Pending -> MaterialTheme.colorScheme.onSurface
    }
}

@Composable
internal fun methodColor(kind: MethodKind): Color {
    val colors = LocalWailoColors.current
    return when (kind) {
        MethodKind.Read -> colors.info
        MethodKind.Create -> colors.success
        MethodKind.Update -> colors.warning
        MethodKind.Delete -> MaterialTheme.colorScheme.error
        MethodKind.Other -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}
