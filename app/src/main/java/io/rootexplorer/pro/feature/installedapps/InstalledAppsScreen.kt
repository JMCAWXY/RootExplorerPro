package io.rootexplorer.pro.feature.installedapps

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import io.rootexplorer.pro.core.apk.AppKindFilter
import io.rootexplorer.pro.core.apk.AppSortField
import io.rootexplorer.pro.core.apk.InstalledApp
import io.rootexplorer.pro.ui.theme.FileManagerTheme
import io.rootexplorer.pro.ui.theme.MetaTextStyle
import io.rootexplorer.pro.ui.theme.MonospaceTextStyle
import io.rootexplorer.pro.util.Formatter

/** State the installed-apps screen renders. */
data class InstalledAppsUiState(
    val apps: List<InstalledApp> = emptyList(),
    val query: String = "",
    val kindFilter: AppKindFilter = AppKindFilter.ALL,
    val sortField: AppSortField = AppSortField.NAME,
    val descending: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    /** Package names whose icons are being loaded, so the list can show a placeholder. */
    val loadingIcons: Set<String> = emptySet(),
)

/**
 * The installed-apps screen.
 *
 * The list is read-only by design: the brief forbids silently uninstalling, clearing data or
 * modifying system packages, so the only actions are opening the app, showing the system's own
 * details page, and exporting the APK. Nothing here changes the device's packages.
 */
