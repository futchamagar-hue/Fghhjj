package com.example.viewmodel

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.model.FileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

enum class SortOption {
    NAME_ASC, NAME_DESC, DATE_NEWEST, DATE_OLDEST, SIZE_LARGEST, SIZE_SMALLEST, TYPE
}

enum class ClipboardMode {
    NONE, COPY, CUT
}

class FileManagerViewModel(application: Application) : AndroidViewModel(application) {

    private val context: Context get() = getApplication()

    // Directories
    private val sandboxRoot = File(context.filesDir, "LocalFiles")
    private val deviceRoot = Environment.getExternalStorageDirectory()

    private val _isBrowsingSandbox = MutableStateFlow(true)
    val isBrowsingSandbox: StateFlow<Boolean> = _isBrowsingSandbox.asStateFlow()

    private val _currentDirectory = MutableStateFlow<File>(sandboxRoot)
    val currentDirectory: StateFlow<File> = _currentDirectory.asStateFlow()

    private val _isPermissionGranted = MutableStateFlow(false)
    val isPermissionGranted: StateFlow<Boolean> = _isPermissionGranted.asStateFlow()

    // File list state
    private val _fileItems = MutableStateFlow<List<FileItem>>(emptyList())
    val fileItems: StateFlow<List<FileItem>> = _fileItems.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // UI state
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _sortOption = MutableStateFlow(SortOption.NAME_ASC)
    val sortOption: StateFlow<SortOption> = _sortOption.asStateFlow()

    private val _multiSelectedItems = MutableStateFlow<Set<FileItem>>(emptySet())
    val multiSelectedItems: StateFlow<Set<FileItem>> = _multiSelectedItems.asStateFlow()

    // Clipboard for copy/cut/paste
    private val _clipboard = MutableStateFlow<List<FileItem>>(emptyList())
    val clipboard: StateFlow<List<FileItem>> = _clipboard.asStateFlow()

    private val _clipboardMode = MutableStateFlow(ClipboardMode.NONE)
    val clipboardMode: StateFlow<ClipboardMode> = _clipboardMode.asStateFlow()

    // Notification message
    private val _notificationMessage = MutableStateFlow<String?>(null)
    val notificationMessage: StateFlow<String?> = _notificationMessage.asStateFlow()

    // Text Editor State
    private val _editingFile = MutableStateFlow<FileItem?>(null)
    val editingFile: StateFlow<FileItem?> = _editingFile.asStateFlow()

    private val _editorContent = MutableStateFlow("")
    val editorContent: StateFlow<String> = _editorContent.asStateFlow()

    init {
        setupSandboxAndCheckPermissions()
    }

    fun setupSandboxAndCheckPermissions() {
        viewModelScope.launch {
            _isLoading.value = true
            // Check storage permissions
            val hasPerm = hasStoragePermission()
            _isPermissionGranted.value = hasPerm

            // Ensure Sandbox folder exists and pre-populate with test files
            withContext(Dispatchers.IO) {
                if (!sandboxRoot.exists()) {
                    sandboxRoot.mkdirs()
                }
                prepopulateSandbox()
            }

            // Decide current folder
            if (hasPerm) {
                _isBrowsingSandbox.value = false
                _currentDirectory.value = deviceRoot
            } else {
                _isBrowsingSandbox.value = true
                _currentDirectory.value = sandboxRoot
            }

            loadCurrentDirectory()
        }
    }

    fun toggleStorageSource() {
        viewModelScope.launch {
            val nextSourceSandbox = !_isBrowsingSandbox.value
            if (!nextSourceSandbox && !hasStoragePermission()) {
                showNotification("Permission is required to browse device storage.")
                return@launch
            }

            _isBrowsingSandbox.value = nextSourceSandbox
            _currentDirectory.value = if (nextSourceSandbox) sandboxRoot else deviceRoot
            _searchQuery.value = ""
            _multiSelectedItems.value = emptySet()
            loadCurrentDirectory()
        }
    }

    fun setPermissionGranted(granted: Boolean) {
        _isPermissionGranted.value = granted
        if (granted) {
            _isBrowsingSandbox.value = false
            _currentDirectory.value = deviceRoot
        } else {
            _isBrowsingSandbox.value = true
            _currentDirectory.value = sandboxRoot
        }
        _searchQuery.value = ""
        _multiSelectedItems.value = emptySet()
        loadCurrentDirectory()
    }

    fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    // Refresh contents
    fun refresh() {
        loadCurrentDirectory()
    }

    fun navigateTo(folder: File) {
        if (folder.isDirectory) {
            _currentDirectory.value = folder
            _searchQuery.value = ""
            _multiSelectedItems.value = emptySet()
            loadCurrentDirectory()
        } else {
            showNotification("Not a directory!")
        }
    }

    fun navigateUp(): Boolean {
        val current = _currentDirectory.value
        val root = if (_isBrowsingSandbox.value) sandboxRoot else deviceRoot
        if (current.absolutePath == root.absolutePath) {
            return false // Already at the root level of browsing limits
        }
        val parent = current.parentFile
        if (parent != null) {
            _currentDirectory.value = parent
            _searchQuery.value = ""
            _multiSelectedItems.value = emptySet()
            loadCurrentDirectory()
            return true
        }
        return false
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
        loadCurrentDirectory()
    }

    fun updateSortOption(option: SortOption) {
        _sortOption.value = option
        loadCurrentDirectory()
    }

    // Multi-select actions
    fun toggleSelectItem(item: FileItem) {
        val current = _multiSelectedItems.value.toMutableSet()
        if (current.contains(item)) {
            current.remove(item)
        } else {
            current.add(item)
        }
        _multiSelectedItems.value = current
    }

    fun clearSelection() {
        _multiSelectedItems.value = emptySet()
    }

    fun selectAll() {
        _multiSelectedItems.value = _fileItems.value.toSet()
    }

