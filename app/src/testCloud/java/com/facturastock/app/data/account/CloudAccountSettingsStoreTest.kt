package com.facturastock.app.data.account

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.facturastock.app.domain.error.AccountError
import com.facturastock.app.domain.error.DomainResult
import com.facturastock.app.domain.model.BusinessRole
import com.facturastock.app.domain.model.CloudBusinessLink
import com.facturastock.app.domain.model.MAX_SAFE_SYNC_SEQUENCE
import com.facturastock.app.domain.model.id.BusinessId
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CloudAccountSettingsStoreTest {
    private lateinit var tempDir: File
    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: CloudAccountSettingsStore

    @Before
    fun setUp() {
        tempDir = createTempDirectory("cloud-account-settings-test").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            File(tempDir, "account.preferences_pb")
        }
        store = CloudAccountSettingsStore(dataStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
        tempDir.deleteRecursively()
    }

    @Test
    fun `cursor fuera del entero seguro se trata como ausente y no sale al backend`() = runTest {
        dataStore.edit { preferences ->
            preferences[longPreferencesKey("pull_cursor_seq_${BUSINESS_ID.value}")] =
                MAX_SAFE_SYNC_SEQUENCE + 1
            preferences[longPreferencesKey("pull_cursor_at_${BUSINESS_ID.value}")] = 1_000L
        }

        assertEquals(0L, store.lastPulledSeq(BUSINESS_ID))
        assertNull(store.observeCursor(BUSINESS_ID).first())
        assertEquals(
            DomainResult.Failure(AccountError.Unexpected),
            store.saveCursor(BUSINESS_ID, Long.MAX_VALUE, Instant.EPOCH),
        )
    }

    @Test
    fun `enlace persiste y recupera el rol empresarial`() = runTest {
        val link = CloudBusinessLink(
            localBusinessId = BUSINESS_ID,
            cloudBusinessId = CLOUD_BUSINESS_ID,
            role = BusinessRole.ADMIN,
        )

        store.setLink(UID, link)

        assertEquals(link, store.observeLink().first())
        assertEquals(UID, store.storedLink()?.ownerUid)
    }

    @Test
    fun `revision sobrevive clear y distingue publicaciones identicas`() = runTest {
        val link = CloudBusinessLink(
            localBusinessId = BUSINESS_ID,
            cloudBusinessId = CLOUD_BUSINESS_ID,
            role = BusinessRole.ADMIN,
        )

        val first = checkNotNull(store.setLink(UID, link))
        store.setLink(UID, null)
        val second = checkNotNull(store.setLink(UID, link))
        store.clear()
        val third = checkNotNull(store.setLink(UID, link))

        assertTrue(second.revision > first.revision)
        assertTrue(third.revision > second.revision)
        assertEquals(third.revision, store.storedLink()?.revision)
    }

    @Test
    fun `enlace legacy sin rol falla cerrado`() = runTest {
        dataStore.edit { preferences ->
            preferences[stringPreferencesKey("linked_local_business_id")] = BUSINESS_ID.value
            preferences[stringPreferencesKey("cloud_business_id")] = CLOUD_BUSINESS_ID.value
        }

        assertNull(store.observeLink().first())
    }

    @Test
    fun `enlace con rol corrupto falla cerrado`() = runTest {
        dataStore.edit { preferences ->
            preferences[stringPreferencesKey("linked_local_business_id")] = BUSINESS_ID.value
            preferences[stringPreferencesKey("cloud_business_id")] = CLOUD_BUSINESS_ID.value
            preferences[stringPreferencesKey("linked_business_role")] = "SUPERUSER"
        }

        assertNull(store.observeLink().first())
    }

    @Test
    fun `checkpoint de borrado persiste y solo lo limpia el UID exacto`() = runTest {
        store.markAccountDeletionPending(UID)

        assertEquals(UID, store.pendingAccountDeletionUid())
        assertEquals(UID, store.observePendingAccountDeletionUid().first())
        store.clearPendingAccountDeletion("otra-cuenta")
        assertEquals(UID, store.pendingAccountDeletionUid())

        store.clearPendingAccountDeletion(UID)

        assertNull(store.pendingAccountDeletionUid())
    }

    private companion object {
        val BUSINESS_ID: BusinessId = BusinessId.from(
            UUID.fromString("11111111-1111-4111-8111-111111111111"),
        )
        val CLOUD_BUSINESS_ID: BusinessId = BusinessId.from(
            UUID.fromString("22222222-2222-4222-8222-222222222222"),
        )
        const val UID = "uid-settings"
    }
}
