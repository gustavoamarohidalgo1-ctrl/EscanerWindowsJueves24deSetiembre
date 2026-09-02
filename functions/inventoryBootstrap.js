// Migracion one-shot del inventario local anterior al ledger cloud autoritativo.
//
// El wire legacy de compras no incluia nombre canonico de almacen ni el total exacto de costo.
// Por eso el servidor puede demostrar la cota de cantidad (compras activas menos ventas locales
// no replicadas), pero el promedio inicial debe venir del snapshot Room de un OWNER/ADMIN con
// autenticacion reciente. Una vez sembrado, todas las mutaciones usan el ledger v4 atomico.
import { onCall, HttpsError } from "firebase-functions/v2/https";
import { FieldValue } from "firebase-admin/firestore";
import {
  CALLABLE_OPTIONS,
  OWNER_ADMIN,
  UUID_REGEX,
  accountDeletionTombstoneRef,
  db,
  invalid,
  requireAccountNotDeleting,
  requireBusinessId,
  requireBusinessNotDeleting,
  requireExpectedUid,
  requireMemberRole,
  requireRecentAuth,
  requireVerifiedEmail,
  sha256,
} from "./common.js";
import {
  buildBootstrappedBalance,
  canonicalizeLocationName,
  compareInventoryDecimalTexts,
  sumInventoryDecimalTexts,
} from "./inventorySync.js";

const OPTIONS = Object.freeze({
  ...CALLABLE_OPTIONS,
  maxInstances: 3,
  concurrency: 2,
  timeoutSeconds: 120,
  memory: "512MiB",
});
const REQUEST_KEYS = new Set([
  "businessId", "expectedUid", "idempotencyKey", "payloadVersion", "locations", "balances",
]);
const OPTIONAL_REQUEST_KEYS = new Set(["expectedUid"]);
const LOCATION_KEYS = new Set(["sourceLocationId", "locationName"]);
const BALANCE_KEYS = new Set([
  "productId", "locationName", "quantityOnHand", "averageUnitCost", "currency",
]);
const NON_NEGATIVE_DECIMAL = /^(0|[1-9]\d{0,37})(\.\d{1,18})?$/;
const ISO_CURRENCY = /^[A-Z]{3}$/;
const MAX_LOCATIONS = 200;
const MAX_BALANCES = 200;
const MAX_LEGACY_PURCHASES = 200;
const MAX_REQUEST_BYTES = 512_000;
const BOOTSTRAP_SEQ = 1;

function exactObject(value, keys, code, optionalKeys = null) {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw invalid(code);
  const actual = Object.keys(value);
  if (
    actual.some((key) => !keys.has(key)) ||
    [...keys].some((key) => !optionalKeys?.has(key) && !Object.hasOwn(value, key))
  ) {
    throw invalid(code);
  }
  return value;
}

function normalizedLocationName(value, code) {
  if (typeof value !== "string" || value.length === 0 || value.length > 100 ||
      value !== value.trim() || /[\u0000-\u001f\u007f]/u.test(value)) {
    throw invalid(code);
  }
  try {
    return canonicalizeLocationName(value, code);
  } catch (failure) {
    if (failure instanceof HttpsError) throw invalid(code);
    throw failure;
  }
}

function canonicalDecimal(value, code) {
  if (typeof value !== "string" || !NON_NEGATIVE_DECIMAL.test(value)) throw invalid(code);
  try {
    return sumInventoryDecimalTexts([value], code);
  } catch (failure) {
    if (failure instanceof HttpsError) throw invalid(code);
    throw failure;
  }
}

function canonicalCurrency(value) {
  if (typeof value !== "string" || !ISO_CURRENCY.test(value) ||
      !Intl.supportedValuesOf("currency").includes(value)) {
    throw invalid("INVENTORY_BOOTSTRAP_CURRENCY");
  }
  return value;
}

function stockKey(productId, canonicalLocationName) {
  return `${productId}\u001f${canonicalLocationName}`;
}

