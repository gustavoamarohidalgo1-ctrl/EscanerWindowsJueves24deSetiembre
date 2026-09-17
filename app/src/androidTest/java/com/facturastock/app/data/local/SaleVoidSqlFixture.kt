package com.facturastock.app.data.local

import androidx.sqlite.db.SupportSQLiteDatabase

/** A fully posted two-product sale, optionally with a partial cash payment. */
internal class SaleVoidSqlFixture(
    private val sql: SupportSQLiteDatabase,
) {
    fun seedPosted(withDebt: Boolean = true) {
        sql.execSQL(
            "INSERT INTO businesses (businessId,legalName,createdAt,updatedAt,ruc,tradeName,status) " +
                "VALUES ('$BUSINESS_ID','Negocio',1,1,NULL,NULL,'ACTIVE')",
        )
        sql.execSQL(
            "INSERT INTO units (unitId,businessId,code,name,createdAt,updatedAt,symbol,status) " +
                "VALUES ('$UNIT_ID','$BUSINESS_ID','NIU','Unidad',1,1,NULL,'ACTIVE')",
        )
        sql.execSQL(
            "INSERT INTO inventory_locations (locationId,businessId,name,createdAt,updatedAt,status) " +
                "VALUES ('$LOCATION_ID','$BUSINESS_ID','Principal',1,1,'ACTIVE')",
        )
        productIds.forEachIndexed { index, productId ->
            sql.execSQL(
                "INSERT INTO products (productId,businessId,unitId,name,createdAt,updatedAt," +
                    "locationId,sku,barcode,normalizedName,purchaseUnitId,purchaseFactor,status,version) " +
                    "VALUES ('$productId','$BUSINESS_ID','$UNIT_ID','Producto $index',1,1," +
                    "'$LOCATION_ID',NULL,NULL,'producto $index',NULL,NULL,'ACTIVE',1)",
            )
            sql.execSQL(
                "INSERT INTO inventory_balances (businessId,productId,locationId,quantityOnHand," +
                    "averageUnitCost,currencyCode,version,updatedAt) " +
                    "VALUES ('$BUSINESS_ID','$productId','$LOCATION_ID','${8 + index}','3.250','PEN',1,2)",
            )
        }
        sql.execSQL(
            "INSERT INTO sales (saleId,businessId,status,currencyCode,subtotalMinorUnits," +
                "discountMinorUnits,taxMinorUnits,totalMinorUnits,contentHash,draftSlot," +
                "checkoutIdempotencyKey,version,createdAt,updatedAt,postedAt) " +
                "VALUES ('$SALE_ID','$BUSINESS_ID','DRAFT','PEN',0,0,0,0,'$HASH'," +
                "'$BUSINESS_ID:PEN',NULL,0,1,1,NULL)",
        )
        lineIds.forEachIndexed { index, lineId ->
            sql.execSQL(
                "INSERT INTO sale_lines (saleLineId,saleId,productId,unitId,locationId,position," +
                    "productNameSnapshot,unitCodeSnapshot,locationNameSnapshot,barcodeSnapshot,quantity," +
                    "unitPriceMinorUnits,discountMinorUnits,taxMinorUnits,lineTotalMinorUnits,currencyCode) " +
                    "VALUES ('$lineId','$SALE_ID','${productIds[index]}','$UNIT_ID','$LOCATION_ID'," +
                    "$index,'Producto $index','NIU','Principal',NULL,'${quantities[index]}',100,0,0," +
                    "${if (index == 0) 200 else 100},'PEN')",
            )
            insertMovement(index, isReturn = false)
        }
        insertAudit(isReturn = false)
        sql.execSQL(
            "UPDATE sales SET status='POSTED',draftSlot=NULL,checkoutIdempotencyKey='sale-checkout:fixture'," +
                "subtotalMinorUnits=300,totalMinorUnits=300,version=1,updatedAt=2,postedAt=2 " +
                "WHERE saleId='$SALE_ID'",
        )
        if (withDebt) {
            sql.execSQL(
                "INSERT INTO debts (debtId,businessId,saleId,debtorName,normalizedDebtorName,currencyCode," +
                    "originalAmountMinorUnits,balanceMinorUnits,status,dueAt,version,createdAt,updatedAt,paidAt) " +
                    "VALUES ('$DEBT_ID','$BUSINESS_ID','$SALE_ID','Cliente','cliente','PEN',300,300," +
                    "'OPEN',NULL,1,2,2,NULL)",
            )
            insertPayment()
            sql.execSQL(
                "UPDATE debts SET balanceMinorUnits=225,version=2,updatedAt=3 WHERE debtId='$DEBT_ID'",
            )
        }
    }

    fun insertPayment(
        paymentId: String = PAYMENT_ID,
        expectedVersion: Long = 1,
        balanceAfter: Long = 225,
        occurredAt: Long = 3,
    ) {
        sql.execSQL(
            "INSERT INTO debt_payments (paymentId,debtId,businessId,currencyCode,amountMinorUnits," +
                "method,note,reference,expectedDebtVersion,balanceAfterMinorUnits,idempotencyKey," +
                "occurredAt,createdAt) VALUES ('$paymentId','$DEBT_ID','$BUSINESS_ID','PEN',75,'CASH'," +
                "NULL,NULL,$expectedVersion,$balanceAfter,'debt-payment:v1:$DEBT_ID:$paymentId'," +
                "$occurredAt,$occurredAt)",
        )
    }

    fun insertMovement(
        index: Int,
        isReturn: Boolean = true,
        quantity: String = if (isReturn) quantities[index] else "-${quantities[index]}",
        cost: String = "3.250",
        currency: String = "PEN",
        productId: String = productIds[index],
        key: String = "${if (isReturn) "sale-void-stock" else "sale-stock"}:v1:$SALE_ID:${lineIds[index]}",
        movementId: String = if (isReturn) returnIds[index] else originalIds[index],
    ) {
        val time = if (isReturn) 4 else 2
        sql.execSQL(
            "INSERT INTO stock_movements (movementId,businessId,purchaseId,purchaseLineId,saleId," +
                "saleLineId,productId,locationId,type,quantityDelta,unitCost,currencyCode," +
                "idempotencyKey,occurredAt,createdAt) VALUES (?,?,NULL,NULL,?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf<Any>(
                movementId,
                BUSINESS_ID,
                SALE_ID,
                lineIds[index],
                productId,
                LOCATION_ID,
                if (isReturn) "SALE_VOID" else "SALE",
                quantity,
                cost,
                currency,
                key,
                time,
                time,
            ),
        )
    }

    fun returnBalance(index: Int) {
        sql.execSQL(
            "UPDATE inventory_balances SET quantityOnHand='10',version=version+1,updatedAt=4 " +
                "WHERE businessId='$BUSINESS_ID' AND productId='${productIds[index]}' AND locationId='$LOCATION_ID'",
        )
    }

    fun insertAudit(
        isReturn: Boolean = true,
        auditId: String = if (isReturn) VOID_AUDIT_ID else POST_AUDIT_ID,
    ) {
        sql.execSQL(
            "INSERT INTO audit_events (auditEventId,businessId,purchaseId,eventType,entityType,entityId," +
                "payload,occurredAt) VALUES ('$auditId','$BUSINESS_ID',NULL," +
                "'${if (isReturn) "SALE_VOIDED" else "SALE_POSTED"}','SALE','$SALE_ID','{\"version\":\"1\"}'," +
                "${if (isReturn) 4 else 2})",
        )
    }

    fun prepareCompleteReturn() {
        lineIds.indices.forEach { index ->
            returnBalance(index)
            insertMovement(index)
        }
        insertAudit()
    }

    fun insertReceipt(
        refund: Long = 75,
        cancelled: Long = 225,
        hash: String = HASH,
        role: String = "OWNER",
        currency: String = "PEN",
        replace: Boolean = false,
    ) {
        sql.execSQL(
            "INSERT ${if (replace) "OR REPLACE " else ""}INTO sale_voids " +
                "(saleId,businessId,impactHash,refundedAmountMinorUnits,cancelledDebtBalanceMinorUnits," +
                "currencyCode,actorId,actorRole,voidedAt) VALUES (?,?,?,?,?,?,?, ?,4)",
            arrayOf<Any>(SALE_ID, BUSINESS_ID, hash, refund, cancelled, currency, "owner", role),
        )
    }

    fun historicalRows(): Map<String, List<List<String?>>> =
        mapOf(
            "sales" to rows("SELECT * FROM sales ORDER BY saleId"),
            "sale_lines" to rows("SELECT * FROM sale_lines ORDER BY saleLineId"),
            "debts" to rows("SELECT * FROM debts ORDER BY debtId"),
            "debt_payments" to rows("SELECT * FROM debt_payments ORDER BY paymentId"),
            "stock_movements" to rows("SELECT * FROM stock_movements WHERE type='SALE' ORDER BY movementId"),
            "audit_events" to rows("SELECT * FROM audit_events WHERE eventType='SALE_POSTED' ORDER BY auditEventId"),
        )

    private fun rows(query: String): List<List<String?>> =
        sql.query(query).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(List(cursor.columnCount) { if (cursor.isNull(it)) null else cursor.getString(it) })
            }
        }

    companion object {
        const val BUSINESS_ID = "11111111-1111-4111-8111-111111111111"
        const val UNIT_ID = "22222222-2222-4222-8222-222222222222"
        const val LOCATION_ID = "44444444-4444-4444-8444-444444444444"
        const val SALE_ID = "55555555-5555-4555-8555-555555555555"
        const val DEBT_ID = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
        const val PAYMENT_ID = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"
        const val POST_AUDIT_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val VOID_AUDIT_ID = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        const val HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val productIds = listOf("33333333-3333-4333-8333-333333333333", "33333333-3333-4333-8333-333333333334")
        val lineIds = listOf("66666666-6666-4666-8666-666666666666", "66666666-6666-4666-8666-666666666667")
        val originalIds = listOf("77777777-7777-4777-8777-777777777777", "77777777-7777-4777-8777-777777777778")
        val returnIds = listOf("88888888-8888-4888-8888-888888888888", "88888888-8888-4888-8888-888888888889")
        val quantities = listOf("2.000", "1")
    }
}
