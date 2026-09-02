package com.facturastock.app.data.sync

import com.facturastock.app.BuildConfig
import com.facturastock.app.core.config.CloudBackupConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import com.google.firebase.functions.FirebaseFunctions
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/** Release usa Play Integrity; el backend permanece en monitorización hasta activación explícita. */
@Singleton
class BuildTypeAppCheckInstaller @Inject constructor() : AppCheckInstaller {
    override fun install(firebaseApp: FirebaseApp) {
        FirebaseAppCheck.getInstance(firebaseApp)
            .installAppCheckProviderFactory(PlayIntegrityAppCheckProviderFactory.getInstance())
    }
}

/** Configuración productiva explícita; no contiene rutas ni valores del Emulator Suite. */
@Singleton
class BuildTypeFirebaseRuntimeEnvironment @Inject constructor() : FirebaseRuntimeEnvironment {
    override val config: CloudBackupConfig? = CloudBackupConfig.fromValues(
        projectId = BuildConfig.FIREBASE_PROJECT_ID,
        applicationId = BuildConfig.FIREBASE_APPLICATION_ID,
        apiKey = BuildConfig.FIREBASE_API_KEY,
        storageBucket = BuildConfig.FIREBASE_STORAGE_BUCKET,
    )

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
