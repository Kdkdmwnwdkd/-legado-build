package io.legado.app.ui.file

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.ui.widget.compose.AppSettingPalette
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.rememberAppSettingPalette
import java.io.File

@Composable
internal fun FileManageScreen(
    title: String,
    currentFiles: List<File>,
    subDocs: List<File>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onFileClick: (File) -> Unit,
    onFileLongClick: (File) -> Unit,
    onBreadcrumbClick: (Int) -> Unit,
    onRootBreadcrumbClick: () -> Unit,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val palette = rememberAppSettingPalette()
    val dialogStyle = rememberAppDialogStyle()
    val headerBg = Color(context.backgroundColor)
    val headerTextColor = Color(context.accentColor)

    val filteredFiles = remember(currentFiles, searchQuery) {
        if (searchQuery.isNotEmpty()) {
            currentFiles.filter {
                it.name == ".." || it.name.contains(searchQuery, ignoreCase = true)
            }
        } else {
            currentFiles
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.page)
    ) {
        FileTopBar(
            title = title,
            palette = palette,
            onBackClick = onBackClick
        )

        // Breadcrumb bar
        BreadcrumbBar(
            subDocs = subDocs,
            headerBg = headerBg,
            headerTextColor = headerTextColor,
            palette = palette,
            onRootClick = onRootBreadcrumbClick,
            onBreadcrumbClick = onBreadcrumbClick
        )

        // Search field
        SearchBar(
            query = searchQuery,
            onQueryChange = onSearchQueryChange,
            palette = palette,
            dialogStyle = dialogStyle
        )

        // File list
        val listState = rememberLazyListState()
        if (filteredFiles.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.empty),
                    color = palette.secondaryText,
                    fontSize = 14.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .windowInsetsPadding(WindowInsets.navigationBars),
                state = listState,
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(
                    items = filteredFiles,
                    key = { it.absolutePath }
                ) { file ->
                    FileItemRow(
                        file = file,
                        palette = palette,
                        onClick = { onFileClick(file) },
                        onLongClick = if (file.name == "..") null else {
                            { onFileLongClick(file) }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    palette: AppSettingPalette,
    dialogStyle: io.legado.app.ui.widget.compose.AppDialogStyle
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        placeholder = {
            Text(
                text = stringResource(R.string.search),
                color = palette.secondaryText,
                fontSize = 14.sp
            )
        },
        singleLine = true,
        shape = RoundedCornerShape(dialogStyle.actionRadius),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = palette.primaryText,
            unfocusedTextColor = palette.primaryText,
            focusedContainerColor = Color(palette.row),
            unfocusedContainerColor = Color(palette.row),
            focusedBorderColor = palette.accent.copy(alpha = 0.55f),
            unfocusedBorderColor = Color.Transparent,
            cursorColor = palette.accent,
            focusedPlaceholderColor = palette.secondaryText,
            unfocusedPlaceholderColor = palette.secondaryText
        ),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
    )
}

@Composable
private fun FileTopBar(
    title: String,
    palette: AppSettingPalette,
    onBackClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.page)
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(id = R.drawable.ic_arrow_back),
            contentDescription = stringResource(R.string.back),
            tint = palette.accent,
            modifier = Modifier
                .size(40.dp)
                .clickable(onClick = onBackClick)
                .padding(8.dp)
        )
        Text(
            text = title,
            color = palette.primaryText,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun BreadcrumbBar(
    subDocs: List<File>,
    headerBg: Color,
    headerTextColor: Color,
    palette: AppSettingPalette,
    onRootClick: () -> Unit,
    onBreadcrumbClick: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(headerBg)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Root "root" item
        BreadcrumbItem(
            text = "root",
            selected = subDocs.isEmpty(),
            palette = palette,
            headerTextColor = headerTextColor,
            onClick = onRootClick
        )

        subDocs.forEachIndexed { index, dir ->
            Text(
                text = ">",
                color = palette.secondaryText,
                fontSize = 13.sp
            )
            BreadcrumbItem(
                text = dir.name,
                selected = index == subDocs.lastIndex,
                palette = palette,
                headerTextColor = headerTextColor,
                onClick = { onBreadcrumbClick(index) }
            )
        }
    }
}

@Composable
private fun BreadcrumbItem(
    text: String,
    selected: Boolean,
    palette: AppSettingPalette,
    headerTextColor: Color,
    onClick: () -> Unit
) {
    Text(
        text = text,
        color = if (selected) headerTextColor else palette.secondaryText,
        fontSize = 13.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp)
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileItemRow(
    file: File,
    palette: AppSettingPalette,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?
) {
    val isUpDir = file.name == ".."
    val isDir = file.isDirectory

    val iconLabel = when {
        isUpDir -> "↑"   // up arrow Unicode
        isDir -> "📁" // folder emoji
        else -> "📄"  // file emoji
    }

    val displayName = when {
        isUpDir -> ".."
        isDir -> file.name
        else -> file.name
    }

    val modifier = Modifier
        .fillMaxWidth()
        .background(Color(palette.row))
        .then(
            if (onLongClick != null) {
                Modifier.combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick
                )
            } else {
                Modifier.clickable(onClick = onClick)
            }
        )
        .padding(horizontal = 16.dp, vertical = 10.dp)

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = iconLabel,
            fontSize = 18.sp,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = displayName,
            color = if (isDir) palette.accent else palette.primaryText,
            fontSize = 15.sp,
            fontWeight = if (isDir) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
