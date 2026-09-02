package com.facturastock.app.data.sync

import com.facturastock.app.BuildConfig
import com.facturastock.app.core.config.CloudBackupConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.storage.FirebaseStorage
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

@Singleton
class BuildTypeAppCheckInstaller @Inject constructor() : AppCheckInstaller {
    override fun install(firebaseApp: FirebaseApp) {
        FirebaseAppCheck.getInstance(firebaseApp)
            .installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance())
    }
}

/** Todos los literales del Emulator Suite quedan confinados al artefacto cloudDebug. */
@Singleton
class BuildTypeFirebaseRuntimeEnvironment @Inject constructor() : FirebaseRuntimeEnvironment {
    override val config: CloudBackupConfig = CloudBackupConfig(
        projectId = DEMO_PROJECT_ID,
        applicationId = DEMO_APPLICATION_ID,
        apiKey = DEMO_API_KEY,
        storageBucket = DEMO_STORAGE_BUCKET,
    )

    override fun configure(firebaseApp: FirebaseApp) {
        FirebaseAuth.getInstance(firebaseApp).useEmulator(EMULATOR_HOST, AUTH_EMULATOR_PORT)
        FirebaseFirestore.getInstance(firebaseApp)
            .useEmulator(EMULATOR_HOST, BuildConfig.FIRESTORE_EMULATOR_PORT)
        FirebaseFunctions.getInstance(firebaseApp)
            .useEmulator(EMULATOR_HOST, FUNCTIONS_EMULATOR_PORT)
        FirebaseStorage.getInstance(firebaseApp)
            .useEmulator(EMULATOR_HOST, STORAGE_EMULATOR_PORT)
    }

    override suspend fun prepareBusiness(functions: FirebaseFunctions, businessId: String) {
        try {
            functions.getHttpsCallable(DEV_MEMBERSHIP_CALLABLE)
                .call(mapOf("businessId" to businessId))
                .await()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_failure: Exception) {
            // Conveniencia exclusiva del emulador: el callable real conserva la autorización.
        }
    }

    private companion object {
        const val DEMO_PROJECT_ID = "demo-facturastock"
        const val DEMO_APPLICATION_ID = "1:1000000000000:android:0000000000000000000000"
        // Sintética, pero con la forma que Firebase Android valida antes de hablar al emulador.
        const val DEMO_API_KEY = "AIza00000000000000000000000000000000000"
        const val DEMO_STORAGE_BUCKET = "demo-facturastock.appspot.com"
        const val EMULATOR_HOST = "10.0.2.2"
        const val AUTH_EMULATOR_PORT = 9_099
        const val FUNCTIONS_EMULATOR_PORT = 5_001
        const val STORAGE_EMULATOR_PORT = 9_199
        const val DEV_MEMBERSHIP_CALLABLE = "devEnsureMembership"
    }
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