function canonicalRequest(raw) {
  exactObject(raw, REQUEST_KEYS, "INVENTORY_BOOTSTRAP_REQUEST_FIELDS", OPTIONAL_REQUEST_KEYS);
  if (Buffer.byteLength(JSON.stringify(raw), "utf8") > MAX_REQUEST_BYTES) {
    throw invalid("INVENTORY_BOOTSTRAP_REQUEST_TOO_LARGE");
  }
  const businessId = requireBusinessId(raw.businessId);
  if (raw.idempotencyKey !== `inventory-bootstrap:v1:${businessId}`) {
    throw invalid("INVENTORY_BOOTSTRAP_IDEMPOTENCY_KEY");
  }
  if (raw.payloadVersion !== 1) throw invalid("INVENTORY_BOOTSTRAP_PAYLOAD_VERSION");
  if (!Array.isArray(raw.locations) || raw.locations.length > MAX_LOCATIONS) {
    throw invalid("INVENTORY_BOOTSTRAP_LOCATIONS_LIMIT");
  }
  if (!Array.isArray(raw.balances) || raw.balances.length < 1 ||
      raw.balances.length > MAX_BALANCES) {
    throw invalid("INVENTORY_BOOTSTRAP_BALANCES_LIMIT");
  }

  const locations = raw.locations.map((entry) => {
    exactObject(entry, LOCATION_KEYS, "INVENTORY_BOOTSTRAP_LOCATION_FIELDS");
    if (!UUID_REGEX.test(entry.sourceLocationId ?? "")) {
      throw invalid("INVENTORY_BOOTSTRAP_SOURCE_LOCATION_ID");
    }
    const location = normalizedLocationName(
      entry.locationName,
      "INVENTORY_BOOTSTRAP_LOCATION_NAME",
    );
    return {
      sourceLocationId: entry.sourceLocationId,
      locationName: location.display,
      canonicalLocationName: location.canonical,
    };
  }).sort((left, right) => left.sourceLocationId.localeCompare(right.sourceLocationId));
  if (new Set(locations.map((location) => location.sourceLocationId)).size !== locations.length) {
    throw invalid("INVENTORY_BOOTSTRAP_LOCATION_DUPLICATED");
  }

  const balances = raw.balances.map((entry) => {
    exactObject(entry, BALANCE_KEYS, "INVENTORY_BOOTSTRAP_BALANCE_FIELDS");
    if (!UUID_REGEX.test(entry.productId ?? "")) {
      throw invalid("INVENTORY_BOOTSTRAP_PRODUCT_ID");
    }
    const location = normalizedLocationName(
      entry.locationName,
      "INVENTORY_BOOTSTRAP_LOCATION_NAME",
    );
    return {
      productId: entry.productId,
      locationName: location.display,
      canonicalLocationName: location.canonical,
      quantityOnHand: canonicalDecimal(
        entry.quantityOnHand,
        "INVENTORY_BOOTSTRAP_QUANTITY",
      ),
      averageUnitCost: canonicalDecimal(
        entry.averageUnitCost,
        "INVENTORY_BOOTSTRAP_AVERAGE_COST",
      ),
      currency: canonicalCurrency(entry.currency),
    };
  }).sort((left, right) =>
    left.productId.localeCompare(right.productId) ||
    left.canonicalLocationName.localeCompare(right.canonicalLocationName));
  if (new Set(balances.map((balance) =>
    stockKey(balance.productId, balance.canonicalLocationName))).size !== balances.length) {
    throw invalid("INVENTORY_BOOTSTRAP_BALANCE_DUPLICATED");
  }
  return { businessId, idempotencyKey: raw.idempotencyKey, locations, balances };
}

function receiptFor(businessId) {
  return `inventory_bootstrap_${sha256(`facturastock:inventory-bootstrap:v1:${businessId}`)
    .slice(0, 32)}`;
}

function requireLegacyPurchase(document) {
  const purchase = document.data();
  if (
    ![1, 2].includes(purchase?.version) ||
    !["POSTED", "VOIDED"].includes(purchase.status) ||
    !Number.isSafeInteger(purchase.seq) || purchase.seq < 1 ||
    typeof purchase.currency !== "string" || !ISO_CURRENCY.test(purchase.currency) ||
    !Intl.supportedValuesOf("currency").includes(purchase.currency) ||
    !Array.isArray(purchase.movements)
  ) {
    throw new HttpsError("data-loss", "INVENTORY_BOOTSTRAP_LEGACY_LEDGER_INVALID", {
      purchaseId: document.id,
    });
  }
  return purchase;
}

