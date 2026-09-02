package com.facturastock.app.data.reporting

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** Gate estático del flavor cloud para evitar reintroducir reportes crudos o identificadores Ads. */
class FirebasePrivacyPolicyTest {

    @Test
    fun `cloud artifact keeps crashlytics disabled and analytics denies advertising storage`() {
        val source = projectFile(
            "app/src/cloud/java/com/facturastock/app/data/reporting/FirebaseObservabilitySink.kt",
        ).readText()
        val collectionGate = projectFile(
            "app/src/cloud/java/com/facturastock/app/data/reporting/" +
                "FirebaseObservabilityCollectionGate.kt",
        ).readText()
        val runtime = projectFile(
            "app/src/cloud/java/com/facturastock/app/data/sync/FirebaseBackupRuntime.kt",
        ).readText()
        val application = projectFile(
            "app/src/main/java/com/facturastock/app/FacturaStockApplication.kt",
        ).readText()
        val appBuild = projectFile("app/build.gradle.kts").readText()
        val catalog = projectFile("gradle/libs.versions.toml").readText()

        assertTrue(collectionGate.contains("com.google.firebase.crashlytics"))
        assertTrue(collectionGate.contains("FirebaseCrashlytics"))
        assertFalse(collectionGate.contains("setCrashlyticsCollectionEnabled(true)"))
        assertTrue(collectionGate.contains("setCrashlyticsCollectionEnabled(false)"))
        assertTrue(collectionGate.contains("deleteUnsentReports()"))
        assertFalse(source.contains("recordException("))
        assertFalse(source.contains("setCustomKey("))
        assertFalse(source.contains("crashlytics.log("))
        assertFalse(source.contains("FirebaseCrashlytics.getInstance().log("))
        assertTrue(appBuild.contains("libs.firebase.crashlytics"))
        assertTrue(catalog.contains("firebase-crashlytics"))

        // El canal operacional no abre almacenamiento identificable ni publicitario.
        assertTrue(collectionGate.contains("FirebaseAnalytics.ConsentType.ANALYTICS_STORAGE"))
        assertTrue(collectionGate.contains("FirebaseAnalytics.ConsentType.AD_STORAGE"))
        assertTrue(collectionGate.contains("FirebaseAnalytics.ConsentType.AD_USER_DATA"))
        assertTrue(collectionGate.contains("FirebaseAnalytics.ConsentType.AD_PERSONALIZATION"))
        assertTrue(collectionGate.countOccurrences("FirebaseAnalytics.ConsentStatus.DENIED") >= 4)
        assertFalse(collectionGate.contains("FirebaseAnalytics.ConsentStatus.GRANTED"))

        // Un proceso nuevo parte denegado y Runtime reaplica esa decisión bajo el mismo lock de
        // initializeApp antes de devolver Firebase a cualquier transporte.
        assertTrue(collectionGate.contains("AtomicBoolean(false)"))
        assertTrue(source.contains("runtime.updateObservabilityCollection(enabled)"))
        assertTrue(runtime.contains("observabilityCollectionGate.onFirebaseInitialized(firebaseApp)"))
        assertFalse(collectionGate.contains("runCatching"))
        assertTrue(collectionGate.contains("verifiedEnabledTarget"))
        assertTrue(runtime.contains("isEmissionAllowed(currentApp)"))
        assertTrue(runtime.contains("quarantine(firebaseApp)"))
        assertTrue(runtime.contains("firebasePrivacyRetryRequired"))
        assertFalse(runtime.contains("firebasePrivacyBlocked"))
        assertTrue(runtime.contains("!enabled && firebasePrivacyRetryRequired && ready() == null"))
        assertTrue(runtime.contains("retryFailure !== firstFailure"))
        assertTrue(runtime.contains("firstFailure.addSuppressed(retryFailure)"))
        assertTrue(
            runtime.countOccurrences(
                "observabilityCollectionGate.update(enabled = false, currentApp)",
            ) == 1,
        )
        assertTrue(source.contains("runtime.emitIfObservabilityEnabled"))
        val consentUpdate = runtime
            .substringAfter("fun updateObservabilityCollection")
            .substringBefore("/** Auth")
        assertFalse(consentUpdate.contains("FirebaseApp.getApps"))
        assertFalse(application.contains("startupObservabilitySink"))
    }

