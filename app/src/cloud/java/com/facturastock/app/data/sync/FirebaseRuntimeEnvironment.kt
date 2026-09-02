package com.facturastock.app.data.sync

import com.facturastock.app.core.config.CloudBackupConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.functions.FirebaseFunctions

/** Selecciona el contrato remoto sin filtrar detalles del build type a los repositorios. */
enum class FirebaseBackendMode {
    /** Backend endurecido que autoriza y materializa mutaciones mediante callables. */
    CALLABLES,

    /** Plan Spark: el cliente propietario usa transacciones directas protegidas por rules. */
    SPARK_DIRECT,
}

/**
 * Frontera por build type para construir Firebase y aplicar su entorno. La implementación
 * release no conoce hosts, puertos ni identificadores del Emulator Suite.
 */
interface FirebaseRuntimeEnvironment {
    val config: CloudBackupConfig?

    /** Los entornos existentes conservan el backend callable salvo override explícito. */
    val backendMode: FirebaseBackendMode
        get() = FirebaseBackendMode.CALLABLES

    /** Configura servicios del entorno después de crear [firebaseApp]. */
    fun configure(firebaseApp: FirebaseApp)

    /** Preparación opcional previa a un envío; producción no modifica membresías. */
    suspend fun prepareBusiness(functions: FirebaseFunctions, businessId: String)
}