function reconstructLegacyLedger(documents, locations) {
  const locationById = new Map(
    locations.map((location) => [location.sourceLocationId, location]),
  );
  const groups = new Map();
  const digestRows = [];
  for (const document of documents) {
    const purchase = requireLegacyPurchase(document);
    for (const movement of purchase.movements) {
      if (
        !movement || typeof movement !== "object" ||
        !UUID_REGEX.test(movement.productId ?? "") ||
        !UUID_REGEX.test(movement.locationId ?? "") ||
        movement.type !== "PURCHASE" ||
        typeof movement.quantityDelta !== "string"
      ) {
        throw new HttpsError("data-loss", "INVENTORY_BOOTSTRAP_LEGACY_MOVEMENT_INVALID", {
          purchaseId: document.id,
        });
      }
      let canonicalQuantity;
      try {
        canonicalQuantity = sumInventoryDecimalTexts(
          [movement.quantityDelta],
          "INVENTORY_BOOTSTRAP_LEGACY_MOVEMENT_INVALID",
        );
      } catch (failure) {
        if (failure instanceof HttpsError) {
          throw new HttpsError("data-loss", "INVENTORY_BOOTSTRAP_LEGACY_MOVEMENT_INVALID", {
            purchaseId: document.id,
          });
        }
        throw failure;
      }
      if (canonicalQuantity === "0") {
        throw new HttpsError("data-loss", "INVENTORY_BOOTSTRAP_LEGACY_MOVEMENT_INVALID", {
          purchaseId: document.id,
        });
      }
      digestRows.push([
        purchase.seq,
        document.id,
        purchase.status,
        purchase.currency,
        movement.productId,
        movement.locationId,
        canonicalQuantity,
      ]);
      if (purchase.status === "VOIDED") continue;
      const location = locationById.get(movement.locationId);
      if (!location) {
        throw new HttpsError("failed-precondition", "INVENTORY_BOOTSTRAP_LOCATION_MAPPING_REQUIRED", {
          sourceLocationId: movement.locationId,
        });
      }
      const key = stockKey(movement.productId, location.canonicalLocationName);
      const current = groups.get(key) ?? {
        productId: movement.productId,
        locationName: location.locationName,
        canonicalLocationName: location.canonicalLocationName,
        currency: purchase.currency,
        purchaseQuantities: [],
        sourceLocationIds: new Set(),
        updatedAtMillis: 0,
      };
      if (current.currency !== purchase.currency) {
        throw new HttpsError(
          "failed-precondition",
          "INVENTORY_BOOTSTRAP_CURRENCY_CONFLICT",
          { productId: movement.productId, locationName: location.locationName },
        );
      }
      current.purchaseQuantities.push(canonicalQuantity);
      current.sourceLocationIds.add(movement.locationId);
      if (Number.isSafeInteger(movement.occurredAt) && movement.occurredAt >= 0) {
        current.updatedAtMillis = Math.max(current.updatedAtMillis, movement.occurredAt);
      }
      groups.set(key, current);
    }
  }
  return {
    ledgerHash: sha256(JSON.stringify(digestRows)),
    groups: new Map([...groups].map(([key, group]) => [key, {
      ...group,
      purchaseQuantity: sumInventoryDecimalTexts(
        group.purchaseQuantities,
        "INVENTORY_BOOTSTRAP_QUANTITY_LIMIT",
      ),
      sourceLocationIds: [...group.sourceLocationIds].sort(),
    }])),
  };
}

function negative(value) {
  return value === "0" ? "0" : `-${value}`;
}

