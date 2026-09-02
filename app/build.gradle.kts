import com.android.build.api.artifact.SingleArtifact
import com.android.tools.profgen.Apk
import com.android.tools.profgen.ArtProfile
import com.android.tools.profgen.ObfuscationMap
import com.android.tools.profgen.dumpProfile
import com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.w3c.dom.Element
import java.net.URI
import java.util.Properties
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.firebase.crashlytics)
    alias(libs.plugins.kover)
}

val localProperties =
    Properties().apply {
        val file = rootProject.file("local.properties")
        if (file.isFile) file.inputStream().use(::load)
    }

fun configuredValue(
    environmentName: String,
    localPropertyName: String,
): String =
    providers
        .environmentVariable(environmentName)
        .orNull
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: localProperties.getProperty(localPropertyName, "").trim()

fun buildConfigString(value: String): String = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

fun configuredDebugEmulatorPort(
    propertyName: String,
    defaultValue: Int,
): Int {
    val raw =
        providers
            .gradleProperty(propertyName)
            .orNull
            ?.trim()
            ?.takeIf(String::isNotEmpty) ?: return defaultValue
    check(Regex("[1-9][0-9]{0,4}").matches(raw)) {
        "$propertyName debe ser un puerto decimal"
    }
    return raw.toInt().also { port ->
        check(port in 1_024..65_535) { "$propertyName debe estar entre 1024 y 65535" }
    }
}

fun validatedOptionalHttpsUrl(
    rawValue: String,
    configurationName: String,
): String {
    if (rawValue.isBlank()) return ""
    val uri =
        runCatching { URI(rawValue) }.getOrElse {
            error("$configurationName debe ser una URL HTTPS válida")
        }
    check(
        uri.isAbsolute &&
            uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrBlank() &&
            uri.userInfo == null,
    ) {
        "$configurationName debe usar HTTPS, incluir host y no contener credenciales"
    }
    return uri.toASCIIString()
}

val definitiveApplicationId = "com.facturastock.app"
// Se arma en configuración para que el gate de higiene pueda buscar el fixture debug en el DEX
// release sin incrustar una cadena con forma de credencial en este script.
val emulatorFirebaseApiKeyMarker = "AI" + "za00000000000000000000000000000000000"
val requiredTargetSdk = 36
val configuredCompileSdk =
    libs.versions.compileSdk
        .get()
        .toInt()
val configuredTargetSdk =
    libs.versions.targetSdk
        .get()
        .toInt()
val configuredMinSdk =
    libs.versions.minSdk
        .get()
        .toInt()

check(configuredTargetSdk == requiredTargetSdk) {
    "targetSdk debe permanecer en $requiredTargetSdk para los artefactos release"
}
check(configuredCompileSdk >= configuredTargetSdk) {
    "compileSdk no puede ser menor que targetSdk"
}
check(configuredMinSdk in 1..configuredTargetSdk) {
    "minSdk debe ser positivo y no superar targetSdk"
}

val rawVersionCode = providers.environmentVariable("FACTURASTOCK_VERSION_CODE").orNull
val configuredVersionCode =
    if (rawVersionCode == null) {
        1
    } else {
        val normalized = rawVersionCode.trim()
        check(Regex("[1-9][0-9]{0,9}").matches(normalized)) {
            "FACTURASTOCK_VERSION_CODE debe ser un entero decimal positivo"
        }
        val parsed = normalized.toLong()
        check(parsed <= 2_100_000_000L) {
            "FACTURASTOCK_VERSION_CODE supera el máximo admitido para distribución"
        }
        parsed.toInt()
    }

val rawVersionName = providers.environmentVariable("FACTURASTOCK_VERSION_NAME").orNull
val configuredVersionName =
    if (rawVersionName == null) {
        "1.0.0"
    } else {
        val normalized = rawVersionName.trim()
        check(Regex("[0-9A-Za-z](?:[0-9A-Za-z._+-]{0,99})?").matches(normalized)) {
            "FACTURASTOCK_VERSION_NAME debe tener 1..100 caracteres alfanuméricos, . _ + o -"
        }
        normalized
    }

val firebaseProjectId =
    configuredValue("FACTURASTOCK_FIREBASE_PROJECT_ID", "firebase.projectId")
val firebaseApplicationId =
    configuredValue("FACTURASTOCK_FIREBASE_APPLICATION_ID", "firebase.applicationId")
val firebaseApiKey = configuredValue("FACTURASTOCK_FIREBASE_API_KEY", "firebase.apiKey")
val firebaseStorageBucket =
    configuredValue("FACTURASTOCK_FIREBASE_STORAGE_BUCKET", "firebase.storageBucket")
val privacyPolicyUrl =
    validatedOptionalHttpsUrl(
        configuredValue("FACTURASTOCK_PRIVACY_POLICY_URL", "privacy.policyUrl"),
        "FACTURASTOCK_PRIVACY_POLICY_URL",
    )
val debugFirestoreEmulatorPort =
    configuredDebugEmulatorPort("facturastock.firestoreEmulatorPort", 8_080)

val signingEnvironment =
    listOf(
        "FACTURASTOCK_SIGNING_STORE_FILE",
        "FACTURASTOCK_SIGNING_STORE_PASSWORD",
        "FACTURASTOCK_SIGNING_KEY_ALIAS",
        "FACTURASTOCK_SIGNING_KEY_PASSWORD",
    ).associateWith { name -> providers.environmentVariable(name).orNull.orEmpty() }
val configuredSigningValues = signingEnvironment.filterValues(String::isNotBlank)
check(configuredSigningValues.isEmpty() || configuredSigningValues.size == signingEnvironment.size) {
    "La firma release está incompleta; define las cuatro variables FACTURASTOCK_SIGNING_*"
}
val hasCiReleaseSigning = configuredSigningValues.size == signingEnvironment.size
val releaseSigningStoreFile =
    if (hasCiReleaseSigning) {
        rootProject
            .file(
                configuredSigningValues
                    .getValue("FACTURASTOCK_SIGNING_STORE_FILE")
                    .trim(),
            ).canonicalFile
    } else {
        null
    }

releaseSigningStoreFile?.let { storeFile ->
    val repositoryPath = rootProject.projectDir.canonicalFile.toPath()
    check(!storeFile.toPath().startsWith(repositoryPath)) {
        "El keystore release debe vivir fuera del repositorio"
    }
    check(storeFile.isFile && storeFile.canRead()) {
        "FACTURASTOCK_SIGNING_STORE_FILE debe apuntar a un archivo externo legible"
    }
}

