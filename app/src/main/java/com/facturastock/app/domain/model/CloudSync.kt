package com.facturastock.app.domain.model

import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.PurchaseId
import java.math.BigDecimal
import java.time.Instant
import java.util.Locale

/**
 * Modelos y lógica pura de la sincronización bidireccional. La regla de oro: el pull es
 * lectura réplica y reconciliación DIAGNÓSTICA — el libro remoto jamás se fusiona
 * automáticamente al local (eso sería un "último gana" encubierto).
 */

/** Movimiento remoto compacto tal como viaja en `movementSummary` (sin geometría ni rutas). */
data class RemoteMovementSummary(
    val productId: String,
    val productName: String?,
    val type: StockMovementType,
    val quantityDelta: BigDecimal,
)

/** Cambio remoto del libro de compras, ordenado por `seq` monotónica del negocio. */
data class RemotePurchaseChange(
    val seq: Long,
    val purchaseId: String,
    val status: PurchaseStatus,
    val documentType: String,
    val documentSeries: String,
    val documentNumber: String,
    val issueDate: String,
    val currency: String,
    val supplierRuc: String?,
    val supplierLegalName: String,
    val totalMinorUnits: Long,
    val movementSummary: List<RemoteMovementSummary>,
    val receiptId: String,
    val syncedAtMillis: Long?,
    val syncedBy: String?,
) {
    init {
        require(seq in 1..MAX_SAFE_SYNC_SEQUENCE) { "seq remoto fuera de rango: $seq" }
        require(status == PurchaseStatus.POSTED || status == PurchaseStatus.VOIDED) {
            "estado remoto no reconciliable: $status"
        }
    }
}

/**
 * Página de pull incremental. [nextCursor] es exclusivamente la última secuencia realmente
 * entregada (o el cursor solicitado si [changes] está vacío); [hasMore] decide la paginación.
 */
data class SyncPullPage(
    val changes: List<RemotePurchaseChange>,
    val nextCursor: Long,
    val hasMore: Boolean,
) {
    init {
        require(nextCursor in 0..MAX_SAFE_SYNC_SEQUENCE)
        require(!hasMore || changes.isNotEmpty())
        changes.lastOrNull()?.let { last -> require(last.seq == nextCursor) }
    }
}

/** Cursor durable del pull incremental por negocio. */
data class SyncCursor(
    val seq: Long,
    val lastPullAt: Instant,
) {
    init {
        require(seq in 0..MAX_SAFE_SYNC_SEQUENCE)
    }
}

/** Firestore Functions usa Number para la secuencia: nunca se cruza el entero seguro de JS. */
const val MAX_SAFE_SYNC_SEQUENCE: Long = 9_007_199_254_740_991L

/** Descripción puntual de una compra remota (para la comparación de un conflicto). */
data class RemotePurchaseDescription(
    val purchaseId: String,
    val status: PurchaseStatus,
    val documentType: String,
    val documentSeries: String,
    val documentNumber: String,
    val issueDate: String,
    val currency: String,
    val supplierRuc: String?,
    val supplierLegalName: String,
    val totalMinorUnits: Long,
    val receiptId: String?,
    val syncedAtMillis: Long?,
    val syncedBy: String?,
)

/**
 * Identidad documental estructurada para la reconciliación. Usa exactamente la normalización
 * de una coincidencia [PurchaseDuplicateKind.EXACT]: RUC, tipo y serie alfanuméricos, y el
 * correlativo conserva sus ceros a la izquierda. Al no serializar campos con delimitadores,
 * valores distintos tampoco pueden colisionar por el contenido de un separador.
 *
 * El diagnóstico remoto no conoce el `supplierId` local. Por eso una identidad sin RUC no es
 * suficiente para afirmar que dos compras pertenecen al mismo proveedor y [normalized] devuelve
 * `null` en ese caso.
 */
