package com.example.model

import java.io.File

data class FileItem(
    val name: String,
    val path: String,
    val size: Long,
    val lastModified: Long,
    val isDirectory: Boolean,
    val itemCount: Int = 0,
    val extension: String = ""
) {
    companion object {
        fun fromFile(file: File): FileItem {
            val isDir = file.isDirectory
            val count = if (isDir) {
                file.list()?.size ?: 0
            } else {
                0
            }
            return FileItem(
                name = file.name,
                path = file.absolutePath,
                size = if (isDir) 0L else file.length(),
                lastModified = file.lastModified(),
                isDirectory = isDir,
                itemCount = count,
                extension = if (isDir) "" else file.extension.lowercase()
            )
        }
    }
}
