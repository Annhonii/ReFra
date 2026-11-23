/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.cloud.ui

import java.util.Base64

/**
 * Everything a family member needs to join a shared album: the bot, the channel and the album key.
 * Encoded as `refra-album:1:<base64url json>`; whoever holds this text can read and write the album,
 * so it is only ever shared by the owner, on purpose.
 */
data class SharedAlbumInvite(val botToken: String, val chatId: String, val albumKey: String) {

    fun encode(): String {
        val json = buildString {
            append("{\"t\":").append(quote(botToken))
            append(",\"c\":").append(quote(chatId))
            append(",\"k\":").append(quote(albumKey)).append('}')
        }
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray(Charsets.UTF_8))
    }

    companion object {
        const val PREFIX = "refra-album:1:"

        /** Finds an invite anywhere in [text] (people paste it with extra words) and decodes it; null if none. */
        fun decode(text: String): SharedAlbumInvite? {
            val start = text.indexOf(PREFIX)
            if (start < 0) return null
            val payload = text.substring(start + PREFIX.length).takeWhile { it.isLetterOrDigit() || it == '-' || it == '_' }
            return runCatching {
                val json = String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)
                val token = field(json, "t")
                val chat = field(json, "c")
                val key = field(json, "k")
                if (token.isBlank() || chat.isBlank() || key.isBlank()) null else SharedAlbumInvite(token, chat, key)
            }.getOrNull()
        }

        private fun quote(s: String): String =
            "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

        private fun field(json: String, name: String): String {
            val marker = "\"$name\":\""
            val from = json.indexOf(marker).also { require(it >= 0) } + marker.length
            val out = StringBuilder()
            var i = from
            while (i < json.length) {
                val c = json[i]
                if (c == '\\' && i + 1 < json.length) { out.append(json[i + 1]); i += 2; continue }
                if (c == '"') break
                out.append(c); i++
            }
            return out.toString()
        }
    }
}
