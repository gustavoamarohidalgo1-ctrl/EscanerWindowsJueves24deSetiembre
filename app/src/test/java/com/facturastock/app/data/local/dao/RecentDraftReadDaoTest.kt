package com.facturastock.app.data.local.dao

import com.facturastock.app.data.local.newFacturaStockDatabaseWithoutInvariants
import org.junit.rules.TemporaryFolder
import org.junit.Rule
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.data.local.entity.BusinessEntity
import com.facturastock.app.data.local.entity.InvoiceDraftEntity
import com.facturastock.app.data.local.entity.SupplierEntity
import com.facturastock.app.domain.model.DraftStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class RecentDraftReadDaoTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var database: FacturaStockDatabase

    @Before
    fun setUp() = runBlocking {
        database = tempFolder.newFacturaStockDatabaseWithoutInvariants()
        database.businessDao().insert(business(BUSINESS_A, "Negocio A"))
        database.businessDao().insert(business(BUSINESS_B, "Negocio B"))
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun recentReadLimitsBeforeMappingAndUsesDeterministicTenantScopedOrder() = runBlocking {
        database.supplierDao().insert(
            supplier(SUPPLIER_A, BUSINESS_A, "Proveedor del negocio A"),
        )
        repeat(12) { index ->
            val seed = index + 1
            database.invoiceDraftDao().insert(
                draft(
                    id = uuid(seed),
                    businessId = BUSINESS_A,
                    updatedAt = SAME_UPDATED_AT,
                    supplierId = if (seed == 12) SUPPLIER_A else null,
                ),
            )
        }
        database.invoiceDraftDao().insert(
            draft(uuid(90), BUSINESS_B, updatedAt = SAME_UPDATED_AT + 10_000L),
        )
        database.invoiceDraftDao().insert(
            draft(
                id = uuid(91),
                businessId = BUSINESS_A,
                updatedAt = SAME_UPDATED_AT + 20_000L,
                status = DraftStatus.COMMITTED.name,
                confirmedPurchaseId = uuid(301),
            ),
        )

        val rows = database.invoiceDraftDao()
            .observeRecentForBusiness(BUSINESS_A, limit = 10)
            .first()

        assertEquals((12 downTo 3).map(::uuid), rows.map { it.draft.draftId })
        assertEquals("Proveedor del negocio A", rows.first().supplierName)
        assertEquals(setOf(BUSINESS_A), rows.map { it.draft.businessId }.toSet())
        assertEquals(
            12,
            database.invoiceDraftDao().observeOpenCountForBusiness(BUSINESS_A).first(),
        )
    }

    @Test
    fun leftJoinNeverProjectsASupplierNameFromAnotherTenant() = runBlocking {
        database.supplierDao().insert(
            supplier(SUPPLIER_B, BUSINESS_B, "Proveedor privado del negocio B"),
        )
        // La FK legada solo referencia supplierId. Sin callbacks de invariantes, esta fila
        // simula almacenamiento histórico inconsistente y comprueba la defensa del read-model.
        database.invoiceDraftDao().insert(
            draft(uuid(1), BUSINESS_A, updatedAt = SAME_UPDATED_AT, supplierId = SUPPLIER_B),
        )

        val row = database.invoiceDraftDao()
            .observeRecentForBusiness(BUSINESS_A, limit = 10)
            .first()
            .single()

        assertNull(row.supplierName)
    }

    private fun business(id: String, legalName: String) = BusinessEntity(
        businessId = id,
        legalName = legalName,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun supplier(id: String, businessId: String, legalName: String) = SupplierEntity(
        supplierId = id,
        businessId = businessId,
        legalName = legalName,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun draft(
        id: String,
        businessId: String,
        updatedAt: Long,
        supplierId: String? = null,
        status: String = DraftStatus.CREATED.name,
        confirmedPurchaseId: String? = null,
    ) = InvoiceDraftEntity(
        draftId = id,
        businessId = businessId,
        createdAt = 1L,
        updatedAt = updatedAt,
        status = status,
        supplierId = supplierId,
        confirmedPurchaseId = confirmedPurchaseId,
    )

    private companion object {
        const val SAME_UPDATED_AT = 50_000L
        val BUSINESS_A = uuid(101)
        val BUSINESS_B = uuid(102)
        val SUPPLIER_A = uuid(201)
        val SUPPLIER_B = uuid(202)

        fun uuid(seed: Int): String =
            "00000000-0000-0000-0000-%012d".format(seed)
    }
}
