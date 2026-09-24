package com.facturastock.app.data.local

import com.facturastock.app.data.local.sqlite.SQLiteConstraintException
import com.facturastock.app.data.local.sqlite.SupportSQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Contract tests for the exact-duplicate exception introduced in schema v14. */
class PurchaseDuplicateOverrideMigrationTest {
    @get:Rule
    val helper = FacturaStockMigrationTestHelper()

    @Test
    fun migrate13To14PreservesHistoryAsPrimaryAndRebuildsFiscalIdentityIndex() {
        createV13Database(PRESERVATION_DB, targetStatus = "POSTED")

        val db = migrate(PRESERVATION_DB)

        db.query(
            "SELECT `documentIdentitySlot`, `duplicateOverrideOfPurchaseId`, " +
                "`duplicateOverrideReason`, `duplicateOverrideActorId`, " +
                "`duplicateOverrideRole` FROM `purchases` " +
                "WHERE `purchaseId` = '$TARGET_PURCHASE_ID'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("PRIMARY", cursor.getString(0))
            for (column in 1..4) assertTrue(cursor.isNull(column))
            assertFalse(cursor.moveToNext())
        }

        assertEquals(
            listOf(
                "businessId",
                "supplierId",
                "documentType",
                "documentSeries",
                "documentNumber",
                "documentIdentitySlot",
            ),
            indexColumns(db, FISCAL_IDENTITY_INDEX),
        )
        assertEquals(listOf("duplicateOverrideOfPurchaseId"), indexColumns(db, TARGET_INDEX))
        assertSingleLong(db, indexCountQuery(LEGACY_FISCAL_IDENTITY_INDEX), 0L)
        assertSingleLong(db, indexCountQuery(FISCAL_IDENTITY_INDEX), 1L)
        assertSingleLong(db, indexCountQuery(TARGET_INDEX), 1L)
        assertNoForeignKeyViolations(db)

        helper.closeWhenFinished(db)
    }

    @Test
    fun migrate13To14MarksAnAlreadyPostedLinkedDraftAsCommitted() {
        createV13Database(
            databaseName = COMMITTED_DRAFT_DB,
            targetStatus = "POSTED",
            linkTargetDraft = true,
        )

        val db = migrate(COMMITTED_DRAFT_DB)

        db.query(
            "SELECT `status`, `confirmedPurchaseId`, `updatedAt` FROM `invoice_drafts` " +
                "WHERE `draftId` = '$TARGET_DRAFT_ID'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("COMMITTED", cursor.getString(0))
            assertEquals(TARGET_PURCHASE_ID, cursor.getString(1))
            assertEquals(2_000L, cursor.getLong(2))
            assertFalse(cursor.moveToNext())
        }
        assertNoForeignKeyViolations(db)

        helper.closeWhenFinished(db)
    }