android {
    namespace = definitiveApplicationId
    compileSdk = configuredCompileSdk
    buildToolsVersion = libs.versions.buildTools.get()

    defaultConfig {
        applicationId = definitiveApplicationId
        minSdk = configuredMinSdk
        targetSdk = configuredTargetSdk
        versionCode = configuredVersionCode
        versionName = configuredVersionName
        testInstrumentationRunner = "com.facturastock.app.testing.HiltTestRunner"
        // Solo la variante profile lo apaga para que la captura termine en Home sin mezclar
        // mantenimiento post-arranque. Producción y benchmark conservan el comportamiento real.
        buildConfigField("boolean", "RUN_DEFERRED_STARTUP", "true")
        // Vacío en builds locales/no configurados. El gate de distribución exige una URL HTTPS
        // explícita para cloudRelease; la UI nunca inventa ni incrusta un destino alternativo.
        buildConfigField("String", "PRIVACY_POLICY_URL", buildConfigString(""))
    }

    signingConfigs {
        if (hasCiReleaseSigning) {
            create("ciRelease") {
                storeFile = releaseSigningStoreFile
                storePassword =
                    configuredSigningValues.getValue(
                        "FACTURASTOCK_SIGNING_STORE_PASSWORD",
                    )
                keyAlias = configuredSigningValues.getValue("FACTURASTOCK_SIGNING_KEY_ALIAS")
                keyPassword =
                    configuredSigningValues.getValue(
                        "FACTURASTOCK_SIGNING_KEY_PASSWORD",
                    )
            }
        }
    }

    // La política offline-first vive en el flavor `local` (sin INTERNET ni Firebase). El
    // flavor `cloud` añade el respaldo Firebase opcional detrás del puerto
    // PurchaseBackupTransport; nunca cambia el flujo local compartido en src/main.
    flavorDimensions += "backend"
    productFlavors {
        create("local") {
            dimension = "backend"
        }
        create("cloud") {
            dimension = "backend"
            // Solo cloudRelease hereda esta firma: debug y benchmark la reemplazan con debug.
            signingConfigs.findByName("ciRelease")?.let { signingConfig = it }
            // Local lee local.properties; CI inyecta solo variables de entorno en el job de
            // release protegido. Vacío significa transporte no configurado.
            buildConfigField(
                "String",
                "FIREBASE_PROJECT_ID",
                buildConfigString(firebaseProjectId),
            )
            buildConfigField(
                "String",
                "FIREBASE_APPLICATION_ID",
                buildConfigString(firebaseApplicationId),
            )
            buildConfigField(
                "String",
                "FIREBASE_API_KEY",
                buildConfigString(firebaseApiKey),
            )
            buildConfigField(
                "String",
                "FIREBASE_STORAGE_BUCKET",
                buildConfigString(firebaseStorageBucket),
            )
            buildConfigField(
                "String",
                "PRIVACY_POLICY_URL",
                buildConfigString(privacyPolicyUrl),
            )
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
            // Permite ejecutar el E2E local cuando 8080 ya pertenece a otro Emulator Suite. El
            // campo solo existe en variantes debug; CI conserva 8080 salvo override explícito.
            buildConfigField(
                "int",
                "FIRESTORE_EMULATOR_PORT",
                debugFirestoreEmulatorPort.toString(),
            )
            configure<CrashlyticsExtension> {
                mappingFileUploadEnabled = false
            }
        }
        create("spark") {
            // APK instalable contra el proyecto Firebase real del plan Spark. Conserva una
            // firma local reproducible y depurabilidad para poder registrar el token de App
            // Check, pero su runtime no conecta ningún servicio al Emulator Suite.
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("debug")
            isDebuggable = true
            configure<CrashlyticsExtension> {
                mappingFileUploadEnabled = false
            }
        }
        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            configure<CrashlyticsExtension> {
                mappingFileUploadEnabled = false
            }
        }
        create("benchmark") {
            initWith(getByName("release"))
            // El artefacto medido conserva optimizaciones de release, pero usa una firma local
            // reproducible. Este build type nunca se distribuye.
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            isDebuggable = false
            configure<CrashlyticsExtension> {
                mappingFileUploadEnabled = false
            }
        }
        create("profile") {
            initWith(getByName("release"))
            // La generación necesita nombres fuente estables. Macrobenchmark continúa usando el
            // build `benchmark` minificado; R8 reescribe este HRF al consumirlo en release.
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            buildConfigField("boolean", "RUN_DEFERRED_STARTUP", "false")
            configure<CrashlyticsExtension> {
                mappingFileUploadEnabled = false
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        // Los esquemas exportados se empaquetan como assets de androidTest para MigrationTestHelper.
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// `spark` solo tiene sentido con el backend Firebase. Evitar localSpark conserva la frontera
// offline: el flavor local no compila fuentes ni proveedores Firebase por accidente.
androidComponents.beforeVariants(
    androidComponents.selector().withBuildType("spark"),
) { variantBuilder ->
    val backendFlavor =
        variantBuilder.productFlavors
            .single { (dimension, _) -> dimension == "backend" }
            .second
    if (backendFlavor != "cloud") variantBuilder.enable = false
}

val verifyAndroidVariantMatrix by tasks.registering {
    group = "verification"
    description = "Verifies the complete app variant matrix and definitive application ID."
}
val verifyReleaseManifests by tasks.registering {
    group = "verification"
    description = "Parses merged release manifests and verifies identity, SDK, and safe flags."
}
val verifyLocalProfileRuleResolution by tasks.registering {
    group = "verification"
    description =
        "Verifies every maintained exact profile rule against the non-minified localProfile DEX."
}
val configuredVariantApplicationIds = mutableMapOf<String, Provider<String>>()

androidComponents.onVariants { variant ->
    configuredVariantApplicationIds[variant.name] = variant.applicationId

    if (variant.name == "localProfile") {
        val profileApkDirectory = variant.artifacts.get(SingleArtifact.APK)
        val builtArtifactsLoader = variant.artifacts.getBuiltArtifactsLoader()
        val maintainedBaselineProfile =
            layout.projectDirectory.file("src/main/baseline-prof.txt")
        val maintainedStartupProfile =
            layout.projectDirectory.file("src/main/baselineProfiles/startup-prof.txt")

        verifyLocalProfileRuleResolution.configure {
            dependsOn("assembleLocalProfile")
            inputs.files(maintainedBaselineProfile, maintainedStartupProfile)
            inputs.dir(profileApkDirectory)

            doLast {
                val builtArtifacts =
                    checkNotNull(builtArtifactsLoader.load(profileApkDirectory.get())) {
                        "localProfile no publicó metadata BuiltArtifacts para inspeccionar su APK"
                    }
                val profileApks =
                    builtArtifacts.elements
                        .map { artifact -> artifact.path.toFile() }
                        .filter { apk -> apk.isFile }
                check(profileApks.size == 1) {
                    "localProfile debe producir un único APK inspeccionable: " +
                        profileApks.joinToString { apk -> apk.invariantSeparatorsPath }
                }
                val profileApk = profileApks.single()
                val embeddedProfilePath = "assets/dexopt/baseline.prof"
                val compiledProfile =
                    ZipFile(profileApk).use { archive ->
                        val entry =
                            archive.getEntry(embeddedProfilePath)
                                ?: error("${profileApk.name} no contiene $embeddedProfilePath")
                        archive.getInputStream(entry).use { input ->
                            checkNotNull(ArtProfile(input)) {
                                "Profgen no pudo leer $embeddedProfilePath de ${profileApk.name}"
                            }
                        }
                    }

                // La variante Profile no pasa por R8: una ausencia aquí representa un símbolo
                // DEX realmente obsoleto, no un método válido que el shrinker integró o eliminó.
                val resolvedProfileText =
                    buildString {
                        dumpProfile(
                            os = this,
                            profile = compiledProfile,
                            apk = Apk(profileApk),
                            obf = ObfuscationMap.Empty,
                            strict = true,
                        )
                    }

                fun String.normalizedProfileRule(): String = trim().dropWhile { flag -> flag == 'H' || flag == 'S' || flag == 'P' }

                fun exactRules(profileFile: File): Sequence<String> =
                    profileFile
                        .useLines { lines ->
                            lines
                                .map(String::trim)
                                .filter { line -> line.isNotEmpty() && !line.startsWith("#") }
                                .filterNot { rule -> '*' in rule || '?' in rule }
                                .map { rule -> rule.normalizedProfileRule() }
                                .toList()
                        }.asSequence()

                val maintainedExactRules =
                    sequenceOf(
                        maintainedBaselineProfile.asFile,
                        maintainedStartupProfile.asFile,
                    ).flatMap(::exactRules)
                        .toSortedSet()
                val resolvedRules =
                    resolvedProfileText
                        .lineSequence()
                        .map { rule -> rule.normalizedProfileRule() }
                        .filter(String::isNotEmpty)
                        .toHashSet()
                val missingExactRules = maintainedExactRules - resolvedRules
                check(missingExactRules.isEmpty()) {
                    "Los perfiles mantenidos contienen ${missingExactRules.size} reglas exactas " +
                        "sin símbolo DEX en localProfile; regenera la captura atómica:\n" +
                        missingExactRules.take(20).joinToString("\n")
                }

                logger.lifecycle(
                    "${profileApk.name}: ${maintainedExactRules.size} reglas exactas " +
                        "resueltas por Profgen contra DEX no minificado",
                )
            }
        }
    }

    if (variant.buildType == "release") {
        val backendFlavor =
            variant.productFlavors.single { (dimension, _) -> dimension == "backend" }.second
        val mergedManifest = variant.artifacts.get(SingleArtifact.MERGED_MANIFEST)
        val capitalizedVariantName = variant.name.replaceFirstChar(Char::uppercase)
        val verifyVariantManifest =
            tasks.register("verify${capitalizedVariantName}MergedManifest") {
                group = "verification"
                description = "Verifies the merged manifest for ${variant.name}."
                inputs.file(mergedManifest)
                inputs.property("expectedApplicationId", definitiveApplicationId)
                inputs.property("expectedVersionCode", configuredVersionCode)
                inputs.property("expectedVersionName", configuredVersionName)
                inputs.property("expectedMinSdk", configuredMinSdk)
                inputs.property("expectedTargetSdk", configuredTargetSdk)

                doLast {
                    val manifestFile = mergedManifest.get().asFile
                    check(manifestFile.isFile) {
                        "No existe el manifest fusionado de ${variant.name}"
                    }
                    val document =
                        DocumentBuilderFactory
                            .newInstance()
                            .apply {
                                isNamespaceAware = true
                                setFeature(
                                    "http://apache.org/xml/features/disallow-doctype-decl",
                                    true,
                                )
                            }.newDocumentBuilder()
                            .parse(manifestFile)
                    val manifest = document.documentElement

                    fun Element.androidAttribute(name: String): String = getAttributeNS("http://schemas.android.com/apk/res/android", name)

                    fun elements(tagName: String): List<Element> {
                        val nodes = document.getElementsByTagName(tagName)
                        return (0 until nodes.length).map { index -> nodes.item(index) as Element }
                    }

                    check(manifest.getAttribute("package") == definitiveApplicationId) {
                        "${variant.name}: applicationId fusionado inesperado"
                    }
                    check(
                        manifest.androidAttribute("versionCode") == configuredVersionCode.toString(),
                    ) { "${variant.name}: versionCode fusionado inesperado" }
                    check(manifest.androidAttribute("versionName") == configuredVersionName) {
                        "${variant.name}: versionName fusionado inesperado"
                    }

                    val usesSdk = elements("uses-sdk").single()
                    check(usesSdk.androidAttribute("minSdkVersion") == configuredMinSdk.toString()) {
                        "${variant.name}: minSdk fusionado inesperado"
                    }
                    check(usesSdk.androidAttribute("targetSdkVersion") == configuredTargetSdk.toString()) {
                        "${variant.name}: targetSdk fusionado inesperado"
                    }

                    val application = elements("application").single()
                    check(application.androidAttribute("allowBackup") == "false") {
                        "${variant.name}: allowBackup debe permanecer false"
                    }
                    check(application.androidAttribute("fullBackupContent") == "false") {
                        "${variant.name}: fullBackupContent debe permanecer false"
                    }
                    check(
                        application.androidAttribute("dataExtractionRules") ==
                            "@xml/data_extraction_rules",
                    ) { "${variant.name}: faltan reglas explícitas de extracción" }
                    check(application.androidAttribute("debuggable") != "true") {
                        "${variant.name}: un release no puede ser debuggable"
                    }
                    check(application.androidAttribute("testOnly") != "true") {
                        "${variant.name}: un release no puede ser testOnly"
                    }
                    check(application.androidAttribute("usesCleartextTraffic") == "false") {
                        "${variant.name}: cleartext debe estar negado explícitamente"
                    }
                    check(
                        application.androidAttribute("networkSecurityConfig") ==
                            "@xml/network_security_config",
                    ) { "${variant.name}: falta Network Security Config" }

                    val exportedProviders =
                        elements("provider").filter {
                            it.androidAttribute("exported") == "true"
                        }
                    check(exportedProviders.isEmpty()) {
                        "${variant.name}: ningún ContentProvider puede quedar exportado"
                    }
                    val exportedActivities =
                        elements("activity").filter {
                            it.androidAttribute("exported") == "true"
                        }
                    val firebaseAuthCallbacks =
                        if (backendFlavor == "cloud") {
                            setOf(
                                "com.google.firebase.auth.internal.GenericIdpActivity" to "genericidp",
                                "com.google.firebase.auth.internal.RecaptchaActivity" to "recaptcha",
                            )
                        } else {
                            emptySet()
                        }
                    val expectedExportedActivities =
                        setOf("com.facturastock.app.MainActivity") +
                            firebaseAuthCallbacks.mapTo(mutableSetOf()) { it.first }
                    val actualExportedActivities =
                        exportedActivities.mapTo(mutableSetOf()) { it.androidAttribute("name") }
                    check(actualExportedActivities == expectedExportedActivities) {
                        "${variant.name}: actividades exportadas inesperadas " +
                            "(esperadas=$expectedExportedActivities, reales=$actualExportedActivities)"
                    }
                    firebaseAuthCallbacks.forEach { (className, expectedScheme) ->
                        val callback =
                            exportedActivities.single { it.androidAttribute("name") == className }
                        val actions =
                            callback
                                .getElementsByTagName("action")
                                .let { nodes ->
                                    (0 until nodes.length).mapTo(mutableSetOf()) { index ->
                                        (nodes.item(index) as Element).androidAttribute("name")
                                    }
                                }
                        val categories =
                            callback
                                .getElementsByTagName("category")
                                .let { nodes ->
                                    (0 until nodes.length).mapTo(mutableSetOf()) { index ->
                                        (nodes.item(index) as Element).androidAttribute("name")
                                    }
                                }
                        val callbackData = callback.getElementsByTagName("data")
                        check(
                            actions == setOf("android.intent.action.VIEW") &&
                                categories ==
                                setOf(
                                    "android.intent.category.DEFAULT",
                                    "android.intent.category.BROWSABLE",
                                ) &&
                                callbackData.length == 1 &&
                                (callbackData.item(0) as Element).androidAttribute("scheme") ==
                                expectedScheme &&
                                (callbackData.item(0) as Element).androidAttribute("host") ==
                                "firebase.auth",
                        ) {
                            "${variant.name}: callback Firebase Auth exportado sin filtro mínimo: " +
                                className
                        }
                    }
                    val exportedBackgroundComponents =
                        (elements("service") + elements("receiver")).filter {
                            it.androidAttribute("exported") == "true"
                        }
                    check(
                        exportedBackgroundComponents.all {
                            it.androidAttribute("permission").isNotBlank()
                        },
                    ) {
                        "${variant.name}: servicio/receiver exportado sin permiso del sistema"
                    }

                    val permissions =
                        elements("uses-permission")
                            .mapTo(mutableSetOf()) { permission -> permission.androidAttribute("name") }
                    val advertisingPermissions =
                        setOf(
                            "com.google.android.gms.permission.AD_ID",
                            "android.permission.ACCESS_ADSERVICES_AD_ID",
                            "android.permission.ACCESS_ADSERVICES_ATTRIBUTION",
                            "android.permission.ACCESS_ADSERVICES_TOPICS",
                        )
                    check(permissions.intersect(advertisingPermissions).isEmpty()) {
                        "${variant.name}: el manifest release conserva permisos publicitarios"
                    }
                    val privacyContradictionPermissions =
                        setOf(
                            "android.permission.ACCESS_COARSE_LOCATION",
                            "android.permission.ACCESS_FINE_LOCATION",
                            "android.permission.ACCESS_BACKGROUND_LOCATION",
                            "android.permission.RECORD_AUDIO",
                            "android.permission.READ_EXTERNAL_STORAGE",
                            "android.permission.WRITE_EXTERNAL_STORAGE",
                            "android.permission.MANAGE_EXTERNAL_STORAGE",
                            "android.permission.READ_MEDIA_IMAGES",
                            "android.permission.READ_MEDIA_VIDEO",
                            "android.permission.READ_MEDIA_AUDIO",
                            "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
                            "android.permission.READ_CONTACTS",
                            "android.permission.WRITE_CONTACTS",
                            "android.permission.GET_ACCOUNTS",
                            "android.permission.READ_CALENDAR",
                            "android.permission.WRITE_CALENDAR",
                            "android.permission.READ_SMS",
                            "android.permission.RECEIVE_SMS",
                            "android.permission.SEND_SMS",
                            "android.permission.RECEIVE_MMS",
                            "android.permission.RECEIVE_WAP_PUSH",
                            "android.permission.READ_PHONE_STATE",
                            "android.permission.READ_PHONE_NUMBERS",
                            "android.permission.CALL_PHONE",
                            "android.permission.READ_CALL_LOG",
                            "android.permission.WRITE_CALL_LOG",
                            "android.permission.ANSWER_PHONE_CALLS",
                            "android.permission.ADD_VOICEMAIL",
                            "android.permission.USE_SIP",
                            "android.permission.PROCESS_OUTGOING_CALLS",
                        )
                    check(permissions.intersect(privacyContradictionPermissions).isEmpty()) {
                        "${variant.name}: el manifest release contradice la declaración de privacidad"
                    }

                    val metadata =
                        elements("meta-data").associate { element ->
                            element.androidAttribute("name") to element.androidAttribute("value")
                        }
                    if (backendFlavor == "cloud") {
                        val providerNames =
                            elements("provider")
                                .mapTo(mutableSetOf()) { provider ->
                                    provider.androidAttribute("name")
                                }
                        check(
                            "com.google.firebase.provider.FirebaseInitProvider" !in providerNames,
                        ) {
                            "cloudRelease debe conservar la inicialización Firebase perezosa"
                        }
                        check("android.permission.INTERNET" in permissions) {
                            "cloudRelease debe declarar INTERNET"
                        }
                        setOf(
                            "firebase_analytics_collection_enabled",
                            "firebase_crashlytics_collection_enabled",
                            "google_analytics_default_allow_ad_personalization_signals",
                            "google_analytics_adid_collection_enabled",
                            "google_analytics_automatic_screen_reporting_enabled",
                        ).forEach { key ->
                            check(metadata[key] == "false") {
                                "cloudRelease: $key debe iniciar en false"
                            }
                        }
                    } else {
                        check("android.permission.INTERNET" !in permissions) {
                            "localRelease debe permanecer sin INTERNET"
                        }
                        check("firebase_analytics_collection_enabled" !in metadata) {
                            "localRelease no debe contener Analytics"
                        }
                        check("firebase_crashlytics_collection_enabled" !in metadata) {
                            "localRelease no debe contener Crashlytics"
                        }
                    }
                }
            }
        verifyReleaseManifests.configure { dependsOn(verifyVariantManifest) }
    }
}

verifyAndroidVariantMatrix.configure {
    doLast {
        val expectedVariants =
            setOf(
                "localDebug",
                "localRelease",
                "localBenchmark",
                "localProfile",
                "cloudDebug",
                "cloudSpark",
                "cloudRelease",
                "cloudBenchmark",
                "cloudProfile",
            )
        check(configuredVariantApplicationIds.keys == expectedVariants) {
            "Matriz Android inesperada: ${configuredVariantApplicationIds.keys.sorted()}"
        }
        configuredVariantApplicationIds.forEach { (variantName, applicationId) ->
            check(applicationId.get() == definitiveApplicationId) {
                "$variantName debe conservar applicationId=$definitiveApplicationId"
            }
        }
        check(android.buildTypes.getByName("release").signingConfig == null) {
            "La firma no debe aplicarse globalmente a localRelease"
        }
        check(android.productFlavors.getByName("local").signingConfig == null) {
            "localRelease es solo un artefacto de validación y debe quedar unsigned"
        }
        val cloudSigning = android.productFlavors.getByName("cloud").signingConfig
        check(
            if (hasCiReleaseSigning) cloudSigning?.name == "ciRelease" else cloudSigning == null,
        ) { "Solo el flavor cloud puede recibir la firma release externa" }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

val composeCompilerReportsEnabled =
    providers
        .gradleProperty("facturastock.composeCompilerReports")
        .map { rawValue ->
            rawValue.toBooleanStrictOrNull()
                ?: error("facturastock.composeCompilerReports debe ser true o false")
        }.orElse(false)
val supportedComposeCompilerReportVariants =
    setOf(
        "localDebug",
        "localRelease",
        "localBenchmark",
        "localProfile",
        "cloudDebug",
        "cloudSpark",
        "cloudRelease",
        "cloudBenchmark",
        "cloudProfile",
    )
val composeCompilerReportVariant =
    providers
        .gradleProperty("facturastock.composeCompilerReportVariant")
        .orElse("localRelease")
        .map(String::trim)
        .map { variant ->
            check(variant in supportedComposeCompilerReportVariants) {
                "facturastock.composeCompilerReportVariant debe ser una variante app: " +
                    supportedComposeCompilerReportVariants.sorted().joinToString()
            }
            variant
        }
val composeCompilerReportRoot =
    layout.buildDirectory.dir(
        composeCompilerReportVariant.map { variant ->
            "reports/compose-compiler/$variant"
        },
    )
val composeCompilerMetricsDirectory = composeCompilerReportRoot.map { it.dir("metrics") }
val composeCompilerReportsDirectory = composeCompilerReportRoot.map { it.dir("reports") }

// Opt-in deliberado: el compilador no escribe diagnósticos ni invalida sus tareas en CI normal.
// Para inspeccionar estabilidad/recomposición:
// ./gradlew :app:generateComposeCompilerReports \
//   -Pfacturastock.composeCompilerReports=true \
//   -Pfacturastock.composeCompilerReportVariant=localRelease
composeCompiler {
    if (composeCompilerReportsEnabled.get()) {
        metricsDestination.set(composeCompilerMetricsDirectory)
        reportsDestination.set(composeCompilerReportsDirectory)
    }
}

val prepareComposeCompilerReports by tasks.registering {
    group = "diagnostics"
    description = "Removes stale opt-in Compose diagnostics before the selected compilation."
    onlyIf { composeCompilerReportsEnabled.get() }
    outputs.upToDateWhen { false }

    doLast {
        project.delete(composeCompilerReportRoot)
    }
}

tasks.withType<KotlinCompile>().configureEach {
    if (Regex("^compile(?:Local|Cloud)(?:Debug|Spark|Release|Benchmark|Profile)Kotlin$").matches(name)) {
        // AGP deriva el nombre de módulo de la variante. Fijarlo en la tarea final evita que los
        // métodos `internal` observados por Profile cambien de firma al consumirlos en Release.
        compilerOptions.moduleName.set("facturastock_app")
    }
    if (composeCompilerReportsEnabled.get()) {
        val variantTaskSuffix = composeCompilerReportVariant.get().replaceFirstChar(Char::uppercase)
        if (name == "compile${variantTaskSuffix}Kotlin") {
            dependsOn(prepareComposeCompilerReports)
            // Los reportes describen el módulo completo. Una compilación incremental sin fuentes
            // sucias puede terminar correctamente pero producir un diagnóstico vacío o parcial.
            incremental = false
            outputs.upToDateWhen { false }
        }
    }
}

val generateComposeCompilerReports by tasks.registering {
    group = "diagnostics"
    description = "Generates opt-in Compose compiler stability and skippability reports."
    dependsOn(
        providers.provider {
            if (composeCompilerReportsEnabled.get()) {
                val variantTaskSuffix =
                    composeCompilerReportVariant.get().replaceFirstChar(Char::uppercase)
                listOf("compile${variantTaskSuffix}Kotlin")
            } else {
                emptyList<String>()
            }
        },
    )
    outputs.dirs(composeCompilerMetricsDirectory, composeCompilerReportsDirectory)
    outputs.upToDateWhen { false }

    doFirst {
        check(composeCompilerReportsEnabled.get()) {
            "Este diagnóstico es opt-in; agrega " +
                "-Pfacturastock.composeCompilerReports=true"
        }
    }
    doLast {
        val generatedFiles =
            listOf(
                composeCompilerMetricsDirectory.get().asFile,
                composeCompilerReportsDirectory.get().asFile,
            ).flatMap { directory ->
                directory.walkTopDown().filter(File::isFile).toList()
            }
        check(generatedFiles.isNotEmpty()) {
            "El compilador Compose no generó reportes para ${composeCompilerReportVariant.get()}"
        }
        logger.lifecycle(
            "Reportes Compose (${composeCompilerReportVariant.get()}): " +
                composeCompilerReportRoot.get().asFile.invariantSeparatorsPath,
        )
    }
}

ksp {
    // El esquema Room se exporta a app/schemas y se versiona junto al código.
    arg("room.schemaLocation", "$projectDir/schemas")
}

kover {
    reports {
        // La cobertura medida y verificada se limita al dominio puro crítico.
        filters {
            includes {
                classes("com.facturastock.app.domain.*")
            }
        }
        verify {
            // Por defecto la cota es de líneas cubiertas (LINE, COVERED_PERCENTAGE).
            rule("Cobertura mínima del dominio crítico") {
                bound {
                    minValue = 80
                }
            }
        }
    }
}

// AGP creates combined flavor/build-type buckets lazily. Declare them before the dependency
// block so their providers remain isolated to the matching cloud variant.
val cloudDebugImplementation by configurations.creating
val cloudSparkImplementation by configurations.creating
val cloudReleaseImplementation by configurations.creating
val cloudBenchmarkImplementation by configurations.creating
val cloudProfileImplementation by configurations.creating

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.activity.compose)
    implementation(libs.androidx.biometric)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.core)
    // Room 2.8.4 migration bundles are compiled against serialization 1.8.1. Keeping the
    // runtime aligned avoids AbstractMethodError in MigrationTestHelper on device.
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    ksp(libs.hilt.compiler)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.mlkit.text.recognition)
    implementation(libs.datastore.preferences)
    implementation(libs.work.runtime.ktx)
    implementation(libs.coil.compose)
    implementation(libs.androidx.exifinterface)

    // Firebase solo existe en el flavor cloud; verifyOfflineFirstBoundaries lo hace cumplir.
    // (forma de string: las configuraciones por flavor no generan accessors type-safe)
    "cloudImplementation"(platform(libs.firebase.bom))
    "cloudImplementation"(libs.firebase.auth)
    "cloudImplementation"(libs.firebase.firestore)
    "cloudImplementation"(libs.firebase.functions)
    "cloudImplementation"(libs.firebase.storage)
    "cloudImplementation"(libs.firebase.crashlytics)
    "cloudImplementation"(libs.firebase.appcheck)
    cloudDebugImplementation(libs.firebase.appcheck.debug)
    cloudSparkImplementation(libs.firebase.appcheck.debug)
    cloudReleaseImplementation(libs.firebase.appcheck.playintegrity)
    cloudBenchmarkImplementation(libs.firebase.appcheck.playintegrity)
    cloudProfileImplementation(libs.firebase.appcheck.playintegrity)
    "cloudImplementation"(libs.firebase.analytics)
    "cloudImplementation"(libs.coroutines.play.services)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.work.testing)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.compiler)
    debugImplementation(libs.compose.ui.test.manifest)
    debugImplementation(libs.compose.ui.tooling)
}

