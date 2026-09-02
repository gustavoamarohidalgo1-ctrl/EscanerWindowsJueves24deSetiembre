package com.facturastock.app.data.account

import com.facturastock.app.domain.error.AccountError
import com.facturastock.app.domain.error.DomainResult
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Página cerrada de los listados de cuenta que usan un cursor opaco de Functions. */
internal data class AccountListPage<T>(
    val values: List<T>,
    val nextCursor: String?,
    val hasMore: Boolean,
)

/**
 * Drena todas las páginas sin reordenar ni deduplicar entradas. Un cursor solo puede usarse
 * una vez: repetirlo (inmediatamente o formando un ciclo) es una respuesta incoherente y se
 * cierra como [AccountError.Unexpected] en lugar de entrar en un bucle remoto.
 *
 * No se fija un máximo artificial de páginas: el backend ya limita cada página y un negocio
 * legítimo puede superar ampliamente ese tamaño. La cancelación se comprueba antes de cada
 * petición para que un cambio de identidad corte el drenaje con prontitud.
 */
internal suspend fun <T> drainAccountPages(
    fetchPage: suspend (cursor: String?) -> DomainResult<AccountListPage<T>>,
): DomainResult<List<T>> {
    val accumulated = mutableListOf<T>()
    val consumedCursors = mutableSetOf<String>()
    var cursor: String? = null

    while (true) {
        currentCoroutineContext().ensureActive()
        when (val result = fetchPage(cursor)) {
            is DomainResult.Failure -> return result
            is DomainResult.Success -> {
                val page = result.value
                accumulated += page.values
                if (!page.hasMore) {
                    return if (page.nextCursor == null) {
                        DomainResult.Success(accumulated)
                    } else {
                        DomainResult.Failure(AccountError.Unexpected)
                    }
                }

                val nextCursor = page.nextCursor
                    ?.takeIf { it.isNotBlank() }
                    ?: return DomainResult.Failure(AccountError.Unexpected)
                if (!consumedCursors.add(nextCursor)) {
                    return DomainResult.Failure(AccountError.Unexpected)
                }
                cursor = nextCursor
            }
        }
    }
}
