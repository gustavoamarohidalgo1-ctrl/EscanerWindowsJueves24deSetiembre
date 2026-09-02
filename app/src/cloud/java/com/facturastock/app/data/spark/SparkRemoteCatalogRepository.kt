package com.facturastock.app.data.spark

import com.facturastock.app.data.sync.FirebaseBackupRuntime
import com.facturastock.app.domain.error.AccountError
import com.facturastock.app.domain.error.AccountException
import com.facturastock.app.domain.error.DomainResult
import com.facturastock.app.domain.model.AccountSession
import com.facturastock.app.domain.model.CatalogSyncPullPage
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.repository.AccountRepository
import com.facturastock.app.domain.repository.CloudBusinessBindingRepository
import com.facturastock.app.domain.repository.RemoteCatalogRepository
import com.google.firebase.firestore.Source
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await

/** Pull incremental directo protegido por ownerUid; no invoca Functions ni Storage. */
@Singleton
class SparkRemoteCatalogRepository @Inject constructor(
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
        if (sinceSeq < 0L || limit !in 1..MAX_PAGE_SIZE) {
            return DomainResult.Failure(AccountError.Unexpected)
        }
        val firestore = runtime.firestore()
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
        return try {
            val documents = firestore.collection(BUSINESSES).document(businessId.value)
                .collection(CATALOG_SYNC_CHANGES)
                .whereGreaterThan(SEQ, sinceSeq)
                .orderBy(SEQ)
                .limit((limit + 1).toLong())
                .get(Source.SERVER)
                .await()
                .documents
            val pageDocuments = documents.take(limit).map { snapshot ->
                SparkCatalogCodec.decodeChange(
                    documentId = snapshot.id,
                    data = snapshot.data
                        ?: throw AccountException(AccountError.Unexpected),
                    expectedOwnerUid = session.uid,
                )
            }
            DomainResult.Success(
                SparkCatalogCodec.pullPage(
                    documents = pageDocuments,
                    sinceSeq = sinceSeq,
                    hasMore = documents.size > limit,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            DomainResult.Failure(SparkFirestoreErrorMapper.fromException(failure))
        }
    }

    private companion object {
        const val BUSINESSES = "businesses"
        const val CATALOG_SYNC_CHANGES = "catalogSyncChanges"
        const val SEQ = "seq"
        const val MAX_PAGE_SIZE = 200
    }
}
