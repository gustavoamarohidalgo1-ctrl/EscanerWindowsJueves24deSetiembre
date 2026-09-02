// Proyeccion cloud de inventario compartida por compras, anulaciones y ventas.
//
// La identidad de almacen NO usa el UUID local: cada dispositivo puede materializar el mismo
// almacen con un locationId distinto. El saldo se identifica por productId remoto + nombre de
// almacen canonico; los UUID locales solo se conservan en movimientos como evidencia de origen.
import { HttpsError } from "firebase-functions/v2/https";
import { sha256 } from "./common.js";

const MAX_SAFE_SEQ = Number.MAX_SAFE_INTEGER;
const MAX_DECIMAL_PRECISION = 38;
const MAX_DECIMAL_SCALE = 18;
const DECIMAL = /^-?(0|[1-9]\d{0,37})(\.\d{1,18})?$/;

function inventoryError(code, details = undefined) {
  return new HttpsError("failed-precondition", code, details);
}

export function canonicalizeLocationName(value, code = "LOCATION_NAME") {
  if (typeof value !== "string") throw inventoryError(code);
  const display = value.normalize("NFKC").trim().replace(/\s+/gu, " ");
  if (display.length === 0 || display.length > 100 || /[\u0000-\u001f\u007f]/u.test(display)) {
    throw inventoryError(code);
  }
  const canonical = display.toLocaleLowerCase("es-PE");
  return { display, canonical };
}

export function inventoryBalanceRef(businessRef, productId, canonicalLocationName) {
  const id = sha256(JSON.stringify(["inventory-balance-v1", productId, canonicalLocationName]));
  return businessRef.collection("inventoryBalances").doc(id);
}

export function parseInventoryDecimal(value, code) {
  if (typeof value !== "string" || !DECIMAL.test(value)) throw inventoryError(code);
  const negative = value.startsWith("-");
  const unsigned = negative ? value.slice(1) : value;
  const [integer, fraction = ""] = unsigned.split(".");
  const units = BigInt(`${integer}${fraction}`) * (negative ? -1n : 1n);
  return normalizeDecimal({ units, scale: fraction.length });
}

function normalizeDecimal(value) {
  let { units, scale } = value;
  while (scale > 0 && units % 10n === 0n) {
    units /= 10n;
    scale -= 1;
  }
  return { units, scale };
}

function atScale(value, scale) {
  return value.units * 10n ** BigInt(scale - value.scale);
}

function add(left, right) {
  const scale = Math.max(left.scale, right.scale);
  return normalizeDecimal({ units: atScale(left, scale) + atScale(right, scale), scale });
}

function negate(value) {
  return { units: -value.units, scale: value.scale };
}

function multiply(left, right) {
  return normalizeDecimal({ units: left.units * right.units, scale: left.scale + right.scale });
}

function compare(left, right) {
  const scale = Math.max(left.scale, right.scale);
  const leftUnits = atScale(left, scale);
  const rightUnits = atScale(right, scale);
  return leftUnits < rightUnits ? -1 : leftUnits > rightUnits ? 1 : 0;
}

function divideHalfEven(numerator, denominator, scale = MAX_DECIMAL_SCALE) {
  if (denominator.units === 0n) throw new HttpsError("data-loss", "INVENTORY_DIVIDE_BY_ZERO");
  let dividend = numerator.units;
  let divisor = denominator.units;
  const exponent = denominator.scale + scale - numerator.scale;
  if (exponent >= 0) dividend *= 10n ** BigInt(exponent);
  else divisor *= 10n ** BigInt(-exponent);

  const negative = (dividend < 0n) !== (divisor < 0n);
  const absoluteDividend = dividend < 0n ? -dividend : dividend;
  const absoluteDivisor = divisor < 0n ? -divisor : divisor;
  let quotient = absoluteDividend / absoluteDivisor;
  const remainder = absoluteDividend % absoluteDivisor;
  const doubled = remainder * 2n;
  if (doubled > absoluteDivisor || (doubled === absoluteDivisor && quotient % 2n === 1n)) {
    quotient += 1n;
  }
  return { units: negative ? -quotient : quotient, scale };
}