    @Test
    fun migratedDatabaseAllowsOnlyCompleteAuthorizedOverridesOfAnExactPostedOrVoidedTarget() {
        // VOIDED exercises the second terminal status accepted by the target guard. The
        // preservation test above covers an existing POSTED target.
        createV13Database(VALIDATION_DB, targetStatus = "VOIDED")
        val db = migrate(VALIDATION_DB)

        // PRIMARY remains the only unexceptional slot for this fiscal identity.
        assertConstraint {
            insertPurchase(db, seed = 10)
        }
        assertConstraint {
            insertPurchase(
                db,
                seed = 11,
                slot = "PRIMARY",
                targetId = TARGET_PURCHASE_ID,
                reason = VALID_REASON,
                actorId = "owner-local",
                role = "OWNER",
            )
        }

        // Every nullable authorization component is checked explicitly. In particular, SQL's
        // three-valued NULL semantics must not let an absent role pass the IN predicate.
        val invalidCases = listOf(
            OverrideCase(seed = 20, slot = uuid(999)),
            OverrideCase(seed = 21, targetId = null),
            OverrideCase(seed = 22, targetId = uuid(998)),
            OverrideCase(seed = 23, reason = null),
            OverrideCase(seed = 24, reason = "123456789"),
            OverrideCase(seed = 25, reason = " motivo suficientemente largo"),
            OverrideCase(seed = 26, reason = "r".repeat(501)),
            OverrideCase(seed = 27, actorId = null),
            OverrideCase(seed = 28, actorId = " "),
            OverrideCase(seed = 29, actorId = "a".repeat(129)),
            OverrideCase(seed = 30, role = null),
            OverrideCase(seed = 31, role = "CASHIER"),
            // The referenced purchase exists and is terminal, but is not the same document.
            OverrideCase(seed = 32, documentSeries = "F002", documentNumber = "7"),
        )
        invalidCases.forEach { invalid ->
            assertConstraint {
                insertOverride(db, invalid)
            }
        }

        // A DRAFT purchase is never an authoritative exception target, even with an exact tuple.
        insertPurchase(
            db,
            seed = 40,
            documentSeries = "F003",
            documentNumber = "8",
        )
        assertConstraint {
            insertOverride(
                db,
                OverrideCase(
                    seed = 41,
                    targetId = purchaseId(40),
                    documentSeries = "F003",
                    documentNumber = "8",
                ),
            )
        }

        // Inclusive boundaries and both authorized roles are accepted. Each exception gets the
        // source draft as a unique slot, while the PRIMARY fiscal identity remains untouched.
        insertOverride(
            db,
            OverrideCase(
                seed = 50,
                reason = "1234567890",
                actorId = "a",
                role = "OWNER",
            ),
        )
        insertOverride(
            db,
            OverrideCase(
                seed = 51,
                reason = "r".repeat(500),
                actorId = "a".repeat(128),
                role = "MANAGER",
            ),
        )

        // La identidad de proveedor también puede venir del RUC congelado en ambos drafts. El
        // proveedor histórico pudo cambiar de RUC y el comprobante nuevo resolver otra fila.
        db.execSQL(
            "UPDATE `suppliers` SET `ruc` = '$UPDATED_SUPPLIER_RUC' " +
                "WHERE `supplierId` = '$SUPPLIER_ID'",
        )
        db.execSQL(
            "INSERT INTO `suppliers` " +
                "(`supplierId`,`businessId`,`legalName`,`createdAt`,`updatedAt`,`ruc`," +
                "`tradeName`,`status`) VALUES ('$SECOND_SUPPLIER_ID','$BUSINESS_ID'," +
                "'Proveedor actual',1000,1000,'$SUPPLIER_RUC',NULL,'ACTIVE')",
        )
        insertOverride(
            db,
            OverrideCase(
                seed = 52,
                supplierId = SECOND_SUPPLIER_ID,
                supplierRuc = SUPPLIER_RUC,
            ),
        )

        assertSingleLong(
            db,
            "SELECT COUNT(*) FROM `purchases` WHERE " +
                "`duplicateOverrideOfPurchaseId` = '$TARGET_PURCHASE_ID'",
            3L,
        )
        assertSingleText(
            db,
            "SELECT `documentIdentitySlot` FROM `purchases` " +
                "WHERE `purchaseId` = '${purchaseId(50)}'",
            draftId(50),
        )
        assertNoForeignKeyViolations(db)

        helper.closeWhenFinished(db)
    }

    @Test
    fun overrideAuthorizationCannotChangeAfterPurchaseIsPosted() {
        createV13Database(IMMUTABILITY_DB, targetStatus = "POSTED")
        val db = migrate(IMMUTABILITY_DB)
        insertOverride(db, OverrideCase(seed = 60))

        // Posting completeness belongs to separate tests. Removing only that guard lets this
        // test put a valid override into the terminal lifecycle state without fabricating an
        // inventory graph; all status and document-identity guards remain active.
        db.execSQL("DROP TRIGGER `purchases_require_complete_posting`")
        db.execSQL(
            "UPDATE `purchases` SET `status` = 'POSTED', `postedAt` = 2000, " +
                "`updatedAt` = 2000 WHERE `purchaseId` = '${purchaseId(60)}'",
        )
        installPostingPersistenceInvariants(db)

        val immutableTriggerSql = singleText(
            db,
            "SELECT `sql` FROM `sqlite_master` WHERE `type` = 'trigger' AND " +
                "`name` = 'purchases_block_posted_identity_update'",
        )
        listOf(
            "documentIdentitySlot",
            "duplicateOverrideOfPurchaseId",
            "duplicateOverrideReason",
            "duplicateOverrideActorId",
            "duplicateOverrideRole",
        ).forEach { field ->
            assertTrue("$field must be immutable after posting", immutableTriggerSql.contains(field))
        }

        assertConstraint {
            db.execSQL(
                "UPDATE `purchases` SET `duplicateOverrideReason` = " +
                    "'Otro motivo autorizado' WHERE `purchaseId` = '${purchaseId(60)}'",
            )
        }
        assertConstraint {
            db.execSQL(
                "UPDATE `purchases` SET `duplicateOverrideActorId` = 'manager-local', " +
                    "`duplicateOverrideRole` = 'MANAGER' " +
                    "WHERE `purchaseId` = '${purchaseId(60)}'",
            )
        }
        assertSingleText(
            db,
            "SELECT `duplicateOverrideReason` FROM `purchases` " +
                "WHERE `purchaseId` = '${purchaseId(60)}'",
            VALID_REASON,
        )
        assertSingleText(
            db,
            "SELECT `duplicateOverrideRole` FROM `purchases` " +
                "WHERE `purchaseId` = '${purchaseId(60)}'",
            "OWNER",
        )

        helper.closeWhenFinished(db)
    }

