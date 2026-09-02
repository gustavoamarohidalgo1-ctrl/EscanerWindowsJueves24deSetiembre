package com.facturastock.app.domain.usecase

import com.facturastock.app.domain.model.BarcodeValue
import com.facturastock.app.domain.model.CatalogStatus
import com.facturastock.app.domain.model.CatalogSearch
import com.facturastock.app.domain.model.MAX_CATALOG_PAGE_SIZE
import com.facturastock.app.domain.model.Product
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.SupplierId
import com.facturastock.app.domain.repository.ProductRepository
import com.facturastock.app.domain.repository.SupplierProductAliasRepository
import java.text.Normalizer
import java.util.Locale
import kotlin.math.roundToInt

/** Por qué un producto coincide con la línea de factura; se muestra en la revisión. */
enum class ProductMatchReason {
    BARCODE,
    SUPPLIER_CODE,
    CONFIRMED_ALIAS,
    SKU,
    EXACT_NAME,
    SIMILAR_NAME,
}

/** Candidato de vinculación con su evidencia: razón y confianza en permil (0..1000). */
data class ProductMatchCandidate(
    val product: Product,
    val reason: ProductMatchReason,
    val confidencePermille: Int,
) {
    init {
        require(confidencePermille in 0..1_000) {
            "confidencePermille fuera de 0..1000: $confidencePermille"
        }
    }
}

/**
 * Consulta de resolución de una línea contra el catálogo. [supplierCode] es el código impreso
 * por el proveedor en la factura; [description] es la descripción impresa; [barcode] solo llega
 * si el usuario escanea el producto físico durante la vinculación.
 */
data class ProductMatchQuery(
    val businessId: BusinessId,
    val supplierId: SupplierId? = null,
    val barcode: String? = null,
    val supplierCode: String? = null,
    val description: String? = null,
) {
    init {
        require(
            listOfNotNull(barcode, supplierCode, description).any { it.isNotBlank() },
        ) { "La consulta de vinculación necesita al menos un dato de la línea" }
    }
}

/** Resultado explicable de la cascada de coincidencia. */
sealed interface ProductMatchOutcome {
    /** Exacto único: se vincula sin intervención; [alternatives] conserva otros exactos. */
    data class AutoLinked(
        val candidate: ProductMatchCandidate,
        val alternatives: List<ProductMatchCandidate> = emptyList(),
    ) : ProductMatchOutcome

    /** Varios exactos distintos: la elección humana es obligatoria. */
    data class Ambiguous(val candidates: List<ProductMatchCandidate>) : ProductMatchOutcome

    /** Solo sugerencias difusas: nunca se seleccionan automáticamente. */
    data class Suggestions(val candidates: List<ProductMatchCandidate>) : ProductMatchOutcome

    data object NoMatch : ProductMatchOutcome
}

/**
 * Resuelve una línea de factura contra el catálogo sin duplicados. La cascada evalúa, en orden:
 * código de barras exacto, código de proveedor (alias), alias confirmado por descripción, SKU,
 * nombre exacto y, solo si nada exacto coincidió, sugerencias difusas. Las coincidencias exactas
 * aparecen primero; las difusas jamás se auto-seleccionan. Todo alias persistido proviene de una
 * confirmación humana previa, por lo que la coincidencia por alias es de confianza alta.
 *
 * Los productos `ARCHIVED` nunca se proponen. La salida se acota a [MAX_CANDIDATES].
 */