val verifyDomainBoundaries by tasks.registering {
    group = "verification"
    description = "Verifies that the pure domain layer has no forbidden dependencies or binary floating-point types."

    val domainSourceDirectories =
        listOf(
            layout.projectDirectory.dir("src/main/java/com/facturastock/app/domain"),
            layout.projectDirectory.dir("src/main/kotlin/com/facturastock/app/domain"),
        )
    inputs.files(domainSourceDirectories)

    doLast {
        val allowedImportPrefixes =
            listOf(
                "java.",
                "kotlin.",
                "kotlinx.coroutines.flow.",
                "kotlinx.coroutines.CancellationException",
                "kotlinx.coroutines.NonCancellable",
                "kotlinx.coroutines.currentCoroutineContext",
                "kotlinx.coroutines.ensureActive",
                "kotlinx.coroutines.withContext",
                "com.facturastock.app.core.",
                "com.facturastock.app.domain.",
            )
        val binaryFloatingPoint = Regex("""\b(?:Float|Double)\b""")
        val violations = mutableListOf<String>()

        domainSourceDirectories
            .asSequence()
            .flatMap { directory ->
                directory.asFileTree
                    .matching { include("**/*.kt") }
                    .files
                    .asSequence()
            }.distinct()
            .sortedBy { it.path }
            .forEach { source ->
                source.readLines().forEachIndexed { index, line ->
                    val code = line.substringBefore("//")
                    val importedName =
                        code
                            .trim()
                            .takeIf { it.startsWith("import ") }
                            ?.removePrefix("import ")
                    if (
                        importedName != null &&
                        allowedImportPrefixes.none(importedName::startsWith)
                    ) {
                        violations += "${source.relativeTo(projectDir)}:${index + 1}: import no permitido: $importedName"
                    }
                    if (binaryFloatingPoint.containsMatchIn(code)) {
                        violations += "${source.relativeTo(projectDir)}:${index + 1}: tipo decimal binario"
                    }
                }
            }

        check(violations.isEmpty()) {
            "Violaciones de la frontera de domain:\n${violations.joinToString("\n")}"
        }
    }
}

