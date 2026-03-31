package com.movtery.zalithlauncher.feature.customprofilepath

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.apache.commons.io.FileUtils
import java.io.File

object ScopedStorageTools {
    fun getOrCreateDocumentTree(context: Context, treeUri: String): DocumentFile {
        val root = DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
            ?: error("Unable to open tree Uri: $treeUri")
        return root
    }

    fun getOrCreateChildDirectory(parent: DocumentFile, name: String): DocumentFile {
        return parent.findFile(name)?.takeIf { it.isDirectory }
            ?: parent.createDirectory(name)
            ?: error("Failed to create directory '$name'")
    }

    fun clearDocumentChildren(target: DocumentFile) {
        target.listFiles().forEach { deleteDocumentFile(it) }
    }

    fun deleteDocumentFile(file: DocumentFile) {
        if (file.isDirectory) {
            file.listFiles().forEach { deleteDocumentFile(it) }
        }
        file.delete()
    }

    fun copyDocumentTreeToFile(
        context: Context,
        sourceDir: DocumentFile,
        targetDir: File
    ) {
        if (!sourceDir.isDirectory) return

        FileUtils.deleteQuietly(targetDir)
        targetDir.mkdirs()

        sourceDir.listFiles().forEach { child ->
            val childName = child.name ?: return@forEach
            val targetChild = File(targetDir, childName)

            when {
                child.isDirectory -> copyDocumentTreeToFile(context, child, targetChild)
                child.isFile -> {
                    targetChild.parentFile?.mkdirs()
                    context.contentResolver.openInputStream(child.uri)?.use { input ->
                        targetChild.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }
        }
    }

    fun copyFileTreeToDocument(
        context: Context,
        sourceDir: File,
        targetDir: DocumentFile
    ) {
        sourceDir.listFiles()?.forEach { child ->
            when {
                child.isDirectory -> {
                    val targetChild = targetDir.findFile(child.name)
                        ?.takeIf { it.isDirectory }
                        ?: targetDir.createDirectory(child.name)
                        ?: return@forEach
                    copyFileTreeToDocument(context, child, targetChild)
                }
                child.isFile -> {
                    targetDir.findFile(child.name)?.delete()
                    val targetFile = targetDir.createFile("*/*", child.name) ?: return@forEach
                    context.contentResolver.openOutputStream(targetFile.uri)?.use { output ->
                        child.inputStream().use { input ->
                            input.copyTo(output)
                        }
                    }
                }
            }
        }
    }
}