class ProductMatchingUseCase(
    private val productRepository: ProductRepository,
    private val supplierProductAliasRepository: SupplierProductAliasRepository,
) {
    suspend operator fun invoke(query: ProductMatchQuery): ProductMatchOutcome {
        exactByBarcode(query)?.let { return it }
        exactByAlias(query, code = true)?.let { return it }
        exactByAlias(query, code = false)?.let { return it }
        exactBySku(query)?.let { return it }
        exactByName(query)?.let { return it }
        return fuzzySuggestions(query)
    }

    /**
     * Búsqueda manual desde la pantalla de vinculación: reúne exactos (en el orden de la
     * cascada) y, después, sugerencias difusas, sin duplicar productos y acotada a
     * [MAX_CANDIDATES]. Nunca auto-vincula: la selección siempre es del usuario.
     */
    suspend fun search(
        businessId: BusinessId,
        supplierId: SupplierId?,
        rawQuery: String,
    ): List<ProductMatchCandidate> {
        val trimmed = lookupText(rawQuery) ?: return emptyList()
        val exacts = mutableListOf<ProductMatchCandidate>()
        fun add(candidate: ProductMatchCandidate?) {
            if (candidate != null && exacts.none { it.product.productId == candidate.product.productId }) {
                exacts += candidate
            }
        }
        BarcodeValue.parse(rawQuery)?.let { barcode ->
            productRepository.findByBarcode(businessId, barcode.value)
                ?.activeForBusinessOrNull(businessId)
                ?.let { add(ProductMatchCandidate(it, ProductMatchReason.BARCODE, CONFIDENCE_BARCODE)) }
        }
        collectAliasMatches(businessId, supplierId, trimmed, ProductMatchReason.SUPPLIER_CODE)
            .forEach { add(it.candidate) }
        productRepository.findBySku(businessId, trimmed)?.activeForBusinessOrNull(businessId)
            ?.let { add(ProductMatchCandidate(it, ProductMatchReason.SKU, CONFIDENCE_SKU)) }
        productRepository.findByNormalizedName(businessId, trimmed)
            .mapNotNull { it.activeForBusinessOrNull(businessId) }
            .sortedBy { it.productId.value }
            .forEach { add(ProductMatchCandidate(it, ProductMatchReason.EXACT_NAME, CONFIDENCE_EXACT_NAME)) }
        val fuzzy = fuzzyCandidates(businessId, trimmed)
            .filter { candidate -> exacts.none { it.product.productId == candidate.product.productId } }
        return (exacts + fuzzy).take(MAX_CANDIDATES)
    }

    /**
     * Búsqueda manual deliberadamente limitada al nombre del producto. Devuelve primero las
     * coincidencias exactas y luego sugerencias difusas; nunca interpreta el texto como código de
     * barras, SKU o alias de proveedor y nunca selecciona un producto automáticamente.
     */
    suspend fun searchByName(
        businessId: BusinessId,
        rawName: String,
    ): List<ProductMatchCandidate> {
        val name = lookupText(rawName) ?: return emptyList()
        val exacts = productRepository.findByNormalizedName(businessId, name)
            .mapNotNull { it.activeForBusinessOrNull(businessId) }
            .sortedBy { it.productId.value }
            .map { product ->
                ProductMatchCandidate(product, ProductMatchReason.EXACT_NAME, CONFIDENCE_EXACT_NAME)
            }
        val exactIds = exacts.mapTo(mutableSetOf()) { it.product.productId }
        val fuzzy = fuzzyNameCandidates(businessId, name)
            .filterNot { it.product.productId in exactIds }
        return (exacts + fuzzy).take(MAX_CANDIDATES)
    }

    private suspend fun exactByBarcode(query: ProductMatchQuery): ProductMatchOutcome? {
        val barcode = query.barcode?.let { BarcodeValue.parse(it) } ?: return null
        val product = productRepository.findByBarcode(query.businessId, barcode.value)
            ?.activeForBusinessOrNull(query.businessId)
            ?: return null
        return ProductMatchOutcome.AutoLinked(
            ProductMatchCandidate(product, ProductMatchReason.BARCODE, CONFIDENCE_BARCODE),
        )
    }

    private suspend fun exactByAlias(
        query: ProductMatchQuery,
        code: Boolean,
    ): ProductMatchOutcome? {
        val text = (if (code) query.supplierCode else query.description)
            ?.let(::lookupText) ?: return null
        val reason = if (code) ProductMatchReason.SUPPLIER_CODE else ProductMatchReason.CONFIRMED_ALIAS
        val matches = collectAliasMatches(query.businessId, query.supplierId, text, reason)
        return when {
            matches.isEmpty() -> null
            matches.size == 1 -> ProductMatchOutcome.AutoLinked(matches.single().candidate)
            else -> {
                // Un alias del propio proveedor de la factura desempata; sin esa preferencia
                // clara la elección es humana.
                val preferred = matches.singleOrNull { it.preferredForSupplier }
                if (preferred != null) {
                    ProductMatchOutcome.AutoLinked(
                        candidate = preferred.candidate,
                        alternatives = matches.filter { it !== preferred }.map { it.candidate }
                            .take(MAX_CANDIDATES - 1),
                    )
                } else {
                    ProductMatchOutcome.Ambiguous(matches.map { it.candidate }.take(MAX_CANDIDATES))
                }
            }
        }
    }

    private suspend fun collectAliasMatches(
        businessId: BusinessId,
        supplierId: SupplierId?,
        aliasText: String,
        reason: ProductMatchReason,
    ): List<AliasMatch> {
        val confidence = if (reason == ProductMatchReason.SUPPLIER_CODE) {
            CONFIDENCE_SUPPLIER_CODE
        } else {
            CONFIDENCE_CONFIRMED_ALIAS
        }
        return supplierProductAliasRepository.findByNormalizedAlias(businessId, aliasText)
            .filter { alias -> alias.businessId == businessId }
            .mapNotNull { alias ->
                productRepository.findById(alias.productId)
                    ?.activeForBusinessOrNull(businessId)
                    ?.let { product ->
                        AliasMatch(
                            candidate = ProductMatchCandidate(product, reason, confidence),
                            preferredForSupplier = supplierId != null && alias.supplierId == supplierId,
                        )
                    }
            }
            .distinctBy { it.candidate.product.productId }
            .sortedBy { it.candidate.product.productId.value }
    }

    private suspend fun exactBySku(query: ProductMatchQuery): ProductMatchOutcome? {
        val sku = query.supplierCode?.let(::boundedTrimmedText) ?: return null
        val product = productRepository.findBySku(query.businessId, sku)
            ?.activeForBusinessOrNull(query.businessId)
            ?: return null
        return ProductMatchOutcome.AutoLinked(
            ProductMatchCandidate(product, ProductMatchReason.SKU, CONFIDENCE_SKU),
        )
    }

    private suspend fun exactByName(query: ProductMatchQuery): ProductMatchOutcome? {
        val description = query.description?.let(::lookupText) ?: return null
        val matches = productRepository.findByNormalizedName(query.businessId, description)
            .mapNotNull { it.activeForBusinessOrNull(query.businessId) }
            .map { ProductMatchCandidate(it, ProductMatchReason.EXACT_NAME, CONFIDENCE_EXACT_NAME) }
            .sortedBy { it.product.productId.value }
        return when {
            matches.isEmpty() -> null
            matches.size == 1 -> ProductMatchOutcome.AutoLinked(matches.single())
            else -> ProductMatchOutcome.Ambiguous(matches.take(MAX_CANDIDATES))
        }
    }

    private suspend fun fuzzySuggestions(query: ProductMatchQuery): ProductMatchOutcome {
        val description = query.description?.let(::lookupText)
            ?: return ProductMatchOutcome.NoMatch
        val candidates = fuzzyCandidates(query.businessId, description)
        return if (candidates.isEmpty()) {
            ProductMatchOutcome.NoMatch
        } else {
            ProductMatchOutcome.Suggestions(candidates)
        }
    }

    private suspend fun fuzzyCandidates(
        businessId: BusinessId,
        description: String,
    ): List<ProductMatchCandidate> {
        val boundedDescription = lookupText(description) ?: return emptyList()
        val preselected = linkedMapOf<ProductId, Product>()
        fuzzySearchSeeds(boundedDescription).take(MAX_FUZZY_SQL_SEEDS).forEach { seed ->
            productRepository.search(
                businessId,
                CatalogSearch(
                    query = seed,
                    status = CatalogStatus.ACTIVE,
                    limit = MAX_CATALOG_PAGE_SIZE,
                ),
            ).items.forEach { product -> preselected.putIfAbsent(product.productId, product) }
        }
        // SQLite LIKE no pliega tildes: una página acotada mantiene sugerencias útiles si el OCR
        // produjo "azucar" y el catálogo contiene "azúcar", sin volver al full scan de 5 000.
        if (preselected.isEmpty()) {
            productRepository.search(
                businessId,
                CatalogSearch(status = CatalogStatus.ACTIVE, limit = MAX_CATALOG_PAGE_SIZE),
            ).items.forEach { product -> preselected.putIfAbsent(product.productId, product) }
        }
        return rankFuzzyCandidates(businessId, boundedDescription, preselected.values)
    }

    private suspend fun fuzzyNameCandidates(
        businessId: BusinessId,
        description: String,
    ): List<ProductMatchCandidate> {
        val boundedDescription = lookupText(description) ?: return emptyList()
        val preselected = linkedMapOf<ProductId, Product>()
        nameOnlySearchSeeds(boundedDescription).forEach { seed ->
            productRepository.searchActiveByName(
                businessId = businessId,
                query = seed,
                limit = MAX_CATALOG_PAGE_SIZE,
            ).forEach { product -> preselected.putIfAbsent(product.productId, product) }
        }
        // Un seed sin tilde puede no coincidir con SQLite aunque el plegado difuso sí lo haga.
        // La página de respaldo sigue siendo name-only y acotada; nunca consulta códigos.
        if (preselected.isEmpty()) {
            productRepository.searchActiveByName(
                businessId = businessId,
                query = "",
                limit = MAX_CATALOG_PAGE_SIZE,
            ).forEach { product -> preselected.putIfAbsent(product.productId, product) }
        }
        return rankFuzzyCandidates(businessId, boundedDescription, preselected.values)
    }

    private fun rankFuzzyCandidates(
        businessId: BusinessId,
        description: String,
        products: Collection<Product>,
    ): List<ProductMatchCandidate> {
        val scorer = ProductNameSimilarity.scorer(description) ?: return emptyList()
        return products
            .asSequence()
            .mapNotNull { it.activeForBusinessOrNull(businessId) }
            // Revalida tenant/estado primero, pero nunca entrega más de una página al cálculo
            // cuadrático de Levenshtein aunque un repositorio defectuoso ignore su propio límite.
            .take(MAX_CATALOG_PAGE_SIZE)
            .map { product -> product to scorer.similarityPermille(product.name) }
            .filter { (_, score) -> score >= MIN_FUZZY_SCORE_PERMILLE }
            .sortedWith(
                compareByDescending<Pair<Product, Int>> { it.second }
                    .thenBy { it.first.name }
                    .thenBy { it.first.productId.value },
            )
            .take(MAX_CANDIDATES)
            .map { (product, score) ->
                ProductMatchCandidate(product, ProductMatchReason.SIMILAR_NAME, score)
            }
            .toList()
    }

    private fun nameOnlySearchSeeds(description: String): List<String> {
        val base = fuzzySearchSeeds(description)
        val accentVariants = base.flatMap { seed ->
            seed.lowercase(Locale.ROOT).mapIndexedNotNull { index, character ->
                val replacement = SPANISH_SEARCH_VARIANTS[character]
                    ?: return@mapIndexedNotNull null
                seed.replaceRange(index, index + 1, replacement.toString())
            }
        }
        return (base + accentVariants).distinct().take(MAX_FUZZY_SQL_SEEDS)
    }

    /** Reduce la preselección SQL a pocos tokens informativos y evita escanear 5.000+ filas. */
    private fun fuzzySearchSeeds(description: String): List<String> =
        lookupText(description)
            ?.split(' ')
            ?.filter { it.length >= 3 }
            ?.distinct()
            ?.sortedByDescending(String::length)
            ?.takeIf(List<String>::isNotEmpty)
            ?: listOf(description.trim())

    private fun Product.activeForBusinessOrNull(businessId: BusinessId): Product? =
        takeIf { it.businessId == businessId && it.status == CatalogStatus.ACTIVE }

    /**
     * Texto de comparación para búsquedas puntuales: sin espacios extremos y con los espacios
     * internos colapsados, igual que la forma que persisten los catálogos.
     */
    private fun lookupText(raw: String): String? =
        boundedTrimmedText(raw)
            ?.replace(Regex("\\s+"), " ")

    /**
     * Acota entrada no confiable antes de normalización, SQL LIKE o Levenshtein. Los nombres de
     * catálogo persistidos ya tienen el mismo máximo; una consulta mayor se rechaza completa para
     * no convertir silenciosamente dos textos distintos en la misma búsqueda.
     */
    private fun boundedTrimmedText(raw: String): String? {
        val trimmed = raw.trim()
        return trimmed.takeIf { it.isNotEmpty() && it.length <= MAX_MATCH_TEXT_LENGTH }
    }

    private data class AliasMatch(
        val candidate: ProductMatchCandidate,
        val preferredForSupplier: Boolean,
    )

    companion object {
        const val MAX_CANDIDATES: Int = 5
        const val MAX_MATCH_TEXT_LENGTH: Int = 200
        private const val MAX_FUZZY_SQL_SEEDS: Int = 4
        const val MIN_FUZZY_SCORE_PERMILLE: Int = 600
        const val CONFIDENCE_BARCODE: Int = 1_000
        const val CONFIDENCE_SUPPLIER_CODE: Int = 1_000
        const val CONFIDENCE_SKU: Int = 1_000
        const val CONFIDENCE_CONFIRMED_ALIAS: Int = 950
        const val CONFIDENCE_EXACT_NAME: Int = 900
        private val SPANISH_SEARCH_VARIANTS = mapOf(
            'a' to 'á',
            'e' to 'é',
            'i' to 'í',
            'o' to 'ó',
            'u' to 'ú',
            'n' to 'ñ',
        )
    }
}

