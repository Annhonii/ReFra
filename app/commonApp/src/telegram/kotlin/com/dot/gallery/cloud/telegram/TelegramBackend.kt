/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.cloud.telegram

import android.content.Context
import com.dot.gallery.cloud.core.CloudServerConfig
import com.dot.gallery.cloud.core.CloudTrace
import com.dot.gallery.cloud.core.ProviderType
import com.dot.gallery.cloud.core.ThumbnailSize
import com.dot.gallery.cloud.netfs.FileSystemBackend
import com.dot.gallery.cloud.netfs.NetFsConnection
import com.dot.gallery.cloud.netfs.NetFsEntry
import com.dot.gallery.cloud.netfs.NetFsStorage
import com.dot.gallery.cloud.netfs.NetFsWriteMeta
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Private cloud on a Telegram channel. Config mapping:
 *  - `serverUrl`: `telegram://<chat id>` (e.g. `telegram://-1001234567890`) or the bare chat id / `@channelusername`
 *  - `apiKey`: the bot token (the bot must be an admin of the channel)
 *  - `password`: the encryption passphrase. It never leaves the device; without it the data is unreadable.
 *
 * Everything stored in Telegram is AES-256-GCM encrypted (see [TelegramCrypto]); only an app instance that
 * has the passphrase can decode it. Reads, deletes and listings are served by [TelegramVault].
 */
class TelegramBackend(private val context: Context) : FileSystemBackend {

    override val providerType: ProviderType = ProviderType.TELEGRAM
    override val displayName: String = "Telegram"
    override val wantsUploadPreviews: Boolean = true

    private class TgConnection(val vault: TelegramVault, val chatLabel: String) : NetFsConnection() {
        override val rootDisplay: String = "Telegram $chatLabel"
    }

    override fun connect(config: CloudServerConfig): NetFsConnection {
        val chatId = parseChatId(config.serverUrl)
        val token = config.apiKey?.trim().orEmpty()
        val passphrase = config.password.orEmpty()
        if (token.isEmpty()) throw IOException("Bot token is required")
        if (passphrase.length < MIN_PASSPHRASE) {
            throw IOException("Encryption passphrase must be at least $MIN_PASSPHRASE characters")
        }
        val key = registryKey(token, chatId, passphrase)
        vaults[key]?.let { return TgConnection(it, chatId) }
        synchronized(vaults) {
            vaults[key]?.let { return TgConnection(it, chatId) }
            val client = TelegramBotClient(token)
            try {
                client.getMe()
            } catch (e: TelegramApiException) {
                throw IOException(
                    if (e.errorCode == 401) "Invalid bot token" else "Telegram rejected the bot: ${e.description}",
                    e
                )
            }
            try {
                client.getChat(chatId)
            } catch (e: TelegramApiException) {
                throw IOException(
                    "Cannot access the channel (${e.description}). Add the bot as an administrator of the channel " +
                        "and check the channel id.",
                    e
                )
            }
            val master = TelegramCrypto.deriveMasterKey(passphrase, chatId)
            val vault = TelegramVault(
                client = client,
                chatId = chatId,
                master = master,
                indexFile = indexFileFor(token, chatId),
                log = { CloudTrace.d("Telegram: $it") }
            )
            try {
                vault.open()
            } catch (e: TelegramCryptoException) {
                vault.shutdown()
                throw IOException("Wrong encryption passphrase for this channel", e)
            }
            vaults[key] = vault
            return TgConnection(vault, chatId)
        }
    }

    private fun vault(conn: NetFsConnection) = (conn as TgConnection).vault

    private fun clean(path: String) = path.trim('/')

    override fun listDir(conn: NetFsConnection, path: String): List<NetFsEntry> {
        val dir = clean(path)
        val prefix = if (dir.isEmpty()) "" else "$dir/"
        val dirs = LinkedHashMap<String, NetFsEntry>()
        val files = ArrayList<NetFsEntry>()
        for (e in vault(conn).entries()) {
            if (!e.path.startsWith(prefix)) continue
            val rest = e.path.substring(prefix.length)
            val slash = rest.indexOf('/')
            if (slash < 0) {
                files += NetFsEntry(rest, e.path, false, e.size, e.lastModified)
            } else {
                val name = rest.substring(0, slash)
                val prev = dirs[name]
                if (prev == null || prev.lastModified < e.lastModified) {
                    dirs[name] = NetFsEntry(name, prefix + name, true, 0L, e.lastModified)
                }
            }
        }
        return dirs.values.toList() + files
    }

