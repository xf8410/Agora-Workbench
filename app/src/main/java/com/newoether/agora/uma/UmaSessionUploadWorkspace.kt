package com.newoether.agora.uma

import java.io.File
import java.security.MessageDigest

/**
 * Resolves the private workspace for one durable Session upload task.
 *
 * Raw downloads and all raw/derived/publish checkpoints live below this directory. Including the
 * immutable task arguments and task ID prevents a new upload from reading Blob checkpoints created
 * for another repository, branch, destination, or cancelled task.
 */
internal fun umaSessionUploadWorkspace(
    baseDirectory: File,
    task: UmaSessionUploadTask,
): File {
    val identity = listOf(
        task.repository,
        task.branch,
        task.sessionId,
        task.targetDirectory,
        task.taskId,
    ).joinToString(separator = "\u0000")
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(identity.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    return File(baseDirectory, "sessions/${task.sessionId}/upload-workspaces/$digest")
}
