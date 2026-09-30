package moe.antimony.hoshi.storage

import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Publish a complete sidecar with a same-directory rename. Each writer owns its temporary
 * file, so concurrent writes cannot publish each other's partial contents. A failed write
 * or rename leaves the previous sidecar intact; never fall back to deleting or truncating it.
 * Callers still need their own lock around read-modify-write operations.
 */
internal fun writeSidecarAtomically(
    file: File,
    text: String,
    rename: (File, File) -> Boolean = File::renameTo,
) {
    val temporary = File(file.parentFile, "$SIDECAR_TEMPORARY_PREFIX${UUID.randomUUID()}.tmp")
    try {
        temporary.writeText(text)
        if (!rename(temporary, file)) {
            throw IOException("Unable to replace sidecar: ${file.path}")
        }
    } finally {
        temporary.delete()
    }
}

/** Reserved app files, including incomplete writes left behind if the process was killed. */
internal fun File.isSidecarTemporaryFile(): Boolean =
    name.startsWith(SIDECAR_TEMPORARY_PREFIX) && name.endsWith(".tmp")

private const val SIDECAR_TEMPORARY_PREFIX = ".hoshi-sidecar-"