val verifyUiConventions by tasks.registering {
    group = "verification"
    description = "Verifies centralized UI strings, colors, and accessibility previews."

    val uiSourceDirectories =
        listOf("ui", "feature", "navigation").flatMap { layer ->
            listOf("java", "kotlin").map { sourceKind ->
                layout.projectDirectory.dir(
                    "src/main/$sourceKind/com/facturastock/app/$layer",
                )
            }
        }
    val resourceDirectory = layout.projectDirectory.dir("src/main/res")
    inputs.files(uiSourceDirectories)
    inputs.dir(resourceDirectory)

    doLast {
        val visibleStringAssignment =
            Regex(
                """\b(?:text|contentDescription|label|title|message|placeholder|supportingText)\s*=\s*\"[^\"]+\"""",
            )
        val directTextCall = Regex("""\bText\s*\(\s*\"[^\"]+\"""")
        val rawColor =
            Regex(
                """\bColor\s*\(\s*0x|\bColor\.(?:Black|White|Red|Green|Blue|Yellow|Cyan|Magenta|Gray|DarkGray|LightGray)\b""",
            )
        val xmlHexColor = Regex("""#[0-9a-fA-F]{6,8}\b""")
        val rawXmlAccessibilityText =
            Regex(
                """android:(?:text|hint|label|contentDescription)\s*=\s*\"(?!@string/)[^\"]+\"""",
            )
        val violations = mutableListOf<String>()
        val kotlinSources =
            uiSourceDirectories
                .asSequence()
                .flatMap { directory ->
                    directory.asFileTree
                        .matching { include("**/*.kt") }
                        .files
                        .asSequence()
                }.distinct()
                .sortedBy { it.path }
                .toList()

        kotlinSources.forEach { source ->
            val isThemeSource = source.path.contains("/ui/theme/")
            source.readLines().forEachIndexed { index, line ->
                val code = line.substringBefore("//")
                if (
                    visibleStringAssignment.containsMatchIn(code) ||
                    directTextCall.containsMatchIn(code)
                ) {
                    violations += "${source.relativeTo(projectDir)}:${index + 1}: texto visible sin recurso"
                }
                if (!isThemeSource && rawColor.containsMatchIn(code)) {
                    violations += "${source.relativeTo(projectDir)}:${index + 1}: color fuera del tema"
                }
            }
        }

        resourceDirectory.asFileTree
            .matching { include("**/*.xml") }
            .files
            .sortedBy { it.path }
            .forEach { source ->
                val relativePath = source.relativeTo(resourceDirectory.asFile).invariantSeparatorsPath
                val xml = source.readText()
                if (relativePath != "values/colors.xml" && xmlHexColor.containsMatchIn(xml)) {
                    violations += "${source.relativeTo(projectDir)}: color XML fuera de values/colors.xml"
                }
                if (
                    relativePath != "values/strings.xml" &&
                    rawXmlAccessibilityText.containsMatchIn(xml)
                ) {
                    violations += "${source.relativeTo(projectDir)}: texto accesible XML sin @string"
                }
            }

        val allKotlin = kotlinSources.joinToString(separator = "\n") { it.readText() }
        if (!allKotlin.contains("@ThemePreviews")) {
            violations += "Faltan previews claros y oscuros con @ThemePreviews"
        }
        if (!allKotlin.contains("@LargeFontPreview") || !allKotlin.contains("fontScale = 2f")) {
            violations += "Falta preview de accesibilidad con fuente al 200 %"
        }

        check(violations.isEmpty()) {
            "Violaciones de convenciones UI:\n${violations.joinToString("\n")}"
        }
    }
}

val verifyNoSensitiveLogging by tasks.registering {
    group = "verification"
    description = "Verifies that production sources never log paths, documents or identifiers."

    val mainSourceDirectories =
        listOf("main", "local", "cloud").flatMap { flavor ->
            listOf("java", "kotlin").map { sourceKind ->
                layout.projectDirectory.dir("src/$flavor/$sourceKind")
            }
        }
    inputs.files(mainSourceDirectories)

    doLast {
        val logImport = Regex("""^import\s+android\.util\.Log\b""")
        val logCall = Regex("""\bLog\s*\.\s*(?:d|i|v|w|e|wtf)\s*\(""")
        val stdStream = Regex("""\bSystem\.(?:out|err)\b""")
        val printCall = Regex("""\bprintln?\s*\(""")
        val violations = mutableListOf<String>()

        mainSourceDirectories
            .asSequence()
            .flatMap { directory ->
                directory.asFileTree
                    .matching { include("**/*.kt") }
                    .files
                    .asSequence()
            }.distinct()
            .sortedBy { it.path }
            .forEach { source ->
                source.readLines().forEachIndexed { index, line ->
                    val code = line.substringBefore("//")
                    if (
                        logImport.containsMatchIn(code) ||
                        logCall.containsMatchIn(code) ||
                        stdStream.containsMatchIn(code) ||
                        printCall.containsMatchIn(code)
                    ) {
                        violations += "${source.relativeTo(projectDir)}:${index + 1}: registro sensible prohibido"
                    }
                }
            }

        check(violations.isEmpty()) {
            "Violaciones de la política de registro:\n${violations.joinToString("\n")}"
        }
    }
}

val verifyMobileSecurityBoundaries by tasks.registering {
    group = "verification"
    description = "Verifies Android storage, URI, cleartext, screenshot, and secret boundaries."

    val productionSources =
        listOf("main", "local", "cloud").map { sourceSet ->
            layout.projectDirectory.dir("src/$sourceSet")
        }
    val mainManifest = layout.projectDirectory.file("src/main/AndroidManifest.xml")
    val networkConfig = layout.projectDirectory.file("src/main/res/xml/network_security_config.xml")
    val settingsKeys =
        layout.projectDirectory.file(
            "src/main/java/com/facturastock/app/data/settings/AppSettingsDataStore.kt",
        )
    val retainedCipher =
        layout.projectDirectory.file(
            "src/main/java/com/facturastock/app/data/files/RetainedImageCipher.kt",
        )
    val mainActivity =
        layout.projectDirectory.file(
            "src/main/java/com/facturastock/app/MainActivity.kt",
        )
    inputs.files(productionSources, mainManifest, networkConfig, settingsKeys, retainedCipher, mainActivity)

    doLast {
        val violations = mutableListOf<String>()
        val manifestText = mainManifest.asFile.readText()
        listOf(
            "android:allowBackup=\"false\"",
            "android:fullBackupContent=\"false\"",
            "android:dataExtractionRules=\"@xml/data_extraction_rules\"",
            "android:usesCleartextTraffic=\"false\"",
            "android:networkSecurityConfig=\"@xml/network_security_config\"",
        ).filterNot(manifestText::contains).forEach { required ->
            violations += "src/main/AndroidManifest.xml: falta $required"
        }
        if (networkConfig.asFile.readText().contains("cleartextTrafficPermitted=\"true\"")) {
            violations += "src/main network security permite cleartext"
        }

        val pendingIntentCall = Regex("""\bPendingIntent\s*\.\s*(?:getActivity|getService|getBroadcast|createPendingResult)\s*\(""")
        val sensitivePreference =
            Regex(
                """(?:boolean|string|int|long|float)PreferencesKey\(\"[^\"]*(?:password|passwd|token|secret|credential)[^\"]*\"\)""",
                RegexOption.IGNORE_CASE,
            )
        productionSources
            .asSequence()
            .flatMap { directory ->
                directory.asFileTree
                    .matching { include("**/*.kt", "**/*.java") }
                    .files
                    .asSequence()
            }.distinct()
            .forEach { source ->
                val text = source.readText()
                if (
                    pendingIntentCall.containsMatchIn(text) &&
                    !text.contains("PendingIntent.FLAG_IMMUTABLE") &&
                    !text.contains("PendingIntent.FLAG_MUTABLE")
                ) {
                    violations += "${source.relativeTo(projectDir)}: PendingIntent sin mutabilidad"
                }
                if (text.contains("Intent.EXTRA_STREAM") && text.contains("Uri.fromFile")) {
                    violations += "${source.relativeTo(projectDir)}: URI file:// compartida externamente"
                }
                if (sensitivePreference.containsMatchIn(text)) {
                    violations += "${source.relativeTo(projectDir)}: credencial/token persistido"
                }
            }

        val preferenceKeys = settingsKeys.asFile.readText()
        if (sensitivePreference.containsMatchIn(preferenceKeys)) {
            violations += "AppSettingsDataStore persiste una credencial/token prohibido"
        }

        val cipherText = retainedCipher.asFile.readText()
        listOf("AndroidKeyStore", "AES/GCM/NoPadding", "setKeySize(KEY_SIZE_BITS)")
            .filterNot(cipherText::contains)
            .forEach { required -> violations += "RetainedImageCipher: falta $required" }
        val activityText = mainActivity.asFile.readText()
        if (!activityText.contains("WindowManager.LayoutParams.FLAG_SECURE")) {
            violations += "MainActivity no bloquea capturas de pantalla sensibles"
        }
        listOf("BIOMETRIC_STRONG", "DEVICE_CREDENTIAL")
            .filterNot(activityText::contains)
            .forEach { required -> violations += "MainActivity: falta recuperación $required" }

        check(violations.isEmpty()) {
            "Violaciones del hardening móvil:\n${violations.joinToString("\n")}"
        }
    }
}