    @Test
    fun `app check providers stay isolated by cloud build type`() {
        val appBuild = projectFile("app/build.gradle.kts").readText()
        val runtime = projectFile(
            "app/src/cloud/java/com/facturastock/app/data/sync/FirebaseBackupRuntime.kt",
        ).readText()
        val debugInstaller = projectFile(
            "app/src/cloudDebug/java/com/facturastock/app/data/sync/" +
                "BuildTypeAppCheckInstaller.kt",
        ).readText()
        val releaseInstaller = projectFile(
            "app/src/cloudRelease/java/com/facturastock/app/data/sync/" +
                "BuildTypeAppCheckInstaller.kt",
        ).readText()
        val benchmarkInstaller = projectFile(
            "app/src/cloudBenchmark/java/com/facturastock/app/data/sync/" +
                "BuildTypeAppCheckInstaller.kt",
        ).readText()
        val profileInstaller = projectFile(
            "app/src/cloudProfile/java/com/facturastock/app/data/sync/" +
                "BuildTypeAppCheckInstaller.kt",
        ).readText()

        assertTrue(
            appBuild.contains(
                "cloudDebugImplementation(libs.firebase.appcheck.debug)",
            ),
        )
        assertTrue(
            appBuild.contains(
                "cloudReleaseImplementation(libs.firebase.appcheck.playintegrity)",
            ),
        )
        assertTrue(
            appBuild.contains(
                "cloudBenchmarkImplementation(libs.firebase.appcheck.playintegrity)",
            ),
        )
        assertTrue(
            appBuild.contains(
                "cloudProfileImplementation(libs.firebase.appcheck.playintegrity)",
            ),
        )
        assertFalse(appBuild.contains("\"cloudImplementation\"(libs.firebase.appcheck.debug)"))
        assertFalse(
            appBuild.contains("\"cloudImplementation\"(libs.firebase.appcheck.playintegrity)"),
        )
        assertFalse(runtime.contains("DebugAppCheckProviderFactory"))
        assertFalse(runtime.contains("PlayIntegrityAppCheckProviderFactory"))
        assertTrue(debugInstaller.contains("DebugAppCheckProviderFactory"))
        assertFalse(debugInstaller.contains("PlayIntegrityAppCheckProviderFactory"))
        listOf(releaseInstaller, benchmarkInstaller, profileInstaller).forEach { installer ->
            assertTrue(installer.contains("PlayIntegrityAppCheckProviderFactory"))
            assertFalse(installer.contains("DebugAppCheckProviderFactory"))
            assertTrue(installer.contains("abstract class AppCheckInstallerModule"))
            assertTrue(installer.contains("bindAppCheckInstaller"))
            assertTrue(installer.contains("bindFirebaseRuntimeEnvironment"))
        }
    }

    @Test
    fun `emulator configuration stays exclusively in cloud debug`() {
        val releaseSource =
            listOf("main", "cloud", "cloudRelease", "cloudBenchmark", "cloudProfile")
                .flatMap { sourceSet ->
                    projectFile("app/src/$sourceSet")
                        .walkTopDown()
                        .filter { file -> file.isFile && file.extension in setOf("kt", "xml") }
                        .toList()
                }.joinToString(separator = "\n") { file -> file.readText() }
        val debugSource = projectFile("app/src/cloudDebug").walkTopDown()
            .filter { file -> file.isFile && file.extension in setOf("kt", "xml") }
            .joinToString(separator = "\n") { file -> file.readText() }

        EMULATOR_MARKERS.forEach { marker ->
            assertFalse("$marker no puede entrar a fuentes release", releaseSource.contains(marker))
            assertTrue("cloudDebug debe declarar $marker", debugSource.contains(marker))
        }
        assertFalse(releaseSource.contains(".useEmulator("))
        assertTrue(debugSource.contains(".useEmulator("))
    }

    @Test
    fun `cloud source manifest is fail closed before explicit opt in`() {
        assertManifestPolicy(
            projectFile("app/src/cloud/AndroidManifest.xml"),
        )
    }