export function saleGrossMinorUnits(quantityText, unitPriceMinorUnits) {
  const quantity = parseInventoryDecimal(quantityText, "SALE_LINE_QUANTITY");
  if (quantity.units <= 0n || !Number.isSafeInteger(unitPriceMinorUnits) ||
      unitPriceMinorUnits <= 0) {
    throw inventoryError("SALE_LINE_PRICE_OR_QUANTITY");
  }
  const exact = multiply(quantity, { units: BigInt(unitPriceMinorUnits), scale: 0 });
  const divisor = 10n ** BigInt(exact.scale);
  let quotient = exact.units / divisor;
  const remainder = exact.units % divisor;
  // La politica Android de Money.fromMajor para ventas es HALF_UP a la unidad menor.
  if (remainder * 2n >= divisor) quotient += 1n;
  if (quotient > BigInt(Number.MAX_SAFE_INTEGER)) {
    throw new HttpsError("resource-exhausted", "SALE_LINE_GROSS_LIMIT");
  }
  return Number(quotient);
}

export function unitCostForAppliedTotal(appliedCostTotalText, quantityText) {
  const total = parseInventoryDecimal(
    appliedCostTotalText,
    "MOVEMENT_APPLIED_COST_TOTAL",
  );
  const quantity = parseInventoryDecimal(quantityText, "MOVEMENT_QUANTITY");
  if (total.units < 0n || quantity.units === 0n) {
    throw inventoryError("MOVEMENT_APPLIED_COST_TOTAL");
  }
  const absoluteQuantity = quantity.units < 0n ? negate(quantity) : quantity;
  return decimalText(
    divideHalfEven(total, absoluteQuantity),
    "MOVEMENT_APPLIED_COST_TOTAL",
  );
}

function decimalText(value, code) {
  const normalized = normalizeDecimal(value);
  const negative = normalized.units < 0n;
  const digits = (negative ? -normalized.units : normalized.units).toString();
  const precision = digits.replace(/^0+/, "").length || 1;
  if (precision > MAX_DECIMAL_PRECISION || normalized.scale > MAX_DECIMAL_SCALE) {
    throw new HttpsError("resource-exhausted", code);
  }
  if (normalized.scale === 0) return `${negative ? "-" : ""}${digits}`;
  const padded = digits.padStart(normalized.scale + 1, "0");
  const split = padded.length - normalized.scale;
  return `${negative ? "-" : ""}${padded.slice(0, split)}.${padded.slice(split)}`;
}

export function sumInventoryDecimalTexts(values, code) {
  return decimalText(
    values.reduce(
      (total, value) => add(total, parseInventoryDecimal(value, code)),
      { units: 0n, scale: 0 },
    ),
    code,
  );
}

export function inventoryDecimalEquals(left, right, code) {
  return compare(
    parseInventoryDecimal(left, code),
    parseInventoryDecimal(right, code),
  ) === 0;
}

export function compareInventoryDecimalTexts(left, right, code) {
  return compare(
    parseInventoryDecimal(left, code),
    parseInventoryDecimal(right, code),
  );
}

function stockKey(productId, canonicalLocationName) {
  return `${productId}\u001f${canonicalLocationName}`;
}

function groupEffects(items, mapper) {
  const grouped = new Map();
  for (const item of items) {
    const effect = mapper(item);
    const key = stockKey(effect.productId, effect.canonicalLocationName);
    const current = grouped.get(key) ?? {
      productId: effect.productId,
      locationName: effect.locationName,
      canonicalLocationName: effect.canonicalLocationName,
      quantityDelta: { units: 0n, scale: 0 },
      incomingValue: { units: 0n, scale: 0 },
      sourceLocationIds: new Set(),
    };
    current.quantityDelta = add(current.quantityDelta, effect.quantityDelta);
    current.incomingValue = add(current.incomingValue, effect.incomingValue);
    if (effect.sourceLocationId !== null) current.sourceLocationIds.add(effect.sourceLocationId);
    grouped.set(key, current);
  }
  return [...grouped.values()]
    .map((effect) => ({
      ...effect,
      sourceLocationIds: [...effect.sourceLocationIds].sort(),
    }))
    .sort((left, right) =>
      left.productId.localeCompare(right.productId) ||
      left.canonicalLocationName.localeCompare(right.canonicalLocationName));
}

