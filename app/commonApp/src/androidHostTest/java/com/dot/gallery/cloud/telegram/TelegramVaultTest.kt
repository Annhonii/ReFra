/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.cloud.telegram

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** In-memory stand-in for the parts of the Telegram Bot API the private cloud uses. */
internal class FakeTelegram(val token: String = "123456:TEST-TOKEN_abc") : AutoCloseable {

    class Stored(var fileName: String, var bytes: ByteArray, var fileId: String)

    val messages = ConcurrentHashMap<Long, Stored>()
    val sendFields = CopyOnWriteArrayList<Map<String, String>>()
    val downloads = AtomicInteger()
    val sends = AtomicInteger()
    private val nextId = AtomicLong(100)
    private val editCounter = AtomicInteger()

    @Volatile var pinnedId: Long? = null
    @Volatile var chatId = "-1001234567890"
    /** 0-based: the send with this ordinal fails with 400. */
    @Volatile var failSendNumber = -1
    @Volatile var deleteMode = "ok" // ok | refuse | error500
    @Volatile var rateLimitNextCall = false
    @Volatile var errorDescriptionWithToken = false
    @Volatile var rejectEdits = false

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also {
        it.executor = Executors.newCachedThreadPool()
        it.createContext("/") { ex -> handle(ex) }
        it.start()
    }

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    override fun close() = server.stop(0)

