/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.cloud.netfs

import com.dot.gallery.cloud.core.CloudServerConfig
import com.dot.gallery.cloud.core.ProviderType
import com.dot.gallery.cloud.core.ThumbnailSize
import java.io.EOFException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.MessageDigest
import com.dot.gallery.cloud.webdav.WebDavDialect
import com.dot.gallery.core.util.or
import com.dot.gallery.core.presentation.util.update
import com.dot.gallery.domain.uri.host
import com.dot.gallery.domain.uri.path


/** A single directory/file entry returned by a backend listing. */
data class NetFsEntry(
    val name: String,
    /** Path relative to the share/export root, forward-slashed, no leading slash. */
    val relativePath: String,
    val isDirectory: Boolean,
    val size: Long,
    /** Last-modified time in epoch milliseconds, or 0 if unknown. */
    val lastModified: Long
)

internal data class NetFsAlbumStats(
    val assetCount: Int,
    val thumbnailAssetId: String?
)

internal class NetFsMediaIndex(private val entries: List<NetFsEntry>) {
    private val entriesByRootAlbum = entries.groupBy {
        it.relativePath.substringBefore('/', "")
    }

    fun page(page: Int, pageSize: Int): List<NetFsEntry> {
        if (page < 0 || pageSize <= 0) return emptyList()
        val offset = page.toLong() * pageSize
        if (offset >= entries.size) return emptyList()
        return entries.subList(offset.toInt(), minOf(entries.size, offset.toInt() + pageSize))
    }

    fun inAlbum(albumPath: String): List<NetFsEntry> {
        val normalized = albumPath.trim('/')
        if (normalized.isEmpty()) return entries
        if ('/' !in normalized) return entriesByRootAlbum[normalized].orEmpty()
        val prefix = "$normalized/"
        return entries.filter { it.relativePath.startsWith(prefix) }
    }

    fun rootAlbumPaths(): List<String> = entriesByRootAlbum.keys.filter { it.isNotEmpty() }.sorted()

    fun albumStats(albumPath: String): NetFsAlbumStats {
        val media = inAlbum(albumPath)
        return NetFsAlbumStats(
            assetCount = media.size,
            thumbnailAssetId = media.maxWithOrNull(
                compareBy<NetFsEntry> { it.lastModified }.thenBy { it.relativePath }
            )?.relativePath
        )
    }
}

/**
 * Extra data handed to [FileSystemBackend.write] for backends that opt in through
 * [FileSystemBackend.wantsUploadPreviews]: the photo's own capture time and small JPEGs rendered
 * from the local original, so the backend can serve grids without ever re-downloading the file.
 */
class NetFsWriteMeta(
    val takenMillis: Long,
    val thumbnailJpeg: ByteArray?,
    val previewJpeg: ByteArray?
)

/** Disk usage for the connected share/export. */
data class NetFsStorage(
    val usedBytes: Long,
    val totalBytes: Long,
    val freeBytes: Long
)

/**
 * Opaque, backend-owned connection handle. Each [FileSystemBackend] subclasses this
 * with whatever session/share objects it needs; the provider treats it as a token and
 * always hands it back to the same backend.
 */
abstract class NetFsConnection {
    /** Human-readable root description, e.g. `host/share/Photos`. */
    abstract val rootDisplay: String

    /**
     * Cheap liveness check for the underlying session. A connection that was closed by
     * the peer (or torn down locally) reports false so the provider can rebuild it
     * instead of serving a zombie handle. Backends that cannot probe cheaply keep the
     * default `true` and rely on op-time failure + reconnect instead.
     */
    open fun isAlive(): Boolean = true
}

/**
 * Protocol-specific I/O for a network filesystem (SMB, NFS, …). This is the SMB/NFS
 * analogue of `WebDavDialect`: it holds no per-connection state — everything is passed
 * back via [NetFsConnection] — so a single instance can be reused.
 *
 * Implementations live in their own flag-gated source set (`src/smb`, `src/nfs`) and are
 * contributed to the graph via kotlin-inject `@IntoSet`, exactly like the WebDAV providers.
 */
internal fun contentSha1(input: InputStream): String {
    val digest = MessageDigest.getInstance("SHA-1")
    val buffer = ByteArray(8192)
    while (true) {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Checksum cancelled")
        val read = input.read(buffer)
        if (read == -1) break
        digest.update(buffer, 0, read)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/**
 * True when the failure means the session itself is unusable — closed by the peer,
 * broken by a transport switch, or otherwise dead — so dropping + re-dialing is the
 * right recovery (as opposed to permission/path errors a retry can't fix). Walks the
 * cause chain since protocol clients wrap the underlying socket error.
 */
fun isNetFsConnectionFailure(error: Throwable): Boolean {
    var current: Throwable? = error
    while (current != null) {
        if (current is SocketException ||
            current is SocketTimeoutException ||
            current is EOFException ||
            "TransportException" in current.javaClass.simpleName
        ) return true
        val message = current.message.orEmpty().lowercase()
        if ("already been closed" in message || "connection is closed" in message ||
            "connection closed" in message || "broken pipe" in message ||
            "connection reset" in message || "no route to host" in message ||
            "network is unreachable" in message || "connection refused" in message ||
            "connection timed out" in message
        ) return true
        current = current.cause
    }
    return false
}

interface FileSystemBackend {

    val providerType: ProviderType
    val displayName: String

    /** Open an authenticated session and resolve the base path. Throws on failure. */
    fun connect(config: CloudServerConfig): NetFsConnection

    /** List the immediate children of [path] (relative to the root). */
    fun listDir(conn: NetFsConnection, path: String): List<NetFsEntry>

    /** Open a read stream for [path] starting at byte [offset] (for HTTP Range support). */
    fun openRead(conn: NetFsConnection, path: String, offset: Long): InputStream

    /** Size of [path] in bytes. */
    fun fileSize(conn: NetFsConnection, path: String): Long

    fun exists(conn: NetFsConnection, path: String): Boolean

    /** Create/overwrite [path] with [size] bytes from [data]. */
    fun write(conn: NetFsConnection, path: String, data: InputStream, size: Long)

    /** True when the backend wants [NetFsWriteMeta] (capture time + previews) with each upload. */
    val wantsUploadPreviews: Boolean get() = false

    /** Like [write] but with [meta]; only called when [wantsUploadPreviews] is true. */
    fun write(conn: NetFsConnection, path: String, data: InputStream, size: Long, meta: NetFsWriteMeta) {
        write(conn, path, data, size)
    }

    /**
     * SHA-1 (hex) of [path]'s content when the backend already knows it, so verification does not
     * have to re-read the whole file. Null means "unknown: read and hash it".
     */
    fun knownContentSha1(conn: NetFsConnection, path: String): String? = null

    /** A ready-made JPEG preview stored at upload time, or null to fall back to generating one. */
    fun readStoredPreview(conn: NetFsConnection, path: String, size: ThumbnailSize): ByteArray? = null

    /** Delete the file at [path]. */
    fun delete(conn: NetFsConnection, path: String)

    /** Create the directory [path] (no-op if it already exists). */
    fun mkdir(conn: NetFsConnection, path: String)

    /** Disk usage, or null if the protocol/server doesn't expose it. */
    fun storage(conn: NetFsConnection): NetFsStorage?

    /** Tear down the session. */
    fun close(conn: NetFsConnection)
}
