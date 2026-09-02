package com.facturastock.app.data.repository

import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.data.local.dao.InventoryDao
import com.facturastock.app.data.local.dao.LedgerDocumentIdentityRow
import com.facturastock.app.data.local.dao.ProductDao
import com.facturastock.app.data.local.dao.PurchaseDao
import com.facturastock.app.data.local.storageCatching
import com.facturastock.app.domain.model.LocalLedgerSnapshot
import com.facturastock.app.domain.model.LocalProductBalance
import com.facturastock.app.domain.model.PurchaseDuplicateCanonicalizer
import com.facturastock.app.domain.model.PurchaseDocumentIdentity
import com.facturastock.app.domain.model.ProductIdentity
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.domain.model.normalizeProductName
import com.facturastock.app.domain.repository.SyncReconciliationRepository
import java.math.BigDecimal
import javax.inject.Inject
import kotlinx.coroutines.withContext

/**
 * Instantánea local para la reconciliación diagnóstica. Tres lecturas de solo lectura sobre
 * Room: identidades documentales terminales (POSTED/VOIDED), saldos agregados por producto en
 * todas las ubicaciones y catálogo activo para el enlace exacto por nombre. Nunca escribe.
 *
 * La clave documental usa el mismo RUC efectivo que viaja en el respaldo (el normalizado del
 * borrador cuando existe) y la misma normalización del dominio, para que la comparación con el
 * libro remoto sea simétrica. Una excepción documental (slot de override) comparte identidad con
 * su original, por lo que la instantánea conserva todos sus `purchaseId`: el reconciliador puede
 * preferir el UUID remoto exacto o declarar la ambigüedad sin sobrescribir historia.
 */
class RoomSyncReconciliationRepository @Inject constructor(
    private val purchaseDao: PurchaseDao,
    private val inventoryDao: InventoryDao,
    private val productDao: ProductDao,
    private val dispatchers: DispatcherProvider,
) : SyncReconciliationRepository {

    override suspend fun loadLocalLedgerSnapshot(businessId: BusinessId): LocalLedgerSnapshot =
        withContext(dispatchers.io) {
            storageCatching {
                val documents = purchaseDao.listLedgerDocumentIdentities(businessId.value)
                    .toLocalDocumentIndex()
                // quantityOnHand viaja como texto decimal exacto; la suma por producto incluye
                // saldos negativos (importaciones o ajustes), que el diagnóstico debe mostrar.
                val balances = inventoryDao.listBalancesForBusiness(businessId.value)
                    .groupBy { it.productId }
                    .map { (productId, rows) ->
                        LocalProductBalance(
                            productId = ProductId.parse(productId) ?: corruptLedgerRow(),
                            quantityOnHand = rows.fold(BigDecimal.ZERO) { total, row ->
                                total + BigDecimal(row.quantityOnHand)
                            },
                        )
                    }
                val products = productDao.listActiveIdentities(businessId.value)
                    .map { row ->
                        ProductIdentity(
                            productId = ProductId.parse(row.productId) ?: corruptLedgerRow(),
                            normalizedName = normalizeProductName(row.name),
                        )
                    }
                LocalLedgerSnapshot(
                    localDocuments = documents,
                    balances = balances,
                    products = products,
                )
            }
        }
}

/**
 * Proyección pura y determinista usada por el repositorio. Las filas llegan ordenadas por fecha
 * de creación/UUID desde Room y ese orden se conserva dentro de cada identidad. Una fila sin RUC
 * no se usa para afirmar identidad cross-device; una compra corrupta sí invalida la instantánea.
 */
internal fun List<LedgerDocumentIdentityRow>.toLocalDocumentIndex():
    Map<PurchaseDocumentIdentity, List<PurchaseId>> =
    mapNotNull { row ->
        val purchaseId = PurchaseId.parse(row.purchaseId) ?: corruptLedgerRow()
        if (PurchaseDuplicateCanonicalizer.ruc(row.supplierRuc) == null) {
            return@mapNotNull null
        }
        val identity = PurchaseDocumentIdentity.normalized(
            supplierRuc = row.supplierRuc,
            documentType = row.documentType,
            documentSeries = row.documentSeries,
            documentNumber = row.documentNumber,
        ) ?: corruptLedgerRow()
        identity to purchaseId
    }
        .groupBy(keySelector = { it.first }, valueTransform = { it.second })
        .mapValues { (_, purchases) -> purchases.distinct() }

private fun corruptLedgerRow(): Nothing =
    throw IllegalStateException("Instantánea local de reconciliación inconsistente")