function validateBalancesAgainstLedger(balances, groups) {
  if (balances.length !== groups.size) {
    throw new HttpsError("failed-precondition", "INVENTORY_BOOTSTRAP_BALANCE_SET_MISMATCH");
  }
  return balances.map((balance) => {
    const key = stockKey(balance.productId, balance.canonicalLocationName);
    const group = groups.get(key);
    if (!group || group.currency !== balance.currency) {
      throw new HttpsError("failed-precondition", "INVENTORY_BOOTSTRAP_BALANCE_SET_MISMATCH", {
        productId: balance.productId,
        locationName: balance.locationName,
      });
    }
    const purchaseQuantity = group.purchaseQuantity;
    if (
      compareInventoryDecimalTexts(
        purchaseQuantity,
        "0",
        "INVENTORY_BOOTSTRAP_QUANTITY_LIMIT",
      ) < 0 ||
      compareInventoryDecimalTexts(
        balance.quantityOnHand,
        purchaseQuantity,
        "INVENTORY_BOOTSTRAP_QUANTITY_LIMIT",
      ) > 0
    ) {
      throw new HttpsError("failed-precondition", "INVENTORY_BOOTSTRAP_QUANTITY_EXCEEDS_LEDGER", {
        productId: balance.productId,
        locationName: balance.locationName,
        requested: balance.quantityOnHand,
        purchased: purchaseQuantity,
      });
    }
    const legacyUnreplicatedSales = sumInventoryDecimalTexts(
      [purchaseQuantity, negative(balance.quantityOnHand)],
      "INVENTORY_BOOTSTRAP_QUANTITY_LIMIT",
    );
    return { balance, group, legacyUnreplicatedSales };
  });
}

function requireProducts(productSnapshots, productIds) {
  productSnapshots.forEach((snapshot, index) => {
    if (!snapshot.exists || snapshot.data()?.entityType !== "PRODUCT" ||
        !snapshot.data()?.snapshot || typeof snapshot.data().snapshot !== "object") {
      throw new HttpsError("failed-precondition", "INVENTORY_BOOTSTRAP_PRODUCT_NOT_SYNCED", {
        productId: productIds[index],
      });
    }
  });
}

