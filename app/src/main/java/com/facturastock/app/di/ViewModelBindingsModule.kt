package com.facturastock.app.di

import androidx.lifecycle.ViewModel
import dagger.Binds
import dagger.Module
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap
import com.facturastock.app.feature.capture.CaptureViewModel
import com.facturastock.app.feature.catalogs.CatalogsViewModel
import com.facturastock.app.feature.debtors.DebtorsViewModel
import com.facturastock.app.feature.headerreview.InvoiceHeaderReviewViewModel
import com.facturastock.app.feature.home.HomeViewModel
import com.facturastock.app.feature.inventory.InventoryViewModel
import com.facturastock.app.feature.linereview.InvoiceLineReviewViewModel
import com.facturastock.app.feature.linking.ProductLinkingViewModel
import com.facturastock.app.feature.matching.InvoiceMatchingViewModel
import com.facturastock.app.feature.ocr.OcrViewModel
import com.facturastock.app.feature.onboarding.OnboardingViewModel
import com.facturastock.app.feature.preparation.PreparationViewModel
import com.facturastock.app.feature.preview.PreviewViewModel
import com.facturastock.app.feature.purchases.PurchaseVoidViewModel
import com.facturastock.app.feature.purchases.PurchasesViewModel
import com.facturastock.app.feature.reports.ReportsViewModel
import com.facturastock.app.feature.review.ReviewViewModel
import com.facturastock.app.feature.root.AppGateViewModel
import com.facturastock.app.feature.sales.SalesViewModel
import com.facturastock.app.feature.settings.SettingsViewModel
import com.facturastock.app.feature.source.SourceViewModel
import com.facturastock.app.feature.summary.PurchaseSummaryViewModel
import com.facturastock.app.navigation.DraftFlowViewModel

/** Registro de ViewModels resueltos por [appViewModel]; reemplaza a `@HiltViewModel`. */
@Module
interface ViewModelBindingsModule {
    @Binds
    @IntoMap
    @ClassKey(AppGateViewModel::class)
    fun bindAppGateViewModel(viewModel: AppGateViewModel): ViewModel

    @Binds
    @IntoMap
    @ClassKey(CaptureViewModel::class)
    fun bindCaptureViewModel(viewModel: CaptureViewModel): ViewModel

    @Binds
    @IntoMap
    @ClassKey(CatalogsViewModel::class)
    fun bindCatalogsViewModel(viewModel: CatalogsViewModel): ViewModel

    @Binds
    @IntoMap
    @ClassKey(DebtorsViewModel::class)
    fun bindDebtorsViewModel(viewModel: DebtorsViewModel): ViewModel

    @Binds
    @IntoMap
    @ClassKey(DraftFlowViewModel::class)
    fun bindDraftFlowViewModel(viewModel: DraftFlowViewModel): ViewModel

    @Binds
    @IntoMap
    @ClassKey(HomeViewModel::class)
    fun bindHomeViewModel(viewModel: HomeViewModel): ViewModel

    @Binds
    @IntoMap
    @ClassKey(InventoryViewModel::class)
    fun bindInventoryViewModel(viewModel: InventoryViewModel): ViewModel

    @Binds
    @IntoMap
    @ClassKey(InvoiceHeaderReviewViewModel::class)
    fun bindInvoiceHeaderReviewViewModel(viewModel: InvoiceHeaderReviewViewModel): ViewModel

    @Binds
    @IntoMap
    @ClassKey(InvoiceLineReviewViewModel::class)
    fun bindInvoiceLineReviewViewModel(viewModel: InvoiceLineReviewViewModel): ViewModel

    @Binds
    @IntoMap
    @ClassKey(InvoiceMatchingViewModel::class)
    fun bindInvoiceMatchingViewModel(viewModel: InvoiceMatchingViewModel): ViewModel

    @Binds
    @IntoMap
    @ClassKey(OcrViewModel::class)
    fun bindOcrViewModel(viewModel: OcrViewModel): ViewModel

    @Binds
    @IntoMap
    @ClassKey(OnboardingViewModel::class)
    fun bindOnboardingViewModel(viewModel: OnboardingViewModel): ViewModel

    @Binds
    @IntoMap
    @ClassKey(PreparationViewModel::class)
    fun bindPreparationViewModel(viewModel: PreparationViewModel): ViewModel

    @Binds
    @IntoMap
    @ClassKey(PreviewViewModel::class)
    fun bindPreviewViewModel(viewModel: PreviewViewModel): ViewModel

    @Binds
    @IntoMap
    @ClassKey(ProductLinkingViewModel::class)
    fun bindProductLinkingViewModel(viewModel: ProductLinkingViewModel): ViewModel

    @Binds
    @IntoMap
    @ClassKey(PurchaseSummaryViewModel::class)
    fun bindPurchaseSummaryViewModel(viewModel: PurchaseSummaryViewModel): ViewModel

    @Binds
    @IntoMap
    @ClassKey(PurchaseVoidViewModel::class)
    fun bindPurchaseVoidViewModel(viewModel: PurchaseVoidViewModel): ViewModel

    @Binds
    @IntoMap
    @ClassKey(PurchasesViewModel::class)
    fun bindPurchasesViewModel(viewModel: PurchasesViewModel): ViewModel

    @Binds
    @IntoMap
    @ClassKey(ReportsViewModel::class)
    fun bindReportsViewModel(viewModel: ReportsViewModel): ViewModel

    @Binds
    @IntoMap
    @ClassKey(ReviewViewModel::class)
    fun bindReviewViewModel(viewModel: ReviewViewModel): ViewModel

    @Binds
    @IntoMap
    @ClassKey(SalesViewModel::class)
    fun bindSalesViewModel(viewModel: SalesViewModel): ViewModel

    @Binds
    @IntoMap
    @ClassKey(SettingsViewModel::class)
    fun bindSettingsViewModel(viewModel: SettingsViewModel): ViewModel

    @Binds
    @IntoMap
    @ClassKey(SourceViewModel::class)
    fun bindSourceViewModel(viewModel: SourceViewModel): ViewModel
}