export function purchaseInventoryEffects(document) {
  return groupEffects(document.movements, (movement) => {
    const location = canonicalizeLocationName(movement.locationName, "MOVEMENT_LOCATION_NAME");
    const quantityDelta = parseInventoryDecimal(
      movement.quantityDelta,
      "MOVEMENT_QUANTITY",
    );
    const appliedCostTotal = parseInventoryDecimal(
      movement.appliedCostTotal,
      "MOVEMENT_APPLIED_COST_TOTAL",
    );
    return {
      productId: movement.productId,
      locationName: location.display,
      canonicalLocationName: location.canonical,
      quantityDelta,
      incomingValue: quantityDelta.units < 0n ? negate(appliedCostTotal) : appliedCostTotal,
      sourceLocationId: movement.locationId,
    };
  });
}

export function saleInventoryEffects(lines) {
  return groupEffects(lines, (line) => {
    const location = canonicalizeLocationName(line.locationName, "SALE_LINE_LOCATION_NAME");
    const requested = parseInventoryDecimal(line.quantity, "SALE_LINE_QUANTITY");
    return {
      productId: line.productId,
      locationName: location.display,
      canonicalLocationName: location.canonical,
      quantityDelta: negate(requested),
      incomingValue: { units: 0n, scale: 0 },
      sourceLocationId: line.locationId,
    };
  });
}

export function voidInventoryEffects(purchase, payload, legacyLocations = []) {
  const legacyLocationById = new Map();
  for (const entry of legacyLocations) {
    if (!entry || typeof entry.sourceLocationId !== "string") {
      throw inventoryError("INVENTORY_WIRE_MIGRATION_REQUIRED");
    }
    legacyLocationById.set(
      entry.sourceLocationId,
      canonicalizeLocationName(entry.locationName, "MOVEMENT_LOCATION_NAME"),
    );
  }
  const locationsByOrigin = new Map();
  for (const movement of purchase.movements ?? []) {
    const location = typeof movement.locationName === "string"
      ? canonicalizeLocationName(movement.locationName, "MOVEMENT_LOCATION_NAME")
      : legacyLocationById.get(movement.locationId);
    if (!location) {
      throw inventoryError("INVENTORY_WIRE_MIGRATION_REQUIRED");
    }
    locationsByOrigin.set(`${movement.productId}\u001f${movement.locationId}`, location);
  }
  return groupEffects(payload.impacts, (impact) => {
    const location = locationsByOrigin.get(`${impact.productId}\u001f${impact.locationId}`);
    if (!location) throw inventoryError("INVENTORY_WIRE_MIGRATION_REQUIRED");
    return {
      productId: impact.productId,
      locationName: location.display,
      canonicalLocationName: location.canonical,
      quantityDelta: parseInventoryDecimal(
        impact.reversalQuantity,
        "VOID_IMPACT_REVERSAL_QUANTITY",
      ),
      incomingValue: { units: 0n, scale: 0 },
      sourceLocationId: impact.locationId,
    };
  });
}

export function nextInventorySequence(metadata) {
  const current = metadata.data()?.seq ?? 0;
  if (!Number.isSafeInteger(current) || current < 0 || current >= MAX_SAFE_SEQ) {
    throw new HttpsError("resource-exhausted", "INVENTORY_SEQUENCE_EXHAUSTED");
  }
  return current + 1;
}

function requireBalance(snapshot, effect) {
  if (!snapshot.exists) return null;
  const data = snapshot.data();
  if (
    data?.schemaVersion !== 1 ||
    data.productId !== effect.productId ||
    data.canonicalLocationName !== effect.canonicalLocationName ||
    typeof data.locationName !== "string" ||
    typeof data.currency !== "string" ||
    !Number.isSafeInteger(data.version) ||
    data.version < 0 ||
    data.version >= MAX_SAFE_SEQ ||
    !Number.isSafeInteger(data.updatedAtMillis) ||
    data.updatedAtMillis < 0
  ) {
    throw new HttpsError("data-loss", "INVENTORY_BALANCE_INVALID", {
      productId: effect.productId,
      locationName: effect.locationName,
    });
  }
  const quantity = parseInventoryDecimal(data.quantityOnHand, "INVENTORY_BALANCE_INVALID");
  const average = parseInventoryDecimal(data.averageUnitCost, "INVENTORY_BALANCE_INVALID");
  if (average.units < 0n) throw new HttpsError("data-loss", "INVENTORY_BALANCE_INVALID");
  return { data, quantity, average };
}