export const bootstrapInventoryBalances = onCall(OPTIONS, async (request) => {
  requireExpectedUid(request);
  requireVerifiedEmail(request);
  const canonical = canonicalRequest(request.data ?? {});
  const requestHash = sha256(JSON.stringify(canonical));
  const uid = request.auth.uid;
  const { businessId, idempotencyKey } = canonical;
  const businessRef = db.doc(`businesses/${businessId}`);
  const bootstrapRef = businessRef.collection("sync").doc("inventoryBootstrap");
  const metadataRef = businessRef.collection("sync").doc("inventoryMetadata");
  const receiptId = receiptFor(businessId);
  const changeRef = businessRef.collection("inventorySyncChanges").doc(receiptId);

  return db.runTransaction(async (tx) => {
    const [business, tombstone, member, bootstrap, metadata, change] = await tx.getAll(
      businessRef,
      accountDeletionTombstoneRef(uid),
      businessRef.collection("members").doc(uid),
      bootstrapRef,
      metadataRef,
      changeRef,
    );
    requireAccountNotDeleting(tombstone);
    requireBusinessNotDeleting(business);
    if (!member.exists) throw new HttpsError("permission-denied", "NOT_A_MEMBER");
    const authorizedRole = requireMemberRole(member, OWNER_ADMIN);
    if (bootstrap.exists) {
      const saved = bootstrap.data();
      if (
        saved.requestHash !== requestHash || saved.receiptId !== receiptId ||
        saved.seq !== BOOTSTRAP_SEQ || !Array.isArray(saved.balances) ||
        metadata.data()?.bootstrapComplete !== true ||
        !Number.isSafeInteger(metadata.data()?.seq) || metadata.data().seq < BOOTSTRAP_SEQ ||
        !change.exists || change.data()?.seq !== BOOTSTRAP_SEQ
      ) {
        throw new HttpsError("failed-precondition", "INVENTORY_BOOTSTRAP_REPLAY_MISMATCH");
      }
      return {
        receiptId,
        idempotencyKey,
        status: "ALREADY_BOOTSTRAPPED",
        seq: BOOTSTRAP_SEQ,
        balances: saved.balances,
      };
    }

    // Solo el primer commit irreversible exige login reciente. Un ACK perdido puede repetirse
    // exactamente sin convertir la idempotencia en una ventana de cinco minutos.
    requireRecentAuth(request);
    if (metadata.exists || change.exists) {
      throw new HttpsError("failed-precondition", "INVENTORY_ALREADY_INITIALIZED");
    }
    const [existingBalances, existingChanges, existingSales, purchases] = await Promise.all([
      tx.get(businessRef.collection("inventoryBalances").limit(1)),
      tx.get(businessRef.collection("inventorySyncChanges").limit(1)),
      tx.get(businessRef.collection("sales").limit(1)),
      tx.get(
        businessRef.collection("purchases")
          .select("version", "status", "seq", "currency", "movements")
          .limit(MAX_LEGACY_PURCHASES + 1),
      ),
    ]);
    if (!existingBalances.empty || !existingChanges.empty || !existingSales.empty) {
      throw new HttpsError("failed-precondition", "INVENTORY_ALREADY_INITIALIZED");
    }
    if (purchases.empty) {
      throw new HttpsError("failed-precondition", "INVENTORY_BOOTSTRAP_NOT_REQUIRED");
    }
    if (purchases.size > MAX_LEGACY_PURCHASES) {
      throw new HttpsError("resource-exhausted", "INVENTORY_BOOTSTRAP_TOO_LARGE", {
        maxLegacyPurchases: MAX_LEGACY_PURCHASES,
      });
    }
    const ledger = reconstructLegacyLedger(purchases.docs, canonical.locations);
    if (ledger.groups.size === 0) {
      throw new HttpsError("failed-precondition", "INVENTORY_BOOTSTRAP_NOT_REQUIRED");
    }
    const validated = validateBalancesAgainstLedger(canonical.balances, ledger.groups);
    const productIds = [...new Set(canonical.balances.map((balance) => balance.productId))].sort();
    const productSnapshots = productIds.length === 0 ? [] : await tx.getAll(
      ...productIds.map((productId) => businessRef.collection("products").doc(productId)),
    );
    requireProducts(productSnapshots, productIds);

    const updates = validated.map(({ balance, group, legacyUnreplicatedSales }) =>
      buildBootstrappedBalance({
        businessRef,
        balance,
        sourceLocationIds: group.sourceLocationIds,
        seq: BOOTSTRAP_SEQ,
        updatedAtMillis: group.updatedAtMillis,
        legacyUnreplicatedSales,
      }));
    const balances = updates.map((update) => update.projection);
    for (const update of updates) tx.create(update.ref, update.record);
    tx.create(changeRef, {
      schemaVersion: 1,
      // El mapper existente interpreta PURCHASE como una mutacion de saldo sin venta. Este
      // primer cambio representa la base legacy completa, no una compra documental nueva.
      kind: "PURCHASE",
      seq: BOOTSTRAP_SEQ,
      receiptId,
      sale: null,
      balances,
      bootstrap: true,
      syncedAt: FieldValue.serverTimestamp(),
    });
    tx.create(bootstrapRef, {
      schemaVersion: 1,
      requestHash,
      ledgerHash: ledger.ledgerHash,
      locations: canonical.locations.map(({ sourceLocationId, locationName }) => ({
        sourceLocationId,
        locationName,
      })),
      receiptId,
      seq: BOOTSTRAP_SEQ,
      balances,
      authorizedRole,
      syncedAt: FieldValue.serverTimestamp(),
    });
    tx.create(businessRef.collection("auditEvents").doc("inventory-bootstrap-audit"), {
      auditEventId: "inventory-bootstrap-audit",
      eventType: "INVENTORY_LEGACY_BOOTSTRAPPED",
      entityType: "INVENTORY",
      entityId: businessId,
      authorizedRole,
      ledgerHash: ledger.ledgerHash,
      legacyUnreplicatedSales: validated.map(({ balance, legacyUnreplicatedSales }) => ({
        productId: balance.productId,
        locationName: balance.locationName,
        quantity: legacyUnreplicatedSales,
      })),
      syncedAt: FieldValue.serverTimestamp(),
    });
    tx.create(metadataRef, {
      seq: BOOTSTRAP_SEQ,
      bootstrapComplete: true,
      bootstrapLedgerHash: ledger.ledgerHash,
      lastKind: "PURCHASE",
      lastReceiptId: receiptId,
      updatedAt: FieldValue.serverTimestamp(),
    });
    return {
      receiptId,
      idempotencyKey,
      status: "BOOTSTRAPPED",
      seq: BOOTSTRAP_SEQ,
      balances,
    };
  });
});