    private fun reply(ex: HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray()
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun fail(ex: HttpExchange, code: Int, description: String, extra: String = "") =
        reply(ex, code, """{"ok":false,"error_code":$code,"description":"$description"$extra}""")

    private fun docJson(id: Long, s: Stored) =
        """{"message_id":$id,"chat":{"id":${chatId}},"document":{"file_name":"${s.fileName}","file_id":"${s.fileId}","file_unique_id":"u$id","file_size":${s.bytes.size}}}"""

    private fun handle(ex: HttpExchange) {
        try {
            val path = ex.requestURI.path
            val body = ex.requestBody.readBytes()
            if (path.startsWith("/file/bot$token/")) {
                val fileId = path.removePrefix("/file/bot$token/documents/").removeSuffix(".bin")
                val stored = messages.values.firstOrNull { it.fileId == fileId }
                if (stored == null) return fail(ex, 404, "Not Found")
                downloads.incrementAndGet()
                ex.sendResponseHeaders(200, stored.bytes.size.toLong())
                ex.responseBody.use { it.write(stored.bytes) }
                return
            }
            if (!path.startsWith("/bot$token/")) return fail(ex, 401, "Unauthorized")
            if (rateLimitNextCall) {
                rateLimitNextCall = false
                return fail(ex, 429, "Too Many Requests: retry after 1", ""","parameters":{"retry_after":1}""")
            }
            val method = path.removePrefix("/bot$token/")
            val parts = parseMultipart(ex.requestHeaders.getFirst("Content-Type").orEmpty(), body)
            val fields = parts.filter { it.fileName == null }.associate { it.name to String(it.bytes) }
            val file = parts.firstOrNull { it.fileName != null }
            if (errorDescriptionWithToken && method == "getMe") {
                return fail(ex, 400, "bad token $token here")
            }
            if (method != "getMe" && fields["chat_id"] != null && fields["chat_id"] != chatId) {
                return fail(ex, 400, "Bad Request: chat not found")
            }
            when (method) {
                "getMe" -> reply(ex, 200, """{"ok":true,"result":{"id":777,"is_bot":true,"username":"refra_test_bot"}}""")
                "getChat" -> {
                    val pinned = pinnedId?.let { id -> messages[id]?.let { ""","pinned_message":${docJson(id, it)}""" } }.orEmpty()
                    reply(ex, 200, """{"ok":true,"result":{"id":$chatId,"type":"channel","title":"Vault"$pinned}}""")
                }
                "sendDocument" -> {
                    val n = sends.getAndIncrement()
                    if (n == failSendNumber) return fail(ex, 400, "Bad Request: wrong file")
                    sendFields += fields
                    val id = nextId.incrementAndGet()
                    val stored = Stored(file!!.fileName!!, file.bytes, "FID$id")
                    messages[id] = stored
                    reply(ex, 200, """{"ok":true,"result":${docJson(id, stored)}}""")
                }
                "editMessageMedia" -> {
                    val id = fields["message_id"]!!.toLong()
                    val stored = messages[id]
                    if (rejectEdits || stored == null) return fail(ex, 400, "Bad Request: message to edit not found")
                    check(fields["media"]!!.contains("attach://payload"))
                    stored.bytes = file!!.bytes
                    stored.fileName = file.fileName!!
                    stored.fileId = "FID$id-e${editCounter.incrementAndGet()}"
                    reply(ex, 200, """{"ok":true,"result":${docJson(id, stored)}}""")
                }
                "pinChatMessage" -> {
                    pinnedId = fields["message_id"]!!.toLong()
                    reply(ex, 200, """{"ok":true,"result":true}""")
                }
                "getFile" -> {
                    val stored = messages.values.firstOrNull { it.fileId == fields["file_id"] }
                        ?: return fail(ex, 400, "Bad Request: wrong file_id")
                    reply(ex, 200, """{"ok":true,"result":{"file_id":"${stored.fileId}","file_size":${stored.bytes.size},"file_path":"documents/${stored.fileId}.bin"}}""")
                }
                "deleteMessage" -> {
                    when (deleteMode) {
                        "refuse" -> return fail(ex, 400, "Bad Request: message can't be deleted")
                        "error500" -> return fail(ex, 500, "Internal Server Error")
                    }
                    val removed = messages.remove(fields["message_id"]!!.toLong())
                    if (removed == null) fail(ex, 400, "Bad Request: message to delete not found")
                    else reply(ex, 200, """{"ok":true,"result":true}""")
                }
                else -> fail(ex, 404, "Not Found")
            }
        } catch (t: Throwable) {
            runCatching { fail(ex, 500, "fake server failure: $t") }
        }
    }

    class Part(val name: String, val fileName: String?, val bytes: ByteArray)

    private fun parseMultipart(contentType: String, body: ByteArray): List<Part> {
        val boundary = contentType.substringAfter("boundary=", "")
        if (boundary.isEmpty()) return emptyList()
        val delimiter = "--$boundary".toByteArray()
        val parts = ArrayList<Part>()
        var pos = indexOf(body, delimiter, 0)
        while (pos >= 0) {
            val start = pos + delimiter.size
            if (start + 2 > body.size || (body[start] == '-'.code.toByte() && body[start + 1] == '-'.code.toByte())) break
            val headerEnd = indexOf(body, "\r\n\r\n".toByteArray(), start)
            val headers = String(body, start, headerEnd - start).trim()
            val next = indexOf(body, delimiter, headerEnd + 4)
            val content = body.copyOfRange(headerEnd + 4, next - 2) // strip trailing CRLF
            val name = Regex("name=\"([^\"]*)\"").find(headers)!!.groupValues[1]
            val fileName = Regex("filename=\"([^\"]*)\"").find(headers)?.groupValues?.get(1)
            parts += Part(name, fileName, content)
            pos = next
        }
        return parts
    }

    private fun indexOf(data: ByteArray, pattern: ByteArray, from: Int): Int {
        outer@ for (i in from..data.size - pattern.size) {
            for (j in pattern.indices) if (data[i + j] != pattern[j]) continue@outer
            return i
        }
        return -1
    }
}

class TelegramVaultTest {

    private lateinit var tg: FakeTelegram
    private lateinit var dir: File
    private val chat = "-1001234567890"
    private val random = Random(42)
    private val chunk = 1 shl 20

    @Before
    fun setUp() {
        tg = FakeTelegram()
        dir = Files.createTempDirectory("tgvault").toFile()
    }