data class PurchaseDocumentIdentity(
    val supplierRuc: String,
    val documentType: PurchaseDocumentType,
    val documentSeries: String,
    val documentNumber: String,
) {
    init {
        require(
            supplierRuc.isNotEmpty() &&
                PurchaseDuplicateCanonicalizer.ruc(supplierRuc) == supplierRuc,
        ) { "supplierRuc debe estar normalizado" }
        require(
            documentSeries.isNotEmpty() &&
                PurchaseDuplicateCanonicalizer.series(documentSeries) == documentSeries,
        ) { "documentSeries debe estar normalizada" }
        require(
            documentNumber.isNotEmpty() &&
                PurchaseDuplicateCanonicalizer.correlative(documentNumber) == documentNumber,
        ) { "documentNumber debe estar normalizado" }
    }

    companion object {
        fun normalized(
            supplierRuc: String?,
            documentType: String,
            documentSeries: String,
            documentNumber: String,
        ): PurchaseDocumentIdentity? {
            val normalizedRuc = PurchaseDuplicateCanonicalizer.ruc(supplierRuc)
                ?: return null
            val normalizedType = PurchaseDuplicateCanonicalizer.documentType(documentType)
            val canonicalType = PurchaseDocumentType.entries.singleOrNull { type ->
                PurchaseDuplicateCanonicalizer.documentType(type.name) == normalizedType
            } ?: return null
            val normalizedSeries = PurchaseDuplicateCanonicalizer.series(documentSeries)
            val normalizedNumber = PurchaseDuplicateCanonicalizer.correlative(documentNumber)
            if (
                normalizedSeries.isEmpty() ||
                normalizedNumber.isEmpty()
            ) {
                return null
            }
            return PurchaseDocumentIdentity(
                supplierRuc = normalizedRuc,
                documentType = canonicalType,
                documentSeries = normalizedSeries,
                documentNumber = normalizedNumber,
            )
        }
    }
}

/** Identidad de catálogo local usada para enlazar productos remotos sin adivinar. */
data class ProductIdentity(
    val productId: ProductId,
    val normalizedName: String,
)

/** Saldo local agregado de un producto (todas las ubicaciones). */
data class LocalProductBalance(
    val productId: ProductId,
    val quantityOnHand: BigDecimal,
)

/** Instantánea local contra la que se reconcilia el libro remoto. */
data class LocalLedgerSnapshot(
    /** Identidad documental exacta → todas las compras locales posteadas/anuladas. */
    val localDocuments: Map<PurchaseDocumentIdentity, List<PurchaseId>>,
    val balances: List<LocalProductBalance>,
    val products: List<ProductIdentity>,
) {
    init {
        require(localDocuments.values.all { purchases -> purchases.isNotEmpty() }) {
            "Una identidad documental local debe contener al menos una compra"
        }
        require(localDocuments.values.all { purchases -> purchases.distinct().size == purchases.size }) {
            "Una identidad documental local no puede repetir purchaseId"
        }
    }
}

/** Compra remota cuya identidad documental ya existe en el libro local. */
data class MatchedRemotePurchase(
    val remote: RemotePurchaseChange,
    val localPurchaseId: PurchaseId,
)

/**
 * La identidad remota coincide con varias compras locales (principal + excepciones), pero el
 * `purchaseId` remoto no identifica inequívocamente a ninguna. El diagnóstico no adivina.
 */
data class AmbiguousRemotePurchase(
    val remote: RemotePurchaseChange,
    val localPurchaseIds: List<PurchaseId>,
) {
    init {
        require(localPurchaseIds.distinct().size == localPurchaseIds.size)
        require(localPurchaseIds.size >= 2) {
            "Una coincidencia ambigua necesita al menos dos compras locales"
        }
    }
}

/** Producto remoto sin correspondencia exacta en el catálogo local (nunca se adivina). */
data class UnlinkedRemoteProduct(
    val remoteProductId: String,
    val productName: String?,
    val remoteNet: BigDecimal,
)

