package com.example.ui

import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.model.FileItem
import com.example.viewmodel.ClipboardMode
import com.example.viewmodel.FileManagerViewModel
import com.example.viewmodel.SortOption
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FileManagerApp(
    viewModel: FileManagerViewModel,
    modifier: Modifier = Modifier,
    onRequestPermission: () -> Unit
) {
    val currentDir by viewModel.currentDirectory.collectAsStateWithLifecycle()
    val isSandbox by viewModel.isBrowsingSandbox.collectAsStateWithLifecycle()
    val isPermissionGranted by viewModel.isPermissionGranted.collectAsStateWithLifecycle()
    val fileItems by viewModel.fileItems.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val sortOption by viewModel.sortOption.collectAsStateWithLifecycle()
    val selectedItems by viewModel.multiSelectedItems.collectAsStateWithLifecycle()
    val clipboard by viewModel.clipboard.collectAsStateWithLifecycle()
    val clipboardMode by viewModel.clipboardMode.collectAsStateWithLifecycle()
    val notification by viewModel.notificationMessage.collectAsStateWithLifecycle()

    // Editor state
    val editingFile by viewModel.editingFile.collectAsStateWithLifecycle()
    val editorContent by viewModel.editorContent.collectAsStateWithLifecycle()

    // Dialog & UI local state
    var showSortMenu by remember { mutableStateOf(false) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var showCreateFileDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf<FileItem?>(null) }
    var showDetailsDialog by remember { mutableStateOf<FileItem?>(null) }
    var showZipDialog by remember { mutableStateOf<List<FileItem>?>(null) } // List of files to zip
    var showFabMenu by remember { mutableStateOf(false) }
    var isSearchActive by remember { mutableStateOf(false) }

    val context = LocalContext.current

    // Formatting utilities
    val dateFormatter = remember { SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()) }

    fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        return String.format(Locale.US, "%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }

    // Auto-clear notification toast banner after 3 seconds
    LaunchedEffect(notification) {
        if (notification != null) {
            kotlinx.coroutines.delay(3000)
            viewModel.clearNotification()
        }
    }

    // Handling system back press
    BackHandler(enabled = editingFile != null || selectedItems.isNotEmpty() || isSearchActive) {
        if (editingFile != null) {
            viewModel.closeEditor()
        } else if (selectedItems.isNotEmpty()) {
            viewModel.clearSelection()
        } else if (isSearchActive) {
            isSearchActive = false
            viewModel.updateSearchQuery("")
        }
    }

    // If text editor is open, show the full-screen editor UI
    if (editingFile != null) {
        var showSaveAsDialog by remember { mutableStateOf(false) }
        TextEditorScreen(
            fileName = editingFile?.name ?: "",
            content = editorContent,
            onContentChange = { viewModel.updateEditorContent(it) },
            onSave = { viewModel.saveEditorChanges() },
            onSaveAs = { showSaveAsDialog = true },
            onClose = { viewModel.closeEditor() }
        )

        if (showSaveAsDialog) {
            InputDialog(
                title = "Save As",
                placeholder = "New file name",
                initialValue = editingFile?.name ?: "",
                onConfirm = {
                    showSaveAsDialog = false
                    viewModel.saveEditorAs(it)
                },
                onDismiss = { showSaveAsDialog = false }
            )
        }
        return
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            Column(modifier = Modifier.background(MaterialTheme.colorScheme.background)) {
                // Main Top App Bar
                TopAppBar(
                    title = {
                        Text(
                            text = "File Manager",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 20.sp
                        )
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                if (!viewModel.navigateUp()) {
                                    // Already at top, trigger source toggle as visual cue
                                    viewModel.toggleStorageSource()
                                }
                            },
                            modifier = Modifier.testTag("back_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowBack,
                                contentDescription = "Go Back",
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    },
                    actions = {
                        // Toggle search
                        IconButton(
                            onClick = {
                                isSearchActive = !isSearchActive
                                if (!isSearchActive) viewModel.updateSearchQuery("")
                            },
                            modifier = Modifier.testTag("search_icon")
                        ) {
                            Icon(
                                imageVector = if (isSearchActive) Icons.Default.Close else Icons.Default.Search,
                                contentDescription = "Search",
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }

                        // Toggle sort dropdown
                        IconButton(
                            onClick = { showSortMenu = true },
                            modifier = Modifier.testTag("sort_menu_icon")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Sort,
                                contentDescription = "Sort Options",
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false },
                                modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Name (A to Z)") },
                                    leadingIcon = { Icon(Icons.Default.SortByAlpha, contentDescription = null) },
                                    onClick = {
                                        viewModel.updateSortOption(SortOption.NAME_ASC)
                                        showSortMenu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Name (Z to A)") },
                                    leadingIcon = { Icon(Icons.Default.SortByAlpha, contentDescription = null) },
                                    onClick = {
                                        viewModel.updateSortOption(SortOption.NAME_DESC)
                                        showSortMenu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Date (Newest First)") },
                                    leadingIcon = { Icon(Icons.Default.CalendarToday, contentDescription = null) },
                                    onClick = {
                                        viewModel.updateSortOption(SortOption.DATE_NEWEST)
                                        showSortMenu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Date (Oldest First)") },
                                    leadingIcon = { Icon(Icons.Default.CalendarToday, contentDescription = null) },
                                    onClick = {
                                        viewModel.updateSortOption(SortOption.DATE_OLDEST)
                                        showSortMenu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Size (Largest First)") },
                                    leadingIcon = { Icon(Icons.Default.Fullscreen, contentDescription = null) },
                                    onClick = {
                                        viewModel.updateSortOption(SortOption.SIZE_LARGEST)
                                        showSortMenu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Size (Smallest First)") },
                                    leadingIcon = { Icon(Icons.Default.FullscreenExit, contentDescription = null) },
                                    onClick = {
                                        viewModel.updateSortOption(SortOption.SIZE_SMALLEST)
                                        showSortMenu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Type") },
                                    leadingIcon = { Icon(Icons.Default.Category, contentDescription = null) },
                                    onClick = {
                                        viewModel.updateSortOption(SortOption.TYPE)
                                        showSortMenu = false
                                    }
                                )
                            }
                        }

                        // Source switch button (Sandbox vs External Device Storage)
                        IconButton(onClick = { viewModel.toggleStorageSource() }) {
                            Icon(
                                imageVector = if (isSandbox) Icons.Default.CloudOff else Icons.Default.SdCard,
                                contentDescription = "Switch Storage Source",
                                tint = if (isSandbox) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground
                    )
                )

                // Search Bar row (if active)
                AnimatedVisibility(visible = isSearchActive) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { viewModel.updateSearchQuery(it) },
                        placeholder = { Text("Search files & folders...", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .testTag("search_input"),
                        singleLine = true,
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { viewModel.updateSearchQuery("") }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear search")
                                }
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        )
                    )
                }

                // Storage state indicator/switcher banner
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val statusText = if (isSandbox) "Local App Sandbox (Demo Folder)" else "Full System Storage"
                    val accentColor = if (isSandbox) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary

                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(accentColor)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = statusText,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    if (!isPermissionGranted && !isSandbox) {
                        Button(
                            onClick = onRequestPermission,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.height(28.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("Grant Permission", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    } else if (isSandbox) {
                        Text(
                            text = "Tap Card for Device Storage",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clickable { viewModel.toggleStorageSource() }
                        )
                    }
                }

                // Breadcrumbs bar (Horizontal scroll of current directory path)
                BreadcrumbBar(
                    currentPath = currentDir.absolutePath,
                    isSandbox = isSandbox,
                    sandboxRootPath = File(context.filesDir, "LocalFiles").absolutePath,
                    deviceRootPath = Environment.getExternalStorageDirectory().absolutePath,
                    onNavigate = { viewModel.navigateTo(it) }
                )

                // Paste clipboard visual feedback banner
                if (clipboard.isNotEmpty()) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (clipboardMode == ClipboardMode.COPY) Icons.Default.ContentCopy else Icons.Default.ContentCut,
                                    contentDescription = "Clipboard action",
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "${if (clipboardMode == ClipboardMode.COPY) "Copied" else "Cut"} ${clipboard.size} item(s)",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            Row {
                                TextButton(onClick = { viewModel.pasteClipboard() }) {
                                    Text("PASTE HERE", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                }
                                IconButton(onClick = { viewModel.clearClipboard() }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear clipboard", tint = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                            }
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            // Floating Action Button with bottom sheet visual expander
            FloatingActionButton(
                onClick = { showFabMenu = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.background,
                modifier = Modifier.testTag("add_button")
            ) {
                Icon(Icons.Default.Add, contentDescription = "Action Menu")
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.primary
                )
            } else if (fileItems.isEmpty()) {
                // Beautiful Empty state
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.FolderOpen,
                        contentDescription = "Empty folder",
                        modifier = Modifier.size(80.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "This folder is empty",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Click the (+) FAB button below to create directories or text files.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                // File item list LazyColumn
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    items(fileItems, key = { it.path }) { item ->
                        val isSelected = selectedItems.contains(item)
                        FileListItem(
                            item = item,
                            isSelected = isSelected,
                            isMultiSelectActive = selectedItems.isNotEmpty(),
                            onItemClick = {
                                if (selectedItems.isNotEmpty()) {
                                    viewModel.toggleSelectItem(item)
                                } else {
                                    if (item.isDirectory) {
                                        viewModel.navigateTo(File(item.path))
                                    } else {
                                        // Handle file types
                                        val ext = item.extension
                                        val textExtensions = listOf("txt", "json", "xml", "html", "css", "js")
                                        if (textExtensions.contains(ext)) {
                                            viewModel.openFileInEditor(item)
                                        } else {
                                            // Fallback open with basic context details
                                            showDetailsDialog = item
                                        }
                                    }
                                }
                            },
                            onItemLongClick = {
                                viewModel.toggleSelectItem(item)
                            },
                            onCheckToggle = {
                                viewModel.toggleSelectItem(item)
                            },
                            onActionClick = { action ->
                                when (action) {
                                    "copy" -> viewModel.copySingleToClipboard(item)
                                    "cut" -> viewModel.cutSingleToClipboard(item)
                                    "delete" -> viewModel.deleteItem(item)
                                    "rename" -> showRenameDialog = item
                                    "zip" -> showZipDialog = listOf(item)
                                    "unzip" -> viewModel.unzipFile(item)
                                    "details" -> showDetailsDialog = item
                                }
                            },
                            formatSize = { formatSize(it) },
                            formatDate = { dateFormatter.format(Date(it)) }
                        )
                    }
                }
            }

            // High priority floating snackbar notification banner
            notification?.let { msg ->
                Card(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                        .fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = msg,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { viewModel.clearNotification() },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Close",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            // Multi-Select bottom action bar helper
            if (selectedItems.isNotEmpty()) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(bottom = 12.dp, start = 16.dp, end = 16.dp)
                        .clip(RoundedCornerShape(24.dp)),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    tonalElevation = 8.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${selectedItems.size} selected",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )

                        Row {
                            IconButton(onClick = { viewModel.copySelectedToClipboard() }) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy selected", tint = MaterialTheme.colorScheme.primary)
                            }
                            IconButton(onClick = { viewModel.cutSelectedToClipboard() }) {
                                Icon(Icons.Default.ContentCut, contentDescription = "Cut selected", tint = MaterialTheme.colorScheme.primary)
                            }
                            IconButton(onClick = { showZipDialog = selectedItems.toList() }) {
                                Icon(Icons.Default.Archive, contentDescription = "Zip selected", tint = MaterialTheme.colorScheme.secondary)
                            }
                            IconButton(onClick = { viewModel.deleteSelectedItems() }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete selected", tint = MaterialTheme.colorScheme.error)
                            }
                            IconButton(onClick = { viewModel.clearSelection() }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear Selection")
                            }
                        }
                    }
                }
            }
        }
    }

    // Modal/Action bottom drawer replacement sheet on FAB click
    if (showFabMenu) {
        ModalBottomSheet(
            onDismissRequest = { showFabMenu = false },
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .navigationBarsPadding()
            ) {
                Text(
                    text = "ACTIONS",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    // New Folder
                    ActionButton(
                        icon = Icons.Default.CreateNewFolder,
                        label = "New Folder",
                        onClick = {
                            showFabMenu = false
                            showCreateFolderDialog = true
                        }
                    )

                    // New File
                    ActionButton(
                        icon = Icons.Default.NoteAdd,
                        label = "New File",
                        onClick = {
                            showFabMenu = false
                            showCreateFileDialog = true
                        }
                    )

                    // Paste Action (Only enabled when clipboard has elements!)
                    ActionButton(
                        icon = Icons.Default.ContentPaste,
                        label = "Paste",
                        enabled = clipboard.isNotEmpty(),
                        onClick = {
                            showFabMenu = false
                            viewModel.pasteClipboard()
                        }
                    )

                    // Refresh
                    ActionButton(
                        icon = Icons.Default.Refresh,
                        label = "Refresh",
                        onClick = {
                            showFabMenu = false
                            viewModel.refresh()
                        }
                    )
                }
            }
        }
    }

    // Dialog: Create Folder
    if (showCreateFolderDialog) {
        InputDialog(
            title = "Create New Folder",
            placeholder = "Folder name",
            onConfirm = {
                showCreateFolderDialog = false
                viewModel.createFolder(it)
            },
            onDismiss = { showCreateFolderDialog = false }
        )
    }

    // Dialog: Create File
    if (showCreateFileDialog) {
        InputDialog(
            title = "Create New Text File",
            placeholder = "File name (e.g. log.txt)",
            onConfirm = {
                showCreateFileDialog = false
                viewModel.createTextFile(it)
            },
            onDismiss = { showCreateFileDialog = false }
        )
    }

    // Dialog: Rename
    showRenameDialog?.let { fileItem ->
        InputDialog(
            title = "Rename File / Folder",
            placeholder = "Enter new name",
            initialValue = fileItem.name,
            onConfirm = {
                showRenameDialog = null
                viewModel.renameItem(fileItem, it)
            },
            onDismiss = { showRenameDialog = null }
        )
    }

    // Dialog: Zip archive name
    showZipDialog?.let { items ->
        val defaultZipName = if (items.size == 1) "${items[0].name}.zip" else "Archive.zip"
        InputDialog(
            title = "Zip Archive Name",
            placeholder = "Enter archive name",
            initialValue = defaultZipName,
            onConfirm = {
                showZipDialog = null
                if (items.size == 1) {
                    viewModel.zipSingleItem(items[0], it)
                } else {
                    viewModel.zipSelectedItems(it)
                }
            },
            onDismiss = { showZipDialog = null }
        )
    }

    // Dialog: File Details
    showDetailsDialog?.let { fileItem ->
        AlertDialog(
            onDismissRequest = { showDetailsDialog = null },
            title = { Text("Properties", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    DetailRow(label = "Name:", value = fileItem.name)
                    DetailRow(label = "Type:", value = if (fileItem.isDirectory) "Directory" else fileItem.extension.uppercase() + " File")
                    DetailRow(label = "Path:", value = fileItem.path)
                    if (!fileItem.isDirectory) {
                        DetailRow(label = "Size:", value = formatSize(fileItem.size))
                    } else {
                        DetailRow(label = "Contains:", value = "${fileItem.itemCount} items")
                    }
                    DetailRow(label = "Modified:", value = dateFormatter.format(Date(fileItem.lastModified)))
                }
            },
            confirmButton = {
                TextButton(onClick = { showDetailsDialog = null }) {
                    Text("OK")
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    }
}

// Beautiful customizable bottom sheet Action Button item
@Composable
fun ActionButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(enabled = enabled, onClick = onClick)
            .padding(8.dp)
            .width(68.dp)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(
                    if (enabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun DetailRow(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(text = label, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
        Text(text = value, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// Elegant Horizontal scroll breadcrumbs indicator
@Composable
fun BreadcrumbBar(
    currentPath: String,
    isSandbox: Boolean,
    sandboxRootPath: String,
    deviceRootPath: String,
    onNavigate: (File) -> Unit
) {
    val scrollState = rememberScrollState()

    // Determine segments to show
    val rootPath = if (isSandbox) sandboxRootPath else deviceRootPath
    val rootLabel = if (isSandbox) "Local Sandbox" else "Device Storage"

    val relativePath = if (currentPath.startsWith(rootPath)) {
        currentPath.substring(rootPath.length)
    } else {
        ""
    }

    val segments = relativePath.split("/").filter { it.isNotEmpty() }

    LaunchedEffect(currentPath) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Root folder button
        Text(
            text = rootLabel,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            modifier = Modifier.clickable { onNavigate(File(rootPath)) }
        )

        var cumulativePath = rootPath
        for (segment in segments) {
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(16.dp)
            )
            cumulativePath += "/$segment"
            val targetPath = cumulativePath
            Text(
                text = segment,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable { onNavigate(File(targetPath)) }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileListItem(
    item: FileItem,
    isSelected: Boolean,
    isMultiSelectActive: Boolean,
    onItemClick: () -> Unit,
    onItemLongClick: () -> Unit,
    onCheckToggle: () -> Unit,
    onActionClick: (String) -> Unit,
    formatSize: (Long) -> String,
    formatDate: (Long) -> String
) {
    var showDropdownMenu by remember { mutableStateOf(false) }

    val icon = when {
        item.isDirectory -> Icons.Default.Folder
        item.extension == "zip" -> Icons.Default.Archive
        listOf("jpg", "jpeg", "png", "gif", "webp").contains(item.extension) -> Icons.Default.Image
        listOf("mp3", "wav", "ogg", "m4a").contains(item.extension) -> Icons.Default.AudioFile
        listOf("mp4", "mkv", "avi", "3gp").contains(item.extension) -> Icons.Default.VideoFile
        item.extension == "pdf" -> Icons.Default.PictureAsPdf
        listOf("txt", "json", "xml", "html", "css", "js").contains(item.extension) -> Icons.Default.Description
        else -> Icons.Default.InsertDriveFile
    }

    val iconColor = when {
        item.isDirectory -> MaterialTheme.colorScheme.primary
        item.extension == "zip" -> MaterialTheme.colorScheme.secondary
        listOf("jpg", "jpeg", "png", "gif", "webp").contains(item.extension) -> Color(0xFFE040FB)
        listOf("mp3", "wav", "ogg", "m4a").contains(item.extension) -> Color(0xFFFF9100)
        listOf("mp4", "mkv", "avi", "3gp").contains(item.extension) -> Color(0xFFFF1744)
        item.extension == "pdf" -> Color(0xFFD32F2F)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onItemClick,
                onLongClick = onItemLongClick
            )
            .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isMultiSelectActive) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onCheckToggle() },
                modifier = Modifier.padding(end = 8.dp)
            )
        }

        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(iconColor.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(24.dp)
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (item.isDirectory) "${item.itemCount} items" else formatSize(item.size),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(3.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = formatDate(item.lastModified),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (!isMultiSelectActive) {
            Box {
                IconButton(onClick = { showDropdownMenu = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Options",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(
                    expanded = showDropdownMenu,
                    onDismissRequest = { showDropdownMenu = false },
                    modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    DropdownMenuItem(
                        text = { Text("Copy") },
                        leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                        onClick = {
                            showDropdownMenu = false
                            onActionClick("copy")
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Cut") },
                        leadingIcon = { Icon(Icons.Default.ContentCut, contentDescription = null) },
                        onClick = {
                            showDropdownMenu = false
                            onActionClick("cut")
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        onClick = {
                            showDropdownMenu = false
                            onActionClick("rename")
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                        onClick = {
                            showDropdownMenu = false
                            onActionClick("delete")
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Zip") },
                        leadingIcon = { Icon(Icons.Default.Archive, contentDescription = null) },
                        onClick = {
                            showDropdownMenu = false
                            onActionClick("zip")
                        }
                    )
                    if (item.extension == "zip") {
                        DropdownMenuItem(
                            text = { Text("Unzip Here") },
                            leadingIcon = { Icon(Icons.Default.Unarchive, contentDescription = null) },
                            onClick = {
                                showDropdownMenu = false
                                onActionClick("unzip")
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Properties") },
                        leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
                        onClick = {
                            showDropdownMenu = false
                            onActionClick("details")
                        }
                    )
                }
            }
        }
    }
}

// Built-in Monospaced Text Editor Screen with fully offline capability
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextEditorScreen(
    fileName: String,
    content: String,
    onContentChange: (String) -> Unit,
    onSave: () -> Unit,
    onSaveAs: () -> Unit,
    onClose: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = fileName,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "Close editor")
                    }
                },
                actions = {
                    IconButton(onClick = onSave) {
                        Icon(Icons.Default.Save, contentDescription = "Save Changes", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = onSaveAs) {
                        Icon(Icons.Default.SaveAs, contentDescription = "Save As New File", tint = MaterialTheme.colorScheme.secondary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color(0xFF070707))
        ) {
            // Helper coding keys row
            val codingSymbols = listOf("{", "}", "[", "]", "(", ")", ";", "=", "\"", "'", "<", ">", "/")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(vertical = 4.dp, horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                codingSymbols.forEach { symbol ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.background)
                            .clickable { onContentChange(content + symbol) }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = symbol,
                            color = MaterialTheme.colorScheme.primary,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }
            }

            // Text editor main content field
            TextField(
                value = content,
                onValueChange = onContentChange,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("text_editor_input"),
                textStyle = LocalTextStyle.current.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                ),
                placeholder = { Text("Start typing text here...", fontFamily = FontFamily.Monospace) },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                )
            )
        }
    }
}

// Simple highly-polished custom Input Dialog
@Composable
fun InputDialog(
    title: String,
    placeholder: String,
    initialValue: String = "",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var textValue by remember { mutableStateOf(initialValue) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) },
        text = {
            OutlinedTextField(
                value = textValue,
                onValueChange = { textValue = it },
                placeholder = { Text(placeholder) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("dialog_text_input"),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary
                )
            )
        },
        confirmButton = {
            Button(
                onClick = { if (textValue.isNotBlank()) onConfirm(textValue.trim()) },
                enabled = textValue.isNotBlank()
            ) {
                Text("Confirm")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceVariant
    )
}
