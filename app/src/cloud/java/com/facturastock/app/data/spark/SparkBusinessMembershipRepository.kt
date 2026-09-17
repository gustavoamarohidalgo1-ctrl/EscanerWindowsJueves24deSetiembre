package com.facturastock.app.data.spark

import com.facturastock.app.data.account.AccountErrorMapper
import com.facturastock.app.data.account.CloudBusinessLinkBinder
import com.facturastock.app.data.sync.FirebaseBackupRuntime
import com.facturastock.app.domain.error.AccountError
import com.facturastock.app.domain.error.AccountException
import com.facturastock.app.domain.error.DomainResult
import com.facturastock.app.domain.model.AccountSession
import com.facturastock.app.domain.model.BusinessInvitation
import com.facturastock.app.domain.model.BusinessRole
import com.facturastock.app.domain.model.CloudBusinessLink
import com.facturastock.app.domain.model.CloudBusinessLinkCasResult
import com.facturastock.app.domain.model.CloudBusinessLinkReceipt
import com.facturastock.app.domain.model.CloudMember
import com.facturastock.app.domain.model.CloudMembership
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.repository.AccountRepository
import com.facturastock.app.domain.repository.BusinessMembershipRepository
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import java.nio.charset.StandardCharsets
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

/**
 * Membresía unipersonal del plan Spark. El mismo usuario verificado es OWNER en todos sus
 * dispositivos; negocio, miembro compatible con Functions e índice propio nacen atómicamente.
 */