@Composable
fun InstalledAppsScreen(
    state: InstalledAppsUiState,
    iconFor: (String) -> Drawable?,
    onBack: () -> Unit,
    onQueryChange: (String) -> Unit,
    onKindFilterChange: (AppKindFilter) -> Unit,
    onSortFieldChange: (AppSortField) -> Unit,
    onToggleSortDirection: () -> Unit,
    onOpenApp: (InstalledApp) -> Unit,
    onShowDetails: (InstalledApp) -> Unit,
    onExport: (InstalledApp) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FileManagerTheme.colors
    var detailApp by remember { mutableStateOf<InstalledApp?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        Surface(color = colors.toolbar) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = colors.onToolbar,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "已安装应用",
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.onToolbar,
                    )
                    Text(
                        text = "${state.apps.size} 个应用" +
                            if (state.query.isNotBlank()) "（已过滤）" else "",
                        style = MetaTextStyle,
                        color = colors.toolbarSecondary,
                    )
                }
                TextButton(onClick = onRefresh) {
                    Text("刷新", style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        // Search and filters
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                label = { Text("搜索名称或包名") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Filled.Close, contentDescription = "清除")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(6.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AppKindFilter.entries.forEach { option ->
                    Chip(
                        label = option.label,
                        selected = option == state.kindFilter,
                        onClick = { onKindFilterChange(option) },
                    )
                }
                Spacer(Modifier.width(6.dp))
                AppSortField.entries.forEach { option ->
                    Chip(
                        label = option.label,
                        selected = option == state.sortField,
                        onClick = { onSortFieldChange(option) },
                    )
                }
                Chip(
                    label = if (state.descending) "降序" else "升序",
                    selected = false,
                    onClick = onToggleSortDirection,
                )
            }
        }

        HorizontalDivider(color = colors.divider)

        Box(modifier = Modifier.weight(1f)) {
            when {
                state.isLoading && state.apps.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "正在读取应用列表…",
                            style = MetaTextStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                state.error != null -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Warning,
                            contentDescription = null,
                            tint = colors.danger,
                            modifier = Modifier.size(30.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = state.error,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                state.apps.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (state.query.isBlank()) "没有可显示的应用" else "没有匹配的应用",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(items = state.apps, key = { it.packageName }) { app ->
                        AppRow(
                            app = app,
                            icon = iconFor(app.packageName),
                            onOpen = { onOpenApp(app) },
                            onDetails = { detailApp = app },
                            onExport = { onExport(app) },
                        )
                    }
                }
            }
        }
    }

    detailApp?.let { app ->
        AppDetailDialog(
            app = app,
            icon = iconFor(app.packageName),
            onOpenApp = { onOpenApp(app) },
            onShowSystemDetails = { onShowDetails(app) },
            onExport = { onExport(app) },
            onDismiss = { detailApp = null },
        )
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun AppRow(
    app: InstalledApp,
    icon: Drawable?,
    onOpen: () -> Unit,
    onDetails: () -> Unit,
    onExport: () -> Unit,
) {
    val colors = FileManagerTheme.colors

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onDetails)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(icon = icon, size = 40)

        Spacer(Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = app.label,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (app.isSystemApp) {
                    Spacer(Modifier.width(6.dp))
                    Surface(
                        color = colors.rootBadge,
                        shape = MaterialTheme.shapes.extraSmall,
                    ) {
                        Text(
                            text = "系统",
                            style = MaterialTheme.typography.labelSmall,
                            color = androidx.compose.ui.graphics.Color.White,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                        )
                    }
                }
                if (app.isSplit) {
                    Spacer(Modifier.width(4.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.extraSmall,
                    ) {
                        Text(
                            text = "分包 ×${app.allApkPaths.size}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                        )
                    }
                }
            }
            Text(
                text = app.packageName,
                style = MetaTextStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    append("版本 ")
                    append(app.versionName ?: "未知")
                    append("（${app.versionCode}）")
                    append("  ·  ")
                    append(Formatter.size(app.installedSizeBytes))
                    append("  ·  ")
                    append(Formatter.listDate(app.lastUpdateMillis))
                },
                style = MetaTextStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.width(6.dp))

        Row {
            if (app.isLaunchable) {
                IconButton(onClick = onOpen) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = "打开",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            IconButton(onClick = onExport) {
                Icon(
                    imageVector = Icons.Filled.Share,
                    contentDescription = "导出安装包",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** An app icon, falling back to a generic glyph when the platform gives none. */
@Composable
private fun AppIcon(icon: Drawable?, size: Int) {
    if (icon == null) {
        Icon(
            imageVector = Icons.Filled.Android,
            contentDescription = null,
            tint = FileManagerTheme.colors.fileIcon,
            modifier = Modifier.size(size.dp),
        )
        return
    }

    // The drawable is rasterised once per icon; caching it in the row's own memory is enough
    // because LazyColumn recycles rows as the user scrolls.
    val bitmap = remember(icon) {
        runCatching { icon.toBitmap(width = 96, height = 96).asImageBitmap() }.getOrNull()
    }

    if (bitmap == null) {
        Icon(
            imageVector = Icons.Filled.Android,
            contentDescription = null,
            tint = FileManagerTheme.colors.fileIcon,
            modifier = Modifier.size(size.dp),
        )
    } else {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = Modifier.size(size.dp),
        )
    }
}

/**
 * The app detail dialog.
 *
 * Shows what the platform actually reports. Fields that could not be read say 未知; nothing is
 * filled in with a plausible default, because a wrong package path or version is worse than a
 * blank one when the user is about to export or debug something.
 */
@Composable
private fun AppDetailDialog(
    app: InstalledApp,
    icon: Drawable?,
    onOpenApp: () -> Unit,
    onShowSystemDetails: () -> Unit,
    onExport: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = FileManagerTheme.colors

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(icon = icon, size = 32)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        text = app.label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = app.packageName,
                        style = MetaTextStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        text = {
            Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                DetailRow("版本名称", app.versionName ?: "未知")
                DetailRow("版本号", app.versionCode.toString())
                DetailRow("类型", if (app.isSystemApp) "系统应用" else "用户应用")
                DetailRow("可启动", if (app.isLaunchable) "是（有启动入口）" else "否（服务或组件类应用）")
                DetailRow("安装/更新时间", Formatter.fullDate(app.lastUpdateMillis))
                DetailRow("安装包大小", Formatter.size(app.installedSizeBytes))
                DetailRow("targetSdk", app.targetSdk.toString())
                DetailRow("minSdk", app.minSdk.toString())

                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

                Text("安装包文件", style = MaterialTheme.typography.labelMedium)
                if (app.allApkPaths.isEmpty()) {
                    Text(
                        text = "未知（该应用没有暴露 APK 路径）",
                        style = MetaTextStyle,
                        color = colors.danger,
                    )
                } else {
                    app.allApkPaths.forEachIndexed { index, path ->
                        Text(
                            text = if (index == 0) "base：$path" else "split$index：$path",
                            style = MonospaceTextStyle,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                    if (app.isSplit) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "该应用由多个 APK 组成（App Bundle 安装）。只导出 base 无法重新安装，" +
                                "导出时会一并复制全部分包。",
                            style = MetaTextStyle,
                            color = colors.bannerText,
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    text = "本应用不会卸载、清除数据或修改任何已安装的包；以上操作均需由你在系统中自行确认。",
                    style = MetaTextStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = onExport) { Text("导出安装包") }
                TextButton(onClick = onShowSystemDetails) { Text("系统信息") }
            }
        },
        dismissButton = {
            Row {
                if (app.isLaunchable) {
                    TextButton(onClick = onOpenApp) { Text("打开") }
                }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MetaTextStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Banner explaining that export writes into the app's own storage when no folder is chosen. */
@Composable
fun ExportTargetNotice(targetDirectory: String) {
    val colors = FileManagerTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.bannerBackground)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Info,
            contentDescription = null,
            tint = colors.bannerText,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "安装包将导出到：$targetDirectory",
            style = MetaTextStyle,
            color = colors.bannerText,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
