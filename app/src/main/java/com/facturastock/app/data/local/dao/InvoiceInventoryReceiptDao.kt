package com.facturastock.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.facturastock.app.data.local.entity.InvoiceInventoryReceiptEntity

@Dao
interface InvoiceInventoryReceiptDao {
    @Query("SELECT * FROM invoice_inventory_receipts WHERE draftId = :draftId")
    suspend fun find(draftId: String): InvoiceInventoryReceiptEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(receipt: InvoiceInventoryReceiptEntity)
}
