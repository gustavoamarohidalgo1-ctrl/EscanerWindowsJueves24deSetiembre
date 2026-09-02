package com.facturastock.app.data.account

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.facturastock.app.domain.error.AccountError
import com.facturastock.app.domain.error.DomainResult
import com.facturastock.app.domain.model.BusinessRole
import com.facturastock.app.domain.model.CloudBusinessLink
import com.facturastock.app.domain.model.MAX_SAFE_SYNC_SEQUENCE
import com.facturastock.app.domain.model.SyncCursor
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.repository.SyncCursorRepository
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Persistencia del enlace local ↔ nube y de los cursores de pull en el DataStore propio
 * `account_settings` (separado de `app_settings`). Mapeo tolerante como en
 * `AppSettingsDataStore`: un valor que no es un UUID canónico, o un enlace guardado a medias,
 * se lee como ausente en lugar de romper la lectura.
 *
 * Aquí viven el enlace, su UID propietario, su revisión, el checkpoint de borrado y las seqs ya
 * aplicadas; jamás tokens ni credenciales.
 */
class CloudAccountSettingsStore(
    private val dataStore: DataStore<Preferences>,
) : SyncCursorRepository {
    /**
     * Enlace durable junto con la cuenta que lo publicó. `ownerUid == null` identifica datos
     * escritos por una versión anterior y se conserva solo para una migración compatible.
     */
    fun observeStoredLink(): Flow<StoredCloudBusinessLink?> = dataStore.data.map { preferences ->
        val cloudBusinessId = BusinessId.parse(preferences[CLOUD_BUSINESS_ID])
        val localBusinessId = BusinessId.parse(preferences[LINKED_LOCAL_BUSINESS_ID])
        val role = BusinessRole.parse(preferences[LINKED_BUSINESS_ROLE])
        val rawRevision = preferences[LINK_REVISION]
        val revision = when {
            rawRevision == null -> 0L // Migración de enlaces escritos antes de la revisión.
            rawRevision >= 0L -> rawRevision
            else -> null
        }
        if (
            cloudBusinessId != null && localBusinessId != null && role != null &&
            revision != null
        ) {
            StoredCloudBusinessLink(
                ownerUid = preferences[LINKED_ACCOUNT_UID]?.takeIf(String::isNotBlank),
                link = CloudBusinessLink(
                    localBusinessId = localBusinessId,
                    cloudBusinessId = cloudBusinessId,
                    role = role,
                ),
                revision = revision,
            )
        } else {
            null
        }
    }

    /** Enlace sin metadatos, usado por consumidores que ya validaron la sesión propietaria. */
    fun observeLink(): Flow<CloudBusinessLink?> = observeStoredLink().map { it?.link }

    suspend fun storedLink(): StoredCloudBusinessLink? = observeStoredLink().first()

    /**
     * Checkpoint durable escrito antes de enviar `deleteMyAccount`. Su presencia obliga a
     * terminar una limpieza local idempotente incluso después de muerte de proceso.
     */
    fun observePendingAccountDeletionUid(): Flow<String?> = dataStore.data.map { preferences ->
        preferences[PENDING_ACCOUNT_DELETION_UID]?.takeIf(String::isNotBlank)
    }

    suspend fun pendingAccountDeletionUid(): String? =
        dataStore.data.first()[PENDING_ACCOUNT_DELETION_UID]?.takeIf(String::isNotBlank)

    suspend fun markAccountDeletionPending(uid: String) {
        require(uid.isNotBlank()) { "uid requerido" }
        dataStore.edit { preferences ->
            val current = preferences[PENDING_ACCOUNT_DELETION_UID]
            require(current == null || current == uid) {
                "otra cuenta ya tiene un borrado pendiente"
            }
            preferences[PENDING_ACCOUNT_DELETION_UID] = uid
        }
    }

    /** Retira solo el checkpoint esperado, sin tocar el enlace/cursor de otra cuenta activa. */
    suspend fun clearPendingAccountDeletion(uid: String) {
        dataStore.edit { preferences ->
            if (preferences[PENDING_ACCOUNT_DELETION_UID] == uid) {
                preferences.remove(PENDING_ACCOUNT_DELETION_UID)
            }
        }
    }

    /**
     * Fija o reemplaza el enlace y emite una revisión durable nueva. `null` elimina el enlace,
     * pero conserva el contador para que un clear→set idéntico nunca recree el mismo recibo.
     */
    suspend fun setLink(
        ownerUid: String,
        link: CloudBusinessLink?,
    ): StoredCloudBusinessLink? {
        require(ownerUid.isNotBlank()) { "uid requerido" }
        var stored: StoredCloudBusinessLink? = null
        dataStore.edit { preferences ->
            if (link == null) {
                preferences.remove(CLOUD_BUSINESS_ID)
                preferences.remove(LINKED_LOCAL_BUSINESS_ID)
                preferences.remove(LINKED_BUSINESS_ROLE)
                preferences.remove(LINKED_ACCOUNT_UID)
            } else {
                val currentRevision = preferences[LINK_REVISION] ?: 0L
                check(currentRevision in 0 until Long.MAX_VALUE) {
                    "revisión de enlace agotada o corrupta"
                }
                val nextRevision = currentRevision + 1L
                preferences[CLOUD_BUSINESS_ID] = link.cloudBusinessId.value
                preferences[LINKED_LOCAL_BUSINESS_ID] = link.localBusinessId.value
                preferences[LINKED_BUSINESS_ROLE] = link.role.name
                preferences[LINKED_ACCOUNT_UID] = ownerUid
                preferences[LINK_REVISION] = nextRevision
                stored = StoredCloudBusinessLink(ownerUid, link, nextRevision)
            }
        }
        return stored
    }

    /**
     * Olvida los datos de cuenta, conservando solo la revisión no identificable. Esto impide que
     * un callback previo al sign-out coincida con un enlace idéntico publicado tras reingresar.
     */
    suspend fun clear() {
        dataStore.edit { preferences ->
            val revision = preferences[LINK_REVISION]
            preferences.clear()
            revision?.let { preferences[LINK_REVISION] = it }
        }
    }

    /**
     * Última seq aplicada del negocio; 0 si nunca se hizo pull. Una preferencia corrupta se
     * trata como "sin cursor": el pull completo es una lectura réplica idempotente, así que
     * repetirlo nunca rompe nada.
     */
    override suspend fun lastPulledSeq(businessId: BusinessId): Long =
        try {
            dataStore.data.first()[pullCursorSeqKey(businessId)]
                ?.takeIf { it in 0..MAX_SAFE_SYNC_SEQUENCE }
                ?: 0L
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            0L
        }

    override suspend fun saveCursor(
        businessId: BusinessId,
        seq: Long,
        pulledAt: Instant,
    ): DomainResult<Unit> {
        if (seq !in 0..MAX_SAFE_SYNC_SEQUENCE) {
            return DomainResult.Failure(AccountError.Unexpected)
        }
        return try {
            dataStore.edit { preferences ->
                preferences[pullCursorSeqKey(businessId)] = seq
                preferences[pullCursorAtKey(businessId)] = pulledAt.toEpochMilli()
            }
            DomainResult.Success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            DomainResult.Failure(AccountErrorMapper.fromException(failure))
        }
    }

    /** Cursor observable del negocio; `null` si falta alguna mitad o los valores no son sanos. */
    override fun observeCursor(businessId: BusinessId): Flow<SyncCursor?> =
        dataStore.data.map { preferences ->
            val seq = preferences[pullCursorSeqKey(businessId)]
                ?.takeIf { it in 0..MAX_SAFE_SYNC_SEQUENCE }
            val pulledAt = preferences[pullCursorAtKey(businessId)]?.takeIf { it >= 0L }
            if (seq != null && pulledAt != null) {
                SyncCursor(seq = seq, lastPullAt = Instant.ofEpochMilli(pulledAt))
            } else {
                null
            }
        }

    private companion object {
        val CLOUD_BUSINESS_ID = stringPreferencesKey("cloud_business_id")
        val LINKED_LOCAL_BUSINESS_ID = stringPreferencesKey("linked_local_business_id")
        val LINKED_BUSINESS_ROLE = stringPreferencesKey("linked_business_role")
        val LINKED_ACCOUNT_UID = stringPreferencesKey("linked_account_uid")
        val LINK_REVISION = longPreferencesKey("linked_business_revision")
        val PENDING_ACCOUNT_DELETION_UID =
            stringPreferencesKey("pending_account_deletion_uid")

        fun pullCursorSeqKey(businessId: BusinessId) =
            longPreferencesKey("pull_cursor_seq_${businessId.value}")

        fun pullCursorAtKey(businessId: BusinessId) =
            longPreferencesKey("pull_cursor_at_${businessId.value}")
    }
}

data class StoredCloudBusinessLink(
    val ownerUid: String?,
    val link: CloudBusinessLink,
    val revision: Long,
)
