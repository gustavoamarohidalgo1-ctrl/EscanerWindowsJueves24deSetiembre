# Evidencia — Deudores, ventas a crédito y sincronización

Fecha: **31 de agosto de 2026** (`America/Lima`). La corrida se realizó desde la raíz local del
proyecto con datos sintéticos y sin credenciales productivas.

## Resultado

La ampliación incorpora ventas a crédito ligadas a una persona, detalle inmutable de los productos
entregados, saldo pendiente e historial append-only de pagos parciales o totales. El alta del
producto se puede hacer con búsqueda manual por nombre o con un lector físico USB/Bluetooth que
Android exponga como teclado HID. No se añadió escaneo por cámara.

En un negocio enlazado, la venta, la deuda y cada pago usan escritura remota idempotente y un feed
ordenado para que otro Android materialice exactamente el mismo estado. La aplicación rechaza pagos
con una versión obsoleta y acepta replays históricos solo cuando puede demostrar que el estado local
es un descendiente íntegro del remoto.

| Control | Resultado |
| --- | --- |
| JVM local | `:app:testLocalDebugUnitTest`: **1.367/1.367**, 0 fallos, errores u omitidas |
| JVM cloud | `:app:testCloudDebugUnitTest`: **1.499/1.499**, 0 fallos, errores u omitidas |
| Android instrumentado | `compileLocalDebugAndroidTestKotlin` y `compileCloudDebugAndroidTestKotlin`: **BUILD SUCCESSFUL** |
| Firebase | Emulator Suite Auth/Firestore/Functions/Storage: **160/160** tests top-level, exit 0 |
| Formato y gates | `spotlessCheck` y gates de dominio, seguridad, offline, Room y UI: **BUILD SUCCESSFUL** |
| Backend Node | `node --check functions/saleSync.js` e `index.js`: exit 0 |
| APK local debug | 68.724.373 bytes; SHA-256 `ab910cce33d0a848d399a55e0453beb8e2f9ffa7f2cf1c2e0be0e7a187afae59` |
| APK cloud debug | 73.808.370 bytes; SHA-256 `9512ec23ac262d32130e9a25cc456e83b099023e5e7f439d48a0eeeb2c1f9f20` |

El puerto Firestore predeterminado `8080` estaba ocupado por un servidor ajeno al proyecto. La
suite final usó temporalmente `18080`; no se detuvo ni modificó ese proceso. Firebase mostró la
advertencia conocida de que el host tiene Node 24 aunque Functions declara Node 22.

## Reglas acreditadas

- Una venta a crédito publica venta, descuenta inventario y crea la deuda en una sola operación
  local; en cloud, el servidor conserva el mismo hecho de forma atómica e idempotente.
- El nombre se normaliza y valida; una venta a crédito sin persona válida no se confirma.
- La deuda referencia la venta publicada y sus líneas exactas; no duplica ni permite reescribir el
  hecho comercial.
- Cada pago conserva monto, medio, referencia/nota opcionales y saldo posterior. Dos teléfonos que
  intentan cobrar sobre la misma versión producen un solo avance válido.
- El feed soporta una venta a crédito seguida de varios pagos antes de que el otro dispositivo tire
  de los cambios, sin restaurar saldos antiguos ni duplicar pagos.
- Room v27 añade `debts` y `debt_payments`; la migración v26→v27 es no destructiva. La restauración
  sigue aceptando imágenes v24-v26 sin exigirles tablas que aún no existían y exige ambas tablas en
  v27.
- `ACCOUNTING_LEDGER` v4 declara explícitamente que ventas, deudas y pagos no pertenecen a esa
  exportación contable parcial; el snapshot integral sí los cubre.

## Límites de esta corrida

1. `adb devices` no mostró dispositivo ni emulador Android conectado. Las pruebas instrumentadas
   nuevas compilaron, pero no se ejecutaron en esta sesión.
2. No se conectó un lector físico real. El contrato implementado es teclado HID; compatibilidad de
   marca, terminador y adaptador OTG requiere una prueba física.
3. El APK `cloudDebug` apunta a Firebase Emulator Suite (`10.0.2.2`) y no es un APK productivo para
   dos teléfonos. No hay `projectId`, App ID, API key, bucket, firma ni cuenta de facturación reales
   en este equipo; por ello no se desplegó ni se simuló una sincronización productiva entre dos
   Android físicos.
4. El proyecto no contiene `.git`, así que no existe un commit SHA verificable para esta entrega.

Manual funcional: [`../DEBTORS_AND_CREDIT_SALES.md`](../DEBTORS_AND_CREDIT_SALES.md).
