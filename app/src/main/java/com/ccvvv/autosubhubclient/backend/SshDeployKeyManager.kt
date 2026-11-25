package com.ccvvv.autosubhubclient.backend

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey
import java.util.Base64

/** Creates a repository deploy-key pair in app-private storage; the private key is never exported. */
class SshDeployKeyManager(private val filesDir: File) {
    data class KeyInfo(val publicKey: String, val fingerprint: String, val privateKeyFile: File)

    @Synchronized
    fun getOrCreate(): KeyInfo {
        val dir = File(filesDir, "ssh").apply { if (!exists()) check(mkdirs()) }
        val privateFile = File(dir, "autosubhub_deploy_key")
        val publicFile = File(dir, "autosubhub_deploy_key.pub")
        if (!privateFile.isFile || !publicFile.isFile) {
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(3072) }.generateKeyPair()
            val public = pair.public as RSAPublicKey
            val privatePem = pem("PRIVATE KEY", pair.private.encoded)
            val publicLine = "ssh-rsa " + Base64.getEncoder().encodeToString(encodeRsaPublic(public)) +
                " autosubhub-android"
            writePrivate(privateFile, privatePem.toByteArray(Charsets.US_ASCII))
            publicFile.writeText(publicLine + "\n", Charsets.US_ASCII)
            publicFile.setReadable(true, true)
            publicFile.setWritable(true, true)
        }
        val publicKey = publicFile.readText(Charsets.US_ASCII).trim()
        require(publicKey.startsWith("ssh-rsa ")) { "本地 SSH 公钥格式异常" }
        val blob = Base64.getDecoder().decode(publicKey.split(Regex("\\s+"))[1])
        val digest = MessageDigest.getInstance("SHA-256").digest(blob)
        val fingerprint = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
        return KeyInfo(publicKey, fingerprint, privateFile)
    }

    private fun writePrivate(file: File, bytes: ByteArray) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            tmp.outputStream().use { it.write(bytes); it.flush() }
            check(tmp.setReadable(false, false))
            check(tmp.setWritable(false, false))
            check(tmp.setReadable(true, true))
            check(tmp.setWritable(true, true))
            check(tmp.renameTo(file)) { "无法安全保存 SSH 私钥" }
        } finally { tmp.delete() }
    }

    private fun pem(label: String, bytes: ByteArray): String {
        val encoded = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(bytes)
        return "-----BEGIN $label-----\n$encoded\n-----END $label-----\n"
    }

    private fun encodeRsaPublic(key: RSAPublicKey): ByteArray {
        val out = ByteArrayOutputStream()
        val data = DataOutputStream(out)
        writeSshString(data, "ssh-rsa".toByteArray(Charsets.US_ASCII))
        writeMpint(data, key.publicExponent)
        writeMpint(data, key.modulus)
        data.flush()
        return out.toByteArray()
    }

    private fun writeMpint(out: DataOutputStream, value: BigInteger) {
        var bytes = value.toByteArray()
        if (bytes.isNotEmpty() && bytes[0].toInt() == 0 && bytes.size > 1) bytes = bytes.copyOfRange(1, bytes.size)
        if (bytes.isNotEmpty() && (bytes[0].toInt() and 0x80) != 0) bytes = byteArrayOf(0) + bytes
        writeSshString(out, bytes)
    }

    private fun writeSshString(out: DataOutputStream, bytes: ByteArray) {
        out.writeInt(bytes.size)
        out.write(bytes)
    }
}
