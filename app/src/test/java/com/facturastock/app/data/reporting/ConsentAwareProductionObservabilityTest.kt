package com.facturastock.app.data.reporting

import com.facturastock.app.domain.config.AppConfiguration
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.domain.observability.InternalIdentifiers
import com.facturastock.app.domain.observability.OperationalAction
import com.facturastock.app.domain.observability.OperationalAgeBucket
import com.facturastock.app.domain.observability.OperationalAuditEvent
import com.facturastock.app.domain.observability.OperationalErrorCode
import com.facturastock.app.domain.observability.OperationalOutcome
import com.facturastock.app.domain.repository.AppConfigurationRepository
import com.facturastock.app.testing.FakeAppConfigurationRepository
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsentAwareProductionObservabilityTest {

    @Test
    fun `the sink receives only closed fields and never throwable content`() = runTest {
        val configuration = FakeAppConfigurationRepository().apply {
            updateDiagnosticsEnabled(true)
        }
        val sink = RecordingSink()
        val observability = ConsentAwareProductionObservability(configuration, sink)
        val failure = IllegalStateException(
            MESSAGE_SENTINEL,
            IllegalArgumentException(CAUSE_SENTINEL),
        ).apply {
            stackTrace = arrayOf(
                StackTraceElement(
                    STACK_CLASS_SENTINEL,
                    STACK_METHOD_SENTINEL,
                    STACK_FILE_SENTINEL,
                    47,
                ),
            )
        }

        observability.record(
            event = OperationalAuditEvent(
                action = OperationalAction.BACKUP_SYNC,
                outcome = OperationalOutcome.FAILED,
                identifiers = internalIdentifiers(),
            ),
            failure = failure,
        )

        assertEquals(listOf(true), sink.collectionDecisions)
        assertEquals(listOf("collection:true", "emit"), sink.actions)
        val emitted = sink.emissions.single()
        assertEquals("backup_sync", emitted.eventName)
        assertTrue(emitted.isFailure)
        assertEquals(
            linkedMapOf(
                ObservabilityAllowlist.OUTCOME to OperationalOutcome.FAILED.name,
                ObservabilityAllowlist.ERROR_CODE to OperationalErrorCode.UNEXPECTED.name,
            ),
            emitted.attributes,
        )
        val reportText = emitted.asReportText()
        listOf(
            MESSAGE_SENTINEL,
            CAUSE_SENTINEL,
            STACK_CLASS_SENTINEL,
            STACK_METHOD_SENTINEL,
            STACK_FILE_SENTINEL,
            BUSINESS_UUID,
            DRAFT_UUID,
            IMAGE_UUID,
            PURCHASE_UUID,
            OPERATION_UUID,
        ).forEach { sensitive ->
            assertFalse("report leaked $sensitive", reportText.contains(sensitive))
        }
    }

    @Test
    fun `allowlist drops unknown keys and invalid sensitive values`() {
        val sanitized = ObservabilityAllowlist.sanitize(
            eventName = "invoice_capture",
            rawAttributes = linkedMapOf(
                ObservabilityAllowlist.OUTCOME to OperationalOutcome.SUCCEEDED.name,
                ObservabilityAllowlist.ERROR_CODE to OperationalErrorCode.FILE_CORRUPT.name,
                "business_id" to BUSINESS_UUID,
                "draft_id" to EMAIL_VALUE_SENTINEL,
                "image_id" to PATH_VALUE_SENTINEL,
                "message" to MESSAGE_SENTINEL,
                "cause" to CAUSE_SENTINEL,
                "stacktrace" to STACK_FILE_SENTINEL,
                "raw_ocr" to RAW_OCR_SENTINEL,
                UNKNOWN_KEY_SENTINEL to UNKNOWN_VALUE_SENTINEL,
            ),
        )

        val event = requireNotNull(sanitized)
        assertEquals(
            linkedMapOf(
                ObservabilityAllowlist.OUTCOME to OperationalOutcome.SUCCEEDED.name,
                ObservabilityAllowlist.ERROR_CODE to OperationalErrorCode.FILE_CORRUPT.name,
            ),
            event.attributes,
        )
        assertEquals(
            setOf(
                ObservabilityAllowlist.OUTCOME,
                ObservabilityAllowlist.ERROR_CODE,
            ),
            event.attributes.keys,
        )
        val reportText = event.eventName + event.attributes.entries.joinToString()
        listOf(
            EMAIL_VALUE_SENTINEL,
            PATH_VALUE_SENTINEL,
            MESSAGE_SENTINEL,
            CAUSE_SENTINEL,
            STACK_FILE_SENTINEL,
            RAW_OCR_SENTINEL,
            UNKNOWN_KEY_SENTINEL,
            UNKNOWN_VALUE_SENTINEL,
            BUSINESS_UUID,
        ).forEach { sensitive ->
            assertFalse("sanitized payload leaked $sensitive", reportText.contains(sensitive))
        }
    }

    @Test
    fun `invalid event name or missing closed outcome is rejected`() {
        assertNull(
            ObservabilityAllowlist.sanitize(
                eventName = "invoice_capture_$MESSAGE_SENTINEL",
                rawAttributes = mapOf(
                    ObservabilityAllowlist.OUTCOME to OperationalOutcome.SUCCEEDED.name,
                ),
            ),
        )
        assertNull(
            ObservabilityAllowlist.sanitize(
                eventName = "invoice_capture",
                rawAttributes = mapOf("message" to MESSAGE_SENTINEL),
            ),
        )
    }

    @Test
    fun `outbox age accepts only a closed bucket and never an exact timestamp`() {
        val sanitized = requireNotNull(
            ObservabilityAllowlist.sanitize(
                eventName = "backup_sync",
                rawAttributes = mapOf(
                    ObservabilityAllowlist.OUTCOME to OperationalOutcome.RETRY_SCHEDULED.name,
                    ObservabilityAllowlist.AGE_BUCKET to
                        OperationalAgeBucket.FROM_6_TO_24_HOURS.name,
                    "created_at" to "1756000000000",
                ),
            ),
        )

        assertEquals(
            mapOf(
                ObservabilityAllowlist.OUTCOME to OperationalOutcome.RETRY_SCHEDULED.name,
                ObservabilityAllowlist.AGE_BUCKET to
                    OperationalAgeBucket.FROM_6_TO_24_HOURS.name,
            ),
            sanitized.attributes,
        )
        assertNull(
            ObservabilityAllowlist.sanitize(
                eventName = "backup_sync",
                rawAttributes = mapOf(
                    ObservabilityAllowlist.AGE_BUCKET to "1756000000000",
                ),
            ),
        )
    }

    @Test
    fun `persisted or runtime opt out prevents every sink emission`() = runTest {
        val configuration = FakeAppConfigurationRepository()
        val sink = RecordingSink()
        val observability = ConsentAwareProductionObservability(configuration, sink)

        observability.record(successEvent())
        assertTrue(sink.collectionDecisions.isEmpty())
        assertTrue(sink.emissions.isEmpty())

        configuration.updateDiagnosticsEnabled(true)
        observability.updateConsent(false)
        observability.record(successEvent())

        assertEquals(listOf(false), sink.collectionDecisions)
        assertTrue(sink.emissions.isEmpty())
    }

    @Test
    fun `concurrent opt out wins over a record whose consent read was already in flight`() = runTest {
        val storedConfiguration = FakeAppConfigurationRepository().apply {
            updateDiagnosticsEnabled(true)
        }
        val readStarted = CompletableDeferred<Unit>()
        val releaseRead = CompletableDeferred<Unit>()
        val delayedConfiguration = object : AppConfigurationRepository by storedConfiguration {
            override suspend fun current(): AppConfiguration {
                val snapshot = storedConfiguration.current()
                readStarted.complete(Unit)
                releaseRead.await()
                return snapshot
            }
        }
        val sink = RecordingSink()
        val observability = ConsentAwareProductionObservability(delayedConfiguration, sink)

        val inFlightRecord = launch { observability.record(successEvent()) }
        readStarted.await()
        observability.updateConsent(false)
        releaseRead.complete(Unit)
        inFlightRecord.join()

        assertEquals(listOf(false), sink.collectionDecisions)
        assertTrue(sink.emissions.isEmpty())
    }

    @Test
    fun `opt out de sesion gana sobre un snapshot viejo del arranque`() = runTest {
        val configuration = FakeAppConfigurationRepository().apply {
            updateDiagnosticsEnabled(true)
        }
        val sink = RecordingSink()
        val observability = ConsentAwareProductionObservability(configuration, sink)

        // El arranque ya leyó true, pero Ajustes completa el opt-out antes de aplicarse el snapshot.
        observability.updateConsent(false)
        observability.syncInitialConsent(true)

        assertEquals(listOf(false), sink.collectionDecisions)
    }

    @Test
    fun `sink failures including cancellation never escape record or consent updates`() = runTest {
        listOf(
            ThrowingSink(throwWhenSettingConsent = true),
            ThrowingSink(throwWhenEmitting = true),
            ThrowingSink(throwCancellationWhenEmitting = true),
        ).forEach { sink ->
            val configuration = FakeAppConfigurationRepository().apply {
                updateDiagnosticsEnabled(true)
            }
            val observability = ConsentAwareProductionObservability(configuration, sink)

            observability.updateConsent(true)
            observability.record(successEvent())
        }
    }

    @Test
    fun `headless process enables collection before its first consented event`() = runTest {
        val configuration = FakeAppConfigurationRepository().apply {
            updateDiagnosticsEnabled(true)
        }
        val sink = RecordingSink()
        val observability = ConsentAwareProductionObservability(configuration, sink)

        // No hay syncInitialConsent: reproduce un proceso iniciado únicamente por WorkManager.
        observability.record(successEvent())

        assertEquals(listOf("collection:true", "emit"), sink.actions)
        assertEquals(1, sink.emissions.size)
    }

    @Test
    fun `failed headless enable drops the event and retries before a later emission`() = runTest {
        val configuration = FakeAppConfigurationRepository().apply {
            updateDiagnosticsEnabled(true)
        }
        val sink = FailFirstEnableSink()
        val observability = ConsentAwareProductionObservability(configuration, sink)

        observability.record(successEvent())
        assertEquals(listOf("collection:true:failed"), sink.actions)

        observability.record(successEvent())
        assertEquals(
            listOf("collection:true:failed", "collection:true", "emit"),
            sink.actions,
        )
    }

    @Test
    fun `operation id only accepts nonzero canonical internal UUIDs`() {
        assertThrows(IllegalArgumentException::class.java) {
            InternalIdentifiers(operationId = "not-an-internal-id")
        }
        assertThrows(IllegalArgumentException::class.java) {
            InternalIdentifiers(operationId = OPERATION_UUID.uppercase())
        }
        assertThrows(IllegalArgumentException::class.java) {
            InternalIdentifiers(operationId = "00000000-0000-0000-0000-000000000000")
        }
        assertEquals(
            OPERATION_UUID,
            InternalIdentifiers(operationId = OPERATION_UUID).operationId,
        )
    }

    private fun successEvent() = OperationalAuditEvent(
        action = OperationalAction.INVOICE_CAPTURE,
        outcome = OperationalOutcome.SUCCEEDED,
        identifiers = internalIdentifiers(),
    )

    private fun internalIdentifiers() = InternalIdentifiers(
        businessId = BusinessId.from(UUID.fromString(BUSINESS_UUID)),
        draftId = DraftId.from(UUID.fromString(DRAFT_UUID)),
        imageId = ImageId.from(UUID.fromString(IMAGE_UUID)),
        purchaseId = PurchaseId.from(UUID.fromString(PURCHASE_UUID)),
        operationId = OPERATION_UUID,
    )

    private data class Emission(
        val eventName: String,
        val attributes: Map<String, String>,
        val isFailure: Boolean,
    ) {
        fun asReportText(): String = buildString {
            append(eventName)
            attributes.toSortedMap().forEach { (key, value) ->
                append(' ').append(key).append('=').append(value)
            }
            append(" failure=").append(isFailure)
        }
    }

    private class RecordingSink : ObservabilitySink {
        val collectionDecisions = mutableListOf<Boolean>()
        val emissions = mutableListOf<Emission>()
        val actions = mutableListOf<String>()

        override fun setCollectionEnabled(enabled: Boolean) {
            collectionDecisions += enabled
            actions += "collection:$enabled"
        }

        override fun emit(
            eventName: String,
            attributes: Map<String, String>,
            isFailure: Boolean,
        ) {
            emissions += Emission(eventName, attributes.toMap(), isFailure)
            actions += "emit"
        }
    }

    private class FailFirstEnableSink : ObservabilitySink {
        val actions = mutableListOf<String>()
        private var failed = false

        override fun setCollectionEnabled(enabled: Boolean) {
            if (enabled && !failed) {
                failed = true
                actions += "collection:true:failed"
                error("transient-enable-failure")
            }
            actions += "collection:$enabled"
        }

        override fun emit(
            eventName: String,
            attributes: Map<String, String>,
            isFailure: Boolean,
        ) {
            actions += "emit"
        }
    }

    private class ThrowingSink(
        private val throwWhenSettingConsent: Boolean = false,
        private val throwWhenEmitting: Boolean = false,
        private val throwCancellationWhenEmitting: Boolean = false,
    ) : ObservabilitySink {
        override fun setCollectionEnabled(enabled: Boolean) {
            if (throwWhenSettingConsent) error("sink-set-$MESSAGE_SENTINEL")
        }

        override fun emit(
            eventName: String,
            attributes: Map<String, String>,
            isFailure: Boolean,
        ) {
            if (throwCancellationWhenEmitting) {
                throw CancellationException("sink-cancel-$MESSAGE_SENTINEL")
            }
            if (throwWhenEmitting) error("sink-emit-$MESSAGE_SENTINEL")
        }
    }

    private companion object {
        const val BUSINESS_UUID = "10000000-0000-4000-8000-000000000001"
        const val DRAFT_UUID = "10000000-0000-4000-8000-000000000002"
        const val IMAGE_UUID = "10000000-0000-4000-8000-000000000003"
        const val PURCHASE_UUID = "10000000-0000-4000-8000-000000000004"
        const val OPERATION_UUID = "a0000000-0000-4000-8000-000000000005"

        const val MESSAGE_SENTINEL = "SENSITIVE_MESSAGE_user@example.pe_TOKEN"
        const val CAUSE_SENTINEL = "SENSITIVE_CAUSE_RUC_20123456789"
        const val STACK_CLASS_SENTINEL = "sensitive.stack.user_example_pe"
        const val STACK_METHOD_SENTINEL = "token_abcd1234"
        const val STACK_FILE_SENTINEL = "/private/invoices/customer-42.jpg"
        const val EMAIL_VALUE_SENTINEL = "private.user@example.pe"
        const val PATH_VALUE_SENTINEL = "content://invoice/raw/customer-42"
        const val RAW_OCR_SENTINEL = "FACTURA F001-42 TOTAL S 1180"
        const val UNKNOWN_KEY_SENTINEL = "supplier_ruc_20123456789"
        const val UNKNOWN_VALUE_SENTINEL = "Bearer secret-access-token"
    }
}