/**
 * Similitud determinista entre descripciones de producto, en permil. Mezcla el coeficiente de
 * solape de tokens (robusto cuando el texto de la factura es un subconjunto del nombre de
 * catálogo) con el ratio de Levenshtein normalizado sobre el texto plegado (sin tildes, en
 * minúsculas y con espacios colapsados).
 */
internal object ProductNameSimilarity {
    fun similarityPermille(query: String, candidate: String): Int =
        scorer(query)?.similarityPermille(candidate) ?: 0

    /**
     * Prepara una búsqueda completa una sola vez. El objeto se usa secuencialmente dentro de una
     * invocación de matching y reutiliza dos filas de Levenshtein acotadas a 200 caracteres.
     */
    fun scorer(query: String): Scorer? {
        if (query.length > ProductMatchingUseCase.MAX_MATCH_TEXT_LENGTH) return null
        // NFD puede expandir caracteres acentuados; el corte mantiene también acotados los
        // arreglos de Levenshtein. Esta ruta solo sugiere y nunca auto-vincula.
        val foldedQuery = fold(query).take(ProductMatchingUseCase.MAX_MATCH_TEXT_LENGTH)
        if (foldedQuery.isEmpty()) return null
        return Scorer(foldedQuery, tokens(foldedQuery))
    }