    private fun createV13Database(
        databaseName: String,
        targetStatus: String,
        linkTargetDraft: Boolean = false,
    ) {
        helper.createDatabase(databaseName, 13).apply {
            execSQL(
                "INSERT INTO `businesses` " +
                    "(`businessId`,`legalName`,`createdAt`,`updatedAt`,`ruc`,`tradeName`,`status`) " +
                    "VALUES ('$BUSINESS_ID','Negocio',1000,1000,'20123456789',NULL,'ACTIVE')",
            )
            execSQL(
                "INSERT INTO `suppliers` " +
                    "(`supplierId`,`businessId`,`legalName`,`createdAt`,`updatedAt`,`ruc`," +
                    "`tradeName`,`status`) VALUES ('$SUPPLIER_ID','$BUSINESS_ID'," +
                    "'Proveedor',1000,1000,'20987654321',NULL,'ACTIVE')",
            )
            insertDraft(this, TARGET_DRAFT_ID, supplierRuc = SUPPLIER_RUC)
            val voidedAt = if (targetStatus == "VOIDED") "3000" else "NULL"
            val updatedAt = if (targetStatus == "VOIDED") 3000 else 2000
            execSQL(
                "INSERT INTO `purchases` (" + PURCHASE_COLUMNS_V13 + ") VALUES (" +
                    "'$TARGET_PURCHASE_ID','$BUSINESS_ID','$TARGET_DRAFT_ID','$SUPPLIER_ID'," +
                    "'INVOICE','F001','42','2026-08-13','PEN',1000,180,0,1180," +
                    "'$targetStatus','target-idempotency',1000,$updatedAt,2000,$voidedAt)",
            )
            if (linkTargetDraft) {
                execSQL(
                    "INSERT INTO `prepared_purchases` " +
                        "(`draftId`,`logicalHash`,`payloadCodecVersion`,`payloadSha256`," +
                        "`payload`,`preparedAt`) VALUES (" +
                        "'$TARGET_DRAFT_ID','${"a".repeat(64)}',2,'${"b".repeat(64)}'," +
                        "X'00',1000)",
                )
                execSQL(
                    "UPDATE `invoice_drafts` SET `status` = 'READY_TO_POST', " +
                        "`confirmedPurchaseId` = '$TARGET_PURCHASE_ID' " +
                        "WHERE `draftId` = '$TARGET_DRAFT_ID'",
                )
            }
            close()
        }
    }

    private fun migrate(databaseName: String): SupportSQLiteDatabase =
        helper.runMigrationsAndValidate(
            databaseName,
            14,
            true,
            FacturaStockDatabase.MIGRATION_13_14,
        )

    private fun insertOverride(db: SupportSQLiteDatabase, value: OverrideCase) {
        insertPurchase(
            db = db,
            seed = value.seed,
            slot = value.slot ?: draftId(value.seed),
            targetId = value.targetId,
            reason = value.reason,
            actorId = value.actorId,
            role = value.role,
            documentSeries = value.documentSeries,
            documentNumber = value.documentNumber,
            supplierId = value.supplierId,
            supplierRuc = value.supplierRuc,
        )
    }

    private fun insertPurchase(
        db: SupportSQLiteDatabase,
        seed: Int,
        slot: String = "PRIMARY",
        targetId: String? = null,
        reason: String? = null,
        actorId: String? = null,
        role: String? = null,
        documentSeries: String = "F001",
        documentNumber: String = "42",
        supplierId: String = SUPPLIER_ID,
        supplierRuc: String? = null,
    ) {
        val sourceDraftId = draftId(seed)
        insertDraft(db, sourceDraftId, supplierRuc)
        db.execSQL(
            "INSERT INTO `purchases` (" + PURCHASE_COLUMNS_V14 + ") " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf<Any?>(
                purchaseId(seed),
                BUSINESS_ID,
                sourceDraftId,
                supplierId,
                "INVOICE",
                documentSeries,
                documentNumber,
                "2026-08-13",
                "PEN",
                1_000L,
                180L,
                0L,
                1_180L,
                "DRAFT",
                "purchase-idempotency-$seed",
                1_000L,
                1_000L,
                null,
                null,
                slot,
                targetId,
                reason,
                actorId,
                role,
            ),
        )
    }

    private fun insertDraft(
        db: SupportSQLiteDatabase,
        id: String,
        supplierRuc: String? = null,
    ) {
        db.execSQL(
            "INSERT INTO `invoice_drafts` " +
                "(`draftId`,`businessId`,`createdAt`,`updatedAt`,`status`," +
                "`supplierRucNormalized`) VALUES (?,?,?,?,?,?)",
            arrayOf<Any?>(id, BUSINESS_ID, 1_000L, 1_000L, "NEEDS_REVIEW", supplierRuc),
        )
    }

