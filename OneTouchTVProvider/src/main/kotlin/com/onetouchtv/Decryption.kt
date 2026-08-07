package com.onetouchtv

import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.base64DecodeArray
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

private val keyHex = base64Decode("Njk2ZDM3MzI2MzY4NjE3MjUwNjE3MzczNzc2ZjcyNjQ2ZjY2NjQ0OTZlNjk3NDU2NjU2Mzc0NmY3MjUzNzQ2ZA==")
private val ivHex = base64Decode("Njk2ZDM3MzI2MzY4NjE3MjUwNjE3MzczNzc2ZjcyNjQ=")
private val key = keyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
private val iv = ivHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
private val WHITESPACE_REGEX = "\\s+".toRegex()
private val mapper = ObjectMapper()
private data class DecryptResult(val result: String)

fun normalizeCustomAlphabet(s: String): String =
    s.replace("-_.", "/").replace("@", "+").replace(WHITESPACE_REGEX, "")

fun base64ToBytes(b64: String): ByteArray {
    var base64Str = b64
    val pad = base64Str.length % 4
    if (pad != 0) base64Str += "=".repeat(4 - pad)
    return base64DecodeArray(base64Str)
}

fun decryptAes256Cbc(cipherBytes: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
    if (cipherBytes.size % 16 != 0)
        throw IllegalArgumentException("Ciphertext length (${cipherBytes.size}) not multiple of 16.")
    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
    return cipher.doFinal(cipherBytes)
}

private fun unwrapApiEnvelope(json: String): String {
    return try {
        mapper.readTree(json).let { tree ->
            if (tree.has("success") && tree.get("success").asBoolean(false) && tree.has("result"))
                tree.get("result").toString() else json
        }
    } catch (_: Exception) { json }
}

fun decryptString(input: String): String {
    val trimmed = input.trim()
    if (trimmed.startsWith("{") || trimmed.startsWith("[")) return unwrapApiEnvelope(trimmed)
    return runCatching {
        val normalized = normalizeCustomAlphabet(input)
        val cipherBytes = base64ToBytes(normalized)
        val plaintextBytes = decryptAes256Cbc(cipherBytes, key, iv)
        val plaintext = String(plaintextBytes, Charsets.UTF_8)
        val inner = tryParseJson<DecryptResult>(plaintext)?.result ?: plaintext
        unwrapApiEnvelope(inner)
    }.getOrDefault("")
}