val verifyLocalOcrConfiguration by tasks.registering {
    group = "verification"
    description = "Verifies bundled local OCR, infrastructure isolation, and absence of OCR keys."

    val versionCatalog = rootProject.layout.projectDirectory.file("gradle/libs.versions.toml")
    val mainSources = layout.projectDirectory.dir("src/main")
    val projectBuild = layout.projectDirectory.file("build.gradle.kts")
    val rootBuild = rootProject.layout.projectDirectory.file("build.gradle.kts")
    val settings = rootProject.layout.projectDirectory.file("settings.gradle.kts")
    inputs.files(versionCatalog, mainSources, projectBuild, rootBuild, settings)

    doLast {
        val catalogText = versionCatalog.asFile.readText()
        val projectBuildText = projectBuild.asFile.readText()
        check(
            catalogText.contains(
                "mlkit-text-recognition = { module = \"com.google.mlkit:text-recognition\"",
            ),
        ) { "OCR debe usar el artefacto latino integrado com.google.mlkit:text-recognition" }
        check(!catalogText.contains("com.google.android.gms:play-services-mlkit-text-recognition\"")) {
            "No se permite la variante OCR descargable de Google Play Services"
        }
        check(projectBuildText.contains("implementation(libs.mlkit.text.recognition)")) {
            "El artefacto OCR latino integrado debe estar declarado como dependencia de la app"
        }
        check(!catalogText.contains("com.google.mlkit:barcode-scanning")) {
            "El lector de códigos HID no debe empaquetar el modelo de cámara ML Kit Barcode"
        }
        val barcodeAlias = listOf("libs", "mlkit", "barcode", "scanning").joinToString(".")
        check(
            projectBuildText.lineSequence().none { line ->
                line.trim() == "implementation($barcodeAlias)"
            },
        ) {
            "El lector de códigos HID no debe declarar ML Kit Barcode como dependencia"
        }

        val violations = mutableListOf<String>()
        val apiKey = Regex("""AIza[0-9A-Za-z_-]{35}""")
        val mlKitImport = Regex("""^import\s+com\.google\.mlkit\.""")
        mainSources.asFileTree
            .matching { include("**/*.kt", "**/*.xml", "**/*.json", "**/*.properties") }
            .files
            .sortedBy { it.path }
            .forEach { source ->
                val relative = source.relativeTo(projectDir).invariantSeparatorsPath
                source.readLines().forEachIndexed { index, line ->
                    if (apiKey.containsMatchIn(line)) {
                        violations += "$relative:${index + 1}: posible API key"
                    }
                    if (
                        source.extension == "kt" &&
                        mlKitImport.containsMatchIn(line) &&
                        !relative.contains("/data/ocr/")
                    ) {
                        violations += "$relative:${index + 1}: ML Kit fuera de data/ocr"
                    }
                }
            }

        listOf(projectBuild.asFile, rootBuild.asFile, settings.asFile)
            .filter(File::isFile)
            .forEach { source ->
                if (apiKey.containsMatchIn(source.readText())) {
                    violations += "${source.relativeTo(rootProject.projectDir)}: posible API key"
                }
            }
        val googleServicesFiles =
            rootProject
                .fileTree(rootProject.projectDir) {
                    include("**/google-services.json")
                    exclude("**/build/**", "**/.gradle/**")
                }.files
        googleServicesFiles.forEach { source ->
            violations += "${source.relativeTo(rootProject.projectDir)}: google-services.json no permitido"
        }
        check(violations.isEmpty()) {
            "Configuración OCR local inválida:\n${violations.joinToString("\n")}"
        }
    }
}

private val offlineFirstSharedConfigurationNames =
    setOf(
        "implementation",
        "api",
        "compileOnly",
        "runtimeOnly",
        "debugImplementation",
        "releaseImplementation",
        "testImplementation",
        "androidTestImplementation",
    )

// Snapshot dependency declarations during configuration. Reading Task.project from doLast is
// deprecated, prevents configuration-cache reuse and becomes an error in Gradle 10.
val firebaseDependenciesOutsideCloudFlavor =
    offlineFirstSharedConfigurationNames
        .mapNotNull(configurations::findByName)
        .flatMap { configuration ->
            configuration.dependencies
                .filter { dependency -> dependency.group?.startsWith("com.google.firebase") == true }
                .map { dependency ->
                    "${configuration.name} -> ${dependency.group}:${dependency.name}"
                }
        }

val verifyOfflineFirstBoundaries by tasks.registering {
    group = "verification"
    description = "Verifies that UI cannot read the network and that airplane-mode support stays explicit."

    val featureSources =
        listOf("main", "local", "cloud").flatMap { flavor ->
            listOf("java", "kotlin").map { sourceKind ->
                layout.projectDirectory.dir("src/$flavor/$sourceKind/com/facturastock/app/feature")
            }
        }
    val localManifest = layout.projectDirectory.file("src/local/AndroidManifest.xml")
    val versionCatalog = rootProject.layout.projectDirectory.file("gradle/libs.versions.toml")
    val projectBuild = layout.projectDirectory.file("build.gradle.kts")
    val repositoryModule =
        layout.projectDirectory.file(
            "src/main/java/com/facturastock/app/di/RepositoryModule.kt",
        )
    inputs.files(featureSources, localManifest, versionCatalog, projectBuild, repositoryModule)

    doLast {
        val forbiddenUiImports =
            listOf(
                "java.net.",
                "java.net.http.",
                "okhttp3.",
                "retrofit2.",
                "io.ktor.client.",
                "com.google.firebase.",
                "com.facturastock.app.data.",
            )
        val violations = mutableListOf<String>()
        featureSources
            .asSequence()
            .flatMap { directory ->
                directory.asFileTree
                    .matching { include("**/*.kt") }
                    .files
                    .asSequence()
            }.distinct()
            .sortedBy(File::getPath)
            .forEach { source ->
                source.readLines().forEachIndexed { index, line ->
                    val importedName =
                        line
                            .substringBefore("//")
                            .trim()
                            .takeIf { it.startsWith("import ") }
                            ?.removePrefix("import ")
                    if (
                        importedName != null &&
                        forbiddenUiImports.any(importedName::startsWith)
                    ) {
                        violations += "${source.relativeTo(projectDir)}:${index + 1}: fuente remota/data prohibida en UI: $importedName"
                    }
                }
            }

        // La política offline-first es la del flavor local: sin INTERNET, sin Firebase.
        val localManifestText = localManifest.asFile.readText()
        if (
            !localManifestText.contains("android.permission.INTERNET") ||
            !localManifestText.contains("tools:node=\"remove\"")
        ) {
            violations += "src/local/AndroidManifest.xml: INTERNET debe permanecer eliminado explícitamente"
        }

        // Firebase solo puede entrar al APK del flavor cloud. Se verifica sobre el modelo de
        // configuraciones: ninguna configuración compartida puede declarar módulos Firebase.
        firebaseDependenciesOutsideCloudFlavor.forEach { dependency ->
            violations += "app/build.gradle.kts: Firebase fuera del flavor cloud: $dependency"
        }
        val catalogText = versionCatalog.asFile.readText().lowercase()
        listOf("retrofit", "okhttp", "ktor-client")
            .filter(catalogText::contains)
            .forEach { dependency ->
                violations += "gradle/libs.versions.toml: cliente remoto no permitido en el flujo offline: $dependency"
            }

        val repositoryBindings = repositoryModule.asFile.readText()
        listOf("InMemoryDraftWorkflowRepository", "InitialFeatureRepository")
            .filter(repositoryBindings::contains)
            .forEach { implementation ->
                violations += "RepositoryModule.kt: $implementation no puede ser fuente productiva"
            }
        if (!repositoryBindings.contains("RoomDraftWorkflowRepository")) {
            violations += "RepositoryModule.kt: el flujo durable debe resolverse desde Room"
        }

        check(violations.isEmpty()) {
            "Violaciones de la arquitectura offline-first:\n${violations.joinToString("\n")}"
        }
    }
}

val verifyCloudAppCheckBoundaries by tasks.registering {
    group = "verification"
    description = "Verifies that debug App Check and emulator config never enter cloud release."

    val cloudReleaseSources =
        listOf("main", "cloud", "cloudRelease", "cloudBenchmark", "cloudProfile").map { sourceSet ->
            layout.projectDirectory.dir("src/$sourceSet")
        }
    inputs.files(cloudReleaseSources)

    doLast {
        fun resolvedModules(configurationName: String): Set<String> =
            configurations
                .getByName(configurationName)
                .incoming
                .resolutionResult
                .allComponents
                .mapNotNull { component -> component.moduleVersion }
                .mapTo(mutableSetOf()) { module -> "${module.group}:${module.name}" }

        val debugModules = resolvedModules("cloudDebugRuntimeClasspath")
        val sparkModules = resolvedModules("cloudSparkRuntimeClasspath")
        val releaseModules = resolvedModules("cloudReleaseRuntimeClasspath")
        val benchmarkModules = resolvedModules("cloudBenchmarkRuntimeClasspath")
        val profileModules = resolvedModules("cloudProfileRuntimeClasspath")
        val debugProvider = "com.google.firebase:firebase-appcheck-debug"
        val releaseProvider = "com.google.firebase:firebase-appcheck-playintegrity"

        check(debugProvider in debugModules) {
            "cloudDebug debe incluir $debugProvider"
        }
        check(releaseProvider !in debugModules) {
            "cloudDebug no debe incluir $releaseProvider"
        }
        check(debugProvider in sparkModules) {
            "cloudSpark debe incluir $debugProvider para registrar esta instalación"
        }
        check(releaseProvider !in sparkModules) {
            "cloudSpark no debe incluir $releaseProvider"
        }
        check(releaseProvider in releaseModules) {
            "cloudRelease debe incluir $releaseProvider"
        }
        check(debugProvider !in releaseModules) {
            "cloudRelease no debe incluir $debugProvider"
        }
        check(releaseProvider in benchmarkModules) {
            "cloudBenchmark debe usar el proveedor no-debug $releaseProvider"
        }
        check(debugProvider !in benchmarkModules) {
            "cloudBenchmark no debe incluir $debugProvider"
        }
        check(releaseProvider in profileModules) {
            "cloudProfile debe usar el proveedor no-debug $releaseProvider"
        }
        check(debugProvider !in profileModules) {
            "cloudProfile no debe incluir $debugProvider"
        }

        val forbiddenEmulatorLiterals =
            setOf(
                "demo-facturastock",
                "demo-api-key",
                emulatorFirebaseApiKeyMarker,
                "1:1000000000000:android:0000000000000000000000",
                "10.0.2.2",
                "devEnsureMembership",
            )
        val sourceViolations = mutableListOf<String>()
        cloudReleaseSources
            .asSequence()
            .flatMap { directory ->
                directory.asFileTree
                    .matching { include("**/*.kt", "**/*.xml", "**/*.json", "**/*.properties") }
                    .files
                    .asSequence()
            }.distinct()
            .sortedBy { source -> source.path }
            .forEach { source ->
                val text = source.readText()
                forbiddenEmulatorLiterals
                    .filter(text::contains)
                    .forEach { literal ->
                        sourceViolations +=
                            "${source.relativeTo(projectDir).invariantSeparatorsPath}: literal emulator prohibido $literal"
                    }
            }
        check(sourceViolations.isEmpty()) {
            "Configuración emulator filtrada a cloudRelease:\n${sourceViolations.joinToString("\n")}"
        }
    }
}