/**
 * Grupo cuyo nombre exacto no determina una relación uno-a-uno entre nube y catálogo local.
 * Puede haber varios UUID remotos para un candidato local o varios candidatos locales para un
 * UUID remoto. El saldo no se asigna a ninguno: sumar o elegir convertiría el orden de una
 * consulta en una decisión contable.
 */
data class AmbiguousRemoteProduct(
    val remoteProductIds: List<String>,
    val productName: String?,
    val remoteNet: BigDecimal,
    val localProductIds: List<ProductId>,
) {
    init {
        require(remoteProductIds.isNotEmpty())
        require(remoteProductIds.distinct().size == remoteProductIds.size)
        require(localProductIds.isNotEmpty())
        require(localProductIds.distinct().size == localProductIds.size)
        require(remoteProductIds.size >= 2 || localProductIds.size >= 2) {
            "Una coincidencia ambigua necesita múltiples candidatos remotos o locales"
        }
    }
}

/**
 * Diferencia de saldo para un producto enlazado: `remoteNet - localOnHand`.
 * Positiva: la nube conoce más stock del que este teléfono tiene (otro dispositivo lo
 * publicó). Negativa: este teléfono tiene stock que la nube no conoce (pendiente de respaldo).
 */
data class BalanceDifference(
    val productId: ProductId,
    val productName: String?,
    val localOnHand: BigDecimal,
    val remoteNet: BigDecimal,
) {
    val difference: BigDecimal
        get() = remoteNet - localOnHand
}

/** Resultado de la reconciliación diagnóstica; no implica escritura alguna. */
data class ReconciliationReport(
    val businessId: BusinessId,
    val latestSeq: Long,
    val matched: List<MatchedRemotePurchase>,
    val ambiguous: List<AmbiguousRemotePurchase>,
    val remoteOnly: List<RemotePurchaseChange>,
    val balanceDifferences: List<BalanceDifference>,
    val unlinkedRemoteProducts: List<UnlinkedRemoteProduct>,
    val comparedProductCount: Int,
    val generatedAt: Instant,
    val ambiguousRemoteProducts: List<AmbiguousRemoteProduct> = emptyList(),
)

/** Normalización de nombre de producto para el enlace exacto local↔remoto. */
fun normalizeProductName(name: String): String =
    name.trim().uppercase(Locale.ROOT).replace(WhitespaceRun, " ")

/**
 * Reconciliador puro: compara el libro remoto completo contra la instantánea local.
 *
 * - Estado remoto por compra = el del MAYOR seq (una anulación reemplaza al alta).
 * - Compra `VOIDED` ⇒ efecto neto 0 en saldos (la anulación compensa, no borra).
 * - Documento local: `purchaseId` remoto exacto > candidato único cross-device; varios
 *   candidatos sin UUID exacto se declaran ambiguos y nunca se elige uno arbitrariamente.
 * - Productos remotos enlazan solo por nombre normalizado exacto (el resumen remoto no trae
 *   barcode/SKU); sin match van a [ReconciliationReport.unlinkedRemoteProducts] y cualquier
 *   relación homónima que no sea uno-a-uno va a
 *   [ReconciliationReport.ambiguousRemoteProducts].
 * - La comparación de saldos recorre la unión: un saldo solo local se enfrenta a cero remoto y
 *   una compra remota anulada conserva la identidad del producto con neto cero.
 */
