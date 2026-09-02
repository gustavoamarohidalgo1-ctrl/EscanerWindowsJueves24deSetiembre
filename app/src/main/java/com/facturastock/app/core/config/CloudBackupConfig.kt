package com.facturastock.app.core.config

import java.util.Properties

/**
 * Configuración explícita del respaldo en la nube. Nunca contiene credenciales privadas:
 * la variante release lee valores de `local.properties` (archivo no versionado) vía
 * BuildConfig. La configuración demo vive exclusivamente en el source set `cloudDebug` y no
 * forma parte de este contrato compartido. Una configuración ausente significa transporte no
 * configurado.
 */
data class CloudBackupConfig(
    val projectId: String,
    val applicationId: String,
    val apiKey: String,
    val storageBucket: String? = null,
) {
    init {
        require(projectId.isNotBlank()) { "projectId requerido" }
        require(applicationId.isNotBlank()) { "applicationId requerido" }
        require(apiKey.isNotBlank()) { "apiKey requerido" }
        require(storageBucket == null || STORAGE_BUCKET.matches(storageBucket)) {
            "storageBucket inválido"
        }
    }

    companion object {
        const val KEY_PROJECT_ID = "firebase.projectId"
        const val KEY_APPLICATION_ID = "firebase.applicationId"
        const val KEY_API_KEY = "firebase.apiKey"
        const val KEY_STORAGE_BUCKET = "firebase.storageBucket"

        /**
         * Configuración real desde propiedades no versionadas. Devuelve `null` salvo que las
         * tres claves estén presentes y no vacías: una configuración parcial nunca produce
         * un transporte a medias.
         */
        fun fromProperties(properties: Properties): CloudBackupConfig? {
            val projectId = properties.getProperty(KEY_PROJECT_ID)?.trim().orEmpty()
            val applicationId = properties.getProperty(KEY_APPLICATION_ID)?.trim().orEmpty()
            val apiKey = properties.getProperty(KEY_API_KEY)?.trim().orEmpty()
            val storageBucket = properties.getProperty(KEY_STORAGE_BUCKET)?.trim().orEmpty()
            if (projectId.isEmpty() || applicationId.isEmpty() || apiKey.isEmpty()) return null
            return CloudBackupConfig(
                projectId = projectId,
                applicationId = applicationId,
                apiKey = apiKey,
                storageBucket = storageBucket.ifEmpty { null },
            )
        }

        /** Variante de BuildConfig: los campos vacíos significan "sin configurar". */
        fun fromValues(
            projectId: String,
            applicationId: String,
            apiKey: String,
            storageBucket: String = "",
        ): CloudBackupConfig? =
            fromProperties(
                Properties().apply {
                    setProperty(KEY_PROJECT_ID, projectId)
                    setProperty(KEY_APPLICATION_ID, applicationId)
                    setProperty(KEY_API_KEY, apiKey)
                    setProperty(KEY_STORAGE_BUCKET, storageBucket)
                },
            )

        private val STORAGE_BUCKET = Regex(
            "^[a-z0-9](?:[a-z0-9.-]{1,220}[a-z0-9])?$",
        )
    }
}