    @After
    fun tearDown() {
        tg.close()
        dir.deleteRecursively()
    }

    private fun master(pass: String = "correct horse battery staple") =
        TelegramCrypto.deriveMasterKey(pass, chat, iterations = 1000)

    private fun client(token: String = tg.token, base: String = tg.baseUrl) =
        TelegramBotClient(token, base, sleeper = {})

    private fun vault(
        master: ByteArray = master(),
        file: File = File(dir, "index.bin"),
        client: TelegramBotClient = client()
    ) = TelegramVault(client, chat, master, file, chunkSize = chunk, manifestDelayMs = 3_600_000L).also { it.open() }

    private fun bytes(size: Int) = ByteArray(size).also { random.nextBytes(it) }

    private fun sha1(b: ByteArray) = TelegramCrypto.toHex(MessageDigest.getInstance("SHA-1").digest(b))

    private fun TelegramVault.put(path: String, data: ByteArray, thumb: ByteArray? = null, preview: ByteArray? = null) =
        upload(path, ByteArrayInputStream(data), data.size.toLong(), 1_700_000_000_000L, thumb, preview)

    private fun TelegramVault.readAll(path: String, offset: Long = 0L): ByteArray =
        openRead(path, offset).use { it.readBytes() }

    // ---- crypto -----------------------------------------------------------------------------