    internal class Scorer internal constructor(
        private val foldedQuery: String,
        private val queryTokens: Set<String>,
    ) {
        private val previousScratch = IntArray(ProductMatchingUseCase.MAX_MATCH_TEXT_LENGTH + 1)
        private val currentScratch = IntArray(ProductMatchingUseCase.MAX_MATCH_TEXT_LENGTH + 1)

        fun similarityPermille(candidate: String): Int {
            if (candidate.length > ProductMatchingUseCase.MAX_MATCH_TEXT_LENGTH) return 0
            val foldedCandidate = fold(candidate).take(ProductMatchingUseCase.MAX_MATCH_TEXT_LENGTH)
            if (foldedCandidate.isEmpty()) return 0
            val candidateTokens = tokens(foldedCandidate)
            val smallerTokens: Set<String>
            val largerTokens: Set<String>
            if (queryTokens.size <= candidateTokens.size) {
                smallerTokens = queryTokens
                largerTokens = candidateTokens
            } else {
                smallerTokens = candidateTokens
                largerTokens = queryTokens
            }
            val overlap = if (smallerTokens.isEmpty()) {
                0.0
            } else {
                smallerTokens.count(largerTokens::contains).toDouble() / smallerTokens.size
            }
            val levenshteinRatio = 1.0 -
                levenshtein(foldedQuery, foldedCandidate).toDouble() /
                maxOf(foldedQuery.length, foldedCandidate.length)
            val baseScore = ((overlap + levenshteinRatio) * 500.0).roundToInt()
            // La puntuación pública conserva su contrato simétrico. En la búsqueda name-only el
            // primer lado suele ser el fragmento; aceptar también la dirección inversa mantiene
            // la misma puntuación si un llamador intercambia los argumentos.
            val allTokensMatchByPrefix =
                tokensMatchByPrefix(queryTokens, candidateTokens) ||
                    tokensMatchByPrefix(candidateTokens, queryTokens)
            val prefixScore = if (allTokensMatchByPrefix) PREFIX_MATCH_SCORE else 0
            return maxOf(baseScore, prefixScore).coerceIn(0, 1_000)
        }

        private fun levenshtein(a: String, b: String): Int {
            if (a == b) return 0
            if (a.isEmpty()) return b.length
            if (b.isEmpty()) return a.length
            var sharedStart = 0
            val shortestLength = minOf(a.length, b.length)
            while (sharedStart < shortestLength && a[sharedStart] == b[sharedStart]) {
                sharedStart++
            }
            var aEnd = a.length
            var bEnd = b.length
            while (
                aEnd > sharedStart &&
                bEnd > sharedStart &&
                a[aEnd - 1] == b[bEnd - 1]
            ) {
                aEnd--
                bEnd--
            }
            val aLength = aEnd - sharedStart
            val bLength = bEnd - sharedStart
            if (aLength == 0) return bLength
            if (bLength == 0) return aLength
            var previous = previousScratch
            var current = currentScratch
            for (index in 0..bLength) previous[index] = index
            for (i in 1..aLength) {
                current[0] = i
                val aCharacter = a[sharedStart + i - 1]
                for (j in 1..bLength) {
                    val substitution = previous[j - 1] +
                        if (aCharacter == b[sharedStart + j - 1]) 0 else 1
                    current[j] = minOf(previous[j] + 1, current[j - 1] + 1, substitution)
                }
                val swap = previous
                previous = current
                current = swap
            }
            return previous[bLength]
        }
    }

    private fun tokensMatchByPrefix(
        fragments: Set<String>,
        candidates: Set<String>,
    ): Boolean = fragments.isNotEmpty() && fragments.all { fragment ->
        fragment.length >= MIN_PREFIX_LENGTH && candidates.any { candidate ->
            candidate.startsWith(fragment)
        }
    }

    private fun fold(raw: String): String {
        val withoutMarks = Normalizer.normalize(raw, Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
        return withoutMarks
            .lowercase(Locale.ROOT)
            .replace(WHITESPACE, " ")
            .trim()
    }

    private fun tokens(folded: String): Set<String> =
        folded.split(' ').filter { it.length >= 2 }.toSet()

    private val COMBINING_MARKS = Regex("\\p{M}+")
    private val WHITESPACE = Regex("\\s+")
    private const val MIN_PREFIX_LENGTH = 3
    private const val PREFIX_MATCH_SCORE = 850
}
