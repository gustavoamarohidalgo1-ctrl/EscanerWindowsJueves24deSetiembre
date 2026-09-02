package com.facturastock.app.data.sync

import com.facturastock.app.data.account.AccountErrorMapper
import com.facturastock.app.domain.error.AccountError
import com.facturastock.app.domain.error.AccountException
import com.facturastock.app.domain.error.DomainResult
import com.facturastock.app.domain.model.AccountSession
import com.facturastock.app.domain.model.CatalogSyncPullPage
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.repository.AccountRepository
import com.facturastock.app.domain.repository.CloudBusinessBindingRepository
import com.facturastock.app.domain.repository.RemoteCatalogRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await

/** Pull incremental de catálogo por ID cloud; solo acepta exactamente el enlace activo. */
@Singleton
class FirebaseRemoteCatalogRepository @Inject constructor(
    private val runtime: FirebaseBackupRuntime,
    private val accountRepository: AccountRepository,
    private val cloudBusinessBindings: CloudBusinessBindingRepository,
) : RemoteCatalogRepository {
    override val available: Boolean
        get() = runtime.config != null

    override suspend fun pullCatalogChanges(
        businessId: BusinessId,
        sinceSeq: Long,
        limit: Int,
    ): DomainResult<CatalogSyncPullPage> {
        val functions = runtime.functions()
            ?: return DomainResult.Failure(AccountError.Unavailable)
        val session = accountRepository.observeSession().first()
        if (session !is AccountSession.Active) {
            return DomainResult.Failure(AccountError.NotAuthenticated)
        }
        val link = session.link ?: return DomainResult.Failure(AccountError.Unavailable)
        if (
            link.cloudBusinessId != businessId ||
            !cloudBusinessBindings.matches(link.localBusinessId, businessId)
        ) {
            return DomainResult.Failure(AccountError.CloudBusinessAlreadyBound)
        }
        val cloudBusinessId = businessId
        return try {
            runtime.prepareBusiness(functions, cloudBusinessId.value)
            val result = functions.getHttpsCallable(LIST_CATALOG_CHANGES_CALLABLE)
                .call(
                    mapOf(
                        "businessId" to cloudBusinessId.value,
                        "expectedUid" to session.uid,
                        "sinceSeq" to sinceSeq,
                        "limit" to limit,
                    ),
                )
                .await()
            val data = result.data as? Map<*, *>
                ?: throw AccountException(AccountError.Unexpected)
            DomainResult.Success(FirebaseCatalogWireMapper.pullPage(data))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: AccountException) {
            DomainResult.Failure(failure.error)
        } catch (failure: Exception) {
            DomainResult.Failure(AccountErrorMapper.fromException(failure))
        }
    }

    private companion object {
        const val LIST_CATALOG_CHANGES_CALLABLE = "listCatalogChanges"
    }
}
