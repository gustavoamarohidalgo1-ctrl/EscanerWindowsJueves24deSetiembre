package com.facturastock.app.data.reporting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FirebaseObservabilityCollectionGateTest {
    @Test
    fun `proceso nuevo niega un override habilitado antes de entregar Firebase`() {
        val previousProcessApplications = mutableListOf<Pair<String, Boolean>>()
        FailClosedFirebaseCollectionGate<String> { target, enabled ->
            previousProcessApplications += target to enabled
        }.apply {
            update(enabled = true, initializedTarget = "firebase-process-1")
        }
        assertEquals(listOf("firebase-process-1" to true), previousProcessApplications)

        val restartedProcessApplications = mutableListOf<Pair<String, Boolean>>()
        val restartedGate = FailClosedFirebaseCollectionGate<String> { target, enabled ->
            restartedProcessApplications += target to enabled
        }

        restartedGate.onTargetInitialized("firebase-process-2")

        assertEquals(listOf("firebase-process-2" to false), restartedProcessApplications)
        assertFalse(restartedGate.isEmissionAllowed("firebase-process-2"))
    }

    @Test
    fun `opt out previo no necesita target y se aplica durante inicializacion`() {
        val applications = mutableListOf<Pair<String, Boolean>>()
        val gate = FailClosedFirebaseCollectionGate<String> { target, enabled ->
            applications += target to enabled
        }

        gate.update(enabled = false, initializedTarget = null)
        assertEquals(emptyList<Pair<String, Boolean>>(), applications)

        gate.onTargetInitialized("firebase")
        assertEquals(listOf("firebase" to false), applications)
        assertFalse(gate.isEmissionAllowed("firebase"))
    }

    @Test
    fun `failed enable never authorizes emission and a successful retry does`() {
        var failEnable = true
        val gate = FailClosedFirebaseCollectionGate<String> { _, enabled ->
            if (enabled && failEnable) error("sdk-enable-failure")
        }

        assertThrows(IllegalStateException::class.java) {
            gate.update(enabled = true, initializedTarget = "firebase")
        }
        assertFalse(gate.isEmissionAllowed("firebase"))

        failEnable = false
        gate.update(enabled = true, initializedTarget = "firebase")
        assertTrue(gate.isEmissionAllowed("firebase"))
    }

    @Test
    fun `opt out revokes emission before invoking an sdk that fails`() {
        lateinit var gate: FailClosedFirebaseCollectionGate<Target>
        val target = Target()
        var failDisable = true
        gate = FailClosedFirebaseCollectionGate { appliedTarget, enabled ->
            if (!enabled) {
                assertFalse(gate.isEmissionAllowed(appliedTarget))
                if (failDisable) error("sdk-disable-failure")
            }
        }
        gate.update(enabled = true, initializedTarget = target)
        assertTrue(gate.isEmissionAllowed(target))

        assertThrows(IllegalStateException::class.java) {
            gate.update(enabled = false, initializedTarget = target)
        }
        assertFalse(gate.isEmissionAllowed(target))

        failDisable = false
        gate.update(enabled = false, initializedTarget = target)
        assertFalse(gate.isEmissionAllowed(target))
    }

    @Test
    fun `failed default denial during initialization never authorizes target`() {
        val target = Target()
        val gate = FailClosedFirebaseCollectionGate<Target> { _, _ ->
            error("sdk-default-denial-failure")
        }

        assertThrows(IllegalStateException::class.java) {
            gate.onTargetInitialized(target)
        }
        assertFalse(gate.isEmissionAllowed(target))
    }

    @Test
    fun `deny that throws after side effect is compensated before enable`() {
        val sdk = FakeSdkState(collection = true, identifiableStorage = true, crashlytics = true)
        var throwAfterFirstDeny = true

        assertThrows(IllegalStateException::class.java) {
            applyFailClosedSdkTransition(
                enabled = true,
                deny = {
                    sdk.denyAll()
                    if (throwAfterFirstDeny) {
                        throwAfterFirstDeny = false
                        error("deny-after-side-effect")
                    }
                },
                enable = { sdk.collection = true },
            )
        }

        assertEquals(FakeSdkState(false, false, false), sdk)
    }

    @Test
    fun `enable that throws after side effect rolls back every channel`() {
        val sdk = FakeSdkState(collection = true, identifiableStorage = true, crashlytics = true)

        assertThrows(IllegalStateException::class.java) {
            applyFailClosedSdkTransition(
                enabled = true,
                deny = sdk::denyAll,
                enable = {
                    sdk.collection = true
                    error("enable-after-side-effect")
                },
            )
        }

        assertEquals(FakeSdkState(false, false, false), sdk)
    }

    @Test
    fun `successful opt in enables only non identifying event collection`() {
        val sdk = FakeSdkState(collection = true, identifiableStorage = true, crashlytics = true)

        applyFailClosedSdkTransition(
            enabled = true,
            deny = sdk::denyAll,
            enable = { sdk.collection = true },
        )

        assertEquals(FakeSdkState(true, false, false), sdk)
    }

    private data class FakeSdkState(
        var collection: Boolean,
        var identifiableStorage: Boolean,
        var crashlytics: Boolean,
    ) {
        fun denyAll() {
            collection = false
            identifiableStorage = false
            crashlytics = false
        }
    }

    private class Target
}
