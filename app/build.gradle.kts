import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

// Versión visible del instalador de Windows. MSI exige MAJOR.MINOR.BUILD numéricos.
val appVersionName: String =
    providers.environmentVariable("FACTURASTOCK_VERSION_NAME").orNull?.trim()?.takeIf(String::isNotEmpty)
        ?: "1.0.12"

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=kotlin.RequiresOptIn",
            "-Xannotation-default-target=param-property",
        )
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

ksp {
    arg("room.generateKotlin", "true")
    arg("room.incremental", "true")
}

compose.resources {
    packageOfResClass = "com.facturastock.app.resources"
    publicResClass = false
    generateResClass = always
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.foundation)
    implementation(compose.material3)
    implementation(compose.ui)
    implementation(compose.components.resources)
    implementation(libs.navigation.compose)
    // BackHandler de la escena (Esc de la ventana): ya llega en tiempo de ejecución vía navigation-compose.
    implementation(libs.compose.ui.backhandler)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.viewmodel.savedstate)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.swing)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.dagger)
    ksp(libs.dagger.compiler)
    implementation(libs.room.runtime)
    implementation(libs.sqlite.bundled)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences.core)
    implementation(libs.coil.compose)
    implementation(libs.pdfbox)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(compose.desktop.uiTestJUnit4)
    testImplementation(libs.room.testing)
    // Grafo Dagger de pruebas (TestAppComponent) para los recorridos de UI con Room real.
    kspTest(libs.dagger.compiler)
}

tasks.withType<Test>().configureEach {
    maxHeapSize = "2g"
    systemProperty("java.awt.headless", "true")
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

compose.desktop {
    application {
        mainClass = "com.facturastock.app.MainKt"
        jvmArgs += listOf("-Dfile.encoding=UTF-8", "-Xmx1024m")

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "FacturaStock"
            packageVersion = appVersionName
            description = "Ventas, inventario y deudas para el negocio"
            vendor = "FacturaStock"
            copyright = "© 2026 FacturaStock"
            modules(
                "java.instrument", "java.logging", "java.management", "java.naming", "java.sql",
                "java.xml", "jdk.charsets", "jdk.unsupported",
            )

            windows {
                menuGroup = "FacturaStock"
                shortcut = true
                menu = true
                perUserInstall = false
                dirChooser = true
                // Identificador fijo: permite que un MSI nuevo actualice la instalación anterior.
                upgradeUuid = "ED397038-CAC3-4EDB-97B9-996246E9FF43"
                iconFile.set(project.file("packaging/facturastock.ico"))
            }
        }

        buildTypes.release.proguard {
            isEnabled.set(false)
        }
    }
}
