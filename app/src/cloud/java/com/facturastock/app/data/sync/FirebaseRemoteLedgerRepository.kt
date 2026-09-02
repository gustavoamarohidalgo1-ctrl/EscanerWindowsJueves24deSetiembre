package com.facturastock.app.data.sync

import com.facturastock.app.domain.error.AccountError
import com.facturastock.app.domain.error.AccountException
import com.facturastock.app.domain.error.DomainResult
import com.facturastock.app.domain.model.AccountSession
import com.facturastock.app.domain.model.RemotePurchaseDescription
import com.facturastock.app.domain.model.SyncPullPage
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.repository.AccountRepository
import com.facturastock.app.domain.repository.CloudBusinessBindingRepository
import com.facturastock.app.domain.repository.RemoteLedgerRepository
import com.facturastock.app.data.account.AccountErrorMapper
import com.google.firebase.Timestamp
import com.google.firebase.firestore.Source
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await

/**
 * `RemoteLedgerRepository` del flavor cloud: lectura réplica del libro remoto. El pull pasa por
 * el callable `listChanges` (la membresía la exige el servidor) y la descripción puntual lee el
 * documento `businesses/{bid}/purchases/{id}` directamente: las reglas de Firestore ya lo
 * permiten a cualquier miembro. Toda operación exige sesión [AccountSession.Active] y todo
 * payload se valida con [SyncPullMappers]: nada crudo del backend sale de aquí.
 *
 * Es estrictamente de lectura: el libro remoto solo lo escriben las Cloud Functions.
 */