function projection(record) {
  return {
    productId: record.productId,
    locationName: record.locationName,
    quantityOnHand: record.quantityOnHand,
    averageUnitCost: record.averageUnitCost,
    currency: record.currency,
    version: record.version,
    updatedAtMillis: record.updatedAtMillis,
    seq: record.lastSeq,
  };
}

export function buildBootstrappedBalance({
  businessRef,
  balance,
  sourceLocationIds,
  seq,
  updatedAtMillis,
  legacyUnreplicatedSales,
}) {
  const location = canonicalizeLocationName(
    balance.locationName,
    "INVENTORY_BOOTSTRAP_LOCATION_NAME",
  );
  const quantity = parseInventoryDecimal(
    balance.quantityOnHand,
    "INVENTORY_BOOTSTRAP_QUANTITY",
  );
  const average = parseInventoryDecimal(
    balance.averageUnitCost,
    "INVENTORY_BOOTSTRAP_AVERAGE_COST",
  );
  if (quantity.units < 0n || average.units < 0n) {
    throw inventoryError("INVENTORY_BOOTSTRAP_NEGATIVE_BALANCE", {
      productId: balance.productId,
      locationName: location.display,
    });
  }
  const canonicalQuantity = decimalText(quantity, "INVENTORY_BOOTSTRAP_QUANTITY");
  const canonicalAverage = decimalText(average, "INVENTORY_BOOTSTRAP_AVERAGE_COST");
  const record = {
    schemaVersion: 1,
    productId: balance.productId,
    locationName: location.display,
    canonicalLocationName: location.canonical,
    quantityOnHand: canonicalQuantity,
    averageUnitCost: canonicalAverage,
    currency: balance.currency,
    version: 0,
    lastSeq: seq,
    updatedAtMillis,
    lastSourceLocationId: sourceLocationIds.length === 1 ? sourceLocationIds[0] : null,
    migration: {
      source: "LEGACY_ADMIN_SNAPSHOT",
      legacyUnreplicatedSales,
    },
  };
  return {
    ref: inventoryBalanceRef(businessRef, balance.productId, location.canonical),
    record,
    projection: projection(record),
  };
}

function finalRecord({ current, effect, currency, seq, updatedAtMillis, mode }) {
  if (current !== null && current.data.currency !== currency) {
    throw inventoryError("INVENTORY_CURRENCY_MISMATCH", {
      productId: effect.productId,
      locationName: effect.locationName,
      expectedCurrency: current.data.currency,
    });
  }
  if (current === null && mode !== "PURCHASE_CREATE") {
    throw inventoryError("INVENTORY_BALANCE_MIGRATION_REQUIRED", {
      productId: effect.productId,
      locationName: effect.locationName,
    });
  }

  const openingQuantity = current?.quantity ?? { units: 0n, scale: 0 };
  const openingAverage = current?.average ?? { units: 0n, scale: 0 };
  const resultingQuantity = add(openingQuantity, effect.quantityDelta);
  if (mode === "SALE" && resultingQuantity.units < 0n) {
    throw inventoryError("INSUFFICIENT_STOCK", {
      productId: effect.productId,
      locationName: effect.locationName,
      requested: decimalText(negate(effect.quantityDelta), "INVENTORY_QUANTITY_LIMIT"),
      available: decimalText(openingQuantity, "INVENTORY_QUANTITY_LIMIT"),
    });
  }
  if (current === null && effect.quantityDelta.units <= 0n) {
    throw inventoryError("INVENTORY_BALANCE_MIGRATION_REQUIRED", {
      productId: effect.productId,
      locationName: effect.locationName,
    });
  }

  let resultingAverage = openingAverage;
  if (mode === "PURCHASE_CREATE" && effect.quantityDelta.units > 0n) {
    if (openingQuantity.units <= 0n) {
      resultingAverage = divideHalfEven(effect.incomingValue, effect.quantityDelta);
    } else {
      const openingValue = multiply(openingQuantity, openingAverage);
      resultingAverage = divideHalfEven(
        add(openingValue, effect.incomingValue),
        resultingQuantity,
      );
    }
  }
  if (resultingAverage.units < 0n) {
    throw new HttpsError("data-loss", "INVENTORY_AVERAGE_COST_INVALID");
  }

  const record = {
    schemaVersion: 1,
    productId: effect.productId,
    locationName: current?.data.locationName ?? effect.locationName,
    canonicalLocationName: effect.canonicalLocationName,
    quantityOnHand: decimalText(resultingQuantity, "INVENTORY_QUANTITY_LIMIT"),
    averageUnitCost: decimalText(resultingAverage, "INVENTORY_AVERAGE_COST_LIMIT"),
    currency,
    version: current === null ? 0 : current.data.version + 1,
    lastSeq: seq,
    updatedAtMillis: Math.max(current?.data.updatedAtMillis ?? 0, updatedAtMillis),
    lastSourceLocationId: effect.sourceLocationIds.length === 1
      ? effect.sourceLocationIds[0]
      : null,
  };
  return { record, projection: projection(record) };
}