val verifyCloudSparkBoundaries by tasks.registering {
    group = "verification"
    description = "Verifies that cloudSpark uses real config and never connects paid/emulated services."

    val sparkSources = layout.projectDirectory.dir("src/spark")
    inputs.dir(sparkSources)
    inputs.property("firebaseProjectConfigured", firebaseProjectId.isNotBlank())
    inputs.property("firebaseApplicationConfigured", firebaseApplicationId.isNotBlank())
    inputs.property("firebaseApiKeyConfigured", firebaseApiKey.isNotBlank())

    doLast {
        check(firebaseProjectId.isNotBlank()) {
            "cloudSpark requiere firebase.projectId en local.properties"
        }
        check(firebaseApplicationId.isNotBlank()) {
            "cloudSpark requiere firebase.applicationId en local.properties"
        }
        check(firebaseApiKey.isNotBlank()) {
            "cloudSpark requiere firebase.apiKey en local.properties"
        }
        check(Regex("[a-z][a-z0-9-]{4,28}[a-z0-9]").matches(firebaseProjectId)) {
            "firebase.projectId no tiene sintaxis Firebase válida"
        }
        check(Regex("1:[0-9]+:android:[0-9a-f]+").matches(firebaseApplicationId)) {
            "firebase.applicationId no tiene sintaxis Android Firebase válida"
        }
        check(Regex("AIza[0-9A-Za-z_-]{35}").matches(firebaseApiKey)) {
            "firebase.apiKey no tiene sintaxis Firebase válida"
        }

        val forbiddenRuntimeFragments =
            setOf(
                ".useEmulator(",
                "FirebaseFunctions.getInstance",
                "FirebaseStorage.getInstance",
                "10.0.2.2",
                "localhost",
                "demo-facturastock",
            )
        val violations = mutableListOf<String>()
        sparkSources.asFileTree
            .matching { include("**/*.kt", "**/*.xml", "**/*.json", "**/*.properties") }
            .files
            .sortedBy(File::getPath)
            .forEach { source ->
                val text = source.readText()
                forbiddenRuntimeFragments.filter(text::contains).forEach { fragment ->
                    violations +=
                        "${source.relativeTo(projectDir).invariantSeparatorsPath}: $fragment"
                }
            }
        check(violations.isEmpty()) {
            "cloudSpark contiene conexiones prohibidas:\n${violations.joinToString("\n")}"
        }
    }
}

val cloudReleaseBundle =
    layout.buildDirectory.file("outputs/bundle/cloudRelease/app-cloud-release.aab")
val verifyCloudReleaseBundleHygiene by tasks.registering {
    group = "verification"
    description = "Scans cloudRelease DEX and rejects Emulator Suite configuration literals."
    dependsOn("bundleCloudRelease")
    inputs.file(cloudReleaseBundle)

    doLast {
        val bundle = cloudReleaseBundle.get().asFile
        check(bundle.isFile) { "No existe el AAB cloudRelease para inspección" }
        val forbidden =
            listOf(
                "demo-facturastock",
                "demo-api-key",
                emulatorFirebaseApiKeyMarker,
                "1:1000000000000:android:0000000000000000000000",
                "10.0.2.2",
                "devEnsureMembership",
            )
        val violations = mutableSetOf<String>()
        var dexCount = 0

        ZipFile(bundle).use { archive ->
            archive
                .entries()
                .asSequence()
                .filter { entry -> !entry.isDirectory && entry.name.endsWith(".dex") }
                .forEach { entry ->
                    dexCount += 1
                    val dexText =
                        archive.getInputStream(entry).use { stream ->
                            stream.readBytes().toString(Charsets.ISO_8859_1)
                        }
                    forbidden.forEach { literal ->
                        if (dexText.contains(literal)) {
                            violations += "${entry.name}: $literal"
                        }
                    }
                }
        }
        check(dexCount > 0) { "El AAB cloudRelease no contiene DEX inspeccionable" }
        check(violations.isEmpty()) {
            "cloudRelease contiene configuración del Emulator Suite: ${violations.sorted()}"
        }
    }
}

val verifyAndroidReleaseConfiguration by tasks.registering {
    group = "verification"
    description = "Runs all deterministic Android release identity and packaging gates."
    dependsOn(
        verifyAndroidVariantMatrix,
        verifyReleaseManifests,
        verifyCloudAppCheckBoundaries,
        verifyLocalProfileRuleResolution,
        "verifyR8ReleaseConfiguration",
    )
}

val verifyR8ReleaseConfiguration by tasks.registering {
    group = "verification"
    description = "Verifies R8, resource shrinking, profiles, and the maintained keep policy."
    val rules = layout.projectDirectory.file("proguard-rules.pro")
    val baselineProfile = layout.projectDirectory.file("src/main/baseline-prof.txt")
    val startupProfile =
        layout.projectDirectory.file("src/main/baselineProfiles/startup-prof.txt")
    val profileGenerator =
        rootProject.layout.projectDirectory.file(
            "benchmark/src/main/java/com/facturastock/app/benchmark/FacturaStockBaselineProfile.kt",
        )
    val startupJourney =
        rootProject.layout.projectDirectory.file(
            "benchmark/src/main/java/com/facturastock/app/benchmark/StartupJourney.kt",
        )
    val profileManifest = layout.projectDirectory.file("src/profile/AndroidManifest.xml")
    val gradleProperties = rootProject.layout.projectDirectory.file("gradle.properties")
    inputs.files(
        rules,
        baselineProfile,
        startupProfile,
        profileGenerator,
        startupJourney,
        profileManifest,
        gradleProperties,
    )

    doLast {
        val release = android.buildTypes.getByName("release")
        val benchmark = android.buildTypes.getByName("benchmark")
        val profile = android.buildTypes.getByName("profile")
        check(release.isMinifyEnabled) { "release debe ejecutar R8" }
        check(release.isShrinkResources) { "release debe eliminar recursos no usados" }
        check(benchmark.isMinifyEnabled && benchmark.isShrinkResources) {
            "benchmark debe conservar la optimización del artefacto release medido"
        }
        check(!profile.isMinifyEnabled && !profile.isShrinkResources && !profile.isDebuggable) {
            "profile debe ser no minificada, no debuggable y exclusiva de generación"
        }
        check(profileManifest.asFile.readText().contains("<profileable android:shell=\"true\"")) {
            "profile debe permitir que shell recopile reglas sin volver la app debuggable"
        }
        val properties =
            Properties().apply {
                gradleProperties.asFile.inputStream().use(::load)
            }
        check(properties.getProperty("android.r8.optimizedResourceShrinking") == "true") {
            "AGP 8.13 debe usar el shrinker integrado de código y recursos"
        }

        val rulesText = rules.asFile.readText()
        check(
            rulesText.contains(
                "-keep class com.facturastock.app.** extends androidx.room.RoomDatabase",
            ),
        ) {
            "Falta la política R8 de Room acotada al namespace de la app"
        }
        check(
            rulesText.contains(
                "-keepnames class com.facturastock.app.** extends androidx.work.CoroutineWorker",
            ),
        ) {
            "Falta preservar por nombre únicamente los CoroutineWorker de la app"
        }
        check(rulesText.contains("-keepclassmembers enum com.facturastock.app.**")) {
            "Falta preservar los miembros de enums persistidos dentro del namespace de la app"
        }
        listOf("-dontshrink", "-dontoptimize", "-dontobfuscate").forEach { forbidden ->
            check(!Regex("(?m)^\\s*${Regex.escape(forbidden)}(?:\\s|$)").containsMatchIn(rulesText)) {
                "$forbidden desactiva una optimización de release global"
            }
        }
        listOf(
            Regex("(?m)^\\s*-keep\\s+class\\s+\\*\\s+extends\\s+androidx\\.room\\.RoomDatabase"),
            Regex("(?m)^\\s*-keepnames\\s+class\\s+\\*\\s+extends\\s+androidx\\.work\\.CoroutineWorker"),
            Regex("(?m)^\\s*-keepclassmembers\\s+enum\\s+\\*"),
        ).forEach { broadRule ->
            check(!broadRule.containsMatchIn(rulesText)) {
                "Una regla R8 global impide optimizar código de dependencias: ${broadRule.pattern}"
            }
        }

        val maintainedRules =
            baselineProfile.asFile
                .readLines()
                .map(String::trim)
                .filter { line -> line.isNotEmpty() && !line.startsWith("#") }
        check(maintainedRules.isNotEmpty()) {
            "src/main/baseline-prof.txt debe conservar reglas de recorridos medidos"
        }
        check(
            maintainedRules.all { rule ->
                Regex("^[HSP]*Lcom/facturastock/app/").containsMatchIn(rule)
            },
        ) {
            "El perfil manual no debe apropiarse de clases de dependencias"
        }
        val variantMangledProfileMethod =
            Regex("\\$[^;()]*_(local|cloud)(Debug|Release|Benchmark|Profile)\\b")
        check(maintainedRules.none(variantMangledProfileMethod::containsMatchIn)) {
            "El Baseline Profile contiene métodos internal ligados a una variante"
        }
        val requiredManualCujRules =
            setOf(
                "HPLcom/facturastock/app/domain/normalization/**->**(**)**",
                "Lcom/facturastock/app/domain/normalization/**;",
                "HPLcom/facturastock/app/feature/linereview/InvoiceLineReviewScreenKt**->**(**)**",
                "HPLcom/facturastock/app/feature/linereview/InvoiceLineReviewContract**->**(**)**",
                "HPLcom/facturastock/app/ui/format/FormattersKt**->**(**)**",
            )
        check(maintainedRules.containsAll(requiredManualCujRules)) {
            "El Baseline Profile perdió CUJ manuales de normalización o lista de cien líneas"
        }

        val startupRules =
            startupProfile.asFile
                .readLines()
                .map(String::trim)
                .filter { line -> line.isNotEmpty() && !line.startsWith("#") }
        check(startupRules.isNotEmpty()) {
            "src/main/baselineProfiles/startup-prof.txt debe conservar reglas observadas de arranque"
        }
        check(startupRules.none(variantMangledProfileMethod::containsMatchIn)) {
            "El Startup Profile contiene métodos internal ligados a una variante"
        }
        check(
            startupRules.all { rule ->
                Regex("^[HSP]*Lcom/facturastock/app/").containsMatchIn(rule) &&
                    if ("->" in rule) {
                        'S' in rule.substringBefore('L')
                    } else {
                        Regex("^[HSP]*Lcom/facturastock/app/[^;]+;$").matches(rule)
                    }
            },
        ) {
            "Startup solo admite owners app; sus métodos llevan S y sus clases son descriptores"
        }
        val normalizedBaselineRules =
            maintainedRules.mapTo(mutableSetOf()) { rule ->
                rule.dropWhile { flag -> flag == 'H' || flag == 'S' || flag == 'P' }
            }
        check(
            startupRules.all { rule ->
                rule.dropWhile { flag -> flag == 'H' || flag == 'S' || flag == 'P' } in
                    normalizedBaselineRules
            },
        ) {
            "Toda regla Startup debe estar contenida también en el Baseline Profile"
        }
        val startupRuleOwner: (String) -> String = { rule ->
            rule.substringAfter('L', missingDelimiterValue = "").substringBefore(';')
        }
        setOf(
            "com/facturastock/app/FacturaStockApplication",
            "com/facturastock/app/MainActivity",
        ).forEach { requiredOwner ->
            check(startupRules.any { rule -> startupRuleOwner(rule) == requiredOwner }) {
                "El Startup Profile perdió el owner medido $requiredOwner"
            }
        }
        setOf(
            "com/facturastock/app/feature/home/HomeRouteKt",
            "com/facturastock/app/feature/home/HomeScreenKt",
            "com/facturastock/app/feature/home/HomeDashboardContentKt",
        ).forEach { requiredHomeOwner ->
            check(startupRules.any { rule -> startupRuleOwner(rule) == requiredHomeOwner }) {
                "El Startup Profile termina antes del Home listo: falta $requiredHomeOwner"
            }
        }

        val generatorText = profileGenerator.asFile.readText()
        check(generatorText.contains("includeInStartupProfile = true")) {
            "El generador debe exportar también el Startup Profile"
        }
        check(generatorText.contains("includeInStartupProfile = false")) {
            "El generador debe exportar por separado el Baseline Profile instalable"
        }
        check(generatorText.contains("^[HSP]*Lcom/facturastock/app/")) {
            "El generador debe filtrar por owner y no por referencias a clases de la app"
        }
        check(
            generatorText.contains("filterPredicate = applicationProfileRule::matches") &&
                !generatorText.contains("deferredMaintenanceOwnerPrefixes"),
        ) {
            "El generador debe conservar owners de la app sin ocultar contaminación con blacklist"
        }
        check(
            generatorText.contains("StartupJourney.prepare(resetPersistentState = true)"),
        ) {
            "El onboarding debe prepararse desde estado durable limpio antes de cada perfil"
        }
        check(generatorText.contains("StartupJourney.waitForHomeReady")) {
            "El perfil debe recorrer el arranque hasta que Home esté operativo"
        }
        val startupJourneyText = startupJourney.asFile.readText()
        check(
            startupJourneyText.contains(
                "private const val HOME_READY_TAG = \"home_scan_cta\"",
            ),
        ) {
            "El recorrido de perfil perdió el marcador de contenido Home"
        }
    }
}