    private fun indexColumns(db: SupportSQLiteDatabase, indexName: String): List<String> {
        val result = mutableListOf<String>()
        db.query("PRAGMA index_info('$indexName')").use { cursor ->
            while (cursor.moveToNext()) result += cursor.getString(2)!!
        }
        return result
    }

    private fun indexCountQuery(indexName: String): String =
        "SELECT COUNT(*) FROM `sqlite_master` WHERE `type` = 'index' AND `name` = '$indexName'"

    private fun assertNoForeignKeyViolations(db: SupportSQLiteDatabase) {
        db.query("PRAGMA foreign_key_check").use { cursor ->
            assertFalse("Foreign-key violations remain after migration", cursor.moveToFirst())
        }
    }

    private fun assertSingleLong(db: SupportSQLiteDatabase, query: String, expected: Long) {
        db.query(query).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(expected, cursor.getLong(0))
            assertFalse(cursor.moveToNext())
        }
    }

    private fun assertSingleText(db: SupportSQLiteDatabase, query: String, expected: String) {
        assertEquals(expected, singleText(db, query))
    }

    private fun singleText(db: SupportSQLiteDatabase, query: String): String =
        db.query(query).use { cursor ->
            assertTrue(cursor.moveToFirst())
            val value = cursor.getString(0)!!
            assertFalse(cursor.moveToNext())
            value
        }

    private fun assertConstraint(block: () -> Unit) {
        assertThrows(SQLiteConstraintException::class.java) { block() }
    }

    private fun purchaseId(seed: Int): String = "10000000-0000-0000-0000-%012d".format(seed)

    private fun draftId(seed: Int): String = "20000000-0000-0000-0000-%012d".format(seed)

    private fun uuid(seed: Int): String = "30000000-0000-0000-0000-%012d".format(seed)

    private data class OverrideCase(
        val seed: Int,
        val slot: String? = null,
        val targetId: String? = TARGET_PURCHASE_ID,
        val reason: String? = VALID_REASON,
        val actorId: String? = "owner-local",
        val role: String? = "OWNER",
        val documentSeries: String = "F001",
        val documentNumber: String = "42",
        val supplierId: String = SUPPLIER_ID,
        val supplierRuc: String? = null,
    )

    private companion object {
        const val PRESERVATION_DB = "purchase-duplicate-override-preservation.db"
        const val COMMITTED_DRAFT_DB = "purchase-v14-committed-draft.db"
        const val VALIDATION_DB = "purchase-duplicate-override-validation.db"
        const val IMMUTABILITY_DB = "purchase-duplicate-override-immutability.db"

        const val BUSINESS_ID = "11111111-1111-1111-1111-111111111111"
        const val SUPPLIER_ID = "22222222-2222-2222-2222-222222222222"
        const val SECOND_SUPPLIER_ID = "22222222-2222-2222-2222-222222222223"
        const val SUPPLIER_RUC = "20987654321"
        const val UPDATED_SUPPLIER_RUC = "20111111111"
        const val TARGET_DRAFT_ID = "33333333-3333-3333-3333-333333333333"
        const val TARGET_PURCHASE_ID = "44444444-4444-4444-4444-444444444444"
        const val VALID_REASON = "Duplicado exacto autorizado"

        const val LEGACY_FISCAL_IDENTITY_INDEX =
            "index_purchases_businessId_supplierId_documentType_documentSeries_documentNumber"
        const val FISCAL_IDENTITY_INDEX =
            "index_purchases_businessId_supplierId_documentType_documentSeries_" +
                "documentNumber_documentIdentitySlot"
        const val TARGET_INDEX = "index_purchases_duplicateOverrideOfPurchaseId"

        const val PURCHASE_COLUMNS_V13 =
            "`purchaseId`,`businessId`,`sourceDraftId`,`supplierId`,`documentType`," +
                "`documentSeries`,`documentNumber`,`issueDate`,`currencyCode`," +
                "`subtotalMinorUnits`,`taxMinorUnits`,`otherChargesMinorUnits`," +
                "`totalMinorUnits`,`status`,`idempotencyKey`,`createdAt`,`updatedAt`," +
                "`postedAt`,`voidedAt`"
        const val PURCHASE_COLUMNS_V14 =
            PURCHASE_COLUMNS_V13 + ",`documentIdentitySlot`,`duplicateOverrideOfPurchaseId`," +
                "`duplicateOverrideReason`,`duplicateOverrideActorId`,`duplicateOverrideRole`"
    }
}
