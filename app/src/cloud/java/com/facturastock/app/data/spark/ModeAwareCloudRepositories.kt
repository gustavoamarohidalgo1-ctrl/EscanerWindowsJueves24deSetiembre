package com.facturastock.app.data.spark

import com.facturastock.app.data.account.FirebaseMembershipRepository
import com.facturastock.app.data.sync.FirebaseBackendMode
import com.facturastock.app.data.sync.FirebaseBackupRuntime
import com.facturastock.app.data.sync.FirebaseRemoteDebtSyncRepository
import com.facturastock.app.data.sync.FirebaseRemoteCatalogRepository
import com.facturastock.app.data.sync.FirebaseRemoteSaleSyncRepository
import com.facturastock.app.domain.error.DomainResult
import com.facturastock.app.domain.model.BusinessInvitation
import com.facturastock.app.domain.model.BusinessRole
import com.facturastock.app.domain.model.CatalogSyncPullPage
import com.facturastock.app.domain.model.CloudBusinessLink
import com.facturastock.app.domain.model.CloudBusinessLinkCasResult
import com.facturastock.app.domain.model.CloudBusinessLinkReceipt
import com.facturastock.app.domain.model.CloudMember
import com.facturastock.app.domain.model.CloudMembership
import com.facturastock.app.domain.model.SharedInventoryPullPage
import com.facturastock.app.domain.model.SharedSaleDocument
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.repository.BusinessMembershipRepository
import com.facturastock.app.domain.repository.RemoteDebtPaymentDocument
import com.facturastock.app.domain.repository.RemoteDebtPaymentResult
import com.facturastock.app.domain.repository.RemoteDebtSyncRepository
import com.facturastock.app.domain.repository.RemoteCatalogRepository
import com.facturastock.app.domain.repository.RemoteSalePostResult
import com.facturastock.app.domain.repository.RemoteSaleSyncRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/** Selección inyectada: CALLABLES conserva el backend endurecido; SPARK nunca intenta Functions. */
@Singleton
class ModeAwareBusinessMembershipRepository @Inject constructor(
    private val runtime: FirebaseBackupRuntime,
    private val callables: FirebaseMembershipRepository,
    private val spark: SparkBusinessMembershipRepository,
) : BusinessMembershipRepository {
    private fun selected(): BusinessMembershipRepository =
        selectForBackendMode(runtime.backendMode, callables, spark)

    override fun currentLocalBusinessIdentityEpoch(): Long =
        selected().currentLocalBusinessIdentityEpoch()

    override suspend fun listMyMemberships(expectedUid: String): DomainResult<List<CloudMembership>> =
        selected().listMyMemberships(expectedUid)

    override suspend fun createBusiness(
        expectedUid: String,
        displayName: String,
    ): DomainResult<CloudMembership> = selected().createBusiness(expectedUid, displayName)

    override suspend fun listMembers(
        expectedUid: String,
        businessId: BusinessId,
    ): DomainResult<List<CloudMember>> = selected().listMembers(expectedUid, businessId)

    override suspend fun listBusinessInvitations(
        expectedUid: String,
        businessId: BusinessId,
    ): DomainResult<List<BusinessInvitation>> =
        selected().listBusinessInvitations(expectedUid, businessId)

    override suspend fun inviteMember(
        expectedUid: String,
        businessId: BusinessId,
        email: String,
        role: BusinessRole,
    ): DomainResult<Unit> = selected().inviteMember(expectedUid, businessId, email, role)

    override suspend fun listMyInvitations(
        expectedUid: String,
    ): DomainResult<List<BusinessInvitation>> = selected().listMyInvitations(expectedUid)

    override suspend fun acceptInvitation(
        expectedUid: String,
        businessId: BusinessId,
    ): DomainResult<CloudMembership> = selected().acceptInvitation(expectedUid, businessId)

    override suspend fun declineInvitation(
        expectedUid: String,
        businessId: BusinessId,
    ): DomainResult<Unit> = selected().declineInvitation(expectedUid, businessId)

    override suspend fun changeMemberRole(
        expectedUid: String,
        businessId: BusinessId,
        memberUid: String,
        role: BusinessRole,
    ): DomainResult<Unit> = selected().changeMemberRole(expectedUid, businessId, memberUid, role)

    override suspend fun removeMember(
        expectedUid: String,
        businessId: BusinessId,
        memberUid: String,
    ): DomainResult<Unit> = selected().removeMember(expectedUid, businessId, memberUid)

    override suspend fun setActiveCloudBusiness(
        expectedUid: String,
        expectedLocalIdentityEpoch: Long,
        link: CloudBusinessLink,
    ): DomainResult<CloudBusinessLinkReceipt> =
        selected().setActiveCloudBusiness(expectedUid, expectedLocalIdentityEpoch, link)

    override suspend fun compareAndSetActiveCloudBusiness(
        expectedUid: String,
        expectedLocalIdentityEpoch: Long,
        expectedReceipt: CloudBusinessLinkReceipt,
        newLink: CloudBusinessLink?,
    ): DomainResult<CloudBusinessLinkCasResult> = selected().compareAndSetActiveCloudBusiness(
        expectedUid,
        expectedLocalIdentityEpoch,
        expectedReceipt,
        newLink,
    )

    override suspend fun clearActiveCloudBusinessIfOwned(
        expectedUid: String,
        expectedReceipt: CloudBusinessLinkReceipt,
    ): DomainResult<Boolean> =
        selected().clearActiveCloudBusinessIfOwned(expectedUid, expectedReceipt)

    override fun observeActiveLink(): Flow<CloudBusinessLink?> = selected().observeActiveLink()
}

