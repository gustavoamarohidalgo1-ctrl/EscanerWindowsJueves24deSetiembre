package com.facturastock.app.data.reporting


import com.facturastock.app.domain.observability.OperationalErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviousProcessExitReporterTest {
    @Test
    fun `abnormal exits map to closed buckets without descriptions or traces`() {
        assertEquals(
            OperationalErrorCode.PROCESS_EXIT_ANR,
            processExitErrorCode(ApplicationExitInfo.REASON_ANR),
        )
        assertEquals(
            OperationalErrorCode.PROCESS_EXIT_CRASH,
            processExitErrorCode(ApplicationExitInfo.REASON_CRASH_NATIVE),
        )
        assertEquals(
            OperationalErrorCode.PROCESS_EXIT_LOW_MEMORY,
            processExitErrorCode(ApplicationExitInfo.REASON_LOW_MEMORY),
        )
        assertEquals(
            OperationalErrorCode.PROCESS_EXIT_RESOURCE_LIMIT,
            processExitErrorCode(ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE),
        )
        assertEquals(
            OperationalErrorCode.PROCESS_EXIT_SYSTEM_FAILURE,
            processExitErrorCode(ApplicationExitInfo.REASON_INITIALIZATION_FAILURE),
        )
    }

    @Test
    fun `normal user and update exits are ignored`() {
        assertNull(processExitErrorCode(ApplicationExitInfo.REASON_EXIT_SELF))
        assertNull(processExitErrorCode(ApplicationExitInfo.REASON_USER_REQUESTED))
        assertNull(processExitErrorCode(ApplicationExitInfo.REASON_USER_STOPPED))
        assertNull(processExitErrorCode(ApplicationExitInfo.REASON_PACKAGE_UPDATED))
    }

    @Test
    fun `fingerprint detects rollback and separates exits in the same millisecond`() {
        val prior = ProcessExitRecord(
            timestamp = 2_000L,
            pid = 101,
            reason = ApplicationExitInfo.REASON_CRASH,
            status = 6,
            processName = "com.facturastock.app",
        )
        val afterClockRewind = ProcessExitRecord(
            timestamp = 1_000L,
            pid = 102,
            reason = ApplicationExitInfo.REASON_ANR,
            status = 0,
            processName = "com.facturastock.app",
        )
        val sameTimeDifferentProcess = prior.copy(pid = 103, processName = "remote-service")

        val priorFingerprint = fingerprintProcessExitRecord(prior)
        val rewindFingerprint = fingerprintProcessExitRecord(afterClockRewind)
        val otherFingerprint = fingerprintProcessExitRecord(sameTimeDifferentProcess)

        assertTrue(priorFingerprint.matches(Regex("[0-9a-f]{64}")))
        assertTrue(rewindFingerprint !in setOf(priorFingerprint))
        assertTrue(otherFingerprint !in setOf(priorFingerprint, rewindFingerprint))
    }

    @Test
    fun `missing or corrupt ring fails open so legacy timestamp cannot hide a crash`() {
        assertEquals(emptyList<String>(), decodeProcessExitFingerprintRing(null))
        assertEquals(emptyList<String>(), decodeProcessExitFingerprintRing("1000:6"))
        assertEquals(emptyList<String>(), decodeProcessExitFingerprintRing("not-a-hash"))
    }

    @Test
    fun `ring refreshes handled records and remains bounded without clock ordering`() {
        val stored = listOf("a".repeat(64), "b".repeat(64), "c".repeat(64))
        val handled = listOf("b".repeat(64), "d".repeat(64), "e".repeat(64))

        assertEquals(
            listOf("d".repeat(64), "e".repeat(64)),
            mergeProcessExitFingerprintRing(stored, handled, limit = 2),
        )
    }

    @Test
    fun `complete checkpoint keeps every record returned by a large Android ring`() {
        val records = List(100) { index ->
            ProcessExitRecord(
                timestamp = 10_000L - index,
                pid = index + 1,
                reason = ApplicationExitInfo.REASON_CRASH,
                status = 6,
                processName = "process-$index",
            )
        }
        val firstPass = records.map(::fingerprintProcessExitRecord)

        val checkpoint = checkpointProcessExitFingerprints(
            stored = emptyList(),
            handled = firstPass,
            completedHistory = true,
        )
        val secondPassUnseen = records.filter { record ->
            fingerprintProcessExitRecord(record) !in checkpoint.toHashSet()
        }

        assertEquals(100, checkpoint.size)
        assertTrue(secondPassUnseen.isEmpty())
    }
}