    override fun openRead(conn: NetFsConnection, path: String, offset: Long): InputStream =
        vault(conn).openRead(clean(path), offset)

    override fun fileSize(conn: NetFsConnection, path: String): Long =
        vault(conn).find(clean(path))?.size ?: throw FileNotFoundException(path)

    override fun exists(conn: NetFsConnection, path: String): Boolean {
        val p = clean(path)
        return p.isEmpty() || vault(conn).find(p) != null ||
            vault(conn).entries().any { it.path.startsWith("$p/") }
    }

    override fun write(conn: NetFsConnection, path: String, data: InputStream, size: Long) {
        upload(conn, path, data, size, takenMs = 0L, thumbnail = null, preview = null)
    }

    override fun write(conn: NetFsConnection, path: String, data: InputStream, size: Long, meta: NetFsWriteMeta) {
        upload(conn, path, data, size, meta.takenMillis, meta.thumbnailJpeg, meta.previewJpeg)
    }

    private fun upload(
        conn: NetFsConnection,
        path: String,
        data: InputStream,
        size: Long,
        takenMs: Long,
        thumbnail: ByteArray?,
        preview: ByteArray?
    ) {
        // The vault replaces an existing entry at the same path and deletes its old encrypted messages.
        vault(conn).upload(clean(path), data, size, takenMs, thumbnail, preview)
    }

    override fun knownContentSha1(conn: NetFsConnection, path: String): String? =
        vault(conn).find(clean(path))?.sha1

    override fun readStoredPreview(conn: NetFsConnection, path: String, size: ThumbnailSize): ByteArray? {
        val pair = vault(conn).readCompanion(clean(path)) ?: return null
        val bytes = if (size == ThumbnailSize.THUMBNAIL) pair.first else pair.second
        return bytes.takeIf { it.isNotEmpty() }
    }

    override fun delete(conn: NetFsConnection, path: String) {
        val outcome = vault(conn).delete(clean(path))
        if (outcome.refused > 0) {
            CloudTrace.d(
                "Telegram: ${outcome.refused} encrypted message(s) could not be removed from the channel " +
                    "(older than 48h); they are unreadable without your passphrase and were dropped from the app."
            )
        }
    }

    /** Folders are implied by the path of the files in them. */
    override fun mkdir(conn: NetFsConnection, path: String) = Unit

    override fun storage(conn: NetFsConnection): NetFsStorage? {
        val used = vault(conn).entries().sumOf { it.size }
        return NetFsStorage(usedBytes = used, totalBytes = 0L, freeBytes = 0L)
    }

    override fun close(conn: NetFsConnection) {
        // The vault is shared by every connection to the same channel; flush the index but keep it open.
        runCatching { vault(conn).requestFlush() }
    }

    private fun indexFileFor(token: String, chatId: String): File {
        val id = sha256Hex("$token|$chatId").take(24)
        val dir = File(context.filesDir, "telegram").apply { mkdirs() }
        return File(dir, "$id.idx")
    }

    companion object {
        const val MIN_PASSPHRASE = 12
        private val vaults = ConcurrentHashMap<String, TelegramVault>()

        internal fun parseChatId(serverUrl: String): String {
            val id = serverUrl.trim().removePrefix("telegram://").removePrefix("TELEGRAM://").trim('/')
            if (id.isEmpty()) throw IOException("Channel id is required (e.g. telegram://-1001234567890)")
            return id
        }

        private fun registryKey(token: String, chatId: String, passphrase: String) =
            sha256Hex("$token|$chatId|$passphrase")

        private fun sha256Hex(s: String): String =
            TelegramCrypto.toHex(MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)))
    }
}