@Singleton
class ModeAwareRemoteCatalogRepository @Inject constructor(
    private val runtime: FirebaseBackupRuntime,
    private val callables: FirebaseRemoteCatalogRepository,
    private val spark: SparkRemoteCatalogRepository,
) : RemoteCatalogRepository {
    private fun selected(): RemoteCatalogRepository =
        selectForBackendMode(runtime.backendMode, callables, spark)

    override val available: Boolean
        get() = selected().available

    override suspend fun pullCatalogChanges(
        businessId: BusinessId,
        sinceSeq: Long,
        limit: Int,
    ): DomainResult<CatalogSyncPullPage> =
        selected().pullCatalogChanges(businessId, sinceSeq, limit)
}

@Singleton
class ModeAwareRemoteSaleSyncRepository @Inject constructor(
    private val runtime: FirebaseBackupRuntime,
    private val callables: FirebaseRemoteSaleSyncRepository,
    private val spark: SparkRemoteSaleSyncRepository,
) : RemoteSaleSyncRepository {
    private fun selected(): RemoteSaleSyncRepository =
        selectForBackendMode(runtime.backendMode, callables, spark)

    override val available: Boolean
        get() = selected().available

    override suspend fun postSale(
        localBusinessId: BusinessId,
        document: SharedSaleDocument,
    ): RemoteSalePostResult = selected().postSale(localBusinessId, document)

    override suspend fun pullInventoryChanges(
        cloudBusinessId: BusinessId,
        sinceSeq: Long,
        limit: Int,
    ): DomainResult<SharedInventoryPullPage> =
        selected().pullInventoryChanges(cloudBusinessId, sinceSeq, limit)
}

@Singleton
class ModeAwareRemoteDebtSyncRepository @Inject constructor(
    private val runtime: FirebaseBackupRuntime,
    private val callables: FirebaseRemoteDebtSyncRepository,
    private val spark: SparkRemoteDebtSyncRepository,
) : RemoteDebtSyncRepository {
    private fun selected(): RemoteDebtSyncRepository =
        selectForBackendMode(runtime.backendMode, callables, spark)

    override val available: Boolean
        get() = selected().available

    override suspend fun postPayment(
        localBusinessId: BusinessId,
        document: RemoteDebtPaymentDocument,
    ): RemoteDebtPaymentResult = selected().postPayment(localBusinessId, document)
}

internal fun <T> selectForBackendMode(
    mode: FirebaseBackendMode,
    callables: T,
    spark: T,
): T = when (mode) {
    FirebaseBackendMode.CALLABLES -> callables
    FirebaseBackendMode.SPARK_DIRECT -> spark
}