    @Test
    fun hkdfMatchesRfc5869TestCase1() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = ByteArray(13) { it.toByte() }
        val info = ByteArray(10) { (0xf0 + it).toByte() }
        val okm = TelegramCrypto.hkdf(ikm, salt, info, 42)
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            TelegramCrypto.toHex(okm)
        )
    }

    @Test
    fun hkdfWithEmptySaltMatchesRfc5869TestCase3() {
        val okm = TelegramCrypto.hkdf(ByteArray(22) { 0x0b }, ByteArray(0), ByteArray(0), 42)
        assertEquals(
            "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8",
            TelegramCrypto.toHex(okm)
        )
    }

    @Test
    fun blobRoundTripAndTamperDetection() {
        val crypto = TelegramCrypto.FileCrypto(master(), TelegramCrypto.randomBytes(16))
        val data = bytes(10_000)
        val blob = crypto.encrypt(TelegramCrypto.KIND_DATA, 3L, true, data)
        assertArrayEquals(data, crypto.decrypt(TelegramCrypto.KIND_DATA, 3L, true, blob))
        assertEquals(TelegramCrypto.blobSize(data.size), blob.size)

        // Wrong position / wrong last-flag / wrong kind must all fail authentication.
        for (attempt in listOf<() -> Unit>(
            { crypto.decrypt(TelegramCrypto.KIND_DATA, 4L, true, blob) },
            { crypto.decrypt(TelegramCrypto.KIND_DATA, 3L, false, blob) },
            { crypto.decrypt(TelegramCrypto.KIND_COMPANION, 3L, true, blob) }
        )) {
            try { attempt(); fail("expected authentication failure") } catch (_: TelegramCryptoException) {}
        }
        // Any flipped bit in the ciphertext must be detected.
        val flipped = blob.copyOf().also { it[TelegramCrypto.FILE_ID_BYTES + 100] = (it[TelegramCrypto.FILE_ID_BYTES + 100].toInt() xor 1).toByte() }
        try { crypto.decrypt(TelegramCrypto.KIND_DATA, 3L, true, flipped); fail() } catch (_: TelegramCryptoException) {}
        // A blob of another file (different id) is refused outright.
        val other = TelegramCrypto.FileCrypto(master(), TelegramCrypto.randomBytes(16))
        try { other.decrypt(TelegramCrypto.KIND_DATA, 3L, true, blob); fail() } catch (_: TelegramCryptoException) {}
        // A different passphrase derives a different key.
        val wrong = TelegramCrypto.FileCrypto(master("another passphrase entirely"), crypto.fileId)
        try { wrong.decrypt(TelegramCrypto.KIND_DATA, 3L, true, blob); fail() } catch (_: TelegramCryptoException) {}
    }

    @Test
    fun paddingHidesExactSizes() {
        val crypto = TelegramCrypto.FileCrypto(master(), TelegramCrypto.randomBytes(16))
        val a = crypto.encrypt(TelegramCrypto.KIND_DATA, 0L, true, bytes(1_000))
        val b = crypto.encrypt(TelegramCrypto.KIND_DATA, 0L, true, bytes(60_000))
        assertEquals(a.size, b.size)
    }

    @Test
    fun everyFileGetsItsOwnKeyAndBlobsLookRandom() {
        val data = ByteArray(5000) { 'A'.code.toByte() }
        val v = vault()
        val e1 = v.put("Cam/a.jpg", data)
        val e2 = v.put("Cam/b.jpg", data)
        assertNotEquals(e1.fileKey, e2.fileKey)
        val stored = tg.messages.values.filter { it.fileName != TelegramVault.MANIFEST_NAME }.map { it.bytes }
        assertEquals(2, stored.size)
        assertFalse(stored[0].contentEquals(stored[1]))
        stored.forEach { blob ->
            // Identical plaintext must not show through anywhere in the ciphertext.
            assertFalse(String(blob, Charsets.ISO_8859_1).contains("AAAAAAAAAAAAAAAA"))
        }
    }

    // ---- upload / download -------------------------------------------------------------------

    @Test
    fun uploadsRoundTripAcrossChunkBoundaries() {
        val v = vault()
        val sizes = listOf(1, chunk - 1, chunk, chunk + 1, 3 * chunk + 12_345, 2 * chunk)
        for ((i, size) in sizes.withIndex()) {
            val data = bytes(size)
            val entry = v.put("Album/f$i.jpg", data)
            assertEquals(size.toLong(), entry.size)
            assertEquals(sha1(data), entry.sha1)
            assertEquals((size + chunk - 1) / chunk, entry.chunks.size)
            assertArrayEquals("size $size", data, v.readAll("Album/f$i.jpg"))
        }
    }

    @Test
    fun emptyFileIsStoredWithoutChunks() {
        val v = vault()
        val entry = v.put("Album/empty.jpg", ByteArray(0))
        assertEquals(0L, entry.size)
        assertTrue(entry.chunks.isEmpty())
        assertEquals(0, v.readAll("Album/empty.jpg").size)
        assertEquals(0, tg.sends.get())
    }

    @Test
    fun rangeReadsMatchTheOriginal() {
        val v = vault()
        val data = bytes(3 * chunk + 777)
        v.put("A/video.mp4", data)
        for (offset in listOf(0L, 1L, chunk - 1L, chunk.toLong(), chunk + 5L, 2L * chunk + 100, data.size - 1L, data.size.toLong())) {
            val expected = data.copyOfRange(offset.toInt(), data.size)
            assertArrayEquals("offset $offset", expected, v.readAll("A/video.mp4", offset))
        }
        // Small reads that straddle a chunk edge.
        v.openRead("A/video.mp4", chunk - 10L).use { s ->
            val buf = ByteArray(25)
            var n = 0
            while (n < buf.size) n += s.read(buf, n, buf.size - n)
            assertArrayEquals(data.copyOfRange(chunk - 10, chunk + 15), buf)
        }
    }

    @Test
    fun sendsDocumentsAsProtectedOpaqueBins() {
        val v = vault()
        v.put("Private/IMG_secret_name.jpg", bytes(2 * chunk))
        assertTrue(tg.sendFields.isNotEmpty())
        tg.sendFields.forEach {
            assertEquals("true", it["protect_content"])
            assertEquals("true", it["disable_content_type_detection"])
            assertEquals(chat, it["chat_id"])
            assertNull(it["caption"])
        }
        tg.messages.values.forEach { assertTrue(it.fileName, Regex("^[0-9a-f]{12}\\.bin$").matches(it.fileName)) }
    }

    @Test
    fun companionPreviewsRoundTrip() {
        val v = vault()
        val thumb = bytes(15_000)
        val preview = bytes(120_000)
        v.put("A/x.jpg", bytes(1000), thumb, preview)
        val (t, p) = v.readCompanion("A/x.jpg")!!
        assertArrayEquals(thumb, t)
        assertArrayEquals(preview, p)
        assertNull(v.readCompanion("A/missing.jpg"))
        v.put("A/y.jpg", bytes(1000))
        assertNull(v.readCompanion("A/y.jpg"))
    }

    @Test
    fun replacingAPathDeletesTheOldMessages() {
        val v = vault()
        val first = v.put("A/x.jpg", bytes(2 * chunk))
        val second = v.put("A/x.jpg", bytes(1000))
        assertEquals(1, v.entries().size)
        first.allMessageIds().forEach { assertFalse(tg.messages.containsKey(it)) }
        second.allMessageIds().forEach { assertTrue(tg.messages.containsKey(it)) }
    }

    @Test
    fun failedUploadRollsBackUploadedChunks() {
        val v = vault()
        tg.failSendNumber = 2 // third chunk of a four chunk file
        try {
            v.put("A/big.mp4", bytes(4 * chunk))
            fail("expected failure")
        } catch (_: IOException) {}
        assertTrue(v.entries().isEmpty())
        assertTrue("orphans left in channel: ${tg.messages.keys}", tg.messages.isEmpty())
    }

    @Test
    fun sizeMismatchIsRejectedAndRolledBack() {
        val v = vault()
        try {
            v.upload("A/x.jpg", ByteArrayInputStream(bytes(5000)), 9999L, 0L, null, null)
            fail()
        } catch (_: IOException) {}
        assertTrue(v.entries().isEmpty())
        assertTrue(tg.messages.isEmpty())
    }

    // ---- delete ------------------------------------------------------------------------------

    @Test
    fun deleteRemovesIndexEntryAndTelegramMessages() {
        val v = vault()
        val entry = v.put("A/x.jpg", bytes(2 * chunk), bytes(100), bytes(200))
        val outcome = v.delete("A/x.jpg")
        assertTrue(outcome.existed)
        assertEquals(entry.allMessageIds().size, outcome.deleted)
        assertNull(v.find("A/x.jpg"))
        assertTrue(tg.messages.isEmpty())
        assertFalse(v.delete("A/x.jpg").existed)
    }

    @Test
    fun deleteStillForgetsWhenTelegramRefusesOldMessages() {
        val v = vault()
        v.put("A/x.jpg", bytes(1000))
        tg.deleteMode = "refuse"
        val outcome = v.delete("A/x.jpg")
        assertTrue(outcome.existed)
        assertEquals(1, outcome.refused)
        assertEquals(0, outcome.deleted)
        assertNull(v.find("A/x.jpg"))
        assertEquals(1, tg.messages.size) // still in the channel, but unreadable ciphertext
    }

    @Test
    fun transientDeleteFailuresAreRetriedLater() {
        val file = File(dir, "index.bin")
        val v = vault(file = file)
        v.put("A/x.jpg", bytes(1000))
        tg.deleteMode = "error500"
        val outcome = v.delete("A/x.jpg")
        assertEquals(1, outcome.deferred)
        assertEquals(1, tg.messages.size)
        tg.deleteMode = "ok"
        v.put("A/y.jpg", bytes(10))
        v.flushManifest() // also retries pending deletions
        // After the retry only y's blob and the manifest remain.
        assertEquals(2, tg.messages.size)
    }

    // ---- passphrase, persistence, restore ------------------------------------------------------

    @Test
    fun indexSurvivesRestartAndNeverStoresPlainNames() {
        val file = File(dir, "index.bin")
        val data = bytes(5000)
        vault(file = file).put("Holiday/Secret-Beach.jpg", data)
        val raw = file.readBytes()
        assertFalse(String(raw, Charsets.ISO_8859_1).contains("Secret-Beach"))
        assertFalse(String(raw, Charsets.ISO_8859_1).contains("Holiday"))
        val reopened = vault(file = file)
        assertArrayEquals(data, reopened.readAll("Holiday/Secret-Beach.jpg"))
    }

    @Test
    fun wrongPassphraseIsRejectedOnLocalIndex() {
        val file = File(dir, "index.bin")
        vault(file = file).put("A/x.jpg", bytes(100))
        try {
            vault(master = master("a different passphrase"), file = file)
            fail("expected wrong-passphrase failure")
        } catch (_: TelegramCryptoException) {}
    }

    @Test
    fun manifestRestoresTheIndexAfterReinstall() {
        val original = vault()
        val a = bytes(3 * chunk + 5)
        val b = bytes(2000)
        original.put("Camera/a.jpg", a, bytes(50), bytes(80))
        original.put("Trips/b.jpg", b)
        original.flushManifest()
        assertNotNull(tg.pinnedId)
        assertEquals(TelegramVault.MANIFEST_NAME, tg.messages[tg.pinnedId]!!.fileName)

        // "Reinstall": new empty storage, same passphrase + bot + channel.
        val fresh = vault(file = File(dir, "other/index.bin"))
        assertEquals(setOf("Camera/a.jpg", "Trips/b.jpg"), fresh.entries().map { it.path }.toSet())
        assertArrayEquals(a, fresh.readAll("Camera/a.jpg"))
        assertArrayEquals(b, fresh.readAll("Trips/b.jpg"))
        assertNotNull(fresh.readCompanion("Camera/a.jpg"))
    }

    @Test
    fun wrongPassphraseNeverOverwritesTheRemoteManifest() {
        val original = vault()
        original.put("A/x.jpg", bytes(100))
        original.flushManifest()
        val manifestBefore = tg.messages[tg.pinnedId]!!.bytes.copyOf()
        try {
            vault(master = master("totally wrong"), file = File(dir, "other/index.bin"))
            fail("expected wrong-passphrase failure")
        } catch (_: TelegramCryptoException) {}
        assertArrayEquals(manifestBefore, tg.messages[tg.pinnedId]!!.bytes)
    }

    @Test
    fun freshChannelStartsEmpty() {
        val v = vault()
        assertTrue(v.entries().isEmpty())
        assertEquals(0, tg.sends.get())
    }

    @Test
    fun manifestIsEditedInPlaceAndFallsBackToResend() {
        val v = vault()
        v.put("A/x.jpg", bytes(100))
        v.flushManifest()
        val firstManifestId = tg.pinnedId!!
        v.put("A/y.jpg", bytes(100))
        v.flushManifest()
        assertEquals("edit in place keeps the same pinned message", firstManifestId, tg.pinnedId)

        tg.rejectEdits = true
        v.put("A/z.jpg", bytes(100))
        v.flushManifest()
        assertNotEquals(firstManifestId, tg.pinnedId)
        assertFalse("old manifest removed", tg.messages.containsKey(firstManifestId))
        tg.rejectEdits = false
        val fresh = vault(file = File(dir, "other/index.bin"))
        assertEquals(3, fresh.entries().size)
    }

    // ---- integrity & networking ----------------------------------------------------------------

    @Test
    fun tamperedBlobOnTelegramIsDetected() {
        val v = vault()
        v.put("A/x.jpg", bytes(5000))
        val stored = tg.messages.values.first()
        stored.bytes = stored.bytes.copyOf().also { it[200] = (it[200].toInt() xor 0x55).toByte() }
        try {
            v.readAll("A/x.jpg")
            fail("tampering must not go unnoticed")
        } catch (_: TelegramCryptoException) {}
    }

    @Test
    fun swappedBlobsBetweenFilesAreDetected() {
        val v = vault()
        v.put("A/one.jpg", bytes(5000))
        v.put("A/two.jpg", bytes(5000))
        val (m1, m2) = tg.messages.values.toList()
        val tmp = m1.bytes; m1.bytes = m2.bytes; m2.bytes = tmp
        try {
            v.readAll("A/one.jpg")
            fail()
        } catch (_: TelegramCryptoException) {}
    }

    @Test
    fun rateLimitsAreRetriedUsingRetryAfter() {
        val waits = CopyOnWriteArrayList<Long>()
        val c = TelegramBotClient(tg.token, tg.baseUrl, sleeper = { waits += it })
        tg.rateLimitNextCall = true
        assertEquals(777L, c.getMe().id)
        assertEquals(1, waits.size)
        assertTrue(waits[0] >= 1000L)
    }

    @Test
    fun theBotTokenNeverAppearsInErrors() {
        val dead = TelegramBotClient(tg.token, "http://127.0.0.1:1", sleeper = {})
        try {
            dead.getMe(); fail()
        } catch (e: IOException) {
            assertFalse(e.message.orEmpty().contains(tg.token))
        }
        tg.errorDescriptionWithToken = true
        try {
            client().getMe(); fail()
        } catch (e: TelegramApiException) {
            assertFalse(e.message.orEmpty().contains(tg.token))
            assertEquals(400, e.errorCode)
        }
        val badToken = TelegramBotClient("999:WRONG", tg.baseUrl, sleeper = {})
        try {
            badToken.getMe(); fail()
        } catch (e: TelegramApiException) {
            assertEquals(401, e.errorCode)
        }
    }

    @Test
    fun wrongChannelGivesAReadableError() {
        val c = client()
        try {
            c.getChat("-100999"); fail()
        } catch (e: TelegramApiException) {
            assertTrue(e.description, e.description.contains("chat not found"))
        }
    }

    @Test
    fun concurrentReadersShareDownloadedChunks() {
        val v = vault()
        val data = bytes(4 * chunk)
        v.put("A/big.mp4", data)
        tg.downloads.set(0)
        val pool = Executors.newFixedThreadPool(8)
        val go = CountDownLatch(1)
        val results = (0 until 8).map {
            pool.submit<ByteArray> { go.await(); v.readAll("A/big.mp4") }
        }
        go.countDown()
        results.forEach { assertArrayEquals(data, it.get(60, TimeUnit.SECONDS)) }
        pool.shutdownNow()
        assertTrue("downloads=${tg.downloads.get()}", tg.downloads.get() <= 4 * 2)
    }

    @Test
    fun repeatedReadsAreServedFromTheChunkCache() {
        val v = vault()
        v.put("A/x.jpg", bytes(chunk / 2))
        v.readAll("A/x.jpg")
        val after = tg.downloads.get()
        v.readAll("A/x.jpg")
        assertEquals(after, tg.downloads.get())
    }

    @Test
    fun indexJsonRoundTrips() {
        val index = TelegramIndex()
        index.manifestMessageId = 55L
        index.pendingDeletes += listOf(1L, 2L)
        index.put(
            TgEntry(
                "A/b.jpg", 10L, 20L, 30L, "abc", "00ff", 1024,
                listOf(TgBlobRef(1, "f1", 100), TgBlobRef(2, "f2", 200)), TgBlobRef(3, "f3", 50), 4, 5
            )
        )
        val copy = TelegramIndex.fromJsonBytes(index.toJsonBytes())
        assertEquals(55L, copy.manifestMessageId)
        assertEquals(listOf(1L, 2L), copy.pendingDeletes)
        val e = copy["A/b.jpg"]!!
        assertEquals(listOf(1L, 2L, 3L), e.allMessageIds())
        assertEquals("f2", e.chunks[1].fileId)
        assertEquals(5, e.previewLen)
        assertEquals(20L, e.lastModified)
    }
}