fun registerReleaseBundleOptimizationVerification(
    taskName: String,
    bundleTaskName: String,
    bundleRelativePath: String,
) = tasks.register(taskName) {
    group = "verification"
    description = "Verifies compiled profile and R8 metadata in $bundleRelativePath."
    dependsOn(bundleTaskName)

    val bundle = layout.buildDirectory.file(bundleRelativePath)
    inputs.file(bundle)

    doLast {
        val bundleFile = bundle.get().asFile
        check(bundleFile.isFile) { "No existe ${bundleFile.invariantSeparatorsPath}" }
        ZipFile(bundleFile).use { archive ->
            val profilePath = "BUNDLE-METADATA/com.android.tools.build.profiles/baseline.prof"
            val profileMetadataPath =
                "BUNDLE-METADATA/com.android.tools.build.profiles/baseline.profm"
            val r8MetadataPath = "BUNDLE-METADATA/com.android.tools/r8.json"
            listOf(profilePath, profileMetadataPath).forEach { entryName ->
                val entry = archive.getEntry(entryName)
                check(entry != null && entry.size > 0L) {
                    "${bundleFile.name} no contiene $entryName compilado"
                }
            }

            val r8Entry =
                archive.getEntry(r8MetadataPath)
                    ?: error("${bundleFile.name} no contiene metadata verificable de R8")
            val r8Metadata = archive.getInputStream(r8Entry).bufferedReader().use { it.readText() }
            listOf(
                "isObfuscationEnabled",
                "isOptimizationsEnabled",
                "isShrinkingEnabled",
                "isOptimizedShrinkingEnabled",
            ).forEach { enabledFlag ->
                check(
                    Regex("\\\"${Regex.escape(enabledFlag)}\\\"\\s*:\\s*true")
                        .containsMatchIn(r8Metadata),
                ) {
                    "${bundleFile.name}: R8 no confirma $enabledFlag=true"
                }
            }
            check(
                Regex("\\\"isDebugModeEnabled\\\"\\s*:\\s*false")
                    .containsMatchIn(r8Metadata),
            ) {
                "${bundleFile.name}: la optimización R8 se generó en modo debug"
            }
            val startupDexStates =
                Regex("\\\"startup\\\"\\s*:\\s*(true|false)")
                    .findAll(r8Metadata)
                    .map { match -> match.groupValues[1].toBoolean() }
                    .toList()
            check(startupDexStates.isNotEmpty()) {
                "${bundleFile.name}: R8 no declaró el estado de layout DEX de inicio"
            }
            check(startupDexStates.any { it }) {
                "${bundleFile.name}: el Startup Profile no produjo layout DEX de inicio"
            }

            val forbiddenEntries =
                archive
                    .entries()
                    .asSequence()
                    .map { entry -> entry.name }
                    .filter { entryName ->
                        entryName.contains("mlkit_barcode_models") ||
                            entryName.endsWith("/libbarhopper_v3.so") ||
                            entryName.substringAfterLast('/').startsWith("barcode-scanning")
                    }.toList()
            check(forbiddenEntries.isEmpty()) {
                "${bundleFile.name} aún empaqueta el scanner de cámara ML Kit: $forbiddenEntries"
            }

            logger.lifecycle(
                "${bundleFile.name}: baseline profile compilado, R8/recurso optimizados; " +
                    "DEX startup=${startupDexStates.joinToString()}",
            )
        }
    }
}

val verifyLocalReleaseBundleOptimization =
    registerReleaseBundleOptimizationVerification(
        taskName = "verifyLocalReleaseBundleOptimization",
        bundleTaskName = "bundleLocalRelease",
        bundleRelativePath = "outputs/bundle/localRelease/app-local-release.aab",
    )
val verifyCloudReleaseBundleOptimization =
    registerReleaseBundleOptimizationVerification(
        taskName = "verifyCloudReleaseBundleOptimization",
        bundleTaskName = "bundleCloudRelease",
        bundleRelativePath = "outputs/bundle/cloudRelease/app-cloud-release.aab",
    )

val verifyCloudProductionReleaseInputs by tasks.registering {
    group = "verification"
    description = "Requires explicit version, cloud config, and external signing for distribution."

    doLast {
        check(rawVersionCode != null && rawVersionName != null) {
            "El candidato cloudRelease requiere FACTURASTOCK_VERSION_CODE y FACTURASTOCK_VERSION_NAME"
        }
        check(hasCiReleaseSigning && releaseSigningStoreFile != null) {
            "El candidato cloudRelease requiere las cuatro variables FACTURASTOCK_SIGNING_*"
        }
        check(
            firebaseProjectId.isNotBlank() &&
                firebaseApplicationId.isNotBlank() &&
                firebaseApiKey.isNotBlank() &&
                firebaseStorageBucket.isNotBlank(),
        ) {
            "El candidato cloudRelease requiere Firebase y su bucket Storage protegidos completos"
        }
        check(Regex("[a-z][a-z0-9-]{4,28}[a-z0-9]").matches(firebaseProjectId)) {
            "FACTURASTOCK_FIREBASE_PROJECT_ID no tiene sintaxis de projectId Firebase válida"
        }
        check(Regex("1:[0-9]+:android:[0-9a-f]+").matches(firebaseApplicationId)) {
            "FACTURASTOCK_FIREBASE_APPLICATION_ID no tiene sintaxis de appId Android válida"
        }
        check(Regex("AIza[0-9A-Za-z_-]{35}").matches(firebaseApiKey)) {
            "FACTURASTOCK_FIREBASE_API_KEY no tiene sintaxis de API key Firebase válida"
        }
        check(
            Regex("[a-z0-9][a-z0-9._-]{1,220}[a-z0-9]").matches(firebaseStorageBucket) &&
                ".." !in firebaseStorageBucket,
        ) {
            "FACTURASTOCK_FIREBASE_STORAGE_BUCKET no tiene sintaxis de bucket válida"
        }
        check(privacyPolicyUrl.isNotBlank()) {
            "El candidato cloudRelease requiere FACTURASTOCK_PRIVACY_POLICY_URL HTTPS"
        }
    }
}

val verifyCloudProductionReleaseQuality by tasks.registering {
    group = "verification"
    description = "Runs release-variant Lint and unit tests before materializing a production candidate."
    dependsOn(
        "lintCloudRelease",
        "testCloudReleaseUnitTest",
    )
}

val packageCloudProductionRelease by tasks.registering {
    group = "build"
    description = "Builds signed cloudRelease APK/AAB candidates; it never publishes them."
    dependsOn(
        verifyCloudProductionReleaseInputs,
        verifyCloudProductionReleaseQuality,
        verifyAndroidReleaseConfiguration,
        verifyCloudReleaseBundleHygiene,
        verifyCloudReleaseBundleOptimization,
        "assembleCloudRelease",
        "bundleCloudRelease",
    )
}

val releasePackagingTaskNames =
    setOf(
        "assembleLocalRelease",
        "assembleCloudRelease",
        "bundleLocalRelease",
        "bundleCloudRelease",
    )
tasks.matching { task -> task.name in releasePackagingTaskNames }.configureEach {
    dependsOn(verifyAndroidReleaseConfiguration)
    if (name == "assembleCloudRelease" || name == "bundleCloudRelease") {
        mustRunAfter(verifyCloudProductionReleaseInputs)
        if (hasCiReleaseSigning) {
            // Un cloudRelease firmado ya es un candidato de distribución: no puede eludir los
            // inputs ni la calidad release aunque alguien invoque assemble/bundle directamente.
            dependsOn(
                verifyCloudProductionReleaseInputs,
                verifyCloudProductionReleaseQuality,
            )
        }
    }
}

verifyOfflineFirstBoundaries.configure {
    dependsOn(verifyCloudAppCheckBoundaries, verifyCloudSparkBoundaries)
}

tasks.matching { task -> task.name == "assembleCloudSpark" }.configureEach {
    dependsOn(verifyCloudSparkBoundaries)
}