    @Test
    fun `account deletion function has no application logging channel for uid or email`() {
        val source = projectFile("functions/accountDeletion.js").readText()

        assertFalse(source.contains("console.log("))
        assertFalse(source.contains("console.info("))
        assertFalse(source.contains("console.warn("))
        assertFalse(source.contains("console.error("))
        assertFalse(source.contains("logger.log("))
        assertFalse(source.contains("logger.info("))
        assertFalse(source.contains("logger.warn("))
        assertFalse(source.contains("logger.error("))
    }

    private fun assertManifestPolicy(manifest: File) {
        val document = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(manifest)
        val metadata = document.getElementsByTagName("meta-data")
            .elements()
            .associate { element ->
                element.androidAttribute("name") to element.androidAttribute("value")
            }
        REQUIRED_FALSE_METADATA.forEach { name ->
            assertEquals("$name debe iniciar en false en ${manifest.path}", "false", metadata[name])
        }

        val permissionElements = document.getElementsByTagName("uses-permission").elements()
        val permissions = permissionElements.associate { element ->
            element.androidAttribute("name") to element.toolsAttribute("node")
        }
        assertTrue("INTERNET es necesario para backup cloud", "android.permission.INTERNET" in permissions)
        ADVERTISING_PERMISSIONS.forEach { permission ->
            assertEquals(
                "$permission debe tener tools:node=remove",
                "remove",
                permissions[permission],
            )
        }

        val firebaseInitProviders = document.getElementsByTagName("provider")
            .elements()
            .filter { element ->
                element.androidAttribute("name") == FIREBASE_INIT_PROVIDER
            }
        assertEquals(1, firebaseInitProviders.size)
        assertEquals(
            "FirebaseInitProvider debe eliminarse para conservar inicialización perezosa",
            "remove",
            firebaseInitProviders.single().toolsAttribute("node"),
        )
    }

    private fun projectFile(relativePath: String): File = File(projectRoot(), relativePath)

    private fun projectRoot(): File = generateSequence(
        File(requireNotNull(System.getProperty("user.dir"))),
    ) {
        it.parentFile
    }.firstOrNull { directory -> File(directory, "settings.gradle.kts").isFile }
        ?: error("No se encontró settings.gradle.kts desde ${System.getProperty("user.dir")}")

    private fun org.w3c.dom.NodeList.elements(): List<Element> =
        (0 until length).map { index -> item(index) as Element }

    private fun Element.androidAttribute(name: String): String =
        getAttributeNS(ANDROID_NAMESPACE, name)

    private fun Element.toolsAttribute(name: String): String =
        getAttributeNS(TOOLS_NAMESPACE, name)

    private fun String.countOccurrences(fragment: String): Int =
        windowed(fragment.length).count { it == fragment }

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
        const val TOOLS_NAMESPACE = "http://schemas.android.com/tools"
        const val FIREBASE_INIT_PROVIDER = "com.google.firebase.provider.FirebaseInitProvider"

        val REQUIRED_FALSE_METADATA = setOf(
            "firebase_analytics_collection_enabled",
            "firebase_crashlytics_collection_enabled",
            "google_analytics_default_allow_analytics_storage",
            "google_analytics_default_allow_ad_storage",
            "google_analytics_default_allow_ad_user_data",
            "google_analytics_default_allow_ad_personalization_signals",
            "google_analytics_adid_collection_enabled",
            "google_analytics_automatic_screen_reporting_enabled",
        )

        val ADVERTISING_PERMISSIONS = setOf(
            "com.google.android.gms.permission.AD_ID",
            "android.permission.ACCESS_ADSERVICES_AD_ID",
            "android.permission.ACCESS_ADSERVICES_ATTRIBUTION",
            "android.permission.ACCESS_ADSERVICES_TOPICS",
        )

        val EMULATOR_MARKERS =
            setOf(
                "demo-facturastock",
                "AIza00000000000000000000000000000000000",
                "1:1000000000000:android:0000000000000000000000",
                "10.0.2.2",
                "9_199",
                "devEnsureMembership",
            )
    }
}