@Singleton
class SparkBusinessMembershipRepository @Inject constructor(
    private val runtime: FirebaseBackupRuntime,
    private val accountRepository: AccountRepository,
    private val linkBinder: CloudBusinessLinkBinder,
) : BusinessMembershipRepository {

    override fun currentLocalBusinessIdentityEpoch(): Long =
        linkBinder.currentLocalBusinessIdentityEpoch()

    override suspend fun listMyMemberships(
        expectedUid: String,
    ): DomainResult<List<CloudMembership>> = guarding(expectedUid) { firestore, session ->
        val memberships = mutableListOf<CloudMembership>()
        val query = firestore.collection(USERS).document(expectedUid)
            .collection(MEMBERSHIPS)
            .orderBy(DISPLAY_NAME)
            .orderBy(FieldPath.documentId())
            .limit(MEMBERSHIP_PAGE_SIZE)
        var cursor: DocumentSnapshot? = null
        do {
            // Nombre e ID son inmutables en Spark. El desempate explícito conserva negocios
            // con el mismo nombre y startAfter evita truncar la cuenta después de cien altas.
            val page = (cursor?.let { query.startAfter(it) } ?: query).get(Source.SERVER).await()
            page.documents.forEach { document ->
                val indexed = decodeMembershipIndex(document, session.uid)
                memberships += getOwnedBusiness(
                    firestore, session, indexed.businessId, indexed.businessDisplayName,
                )
            }
            cursor = page.documents.lastOrNull()
        } while (page.size().toLong() == MEMBERSHIP_PAGE_SIZE)
        DomainResult.Success(memberships)
    }

    override suspend fun createBusiness(
        expectedUid: String,
        displayName: String,
    ): DomainResult<CloudMembership> = guarding(expectedUid) { firestore, session ->
        val normalizedName = displayName.trim()
        if (normalizedName.isEmpty() || normalizedName.length > MAX_DISPLAY_NAME_LENGTH) {
            throw AccountException(AccountError.Unexpected)
        }
        // Dos dispositivos que repiten la misma intención obtienen el mismo UUID v3 canónico.
        val businessId = deterministicSparkBusinessId(expectedUid, normalizedName)
        val businessRef = firestore.collection(BUSINESSES).document(businessId.value)
        val memberRef = businessRef.collection(MEMBERS).document(expectedUid)
        val membershipRef = firestore.collection(USERS).document(expectedUid)
            .collection(MEMBERSHIPS).document(businessId.value)
        val serverTimestamp = FieldValue.serverTimestamp()
        val documents = sparkBusinessDocuments(
            businessId = businessId,
            ownerUid = expectedUid,
            ownerEmail = session.email,
            displayName = normalizedName,
            serverTimestamp = serverTimestamp,
        )

        try {
            // Las reglas validan los tres documentos con getAfter(). Un batch evita abrir un
            // oraculo de existencia para poder leer documentos ausentes antes del alta.
            firestore.batch().apply {
                set(businessRef, documents.business)
                set(memberRef, documents.member)
                set(membershipRef, documents.membership)
            }.commit().await()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (!failure.isFirestorePermissionDenied()) throw failure

            // Un segundo set de una intencion ya confirmada se evalua como update y las reglas
            // lo deniegan. Solo se acepta como replay si los tres documentos existentes tienen
            // exactamente el esquema, propietario e intencion esperados.
            val business = businessRef.get(Source.SERVER).await()
            val member = memberRef.get(Source.SERVER).await()
            val membership = membershipRef.get(Source.SERVER).await()
            decodeBusiness(business, expectedUid, normalizedName)
            decodeOwnerMember(member, expectedUid, session.email)
            decodeMembershipIndex(membership, expectedUid, normalizedName)
        }
        DomainResult.Success(
            CloudMembership(businessId, normalizedName, BusinessRole.OWNER),
        )
    }

    override suspend fun listMembers(
        expectedUid: String,
        businessId: BusinessId,
    ): DomainResult<List<CloudMember>> = guarding(expectedUid) { firestore, session ->
        getOwnedBusiness(firestore, session, businessId)
        val member = firestore.collection(BUSINESSES).document(businessId.value)
            .collection(MEMBERS).document(expectedUid).get(Source.SERVER).await()
        DomainResult.Success(listOf(decodeOwnerMember(member, expectedUid, session.email)))
    }

    override suspend fun listBusinessInvitations(
        expectedUid: String,
        businessId: BusinessId,
    ): DomainResult<List<BusinessInvitation>> = guarding(expectedUid) { firestore, session ->
        getOwnedBusiness(firestore, session, businessId)
        DomainResult.Success(emptyList())
    }

    override suspend fun inviteMember(
        expectedUid: String,
        businessId: BusinessId,
        email: String,
        role: BusinessRole,
    ): DomainResult<Unit> = unsupportedSameAccountMode(expectedUid)

    override suspend fun listMyInvitations(
        expectedUid: String,
    ): DomainResult<List<BusinessInvitation>> = guarding(expectedUid) { _, _ ->
        DomainResult.Success(emptyList())
    }

    override suspend fun acceptInvitation(
        expectedUid: String,
        businessId: BusinessId,
    ): DomainResult<CloudMembership> = unsupportedSameAccountMode(expectedUid)

    override suspend fun declineInvitation(
        expectedUid: String,
        businessId: BusinessId,
    ): DomainResult<Unit> = unsupportedSameAccountMode(expectedUid)

    override suspend fun changeMemberRole(
        expectedUid: String,
        businessId: BusinessId,
        memberUid: String,
        role: BusinessRole,
    ): DomainResult<Unit> = unsupportedSameAccountMode(expectedUid)

    override suspend fun removeMember(
        expectedUid: String,
        businessId: BusinessId,
        memberUid: String,
    ): DomainResult<Unit> = when (val session = checkedSession(expectedUid)) {
        is DomainResult.Failure -> session
        is DomainResult.Success -> if (memberUid == expectedUid) {
            DomainResult.Failure(AccountError.LastOwnerRequired)
        } else {
            DomainResult.Failure(AccountError.NotFound)
        }
    }

    override suspend fun setActiveCloudBusiness(
        expectedUid: String,
        expectedLocalIdentityEpoch: Long,
        link: CloudBusinessLink,
    ): DomainResult<CloudBusinessLinkReceipt> = try {
        linkBinder.setActive(expectedUid, expectedLocalIdentityEpoch, link) {
            activeSession()?.uid == expectedUid
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        DomainResult.Failure(AccountErrorMapper.fromException(failure))
    }

    override suspend fun compareAndSetActiveCloudBusiness(
        expectedUid: String,
        expectedLocalIdentityEpoch: Long,
        expectedReceipt: CloudBusinessLinkReceipt,
        newLink: CloudBusinessLink?,
    ): DomainResult<CloudBusinessLinkCasResult> = try {
        linkBinder.compareAndSet(
            expectedUid = expectedUid,
            expectedLocalIdentityEpoch = expectedLocalIdentityEpoch,
            expectedReceipt = expectedReceipt,
            newLink = newLink,
        ) { activeSession()?.uid == expectedUid }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        DomainResult.Failure(AccountErrorMapper.fromException(failure))
    }

    override suspend fun clearActiveCloudBusinessIfOwned(
        expectedUid: String,
        expectedReceipt: CloudBusinessLinkReceipt,
    ): DomainResult<Boolean> = try {
        when (
            val result = linkBinder.compareAndSet(
                expectedUid = expectedUid,
                expectedLocalIdentityEpoch = currentLocalBusinessIdentityEpoch(),
                expectedReceipt = expectedReceipt,
                newLink = null,
            )
        ) {
            is DomainResult.Success -> DomainResult.Success(result.value.applied)
            is DomainResult.Failure -> result
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        DomainResult.Failure(AccountErrorMapper.fromException(failure))
    }

    override fun observeActiveLink(): Flow<CloudBusinessLink?> =
        accountRepository.observeSession().map { session ->
            (session as? AccountSession.Active)?.link
        }

    private suspend fun getOwnedBusiness(
        firestore: FirebaseFirestore,
        session: AccountSession.Active,
        businessId: BusinessId,
        expectedDisplayName: String? = null,
    ): CloudMembership {
        val snapshot = firestore.collection(BUSINESSES).document(businessId.value)
            .get(Source.SERVER).await()
        return decodeBusiness(snapshot, session.uid, expectedDisplayName)
    }

    private fun decodeBusiness(
        snapshot: DocumentSnapshot,
        expectedUid: String,
        expectedDisplayName: String? = null,
    ): CloudMembership {
        val data = snapshot.data ?: throw AccountException(AccountError.NotFound)
        if (data.keys != BUSINESS_KEYS ||
            data.safeLong(SCHEMA_VERSION) != SPARK_SCHEMA_VERSION ||
            data[BUSINESS_ID] != snapshot.id ||
            data[OWNER_UID] != expectedUid ||
            data[CREATED_BY] != expectedUid ||
            data[CREATED_AT] !is Timestamp
        ) {
            throw AccountException(AccountError.Unexpected)
        }
        val businessId = BusinessId.parse(snapshot.id)
            ?: throw AccountException(AccountError.Unexpected)
        val displayName = (data[DISPLAY_NAME] as? String)
            ?.takeIf { it.isNotEmpty() && it.length <= MAX_DISPLAY_NAME_LENGTH }
            ?: throw AccountException(AccountError.Unexpected)
        if (expectedDisplayName != null && displayName != expectedDisplayName) {
            throw AccountException(AccountError.Conflict)
        }
        return CloudMembership(businessId, displayName, BusinessRole.OWNER)
    }

    private fun decodeMembershipIndex(
        snapshot: DocumentSnapshot,
        expectedUid: String,
        expectedDisplayName: String? = null,
    ): CloudMembership {
        val data = snapshot.data ?: throw AccountException(AccountError.Unexpected)
        if (data.keys != MEMBERSHIP_KEYS ||
            data.safeLong(SCHEMA_VERSION) != SPARK_SCHEMA_VERSION ||
            data[BUSINESS_ID] != snapshot.id ||
            data[UID] != expectedUid ||
            data[ROLE] != BusinessRole.OWNER.name ||
            data[CREATED_AT] !is Timestamp
        ) {
            throw AccountException(AccountError.Unexpected)
        }
        val businessId = BusinessId.parse(snapshot.id)
            ?: throw AccountException(AccountError.Unexpected)
        val displayName = (data[DISPLAY_NAME] as? String)
            ?.takeIf { it.isNotEmpty() && it.length <= MAX_DISPLAY_NAME_LENGTH }
            ?: throw AccountException(AccountError.Unexpected)
        if (expectedDisplayName != null && displayName != expectedDisplayName) {
            throw AccountException(AccountError.Conflict)
        }
        return CloudMembership(businessId, displayName, BusinessRole.OWNER)
    }

    private fun decodeOwnerMember(
        snapshot: DocumentSnapshot,
        expectedUid: String,
        expectedEmail: String,
    ): CloudMember {
        val data = snapshot.data ?: throw AccountException(AccountError.NotFound)
        if (data.keys != MEMBER_KEYS ||
            data.safeLong(SCHEMA_VERSION) != SPARK_SCHEMA_VERSION ||
            data[UID] != expectedUid ||
            data[EMAIL] != expectedEmail ||
            data[ROLE] != BusinessRole.OWNER.name ||
            data[ADDED_VIA] != SPARK_DIRECT ||
            data[OWNER_UID] != expectedUid ||
            data[ADDED_AT] !is Timestamp
        ) {
            throw AccountException(AccountError.Unexpected)
        }
        return CloudMember(expectedUid, expectedEmail, BusinessRole.OWNER)
    }

    private suspend fun <T> guarding(
        expectedUid: String,
        block: suspend (FirebaseFirestore, AccountSession.Active) -> DomainResult<T>,
    ): DomainResult<T> = try {
        val firestore = runtime.firestore()
            ?: return DomainResult.Failure(AccountError.Unavailable)
        when (val session = checkedSession(expectedUid)) {
            is DomainResult.Failure -> session
            is DomainResult.Success -> block(firestore, session.value)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        DomainResult.Failure(SparkFirestoreErrorMapper.fromException(failure))
    }

    private suspend fun checkedSession(
        expectedUid: String,
    ): DomainResult<AccountSession.Active> {
        val session = activeSession()
            ?: return DomainResult.Failure(AccountError.NotAuthenticated)
        return if (session.uid == expectedUid) {
            DomainResult.Success(session)
        } else {
            DomainResult.Failure(AccountError.SessionExpired)
        }
    }

    private suspend fun unsupportedSameAccountMode(expectedUid: String): DomainResult.Failure =
        when (val session = checkedSession(expectedUid)) {
            is DomainResult.Failure -> session
            is DomainResult.Success -> DomainResult.Failure(AccountError.Unavailable)
        }

    private suspend fun activeSession(): AccountSession.Active? =
        accountRepository.observeSession().first() as? AccountSession.Active

    private fun Map<String, Any?>.safeLong(key: String): Long? = when (val value = this[key]) {
        is Byte -> value.toLong()
        is Short -> value.toLong()
        is Int -> value.toLong()
        is Long -> value
        else -> null
    }

    private companion object {
        const val USERS = "users"
        const val BUSINESSES = "businesses"
        const val MEMBERS = "members"
        const val MEMBERSHIPS = "memberships"
        const val SCHEMA_VERSION = "schemaVersion"
        const val SPARK_SCHEMA_VERSION = 1L
        const val BUSINESS_ID = "businessId"
        const val DISPLAY_NAME = "displayName"
        const val OWNER_UID = "ownerUid"
        const val CREATED_BY = "createdBy"
        const val CREATED_AT = "createdAt"
        const val UID = "uid"
        const val EMAIL = "email"
        const val ROLE = "role"
        const val ADDED_AT = "addedAt"
        const val ADDED_VIA = "addedVia"
        const val SPARK_DIRECT = "spark_direct"
        const val MAX_DISPLAY_NAME_LENGTH = 120
        const val MEMBERSHIP_PAGE_SIZE = 100L
        val BUSINESS_KEYS = setOf(
            SCHEMA_VERSION, BUSINESS_ID, DISPLAY_NAME, OWNER_UID, CREATED_BY, CREATED_AT,
        )
        val MEMBER_KEYS = setOf(
            SCHEMA_VERSION, UID, EMAIL, ROLE, ADDED_AT, ADDED_VIA, OWNER_UID,
        )
        val MEMBERSHIP_KEYS = setOf(
            SCHEMA_VERSION, BUSINESS_ID, UID, DISPLAY_NAME, ROLE, CREATED_AT,
        )
    }
}

private fun Throwable.isFirestorePermissionDenied(): Boolean {
    var current: Throwable? = this
    repeat(MAX_FIRESTORE_CAUSE_DEPTH) {
        val failure = current ?: return false
        if (failure is FirebaseFirestoreException) {
            return failure.code == FirebaseFirestoreException.Code.PERMISSION_DENIED
        }
        val cause = failure.cause
        if (cause === failure) return false
        current = cause
    }
    return false
}

internal fun deterministicSparkBusinessId(expectedUid: String, displayName: String): BusinessId {
    require(expectedUid.isNotBlank())
    require(displayName.isNotBlank())
    val seed = "$SPARK_BUSINESS_ID_NAMESPACE$expectedUid\u001f$displayName"
    return BusinessId.from(UUID.nameUUIDFromBytes(seed.toByteArray(StandardCharsets.UTF_8)))
}

internal data class SparkBusinessDocuments(
    val business: Map<String, Any?>,
    val member: Map<String, Any?>,
    val membership: Map<String, Any?>,
)

/** Forma exacta compartida con firestore.spark.rules; el timestamp sigue siendo server-side. */
internal fun sparkBusinessDocuments(
    businessId: BusinessId,
    ownerUid: String,
    ownerEmail: String,
    displayName: String,
    serverTimestamp: Any,
): SparkBusinessDocuments {
    require(ownerUid.isNotBlank() && ownerEmail.isNotBlank())
    require(displayName.isNotBlank() && displayName.length <= 120)
    return SparkBusinessDocuments(
        business = linkedMapOf(
            "schemaVersion" to 1L,
            "businessId" to businessId.value,
            "displayName" to displayName,
            "ownerUid" to ownerUid,
            "createdBy" to ownerUid,
            "createdAt" to serverTimestamp,
        ),
        member = linkedMapOf(
            "schemaVersion" to 1L,
            "uid" to ownerUid,
            "email" to ownerEmail,
            "role" to BusinessRole.OWNER.name,
            "addedAt" to serverTimestamp,
            "addedVia" to "spark_direct",
            "ownerUid" to ownerUid,
        ),
        membership = linkedMapOf(
            "schemaVersion" to 1L,
            "businessId" to businessId.value,
            "uid" to ownerUid,
            "displayName" to displayName,
            "role" to BusinessRole.OWNER.name,
            "createdAt" to serverTimestamp,
        ),
    )
}

private const val SPARK_BUSINESS_ID_NAMESPACE = "facturastock:spark-business:v1:"
private const val MAX_FIRESTORE_CAUSE_DEPTH = 8