val verifyRoomSchemaPolicy by tasks.registering {
    group = "verification"
    description =
        "Verifies Room schemas, migrations, WAL, and fail-closed non-destructive production setup."

    val databaseSource =
        layout.projectDirectory.file(
            "src/main/java/com/facturastock/app/data/local/FacturaStockDatabase.kt",
        )
    val schemaDirectory =
        layout.projectDirectory.dir(
            "schemas/com.facturastock.app.data.local.FacturaStockDatabase",
        )
    val operationalPolicySource =
        layout.projectDirectory.file(
            "src/main/java/com/facturastock/app/data/local/FailClosedSQLiteOpenHelperFactory.kt",
        )
    val snapshotContractSource =
        layout.projectDirectory.file(
            "src/main/java/com/facturastock/app/data/restore/FullDeviceSnapshotContract.kt",
        )
    val productionSourceDirectories =
        listOf("main", "local", "cloud").flatMap { sourceSet ->
            listOf("java", "kotlin").map { language ->
                layout.projectDirectory.dir("src/$sourceSet/$language")
            }
        }
    inputs.file(databaseSource)
    inputs.file(operationalPolicySource)
    inputs.file(snapshotContractSource)
    inputs.dir(schemaDirectory)
    inputs.files(productionSourceDirectories)

    doLast {
        val databaseText = databaseSource.asFile.readText()
        val snapshotContractText = snapshotContractSource.asFile.readText()
        val databaseAnnotation =
            checkNotNull(
                Regex(
                    """@Database\s*\((.*?)\)\s*abstract\s+class\s+FacturaStockDatabase""",
                    RegexOption.DOT_MATCHES_ALL,
                ).find(databaseText)?.groupValues?.get(1),
            ) { "No se pudo localizar @Database de FacturaStockDatabase" }
        val databaseVersion =
            checkNotNull(
                Regex("""\bversion\s*=\s*(\d+)\b""")
                    .find(databaseAnnotation)
                    ?.groupValues
                    ?.get(1)
                    ?.toIntOrNull(),
            ) { "@Database debe declarar una versión entera literal" }
        val sharedDatabaseVersion =
            checkNotNull(
                Regex(
                    """\bconst\s+val\s+FACTURA_STOCK_DATABASE_SCHEMA_VERSION\s*:\s*Int\s*=\s*(\d+)\b""",
                ).find(databaseText)?.groupValues?.get(1)?.toIntOrNull(),
            ) {
                "FacturaStockDatabase debe publicar FACTURA_STOCK_DATABASE_SCHEMA_VERSION"
            }
        check(sharedDatabaseVersion == databaseVersion) {
            "El contrato de restore declara esquema $sharedDatabaseVersion pero @Database usa " +
                databaseVersion
        }
        check(
            Regex(
                """maxSupportedDatabaseSchemaVersion\s*:\s*Int\s*=\s*FACTURA_STOCK_DATABASE_SCHEMA_VERSION\b""",
            ).containsMatchIn(snapshotContractText),
        ) {
            "El techo de FULL_DEVICE_SNAPSHOT debe usar la versión Room compartida"
        }
        check(Regex("""\bexportSchema\s*=\s*true\b""").containsMatchIn(databaseAnnotation)) {
            "FacturaStockDatabase debe mantener exportSchema = true"
        }
        check(Regex("""\.openHelperFactory\s*\(""").containsMatchIn(databaseText)) {
            "El builder productivo debe instalar el open-helper que preserva una base corrupta"
        }
        check(
            Regex(
                """\.setJournalMode\s*\(\s*RoomDatabase\.JournalMode\.WRITE_AHEAD_LOGGING\s*\)""",
                RegexOption.DOT_MATCHES_ALL,
            ).containsMatchIn(databaseText),
        ) {
            "El builder productivo debe fijar WRITE_AHEAD_LOGGING explícitamente"
        }

        val operationalPolicyText = operationalPolicySource.asFile.readText()
        check(
            Regex("""\.allowDataLossOnRecovery\s*\(\s*false\s*\)""")
                .containsMatchIn(operationalPolicyText),
        ) {
            "El open-helper productivo no puede permitir recuperación destructiva"
        }
        check(
            Regex("""override\s+fun\s+onCorruption\s*\(""")
                .containsMatchIn(operationalPolicyText),
        ) {
            "El open-helper productivo debe definir una política explícita de corrupción"
        }

        val schemaFiles =
            schemaDirectory.asFile
                .listFiles()
                .orEmpty()
                .filter { it.isFile && it.extension == "json" }
        val invalidSchemaNames = schemaFiles.filter { it.nameWithoutExtension.toIntOrNull() == null }
        check(invalidSchemaNames.isEmpty()) {
            "Los esquemas Room deben llamarse <version>.json: " +
                invalidSchemaNames.joinToString { it.name }
        }
        val schemasByVersion = schemaFiles.associateBy { it.nameWithoutExtension.toInt() }
        val expectedSchemaVersions = (1..databaseVersion).toSet()
        check(schemasByVersion.keys == expectedSchemaVersions) {
            val missing = expectedSchemaVersions - schemasByVersion.keys
            val unexpected = schemasByVersion.keys - expectedSchemaVersions
            "Cadena de esquemas Room incompleta para versión $databaseVersion; " +
                "faltan=${missing.sorted()}, sobran=${unexpected.sorted()}"
        }
        val exportedVersion =
            Regex(
                """"database"\s*:\s*\{.*?"version"\s*:\s*(\d+)""",
                RegexOption.DOT_MATCHES_ALL,
            )
        schemasByVersion.toSortedMap().forEach { (version, schema) ->
            val declaredVersion =
                exportedVersion
                    .find(schema.readText())
                    ?.groupValues
                    ?.get(1)
                    ?.toIntOrNull()
            check(declaredVersion == version) {
                "${schema.relativeTo(projectDir)} declara database.version=$declaredVersion, se esperaba $version"
            }
        }
        val minimumSnapshotSchemaVersion =
            checkNotNull(
                Regex(
                    """MIN_SOURCE_DATABASE_SCHEMA_VERSION\s*=\s*(\d+)\b""",
                ).find(snapshotContractText)?.groupValues?.get(1)?.toIntOrNull(),
            ) { "FULL_DEVICE_SNAPSHOT debe declarar su versión Room mínima" }
        (minimumSnapshotSchemaVersion..databaseVersion).forEach { restoreVersion ->
            val identityConstantName = "FACTURA_STOCK_ROOM_IDENTITY_HASH_V$restoreVersion"
            val identityHash =
                checkNotNull(
                    Regex(
                        """\bconst\s+val\s+${Regex.escape(identityConstantName)}\s*:\s*String\s*=\s*\"([0-9a-f]{32})\"""",
                    ).find(databaseText)?.groupValues?.get(1),
                ) { "Falta $identityConstantName para el restore aislado" }
            val exportedIdentity =
                Regex("""\"identityHash\"\s*:\s*\"([0-9a-f]{32})\"""")
                    .find(schemasByVersion.getValue(restoreVersion).readText())
                    ?.groupValues
                    ?.get(1)
            check(exportedIdentity == identityHash) {
                "$identityConstantName=$identityHash no coincide con el schema Room " +
                    "$restoreVersion ($exportedIdentity)"
            }
            check(
                Regex(
                    """\b$restoreVersion\s*->\s*${Regex.escape(identityConstantName)}\b""",
                ).containsMatchIn(databaseText),
            ) {
                "expectedFacturaStockRoomIdentityHash no enruta la versión $restoreVersion"
            }
        }

        val requiredSnapshotTablesBlock =
            checkNotNull(
                Regex(
                    """REQUIRED_V1_TABLES\s*:\s*Set<String>\s*=\s*sortedSetOf\((.*?)\n\s*\)""",
                    RegexOption.DOT_MATCHES_ALL,
                ).find(snapshotContractText)?.groupValues?.get(1),
            ) { "No se pudo leer REQUIRED_V1_TABLES del contrato de snapshot" }
        val requiredSnapshotTables =
            Regex("""\"([a-z][a-z0-9_]*)\"""")
                .findAll(requiredSnapshotTablesBlock)
                .map { it.groupValues[1] }
                .toSet()
        val currentRoomTables =
            Regex("""\"tableName\"\s*:\s*\"([a-z][a-z0-9_]*)\"""")
                .findAll(schemasByVersion.getValue(databaseVersion).readText())
                .map { it.groupValues[1] }
                .toSet()
        check(requiredSnapshotTables == currentRoomTables) {
            "FULL_DEVICE_SNAPSHOT no cubre exactamente el schema Room $databaseVersion; " +
                "faltan=${(currentRoomTables - requiredSnapshotTables).sorted()}, " +
                "sobran=${(requiredSnapshotTables - currentRoomTables).sorted()}"
        }

        val migrationDeclaration =
            Regex(
                """\bval\s+(MIGRATION_(\d+)_(\d+))\s*=\s*object\s*:\s*Migration\s*\(\s*(\d+)\s*,\s*(\d+)\s*\)""",
            )
        val declaredMigrations =
            migrationDeclaration.findAll(databaseText).associate { match ->
                val name = match.groupValues[1]
                val nameStart = match.groupValues[2].toInt()
                val nameEnd = match.groupValues[3].toInt()
                val constructorStart = match.groupValues[4].toInt()
                val constructorEnd = match.groupValues[5].toInt()
                check(nameStart == constructorStart && nameEnd == constructorEnd) {
                    "$name no coincide con Migration($constructorStart, $constructorEnd)"
                }
                (constructorStart to constructorEnd) to name
            }
        val addMigrationsBlock =
            checkNotNull(
                Regex("""\.addMigrations\s*\(([^)]*)\)""", RegexOption.DOT_MATCHES_ALL)
                    .find(databaseText)
                    ?.groupValues
                    ?.get(1),
            ) { "El builder productivo debe registrar migraciones explícitas con addMigrations" }
        (1 until databaseVersion).forEach { startVersion ->
            val transition = startVersion to startVersion + 1
            val migrationName =
                checkNotNull(declaredMigrations[transition]) {
                    "Falta Migration($startVersion, ${startVersion + 1}) para la versión Room $databaseVersion"
                }
            check(Regex("""\b${Regex.escape(migrationName)}\b""").containsMatchIn(addMigrationsBlock)) {
                "$migrationName existe pero no está registrada en el builder productivo"
            }
        }

        val destructiveFallback =
            Regex(
                """(?:\.\s*|\b)fallbackToDestructiveMigration(?:From|OnDowngrade)?\s*\(""",
            )
        val destructiveViolations =
            productionSourceDirectories
                .asSequence()
                .flatMap { directory ->
                    directory.asFileTree
                        .matching { include("**/*.kt", "**/*.java") }
                        .files
                        .asSequence()
                }.distinct()
                .sortedBy(File::getPath)
                .flatMap { source ->
                    val text = source.readText()
                    destructiveFallback.findAll(text).map { match ->
                        val line = text.take(match.range.first).count { it == '\n' } + 1
                        "${source.relativeTo(projectDir)}:$line"
                    }
                }.toList()
        check(destructiveViolations.isEmpty()) {
            "fallbackToDestructiveMigration* está prohibido en producción:\n" +
                destructiveViolations.joinToString("\n")
        }
    }
}

tasks.named("check").configure {
    dependsOn(
        verifyRoomSchemaPolicy,
        verifyAndroidReleaseConfiguration,
    )
}

tasks.named("preBuild").configure {
    dependsOn(
        verifyDomainBoundaries,
        verifyUiConventions,
        verifyNoSensitiveLogging,
        verifyMobileSecurityBoundaries,
        verifyLocalOcrConfiguration,
        verifyOfflineFirstBoundaries,
        verifyRoomSchemaPolicy,
    )
}
