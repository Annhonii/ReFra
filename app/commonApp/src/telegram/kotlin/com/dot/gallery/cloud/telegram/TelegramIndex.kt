/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.cloud.telegram

import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** One encrypted document in the channel: its message id, its (bot-scoped) file id and its size on Telegram. */
internal class TgBlobRef(val messageId: Long, val fileId: String, val blobSize: Int)

/** Everything needed to find, decrypt and describe one uploaded photo/video. */
internal class TgEntry(
    /** Virtual path, e.g. `Camera/IMG_0001.jpg` (the folder is the local album the photo was uploaded from). */
    val path: String,
    /** Plain (decrypted) size in bytes. */
    val size: Long,
    /** Original capture/modified time in epoch millis (drives timeline ordering). */
    val takenMs: Long,
    val addedMs: Long,
    /** SHA-1 (hex) of the plain content, so verification never needs to re-download the file. */
    val sha1: String,
    /** Hex of the random 16-byte id that salts this file's key. */
    val fileKey: String,
    /** Plain bytes per chunk (every chunk but the last is exactly this big). */
    val chunkSize: Int,
    val chunks: List<TgBlobRef>,
    /** Small encrypted blob holding the thumbnail + preview JPEGs, so grids never download originals. */
    val companion: TgBlobRef?,
    val thumbLen: Int,
    val previewLen: Int
) {
    val lastModified: Long get() = if (takenMs > 0) takenMs else addedMs

    fun allMessageIds(): List<Long> =
        chunks.map { it.messageId } + listOfNotNull(companion?.messageId)
}

/**
 * The map of virtual path -> [TgEntry]. Not thread-safe by itself; [TelegramVault] guards it.
 * Serialised to JSON, then sealed by [TelegramCrypto.sealContainer] before it ever touches disk or Telegram.
 */
internal class TelegramIndex {

    private val entries = LinkedHashMap<String, TgEntry>()

    /** Message id of the manifest document in the channel (the pinned message), once known. */
    var manifestMessageId: Long? = null

    /** Telegram messages we still need to delete (a previous attempt failed for a transient reason). */
    val pendingDeletes: MutableList<Long> = ArrayList()

    /** Paths removed on some device (path -> deletion time), so a merge never resurrects them. */
    val tombstones: MutableMap<String, Long> = LinkedHashMap()

    val size: Int get() = entries.size

    operator fun get(path: String): TgEntry? = entries[path]

    fun put(entry: TgEntry): TgEntry? = entries.put(entry.path, entry)

    fun remove(path: String): TgEntry? = entries.remove(path)

    /** Removes [path] and remembers the deletion (shared albums merge indexes from several phones). */
    fun removeWithTombstone(path: String, nowMs: Long): TgEntry? {
        val removed = entries.remove(path)
        tombstones[path] = nowMs
        while (tombstones.size > MAX_TOMBSTONES) tombstones.remove(tombstones.keys.first())
        return removed
    }

    class MergeResult(
        /** The local index gained or lost entries because of the remote one. */
        val changed: Boolean,
        /** The local index holds something the remote one lacks, so it must be pushed back. */
        val localOnly: Boolean
    )

    /**
     * Folds the index another phone pushed to the channel into this one. Entries are unioned by path
     * (the newer upload wins), and deletions travel as tombstones. Pure: no I/O.
     */
    fun mergeRemote(remote: TelegramIndex): MergeResult {
        var changed = false
        var localOnly = false
        for ((path, time) in remote.tombstones) {
            if ((tombstones[path] ?: Long.MIN_VALUE) < time) {
                tombstones[path] = time
            }
        }
        while (tombstones.size > MAX_TOMBSTONES) tombstones.remove(tombstones.keys.first())
        for (mine in all()) {
            val t = tombstones[mine.path]
            if (t != null && t >= mine.addedMs) {
                entries.remove(mine.path)
                changed = true
            }
        }
        for (theirs in remote.all()) {
            val t = tombstones[theirs.path]
            if (t != null && t >= theirs.addedMs) continue
            val mine = entries[theirs.path]
            when {
                mine == null || theirs.addedMs > mine.addedMs -> {
                    entries[theirs.path] = theirs
                    changed = true
                }
                mine.addedMs > theirs.addedMs -> localOnly = true
            }
        }
        for (mine in all()) {
            val theirs = remote[mine.path]
            if (theirs == null || mine.addedMs > theirs.addedMs) localOnly = true
        }
        for ((path, time) in tombstones) {
            if ((remote.tombstones[path] ?: Long.MIN_VALUE) < time) localOnly = true
        }
        return MergeResult(changed, localOnly)
    }