@Singleton
class FirebaseRemoteLedgerRepository @Inject constructor(
    private val runtime: FirebaseBackupRuntime,
    private val accountRepository: AccountRepository,
    private val cloudBusinessBindings: CloudBusinessBindingRepository,
) : RemoteLedgerRepository {

    override val available: Boolean
        get() = runtime.config != null

    override suspend fun pullChanges(
        businessId: BusinessId,
        sinceSeq: Long,
        limit: Int,
    ): DomainResult<SyncPullPage> {
        val tenant = when (val resolution = validateLinkedCloudBusiness(businessId)) {
            is DomainResult.Success -> resolution.value
            is DomainResult.Failure -> return resolution
        }
        val cloudBusinessId = tenant.cloudBusinessId
        if (runtime.backendMode == FirebaseBackendMode.SPARK_DIRECT) {
            return pullSparkChanges(cloudBusinessId, sinceSeq, limit)
        }
        val functions = runtime.functions()
            ?: return DomainResult.Failure(AccountError.Unavailable)
        return try {
            runtime.prepareBusiness(functions, cloudBusinessId.value)
            val result = functions.getHttpsCallable(LIST_CHANGES_CALLABLE)
                .call(
                    mapOf(
                        "businessId" to cloudBusinessId.value,
                        "expectedUid" to tenant.expectedUid,
                        "sinceSeq" to sinceSeq,
                        "limit" to limit,
                    ),
                )
                .await()
            val data = result.data as? Map<*, *>
                ?: throw AccountException(AccountError.Unexpected)
            DomainResult.Success(SyncPullMappers.pullPage(data))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: AccountException) {
            DomainResult.Failure(failure.error)
        } catch (failure: Exception) {
            DomainResult.Failure(AccountErrorMapper.fromException(failure))
        }
    }

    /**
     * El plan gratuito no dispone de Cloud Functions. Consulta la misma proyeccion compacta
     * `syncChanges` que mantiene la transaccion Spark y la vuelve a pasar por el mapper estricto
     * compartido. Se fuerza servidor para no confundir una cache vieja con sincronizacion
     * completada cuando el dispositivo esta sin internet.
     */
    private suspend fun pullSparkChanges(
        cloudBusinessId: BusinessId,
        sinceSeq: Long,
        limit: Int,
    ): DomainResult<SyncPullPage> {
        val firestore = runtime.firestore()
            ?: return DomainResult.Failure(AccountError.Unavailable)
        if (sinceSeq < 0L || limit !in 1..MAX_SPARK_PAGE_SIZE) {
            return DomainResult.Failure(AccountError.Unexpected)
        }
        return try {
            val snapshots = firestore
                .collection("businesses").document(cloudBusinessId.value)
                .collection("syncChanges")
                .whereGreaterThan("seq", sinceSeq)
                .orderBy("seq")
                .limit(limit.toLong() + 1L)
                .get(Source.SERVER)
                .await()
            val pageDocuments = snapshots.documents.take(limit)
            val changes = pageDocuments.map { snapshot ->
                snapshot.data?.asPullWire()
                    ?: throw AccountException(AccountError.Unexpected)
            }
            val nextCursor = changes.lastOrNull()?.get("seq") ?: sinceSeq
            DomainResult.Success(
                SyncPullMappers.pullPage(
                    linkedMapOf(
                        "changes" to changes,
                        "nextCursor" to nextCursor,
                        "hasMore" to (snapshots.size() > limit),
                    ),
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: AccountException) {
            DomainResult.Failure(failure.error)
        } catch (failure: Exception) {
            DomainResult.Failure(AccountErrorMapper.fromException(failure))
        }
    }

    override suspend fun describeRemotePurchase(
        businessId: BusinessId,
        remotePurchaseId: String,
    ): DomainResult<RemotePurchaseDescription?> {
        val firestore = runtime.firestore()
            ?: return DomainResult.Failure(AccountError.Unavailable)
        val tenant = when (val resolution = validateLinkedCloudBusiness(businessId)) {
            is DomainResult.Success -> resolution.value
            is DomainResult.Failure -> return resolution
        }
        val cloudBusinessId = tenant.cloudBusinessId
        return try {
            val snapshot = firestore
                .collection("businesses").document(cloudBusinessId.value)
                .collection("purchases").document(remotePurchaseId)
                .get()
                .await()
            if (!snapshot.exists()) return DomainResult.Success(null)
            val data = snapshot.data ?: throw AccountException(AccountError.Unexpected)
            DomainResult.Success(SyncPullMappers.purchaseDescription(snapshot.id, data.asWire()))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: AccountException) {
            DomainResult.Failure(failure.error)
        } catch (failure: Exception) {
            DomainResult.Failure(AccountErrorMapper.fromException(failure))
        }
    }

    /** Pull/cache se indexan por ID cloud; se comprueba que sea exactamente el enlace activo. */
    private suspend fun validateLinkedCloudBusiness(
        requestedCloudBusinessId: BusinessId,
    ): DomainResult<LinkedCloudTenant> {
        val session = accountRepository.observeSession().first()
        if (session !is AccountSession.Active) {
            return DomainResult.Failure(AccountError.NotAuthenticated)
        }
        val link = session.link ?: return DomainResult.Failure(AccountError.Unavailable)
        return if (
            link.cloudBusinessId == requestedCloudBusinessId &&
            cloudBusinessBindings.matches(link.localBusinessId, requestedCloudBusinessId)
        ) {
            DomainResult.Success(
                LinkedCloudTenant(
                    cloudBusinessId = requestedCloudBusinessId,
                    expectedUid = session.uid,
                ),
            )
        } else {
            DomainResult.Failure(AccountError.CloudBusinessAlreadyBound)
        }
    }

    private companion object {
        const val LIST_CHANGES_CALLABLE = "listChanges"
        const val MAX_SPARK_PAGE_SIZE = 200

        /**
         * El documento Firestore trae `syncedAt` como [Timestamp] del SDK; el mapper puro solo
         * entiende JSON plano, así que aquí se convierte a millis epoch (o null recién escrito).
         */
        fun Map<String, Any?>.asWire(): Map<String, Any?> {
            val syncedAtMillis = when (val syncedAt = this["syncedAt"]) {
                null -> null
                is Timestamp -> syncedAt.toDate().time
                else -> throw AccountException(AccountError.Unexpected)
            }
            // La lectura puntual trae el documento contable completo. Se proyecta aquí a la
            // descripción cerrada que entiende el mapper para que líneas, hashes o campos futuros
            // nunca crucen accidentalmente la frontera de reconciliación.
            return linkedMapOf(
                "status" to this["status"],
                "documentType" to this["documentType"],
                "documentSeries" to this["documentSeries"],
                "documentNumber" to this["documentNumber"],
                "issueDate" to this["issueDate"],
                "currency" to this["currency"],
                "supplierRuc" to this["supplierRuc"],
                "supplierLegalName" to this["supplierLegalName"],
                "totalMinorUnits" to this["totalMinorUnits"],
                "receiptId" to this["receiptId"],
                "syncedAtMillis" to syncedAtMillis,
                "syncedBy" to this["syncedBy"],
            )
        }

        /** Forma exacta aceptada por [SyncPullMappers.pullPage]. */
        fun Map<String, Any?>.asPullWire(): Map<String, Any?> {
            val syncedAtMillis = when (val syncedAt = this["syncedAt"]) {
                null -> null
                is Timestamp -> syncedAt.toDate().time
                else -> throw AccountException(AccountError.Unexpected)
            }
            return linkedMapOf(
                "seq" to this["seq"],
                "purchaseId" to this["purchaseId"],
                "status" to this["status"],
                "documentType" to this["documentType"],
                "documentSeries" to this["documentSeries"],
                "documentNumber" to this["documentNumber"],
                "issueDate" to this["issueDate"],
                "currency" to this["currency"],
                "supplierRuc" to this["supplierRuc"],
                "supplierLegalName" to this["supplierLegalName"],
                "totalMinorUnits" to this["totalMinorUnits"],
                "movementSummary" to this["movementSummary"],
                "receiptId" to this["receiptId"],
                "syncedAtMillis" to syncedAtMillis,
            )
        }
    }
}

private data class LinkedCloudTenant(
    val cloudBusinessId: BusinessId,
    val expectedUid: String,
)
