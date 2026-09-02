package com.facturastock.app.data.sync

import com.facturastock.app.BuildConfig
import com.facturastock.app.core.config.CloudBackupConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.functions.FirebaseFunctions
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * El APK Spark usa el proveedor debug para que el token de esta instalación pueda registrarse
 * en App Check sin una publicación en Play. El proveedor nunca entra a cloudRelease.
 */
@Singleton
class BuildTypeAppCheckInstaller @Inject constructor() : AppCheckInstaller {
    override fun install(firebaseApp: FirebaseApp) {
        FirebaseAppCheck.getInstance(firebaseApp)
            .installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance())
    }
}

/**
 * Configuración del proyecto real leída de local.properties. Este entorno no conoce hosts ni
 * puertos del Emulator Suite y desactiva las superficies de Functions/Storage en el runtime.
 */
@Singleton
class BuildTypeFirebaseRuntimeEnvironment @Inject constructor() : FirebaseRuntimeEnvironment {
    override val config: CloudBackupConfig? = CloudBackupConfig.fromValues(
        projectId = BuildConfig.FIREBASE_PROJECT_ID,
        applicationId = BuildConfig.FIREBASE_APPLICATION_ID,
        apiKey = BuildConfig.FIREBASE_API_KEY,
        storageBucket = "",
    )

    override val backendMode: FirebaseBackendMode = FirebaseBackendMode.SPARK_DIRECT

    override fun configure(firebaseApp: FirebaseApp) = Unit

    override suspend fun prepareBusiness(functions: FirebaseFunctions, businessId: String) = Unit
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AppCheckInstallerModule {
    @Binds
    @Singleton
    abstract fun bindAppCheckInstaller(
        implementation: BuildTypeAppCheckInstaller,
    ): AppCheckInstaller

    @Binds
    @Singleton
    abstract fun bindFirebaseRuntimeEnvironment(
        implementation: BuildTypeFirebaseRuntimeEnvironment,
    ): FirebaseRuntimeEnvironment
}
