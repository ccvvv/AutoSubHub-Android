package com.ccvvv.autosubhubclient.backend

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptStatusVerifierTest {
    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private val candidate = "a".repeat(40)

    private fun receipt(): ByteArray =
        """{"schema_version":1,"status":"completed","candidate_commit":"$candidate"}"""
            .toByteArray(Charsets.UTF_8)

    @Test
    fun exactRemotePendingBytesAreVerifiedAsUploadedPending() {
        val local = receipt()
        val status = ReceiptStatusVerifier.verify(local, local.copyOf(), null)
        assertEquals("uploaded_pending", status.status)
        assertTrue(status.verified)
        assertEquals(sha256(local), status.receiptSha256)
    }

    @Test
    fun durableProcessedRecordMustMatchExactReceiptShaAndCandidate() {
        val local = receipt()
        val record = """{"schema_version":1,"status":"processed","receipt_sha256":"${sha256(local)}","candidate_commit":"$candidate"}"""
            .toByteArray()
        val status = ReceiptStatusVerifier.verify(local, null, record)
        assertEquals("processed", status.status)
        assertTrue(status.verified)
    }

    @Test
    fun mismatchedProcessedRecordIsNotAccepted() {
        val local = receipt()
        val record = """{"schema_version":1,"status":"processed","receipt_sha256":"${"b".repeat(64)}","candidate_commit":"$candidate"}"""
            .toByteArray()
        val status = ReceiptStatusVerifier.verify(local, null, record)
        assertEquals("unverified", status.status)
        assertFalse(status.verified)
    }

    @Test
    fun differentRemotePendingReceiptDoesNotVerifyLocalReceipt() {
        val status = ReceiptStatusVerifier.verify(receipt(), """{"status":"completed"}""".toByteArray(), null)
        assertEquals("unverified", status.status)
        assertFalse(status.verified)
    }
}