    // File Operation: Create Folder
    fun createFolder(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val folder = File(_currentDirectory.value, name)
            if (folder.exists()) {
                showNotification("A folder with this name already exists.")
            } else {
                val success = folder.mkdirs()
                if (success) {
                    showNotification("Folder created: $name")
                    loadCurrentDirectory()
                } else {
                    showNotification("Failed to create folder.")
                }
            }
        }
    }

    // File Operation: Create File
    fun createTextFile(name: String, content: String = "") {
        viewModelScope.launch(Dispatchers.IO) {
            val fileName = if (name.endsWith(".txt")) name else "$name.txt"
            val file = File(_currentDirectory.value, fileName)
            if (file.exists()) {
                showNotification("A file with this name already exists.")
            } else {
                try {
                    file.writeText(content)
                    showNotification("File created: $fileName")
                    loadCurrentDirectory()
                } catch (e: IOException) {
                    showNotification("Error creating file: ${e.localizedMessage}")
                }
            }
        }
    }

    // File Operation: Rename
    fun renameItem(item: FileItem, newName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val sourceFile = File(item.path)
            if (!sourceFile.exists()) {
                showNotification("File does not exist.")
                return@launch
            }
            val parent = sourceFile.parentFile
            val targetFile = File(parent, newName)
            if (targetFile.exists()) {
                showNotification("A file/folder with that name already exists.")
                return@launch
            }
            val success = sourceFile.renameTo(targetFile)
            if (success) {
                showNotification("Renamed successfully.")
                loadCurrentDirectory()
            } else {
                showNotification("Rename failed. Check file permissions.")
            }
        }
    }

    // File Operation: Delete
    fun deleteSelectedItems() {
        val itemsToDelete = _multiSelectedItems.value.toList()
        if (itemsToDelete.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            var deletedCount = 0
            var failedCount = 0
            for (item in itemsToDelete) {
                val file = File(item.path)
                val success = if (file.isDirectory) file.deleteRecursively() else file.delete()
                if (success) deletedCount++ else failedCount++
            }
            _multiSelectedItems.value = emptySet()
            showNotification("Deleted $deletedCount items" + (if (failedCount > 0) " ($failedCount failed)" else ""))
            loadCurrentDirectory()
        }
    }

    fun deleteItem(item: FileItem) {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            val file = File(item.path)
            val success = if (file.isDirectory) file.deleteRecursively() else file.delete()
            if (success) {
                showNotification("Deleted: ${item.name}")
            } else {
                showNotification("Failed to delete.")
            }
            loadCurrentDirectory()
        }
    }

    // Clipboard system: Cut & Copy
    fun copySelectedToClipboard() {
        val selected = _multiSelectedItems.value.toList()
        if (selected.isNotEmpty()) {
            _clipboard.value = selected
            _clipboardMode.value = ClipboardMode.COPY
            _multiSelectedItems.value = emptySet()
            showNotification("Copied ${selected.size} items to clipboard.")
        }
    }

    fun cutSelectedToClipboard() {
        val selected = _multiSelectedItems.value.toList()
        if (selected.isNotEmpty()) {
            _clipboard.value = selected
            _clipboardMode.value = ClipboardMode.CUT
            _multiSelectedItems.value = emptySet()
            showNotification("Cut ${selected.size} items to clipboard.")
        }
    }

    fun copySingleToClipboard(item: FileItem) {
        _clipboard.value = listOf(item)
        _clipboardMode.value = ClipboardMode.COPY
        showNotification("Copied ${item.name} to clipboard.")
    }

    fun cutSingleToClipboard(item: FileItem) {
        _clipboard.value = listOf(item)
        _clipboardMode.value = ClipboardMode.CUT
        showNotification("Cut ${item.name} to clipboard.")
    }

    fun clearClipboard() {
        _clipboard.value = emptyList()
        _clipboardMode.value = ClipboardMode.NONE
    }

    // File Operation: Paste
    fun pasteClipboard() {
        val items = _clipboard.value
        val mode = _clipboardMode.value
        if (items.isEmpty() || mode == ClipboardMode.NONE) return

        val targetDir = _currentDirectory.value

        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            var successCount = 0
            var errorCount = 0

            for (item in items) {
                val srcFile = File(item.path)
                if (!srcFile.exists()) {
                    errorCount++
                    continue
                }

                val destFile = File(targetDir, srcFile.name)
                if (destFile.exists()) {
                    showNotification("Destination file already exists: ${srcFile.name}")
                    errorCount++
                    continue
                }

                try {
                    if (mode == ClipboardMode.COPY) {
                        if (srcFile.isDirectory) {
                            copyDirectory(srcFile, destFile)
                        } else {
                            srcFile.copyTo(destFile, overwrite = true)
                        }
                        successCount++
                    } else if (mode == ClipboardMode.CUT) {
                        moveItem(srcFile, destFile)
                        successCount++
                    }
                } catch (e: Exception) {
                    errorCount++
                }
            }

            if (mode == ClipboardMode.CUT) {
                // Clear clipboard after cut-paste succeeds
                _clipboard.value = emptyList()
                _clipboardMode.value = ClipboardMode.NONE
            }

            showNotification("Pasted $successCount items" + (if (errorCount > 0) ", $errorCount errors" else ""))
            loadCurrentDirectory()
        }
    }

    private fun copyDirectory(source: File, target: File) {
        if (source.isDirectory) {
            if (!target.exists()) {
                target.mkdirs()
            }
            val children = source.list() ?: return
            for (child in children) {
                copyDirectory(File(source, child), File(target, child))
            }
        } else {
            source.copyTo(target, overwrite = true)
        }
    }

    private fun moveItem(source: File, target: File) {
        if (source.renameTo(target)) {
            // Worked!
        } else {
            // Cross-volume / fallback: Copy and then Delete source
            copyDirectory(source, target)
            if (source.isDirectory) {
                source.deleteRecursively()
            } else {
                source.delete()
            }
        }
    }

    // File Operation: ZIP
    fun zipSelectedItems(zipName: String) {
        val selected = _multiSelectedItems.value.toList()
        if (selected.isEmpty()) return

        val cleanZipName = if (zipName.endsWith(".zip")) zipName else "$zipName.zip"
        val zipFile = File(_currentDirectory.value, cleanZipName)

        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            try {
                ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
                    for (item in selected) {
                        val file = File(item.path)
                        zipFileOrFolder(file, "", zos)
                    }
                }
                _multiSelectedItems.value = emptySet()
                showNotification("Archived successfully to $cleanZipName")
                loadCurrentDirectory()
            } catch (e: Exception) {
                showNotification("Archive failed: ${e.localizedMessage}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun zipSingleItem(item: FileItem, zipName: String) {
        val cleanZipName = if (zipName.endsWith(".zip")) zipName else "$zipName.zip"
        val zipFile = File(_currentDirectory.value, cleanZipName)

        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            try {
                ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
                    val file = File(item.path)
                    zipFileOrFolder(file, "", zos)
                }
                showNotification("Archived successfully to $cleanZipName")
                loadCurrentDirectory()
            } catch (e: Exception) {
                showNotification("Archive failed: ${e.localizedMessage}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun zipFileOrFolder(file: File, parentPath: String, zos: ZipOutputStream) {
        val entryPath = if (parentPath.isEmpty()) file.name else "$parentPath/${file.name}"
        if (file.isDirectory) {
            val children = file.listFiles()
            if (children.isNullOrEmpty()) {
                zos.putNextEntry(ZipEntry("$entryPath/"))
                zos.closeEntry()
            } else {
                for (child in children) {
                    zipFileOrFolder(child, entryPath, zos)
                }
            }
        } else {
            zos.putNextEntry(ZipEntry(entryPath))
            file.inputStream().use { input ->
                input.copyTo(zos)
            }
            zos.closeEntry()
        }
    }

    // File Operation: UNZIP
    fun unzipFile(item: FileItem) {
        val zipFile = File(item.path)
        val targetDir = _currentDirectory.value

        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            try {
                if (!targetDir.exists()) {
                    targetDir.mkdirs()
                }
                ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val file = File(targetDir, entry.name)
                        if (entry.isDirectory) {
                            file.mkdirs()
                        } else {
                            file.parentFile?.mkdirs()
                            file.outputStream().use { output ->
                                zis.copyTo(output)
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
                showNotification("Unzipped ${item.name} successfully")
                loadCurrentDirectory()
            } catch (e: Exception) {
                showNotification("Unzip failed: ${e.localizedMessage}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    // Text Editor Functions
    fun openFileInEditor(item: FileItem) {
        viewModelScope.launch(Dispatchers.IO) {
            val file = File(item.path)
            try {
                val content = file.readText()
                _editingFile.value = item
                _editorContent.value = content
            } catch (e: Exception) {
                showNotification("Failed to open file: ${e.localizedMessage}")
            }
        }
    }

    fun closeEditor() {
        _editingFile.value = null
        _editorContent.value = ""
    }

    fun updateEditorContent(content: String) {
        _editorContent.value = content
    }

    fun saveEditorChanges() {
        val currentFile = _editingFile.value ?: return
        val content = _editorContent.value

        viewModelScope.launch(Dispatchers.IO) {
            try {
                File(currentFile.path).writeText(content)
                showNotification("Changes saved to ${currentFile.name}")
                loadCurrentDirectory()
            } catch (e: Exception) {
                showNotification("Failed to save changes: ${e.localizedMessage}")
            }
        }
    }

    fun saveEditorAs(newName: String) {
        val cleanName = if (newName.contains(".")) newName else "$newName.txt"
        val content = _editorContent.value

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = File(_currentDirectory.value, cleanName)
                file.writeText(content)
                showNotification("Saved as: $cleanName")
                // Load this new file into the editor
                _editingFile.value = FileItem.fromFile(file)
                loadCurrentDirectory()
            } catch (e: Exception) {
                showNotification("Save failed: ${e.localizedMessage}")
            }
        }
    }

    // Main loading loop with search & sort
    private fun loadCurrentDirectory() {
        viewModelScope.launch {
            _isLoading.value = true
            val directory = _currentDirectory.value
            val query = _searchQuery.value
            val sort = _sortOption.value

            val items = withContext(Dispatchers.IO) {
                try {
                    val filesList = directory.listFiles() ?: emptyArray()
                    val filtered = if (query.isEmpty()) {
                        filesList
                    } else {
                        filesList.filter { it.name.contains(query, ignoreCase = true) }.toTypedArray()
                    }

                    // Map to FileItems
                    val mapped = filtered.map { FileItem.fromFile(it) }

                    // Sort items: Directories always at top, then sort files
                    val sorted = when (sort) {
                        SortOption.NAME_ASC -> mapped.sortedWith(compareBy<FileItem> { !it.isDirectory }.thenBy { it.name.lowercase() })
                        SortOption.NAME_DESC -> mapped.sortedWith(compareBy<FileItem> { !it.isDirectory }.thenByDescending { it.name.lowercase() })
                        SortOption.DATE_NEWEST -> mapped.sortedWith(compareBy<FileItem> { !it.isDirectory }.thenByDescending { it.lastModified })
                        SortOption.DATE_OLDEST -> mapped.sortedWith(compareBy<FileItem> { !it.isDirectory }.thenBy { it.lastModified })
                        SortOption.SIZE_LARGEST -> mapped.sortedWith(compareBy<FileItem> { !it.isDirectory }.thenByDescending { it.size })
                        SortOption.SIZE_SMALLEST -> mapped.sortedWith(compareBy<FileItem> { !it.isDirectory }.thenBy { it.size })
                        SortOption.TYPE -> mapped.sortedWith(compareBy<FileItem> { !it.isDirectory }.thenBy { it.extension })
                    }
                    sorted
                } catch (e: Exception) {
                    emptyList()
                }
            }

            _fileItems.value = items
            _isLoading.value = false
        }
    }

    // Show high-priority user-visible message
    private fun showNotification(message: String) {
        _notificationMessage.value = message
    }

    fun clearNotification() {
        _notificationMessage.value = null
    }

    // Prepopulate some folders/files on first run inside the sandbox so the user isn't greeted by an empty screen.
    private fun prepopulateSandbox() {
        try {
            val docsFolder = File(sandboxRoot, "Documents")
            val mediaFolder = File(sandboxRoot, "Media")
            val downloadsFolder = File(sandboxRoot, "Downloads")

            if (!docsFolder.exists()) docsFolder.mkdirs()
            if (!mediaFolder.exists()) mediaFolder.mkdirs()
            if (!downloadsFolder.exists()) downloadsFolder.mkdirs()

            val guideFile = File(docsFolder, "User_Guide.txt")
            if (!guideFile.exists()) {
                guideFile.writeText(
                    """==================================================
WELCOME TO OFFLINE FILE MANAGER
==================================================

This application runs entirely offline to manage your local storage with speed and precision.

🔑 KEY FEATURES:
1. Browse Storage: Seamless navigation with responsive breadcrumbs.
2. File Operations: Easily create, rename, delete, copy, cut, or paste files and directories.
3. Multi-Select: Press & hold or select multiple items to batch delete, archive, or move.
4. Archive Support: Zip multiple files into a single archive, or extract existing zip archives.
5. Text Editor: Tap on supported text extensions (.txt, .json, .xml, .html) to read, edit, or "Save As" directly.

🛠️ QUICK TEST TIPS:
- Try selecting multiple files inside this folder, clicking the options menu to "Zip" them.
- Create a new text file by clicking the (+) Floating Action Button (FAB) -> "New File".
- Edit this very document and save your updates!

Enjoy a fast, beautiful, stock-style experience.
"""
                )
            }

            val jsonFile = File(docsFolder, "app_configuration.json")
            if (!jsonFile.exists()) {
                jsonFile.writeText(
                    """{
  "appName": "File Manager",
  "version": "1.0.0",
  "theme": "pure_dark",
  "offlineOnly": true,
  "supportedFormats": ["txt", "json", "xml", "html", "css", "js", "zip"]
}"""
                )
            }

            val webFile = File(docsFolder, "index.html")
            if (!webFile.exists()) {
                webFile.writeText(
                    """<!DOCTYPE html>
<html>
<head>
    <title>File Manager App</title>
    <style>
        body { background: #0A0A0A; color: #00F5D4; font-family: sans-serif; text-align: center; }
    </style>
</head>
<body>
    <h1>Fully offline and beautiful file browser.</h1>
</body>
</html>"""
                )
            }

            // Create a small zip file inside downloads to let them try the Unzip feature instantly!
            val testZipFile = File(downloadsFolder, "Welcome_Archive.zip")
            if (!testZipFile.exists()) {
                val fileToZip1 = File(downloadsFolder, "welcome_message.txt")
                if (!fileToZip1.exists()) {
                    fileToZip1.writeText("Welcome to the file extraction test! This file was successfully unzipped from Welcome_Archive.zip.")
                }
                ZipOutputStream(BufferedOutputStream(FileOutputStream(testZipFile))).use { zos ->
                    zos.putNextEntry(ZipEntry(fileToZip1.name))
                    fileToZip1.inputStream().use { input -> input.copyTo(zos) }
                    zos.closeEntry()
                }
                // Clean up the temporary unzipped raw file so the user only sees the zip file at first.
                fileToZip1.delete()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
