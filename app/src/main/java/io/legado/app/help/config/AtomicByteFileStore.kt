package io.legado.app.help.config

import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

/** Transactional binary-file replacement with recovery of an interrupted commit. */
internal class AtomicByteFileStore(
    private val target: File,
    private val exchange: FileExchange = NioFileExchange
) {

    private val staging = File(target.parentFile, ".${target.name}.staging")
    private val backup = File(target.parentFile, ".${target.name}.backup")
    private val pathLock = pathLocks.computeIfAbsent(target.canonicalPath) { Any() }

    fun writeVerified(bytes: ByteArray, verify: (File) -> Boolean) {
        synchronized(pathLock) {
            recoverInterruptedCommitUnlocked()
            target.parentFile?.let { parent ->
                check(parent.exists() || parent.mkdirs()) { "failed to create ${parent.absolutePath}" }
            }
            deleteRequired(staging)
            var backupCreated = false
            try {
                writeSynced(staging, bytes)
                check(verify(staging)) { "failed to verify ${staging.absolutePath}" }
                if (target.exists()) {
                    deleteRequired(backup)
                    exchange.move(target, backup)
                    backupCreated = true
                }
                try {
                    exchange.move(staging, target)
                } catch (installError: Exception) {
                    if (backupCreated) {
                        try {
                            exchange.move(backup, target)
                            backupCreated = false
                        } catch (restoreError: Exception) {
                            installError.addSuppressed(restoreError)
                            throw AtomicFileRestoreException(backup, installError)
                        }
                    }
                    throw installError
                }
                if (backupCreated) deleteBestEffort(backup)
            } finally {
                deleteBestEffort(staging)
            }
        }
    }

    fun recoverInterruptedCommit() {
        synchronized(pathLock) {
            recoverInterruptedCommitUnlocked()
        }
    }

    private fun recoverInterruptedCommitUnlocked() {
        if (target.exists()) {
            deleteBestEffort(backup)
        } else if (backup.exists()) {
            exchange.move(backup, target)
        }
        deleteBestEffort(staging)
    }

    private fun writeSynced(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
    }

    private fun deleteRequired(file: File) {
        if (file.exists()) exchange.delete(file)
    }

    private fun deleteBestEffort(file: File) {
        runCatching {
            if (file.exists()) exchange.delete(file)
        }
    }

    private companion object {
        val pathLocks = ConcurrentHashMap<String, Any>()
    }
}
