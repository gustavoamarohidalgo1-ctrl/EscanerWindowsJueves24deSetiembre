package com.facturastock.app.core.config

import java.util.Properties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CloudBackupConfigTest {

    @Test
    fun `propiedades completas producen configuracion real`() {
        val config = CloudBackupConfig.fromProperties(
            Properties().apply {
                setProperty("firebase.projectId", "facturastock-prod")
                setProperty("firebase.applicationId", "1:2:android:abc")
                setProperty("firebase.apiKey", "public-key")
            },
        )

        assertEquals("facturastock-prod", config?.projectId)
        assertEquals("1:2:android:abc", config?.applicationId)
        assertEquals("public-key", config?.apiKey)
    }

    @Test
    fun `configuracion parcial o vacia es ausente, nunca a medias`() {
        assertNull(CloudBackupConfig.fromProperties(Properties()))
        assertNull(
            CloudBackupConfig.fromProperties(
                Properties().apply { setProperty("firebase.projectId", "algo") },
            ),
        )
        assertNull(
            CloudBackupConfig.fromProperties(
                Properties().apply {
                    setProperty("firebase.projectId", " ")
                    setProperty("firebase.applicationId", "1:2:android:abc")
                    setProperty("firebase.apiKey", "public-key")
                },
            ),
        )
        assertNull(CloudBackupConfig.fromValues("", "1:2:android:abc", "public-key"))
    }
}