    fun all(): List<TgEntry> = entries.values.toList()

    fun toJsonBytes(): ByteArray {
        val root = buildJsonObject {
            put("v", 1)
            manifestMessageId?.let { put("mm", it) }
            put("pd", buildJsonArray { pendingDeletes.forEach { add(JsonPrimitive(it)) } })
            if (tombstones.isNotEmpty()) {
                put("tb", buildJsonObject { tombstones.forEach { (path, time) -> put(path, time) } })
            }
            put("e", buildJsonArray { entries.values.forEach { add(entryToJson(it)) } })
        }
        return root.toString().toByteArray(Charsets.UTF_8)
    }

    private fun refToJson(ref: TgBlobRef): JsonArray = buildJsonArray {
        add(JsonPrimitive(ref.messageId))
        add(JsonPrimitive(ref.fileId))
        add(JsonPrimitive(ref.blobSize))
    }

    private fun entryToJson(e: TgEntry): JsonObject = buildJsonObject {
        put("p", e.path)
        put("s", e.size)
        put("t", e.takenMs)
        put("a", e.addedMs)
        put("h", e.sha1)
        put("k", e.fileKey)
        put("cs", e.chunkSize)
        put("c", buildJsonArray { e.chunks.forEach { add(refToJson(it)) } })
        e.companion?.let { put("m", refToJson(it)) }
        put("tl", e.thumbLen)
        put("pl", e.previewLen)
    }

    companion object {
        private const val MAX_TOMBSTONES = 5000

        fun fromJsonBytes(bytes: ByteArray): TelegramIndex {
            val root = try {
                Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
            } catch (e: Exception) {
                throw IOException("Index is corrupted", e)
            }
            val version = root["v"]?.jsonPrimitive?.intOrNull
            if (version != 1) throw IOException("Unsupported index version: $version")
            val index = TelegramIndex()
            index.manifestMessageId = root["mm"]?.jsonPrimitive?.longOrNull
            root["tb"]?.jsonObject?.forEach { (path, el) ->
                el.jsonPrimitive.longOrNull?.let { index.tombstones[path] = it }
            }
            root["pd"]?.jsonArray?.forEach { el -> el.jsonPrimitive.longOrNull?.let(index.pendingDeletes::add) }
            root["e"]?.jsonArray?.forEach { el ->
                val e = el.jsonObject
                index.put(
                    TgEntry(
                        path = e.str("p"),
                        size = e.lng("s"),
                        takenMs = e.lng("t"),
                        addedMs = e.lng("a"),
                        sha1 = e.str("h"),
                        fileKey = e.str("k"),
                        chunkSize = e.lng("cs").toInt(),
                        chunks = e["c"]!!.jsonArray.map(::refFromJson),
                        companion = e["m"]?.let(::refFromJson),
                        thumbLen = e.lng("tl").toInt(),
                        previewLen = e.lng("pl").toInt()
                    )
                )
            }
            return index
        }

        private fun refFromJson(el: JsonElement): TgBlobRef {
            val a = el.jsonArray
            return TgBlobRef(
                messageId = a[0].jsonPrimitive.longOrNull ?: throw IOException("Index is corrupted"),
                fileId = a[1].jsonPrimitive.contentOrNull ?: throw IOException("Index is corrupted"),
                blobSize = a[2].jsonPrimitive.intOrNull ?: throw IOException("Index is corrupted")
            )
        }

        private fun JsonObject.str(key: String): String =
            this[key]?.jsonPrimitive?.contentOrNull ?: throw IOException("Index is corrupted")

        private fun JsonObject.lng(key: String): Long =
            this[key]?.jsonPrimitive?.longOrNull ?: throw IOException("Index is corrupted")
    }
}