export function buildInventoryUpdates({
  snapshots,
  effects,
  refs,
  currency,
  seq,
  updatedAtMillis,
  mode,
}) {
  if (snapshots.length !== effects.length || refs.length !== effects.length) {
    throw new HttpsError("internal", "INVENTORY_READ_SET_INVALID");
  }
  return effects.map((effect, index) => {
    const current = requireBalance(snapshots[index], effect);
    const final = finalRecord({ current, effect, currency, seq, updatedAtMillis, mode });
    return { ref: refs[index], effect, ...final };
  });
}

/**
 * Un saldo ausente solo puede inicializarse desde una compra v4 si no existe historia legacy de
 * ese producto. Como los movimientos antiguos no traen nombre de almacen, cualquier coincidencia
 * de producto obliga a una migracion explicita en vez de adivinar una identidad y duplicar stock.
 */
export async function requireMissingBalancesSafeToCreate(
  tx,
  businessRef,
  effects,
  balanceSnapshots,
  inventoryMetadata,
) {
  if (inventoryMetadata?.data()?.bootstrapComplete === true) return;
  const missingProducts = [...new Set(
    effects
      .filter((_effect, index) => !balanceSnapshots[index].exists)
      .map((effect) => effect.productId),
  )].sort();
  for (const productId of missingProducts) {
    const history = await tx.get(
      businessRef.collection("stockMovements").where("productId", "==", productId).limit(101),
    );
    if (history.size > 100) {
      throw inventoryError("INVENTORY_HISTORY_SCAN_LIMIT", { productId });
    }
    const missingLocations = new Set(
      effects
        .filter((effect, index) =>
          effect.productId === productId && !balanceSnapshots[index].exists)
        .map((effect) => effect.canonicalLocationName),
    );
    const legacyDocuments = history.docs.filter((document) =>
      typeof document.data()?.canonicalLocationName !== "string");
    const existingLocation = history.docs.some((document) =>
      missingLocations.has(document.data()?.canonicalLocationName));
    let activeLegacy = false;
    if (legacyDocuments.length > 0) {
      const legacyPurchaseIds = [...new Set(
        legacyDocuments.map((document) => document.data()?.purchaseId),
      )];
      if (legacyPurchaseIds.some((purchaseId) => typeof purchaseId !== "string")) {
        activeLegacy = true;
      } else {
        const purchases = await tx.getAll(...legacyPurchaseIds.map((purchaseId) =>
          businessRef.collection("purchases").doc(purchaseId)));
        activeLegacy = purchases.some((purchase) =>
          !purchase.exists || purchase.data()?.status !== "VOIDED");
      }
    }
    if (activeLegacy || existingLocation) {
      throw inventoryError("INVENTORY_BALANCE_MIGRATION_REQUIRED", { productId });
    }
  }
}

export function inventoryChangeRecord({ kind, seq, receiptId, sale, balances }) {
  return {
    schemaVersion: 1,
    kind,
    seq,
    receiptId,
    sale,
    balances,
    syncedAt: null,
  };
}