fun reconcileRemoteLedger(
    businessId: BusinessId,
    remoteChanges: List<RemotePurchaseChange>,
    local: LocalLedgerSnapshot,
    generatedAt: Instant,
): ReconciliationReport {
    val latestByPurchase = remoteChanges
        .groupBy(RemotePurchaseChange::purchaseId)
        .values
        .map { group -> group.maxBy(RemotePurchaseChange::seq) }
        .sortedBy(RemotePurchaseChange::seq)

    val matched = mutableListOf<MatchedRemotePurchase>()
    val ambiguous = mutableListOf<AmbiguousRemotePurchase>()
    val remoteOnly = mutableListOf<RemotePurchaseChange>()
    latestByPurchase.forEach { change ->
        val identity = PurchaseDocumentIdentity.normalized(
            change.supplierRuc,
            change.documentType,
            change.documentSeries,
            change.documentNumber,
        )
        val candidates = identity
            ?.let(local.localDocuments::get)
            .orEmpty()
            .distinct()
        val exactRemotePurchaseId = PurchaseId.parse(change.purchaseId)
            ?.takeIf(candidates::contains)
        when {
            exactRemotePurchaseId != null -> {
                matched += MatchedRemotePurchase(change, exactRemotePurchaseId)
            }
            candidates.size == 1 -> {
                // Compatibilidad entre dispositivos: una identidad documental inequívoca basta
                // aunque el libro remoto haya nacido con otro UUID local.
                matched += MatchedRemotePurchase(change, candidates.single())
            }
            candidates.size > 1 -> {
                ambiguous += AmbiguousRemotePurchase(
                    remote = change,
                    localPurchaseIds = candidates.sortedBy(PurchaseId::value),
                )
            }
            else -> remoteOnly += change
        }
    }

    /*
     * Conserva primero el universo remoto completo de productos observado en el libro. Esto es
     * deliberadamente distinto de sumar solo las compras POSTED: si el último estado es VOIDED,
     * su saldo neto es cero pero la identidad del producto sigue siendo necesaria para comparar
     * un saldo local que todavía sea distinto de cero.
     */
    val remoteProductsById = linkedMapOf<String, RemoteProductBalance>()
    remoteChanges.sortedBy(RemotePurchaseChange::seq).forEach { change ->
        change.movementSummary.forEach { movement ->
            val current = remoteProductsById[movement.productId]
            remoteProductsById[movement.productId] = RemoteProductBalance(
                productName = movement.productName ?: current?.productName,
                remoteNet = current?.remoteNet ?: BigDecimal.ZERO,
            )
        }
    }
    latestByPurchase
        .filter { it.status == PurchaseStatus.POSTED }
        .flatMap { it.movementSummary }
        .forEach { movement ->
            val current = remoteProductsById[movement.productId]
            remoteProductsById[movement.productId] = RemoteProductBalance(
                productName = movement.productName ?: current?.productName,
                remoteNet = (current?.remoteNet ?: BigDecimal.ZERO) + movement.quantityDelta,
            )
        }

    // El resumen remoto solo trae id + nombre del producto: el enlace exacto es por nombre
    // normalizado. Se agrupa en vez de `associateBy`: un homónimo local es una ambigüedad
    // contable explícita, no permiso para elegir la última fila recibida.
    val localByName = local.products.groupBy { normalizeProductName(it.normalizedName) }
    val localById = local.products.associateBy(ProductIdentity::productId)
    val balanceByProduct = local.balances
        .groupBy(LocalProductBalance::productId)
        .mapValues { (_, balances) ->
            balances.fold(BigDecimal.ZERO) { total, balance -> total + balance.quantityOnHand }
        }
    val differences = mutableListOf<BalanceDifference>()
    val unlinked = mutableListOf<UnlinkedRemoteProduct>()
    val ambiguousProducts = mutableListOf<AmbiguousRemoteProduct>()
    val coveredLocalProducts = mutableSetOf<ProductId>()
    var compared = 0

    val remoteByName = remoteProductsById.entries.groupBy { (_, balance) ->
        balance.productName?.let(::normalizeProductName)
    }
    remoteByName.entries
        .sortedBy { (name) -> name ?: "" }
        .forEach { (normalizedName, remoteEntries) ->
            val orderedRemote = remoteEntries.sortedBy { it.key }
            val candidates = normalizedName?.let(localByName::get).orEmpty()
                .distinctBy(ProductIdentity::productId)
                .sortedBy { it.productId.value }
            when {
                normalizedName == null || candidates.isEmpty() -> {
                    orderedRemote.forEach { (remoteProductId, balance) ->
                        unlinked += UnlinkedRemoteProduct(
                            remoteProductId = remoteProductId,
                            productName = balance.productName,
                            remoteNet = balance.remoteNet,
                        )
                    }
                }
                candidates.size > 1 || orderedRemote.size > 1 -> {
                    // Ningún candidato se vuelve "solo local": sabemos que existe contraparte
                    // remota, pero una relación many-to-one u one-to-many no autoriza sumar ni
                    // elegir IDs. El grupo ambiguo mantiene esa incertidumbre visible.
                    coveredLocalProducts += candidates.map(ProductIdentity::productId)
                    ambiguousProducts += AmbiguousRemoteProduct(
                        remoteProductIds = orderedRemote.map { it.key },
                        productName = orderedRemote
                            .mapNotNull { entry -> entry.value.productName }
                            .lastOrNull() ?: normalizedName,
                        remoteNet = orderedRemote.fold(BigDecimal.ZERO) { total, entry ->
                            total + entry.value.remoteNet
                        },
                        localProductIds = candidates.map(ProductIdentity::productId),
                    )
                }
                else -> {
                    val identity = candidates.single()
                    coveredLocalProducts += identity.productId
                    compared++
                    val remoteNet = orderedRemote.fold(BigDecimal.ZERO) { total, entry ->
                        total + entry.value.remoteNet
                    }
                    val localOnHand = balanceByProduct[identity.productId] ?: BigDecimal.ZERO
                    if (remoteNet.compareTo(localOnHand) != 0) {
                        differences += BalanceDifference(
                            productId = identity.productId,
                            productName = orderedRemote
                                .mapNotNull { entry -> entry.value.productName }
                                .lastOrNull() ?: identity.normalizedName,
                            localOnHand = localOnHand,
                            remoteNet = remoteNet,
                        )
                    }
                }
            }
        }

    // Segunda mitad de la unión: un saldo que existe solo en este teléfono se compara contra
    // cero remoto. Antes se omitía por completo porque el algoritmo iteraba solo el mapa cloud.
    balanceByProduct.entries
        .filterNot { (productId) -> productId in coveredLocalProducts }
        .sortedBy { (productId) -> productId.value }
        .forEach { (productId, localOnHand) ->
            compared++
            if (localOnHand.compareTo(BigDecimal.ZERO) != 0) {
                differences += BalanceDifference(
                    productId = productId,
                    productName = localById[productId]?.normalizedName,
                    localOnHand = localOnHand,
                    remoteNet = BigDecimal.ZERO,
                )
            }
        }

    return ReconciliationReport(
        businessId = businessId,
        latestSeq = remoteChanges.maxOfOrNull(RemotePurchaseChange::seq) ?: 0L,
        matched = matched,
        ambiguous = ambiguous,
        remoteOnly = remoteOnly,
        balanceDifferences = differences.sortedWith(
            compareBy<BalanceDifference> { it.productName ?: "" }.thenBy { it.productId.value },
        ),
        unlinkedRemoteProducts = unlinked.sortedWith(
            compareBy<UnlinkedRemoteProduct> { it.productName ?: "" }
                .thenBy(UnlinkedRemoteProduct::remoteProductId),
        ),
        comparedProductCount = compared,
        generatedAt = generatedAt,
        ambiguousRemoteProducts = ambiguousProducts.sortedWith(
            compareBy<AmbiguousRemoteProduct> { it.productName ?: "" }
                .thenBy { it.remoteProductIds.first() },
        ),
    )
}

private data class RemoteProductBalance(
    val productName: String?,
    val remoteNet: BigDecimal,
)

// Compilada una vez: antes se creaba una expresión regular nueva en cada normalización.
private val WhitespaceRun = Regex("\\s+")
