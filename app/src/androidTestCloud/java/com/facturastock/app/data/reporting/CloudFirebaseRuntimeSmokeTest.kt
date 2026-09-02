package com.facturastock.app.data.reporting

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import com.facturastock.app.data.sync.FirebaseBackupRuntime
import com.facturastock.app.domain.observability.OperationalAction
import com.facturastock.app.domain.observability.OperationalAuditEvent
import com.facturastock.app.domain.observability.OperationalOutcome
import com.facturastock.app.domain.observability.ProductionObservability
import com.google.firebase.analytics.FirebaseAnalytics
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Smoke real del grafo cloudDebug: Firebase/App Check inicializan y el opt-out queda estable. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class CloudFirebaseRuntimeSmokeTest {
    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var runtime: FirebaseBackupRuntime

    @Inject
    lateinit var observability: ProductionObservability

    @Inject
    lateinit var collectionGate: FirebaseObservabilityCollectionGate

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun firebaseRuntimeRequiresVerifiedOptInAndOptOutRevokesEmission() = runBlocking {
        val firebaseApp = requireNotNull(runtime.ready())
        assertFalse(collectionGate.isEmissionAllowed(firebaseApp))

        observability.updateConsent(true)
        assertTrue(collectionGate.isEmissionAllowed(firebaseApp))
        observability.record(
            OperationalAuditEvent(
                action = OperationalAction.OUTBOX_HEALTH,
                outcome = OperationalOutcome.SUCCEEDED,
            ),
        )
        val analytics = FirebaseAnalytics.getInstance(ApplicationProvider.getApplicationContext())
        assertNull(
            "ANALYTICS_STORAGE=DENIED nunca debe crear un app-instance ID",
            withTimeout(10_000L) { analytics.appInstanceId.await() },
        )

        observability.updateConsent(false)
        assertFalse(collectionGate.isEmissionAllowed(firebaseApp))
        observability.record(
            OperationalAuditEvent(
                action = OperationalAction.BACKUP_SYNC,
                outcome = OperationalOutcome.SKIPPED,
            ),
        )
    }
}
