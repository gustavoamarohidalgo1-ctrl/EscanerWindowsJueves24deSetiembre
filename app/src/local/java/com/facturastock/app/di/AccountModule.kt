package com.facturastock.app.di

import com.facturastock.app.data.account.UnavailableAccountRepository
import com.facturastock.app.data.account.UnavailableMembershipRepository
import com.facturastock.app.data.repository.LocalOwnerPurchaseOverrideAuthorizationRepository
import com.facturastock.app.data.sync.UnavailableRemoteLedgerRepository
import com.facturastock.app.domain.repository.AccountRepository
import com.facturastock.app.domain.repository.BusinessMembershipRepository
import com.facturastock.app.domain.repository.RemoteLedgerRepository
import com.facturastock.app.domain.repository.PurchaseOverrideAuthorizationRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Flavor local: sin nube no hay cuenta ni membresías. Los puertos declaran no estar
 * disponibles y el perfil local sigue funcionando sin nube.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AccountModule {
    @Binds
    @Singleton
    abstract fun bindAccountRepository(
        implementation: UnavailableAccountRepository,
    ): AccountRepository

    @Binds
    @Singleton
    abstract fun bindBusinessMembershipRepository(
        implementation: UnavailableMembershipRepository,
    ): BusinessMembershipRepository

    @Binds
    @Singleton
    abstract fun bindRemoteLedgerRepository(
        implementation: UnavailableRemoteLedgerRepository,
    ): RemoteLedgerRepository

    @Binds
    @Singleton
    abstract fun bindPurchaseOverrideAuthorizationRepository(
        implementation: LocalOwnerPurchaseOverrideAuthorizationRepository,
    ): PurchaseOverrideAuthorizationRepository
}
