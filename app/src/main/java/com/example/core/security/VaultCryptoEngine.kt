package com.example.core.security

import com.example.core.common.FormatUtils
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class EncryptionMetadata(
    val saltHex: String,
    val ivHex: String,
    val encryptedFileSize: Long,
    val encryptedSha256: String
)

/**
 * Authenticated AES-256-GCM file and manifest encryption engine.
 * - Uses unique 16-byte random salt and 12-byte (96-bit) random nonce per file.
 * - Prepends 5-byte magic header ("NVLT1") + 16-byte salt + 12-byte IV.
 * - Appends 128-bit GCM authentication tag automatically via Cipher.
 */
object VaultCryptoEngine {

    private val MAGIC_HEADER = "NVLT1".toByteArray(Charsets.US_ASCII) // 5 bytes
    private const val SALT_LENGTH = 16
    private const val IV_LENGTH = 12
    private const val GCM_TAG_BITS = 128
    private const val PBKDF2_ITERATIONS = 32_768
    private const val KEY_BITS = 256

    fun deriveAesKey(passphrase: String, salt: ByteArray): SecretKey {
        require(passphrase.isNotBlank()) { "Encryption passphrase must not be empty" }
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_BITS)
        val rawKey = factory.generateSecret(spec).encoded
        return SecretKeySpec(rawKey, "AES")
    }

    fun computeKeyFingerprint(passphrase: String): String {
        if (passphrase.isBlank()) return "NONE"
        val hash = FormatUtils.computeSha256Bytes("NVLT_FP:${passphrase.trim()}".toByteArray(Charsets.UTF_8))
        return hash.take(12).uppercase().chunked(4).joinToString("-")
    }

    fun encryptFile(
        sourceFile: File,
        targetEncryptedFile: File,
        passphrase: String
    ): EncryptionMetadata {
        val random = SecureRandom()
        val salt = ByteArray(SALT_LENGTH).also { random.nextBytes(it) }
        val iv = ByteArray(IV_LENGTH).also { random.nextBytes(it) }
        val secretKey = deriveAesKey(passphrase, salt)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))

        FileInputStream(sourceFile).use { input ->
            FileOutputStream(targetEncryptedFile).use { output ->
                output.write(MAGIC_HEADER)
                output.write(salt)
                output.write(iv)
                CipherOutputStream(output, cipher).use { cipherOut ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var read = input.read(buffer)
                    while (read != -1) {
                        cipherOut.write(buffer, 0, read)
                        read = input.read(buffer)
                    }
                }
            }
        }

        val encryptedHash = FileInputStream(targetEncryptedFile).use { FormatUtils.computeSha256(it) }
        return EncryptionMetadata(
            saltHex = salt.toHex(),
            ivHex = iv.toHex(),
            encryptedFileSize = targetEncryptedFile.length(),
            encryptedSha256 = encryptedHash
        )
    }

    fun decryptFile(
        encryptedFile: File,
        targetPlainFile: File,
        passphrase: String
    ) {
        FileInputStream(encryptedFile).use { input ->
            val header = ByteArray(MAGIC_HEADER.size)
            val headerRead = input.read(header)
            require(headerRead == MAGIC_HEADER.size && header.contentEquals(MAGIC_HEADER)) {
                "Invalid NothingVault encrypted file header (expected NVLT1)"
            }

            val salt = ByteArray(SALT_LENGTH)
            require(input.read(salt) == SALT_LENGTH) { "Corrupted salt in encrypted file" }

            val iv = ByteArray(IV_LENGTH)
            require(input.read(iv) == IV_LENGTH) { "Corrupted nonce/IV in encrypted file" }

            val secretKey = deriveAesKey(passphrase, salt)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))

            CipherInputStream(input, cipher).use { cipherIn ->
                FileOutputStream(targetPlainFile).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var read = cipherIn.read(buffer)
                    while (read != -1) {
                        output.write(buffer, 0, read)
                        read = cipherIn.read(buffer)
                    }
                }
            }
        }
    }

    fun encryptBytes(plainBytes: ByteArray, passphrase: String): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(SALT_LENGTH).also { random.nextBytes(it) }
        val iv = ByteArray(IV_LENGTH).also { random.nextBytes(it) }
        val secretKey = deriveAesKey(passphrase, salt)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        val cipherText = cipher.doFinal(plainBytes)

        return MAGIC_HEADER + salt + iv + cipherText
    }

    fun decryptBytes(encryptedPayload: ByteArray, passphrase: String): ByteArray {
        val minSize = MAGIC_HEADER.size + SALT_LENGTH + IV_LENGTH + (GCM_TAG_BITS / 8)
        require(encryptedPayload.size >= minSize) { "Encrypted payload too short" }

        val header = encryptedPayload.copyOfRange(0, MAGIC_HEADER.size)
        require(header.contentEquals(MAGIC_HEADER)) { "Invalid NVLT1 magic header" }

        val saltStart = MAGIC_HEADER.size
        val ivStart = saltStart + SALT_LENGTH
        val cipherStart = ivStart + IV_LENGTH

        val salt = encryptedPayload.copyOfRange(saltStart, ivStart)
        val iv = encryptedPayload.copyOfRange(ivStart, cipherStart)
        val cipherBytes = encryptedPayload.copyOfRange(cipherStart, encryptedPayload.size)

        val secretKey = deriveAesKey(passphrase, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(cipherBytes)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
