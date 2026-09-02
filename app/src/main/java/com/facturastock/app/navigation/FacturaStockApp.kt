package com.facturastock.app.navigation

import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.facturastock.app.FacturaStockApplication
import com.facturastock.app.R
import com.facturastock.app.core.id.RandomUuidGenerator
import com.facturastock.app.core.id.UuidGenerator
import com.facturastock.app.feature.account.AccountRoute
import com.facturastock.app.feature.account.invitations.InvitationsRoute
import com.facturastock.app.feature.account.members.MembersRoute
import com.facturastock.app.feature.capture.CaptureRoute
import com.facturastock.app.feature.catalogs.CatalogsRoute
import com.facturastock.app.feature.common.CollectUiEffects
import com.facturastock.app.feature.debtors.DebtorsContract
import com.facturastock.app.feature.debtors.DebtorsListScreen
import com.facturastock.app.feature.debtors.DebtorsRoute
import com.facturastock.app.feature.home.HomeScreen
import com.facturastock.app.feature.home.HomeRoute
import com.facturastock.app.feature.inventory.InventoryRoute
import com.facturastock.app.feature.invoices.InvoiceHubScreen
import com.facturastock.app.feature.headerreview.InvoiceHeaderReviewRoute
import com.facturastock.app.feature.linereview.InvoiceLineReviewRoute
import com.facturastock.app.feature.linking.ProductLinkingRoute
import com.facturastock.app.feature.ocr.OcrRoute
import com.facturastock.app.feature.onboarding.OnboardingRoute
import com.facturastock.app.feature.preparation.PreparationRoute
import com.facturastock.app.feature.preview.PreviewRoute
import com.facturastock.app.feature.purchase.InvalidNavigationScreen
import com.facturastock.app.feature.purchase.PurchaseFlowScreen
import com.facturastock.app.feature.purchases.PurchasesRoute
import com.facturastock.app.feature.purchases.PurchaseSuccessRoute
import com.facturastock.app.feature.purchases.PurchaseVoidRoute
import com.facturastock.app.feature.reports.ReportsRoute
import com.facturastock.app.feature.root.AppGateViewModel
import com.facturastock.app.feature.root.GateState
import com.facturastock.app.feature.sales.SalesContract
import com.facturastock.app.feature.sales.SalesRoute
import com.facturastock.app.feature.sales.SalesScreen
import com.facturastock.app.feature.settings.SettingsRoute
import com.facturastock.app.feature.source.SourceRoute
import com.facturastock.app.feature.summary.PurchaseSummaryRoute
import com.facturastock.app.feature.sync.SyncRoute
import com.facturastock.app.feature.top.TopLevelPlaceholderScreen
import com.facturastock.app.ui.components.FacturaStockBottomItem
import com.facturastock.app.ui.components.FacturaStockBottomNavigation
import com.facturastock.app.ui.components.FacturaStockDialog
import com.facturastock.app.ui.components.FacturaStockNavigationRail
import com.facturastock.app.ui.components.FacturaStockScaffold
import com.facturastock.app.ui.components.FacturaStockTopBar
import com.facturastock.app.ui.components.LoadingState
import com.facturastock.app.ui.components.RecoverableError
import com.facturastock.app.ui.components.StatusTone
import com.facturastock.app.ui.theme.FacturaStockDesign
import java.util.UUID

private val DefaultUuidGenerator: UuidGenerator = RandomUuidGenerator()

private data class PendingNavigation(
    val route: String,
    val topLevel: Boolean,
)

@Composable
fun FacturaStockApp(
    navController: NavHostController = rememberNavController(),
    uuidGenerator: UuidGenerator = DefaultUuidGenerator,
    initialInternalDeepLink: String? = null,
    initialInternalDeepLinkRequestId: Long = 0L,
    onDiscardDraft: (DraftId) -> Unit = {},
    useInjectedViewModels: Boolean = true,
) {
    // Compuerta de primer inicio: con ViewModels inyectados se observa la configuración real;
    // sin ellos (tests de navegación) la compuerta se fuerza a Complete.
    val gateViewModel = if (useInjectedViewModels) {
        hiltViewModel<AppGateViewModel>()
    } else {
        null
    }
    val gateState = if (gateViewModel != null) {
        gateViewModel.uiState.collectAsStateWithLifecycle().value
    } else {
        GateState.Complete
    }
    val draftFlowViewModel = if (shouldCreateDraftFlowViewModel(gateState, useInjectedViewModels)) {
        hiltViewModel<DraftFlowViewModel>()
    } else {
        null
    }

    when (gateState) {
        GateState.Loading -> LoadingState(
            message = stringResource(R.string.feature_loading_message),
            modifier = Modifier.fillMaxSize(),
        )

        GateState.Unavailable -> {
            DeferredStartupAfterFrameEffect()
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                RecoverableError(
                    title = stringResource(R.string.app_configuration_error_title),
                    message = stringResource(R.string.app_configuration_error_message),
                    actionLabel = stringResource(R.string.action_retry),
                    onAction = { gateViewModel?.retry() },
                    modifier = Modifier.padding(FacturaStockDesign.spacing.lg),
                )
            }
        }

        GateState.Incomplete -> {
            FacturaStockContent(
                navController = navController,
                uuidGenerator = uuidGenerator,
                initialInternalDeepLink = initialInternalDeepLink,
                initialInternalDeepLinkRequestId = initialInternalDeepLinkRequestId,
                onDiscardDraft = onDiscardDraft,
                useInjectedViewModels = useInjectedViewModels,
                startDestination = AppRoutes.ONBOARDING,
                deepLinksEnabled = false,
                draftFlowViewModel = draftFlowViewModel,
            )
        }

        GateState.Complete -> {
            FacturaStockContent(
                navController = navController,
                uuidGenerator = uuidGenerator,
                initialInternalDeepLink = initialInternalDeepLink,
                initialInternalDeepLinkRequestId = initialInternalDeepLinkRequestId,
                onDiscardDraft = onDiscardDraft,
                useInjectedViewModels = useInjectedViewModels,
                startDestination = AppRoutes.HOME,
                deepLinksEnabled = true,
                draftFlowViewModel = draftFlowViewModel,
            )
        }
    }
}

internal fun shouldCreateDraftFlowViewModel(
    gateState: GateState,
    useInjectedViewModels: Boolean,
): Boolean = when (gateState) {
    GateState.Loading,
    GateState.Unavailable,
    GateState.Incomplete,
    -> false

    GateState.Complete,
    -> useInjectedViewModels
}

/** Espera un frame completo del destino antes de despertar Room, DataStore y WorkManager. */
@Composable
private fun DeferredStartupAfterFrameEffect() {
    val application = LocalContext.current.applicationContext as? FacturaStockApplication
    LaunchedEffect(application) {
        application ?: return@LaunchedEffect
        // LaunchedEffect puede entrar antes del draw del frame que acaba de componer este destino.
        // El primer pulso permite que ese frame se dibuje; el segundo garantiza que el trabajo
        // diferido empieza cuando al menos un frame completo ya fue presentado.
        withFrameNanos { }
        withFrameNanos { }
        application.onFirstAppFrameRendered()
    }
}

@Composable
private fun FacturaStockContent(
    navController: NavHostController,
    uuidGenerator: UuidGenerator,
    initialInternalDeepLink: String?,
    initialInternalDeepLinkRequestId: Long,
    onDiscardDraft: (DraftId) -> Unit,
    useInjectedViewModels: Boolean,
    startDestination: String,
    deepLinksEnabled: Boolean,
    draftFlowViewModel: DraftFlowViewModel?,
) {
    val onBackPressedDispatcher =
        LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentPattern = backStackEntry?.destination?.route
    if (shouldSignalDeferredStartupFromDestination(currentPattern)) {
        // Un deep link que evita Home conserva un fallback posterior al primer frame. Home
        // despierta el mantenimiento únicamente cuando su dashboard ya está operativo.
        DeferredStartupAfterFrameEffect()
    }
    val currentDefinition = AppRoutes.definitionFor(currentPattern)
    val protectedDraft = currentDefinition?.protectsDraftOnExit == true
    var showDiscardDialog by rememberSaveable { mutableStateOf(false) }
    var handledDeepLinkRequestId by rememberSaveable { mutableStateOf<Long?>(null) }
    var pendingSalesExit by remember { mutableStateOf<PendingNavigation?>(null) }
    var salesExitRequest by remember { mutableStateOf<(() -> Unit)?>(null) }
    val draftFlowState = if (draftFlowViewModel != null) {
        draftFlowViewModel.uiState.collectAsStateWithLifecycle().value
    } else {
        DraftFlowContract.State()
    }

    fun requestBack() {
        if (currentPattern == AppRoutes.NEW_DEBT && salesExitRequest != null) {
            salesExitRequest?.invoke()
        } else if (protectedDraft) {
            showDiscardDialog = true
        } else {
            onBackPressedDispatcher?.onBackPressed() ?: navController.popBackStack()
        }
    }

    fun navigateTo(target: PendingNavigation) {
        if (target.topLevel) {
            val destination = TopLevelDestination.entries.single { it.route == target.route }
            navController.navigateTopLevel(destination)
        } else {
            navController.navigate(target.route) { launchSingleTop = true }
        }
    }

    fun requestNavigation(target: PendingNavigation) {
        if (
            currentPattern in setOf(AppRoutes.SALES, AppRoutes.NEW_DEBT) &&
            target.route != currentPattern &&
            salesExitRequest != null
        ) {
            pendingSalesExit = target
            salesExitRequest?.invoke()
        } else {
            navigateTo(target)
        }
    }

    fun removeFlowAndOpenInvoices() {
        navController.navigate(AppRoutes.INVOICES) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = false
            }
            launchSingleTop = true
        }
    }

    if (draftFlowViewModel != null) {
        CollectUiEffects(draftFlowViewModel.effects) { effect ->
            when (effect) {
                is DraftFlowContract.Effect.OpenDraftCamera -> {
                    navController.navigate(AppRoutes.camera(effect.draftId)) {
                        launchSingleTop = true
                    }
                    draftFlowViewModel.onAction(
                        DraftFlowContract.Action.StartNavigationHandled,
                    )
                }

                is DraftFlowContract.Effect.DraftDiscarded -> {
                    showDiscardDialog = false
                    removeFlowAndOpenInvoices()
                    draftFlowViewModel.onAction(
                        DraftFlowContract.Action.DiscardNavigationHandled,
                    )
                }
            }
        }
    }

    LaunchedEffect(currentPattern) {
        showDiscardDialog = false
    }

    LaunchedEffect(
        initialInternalDeepLink,
        initialInternalDeepLinkRequestId,
        currentPattern,
        deepLinksEnabled,
    ) {
        if (!deepLinksEnabled) {
            return@LaunchedEffect
        }
        val rawDeepLink = initialInternalDeepLink ?: return@LaunchedEffect
        if (
            currentPattern == null ||
            handledDeepLinkRequestId == initialInternalDeepLinkRequestId
        ) {
            return@LaunchedEffect
        }
        if (currentDefinition?.protectsDraftOnExit == true) {
            return@LaunchedEffect
        }
        val target = InternalDeepLinks.resolve(rawDeepLink)
        handledDeepLinkRequestId = initialInternalDeepLinkRequestId
        when (target) {
            is InternalDeepLinkTarget.PurchaseDetail -> {
                navController.navigate(AppRoutes.purchaseDetail(target.purchaseId)) {
                    launchSingleTop = true
                }
            }
            null -> Unit
        }
    }

    val topLevelDestinations = TopLevelDestination.entries
    val matchedTopLevelIndex = topLevelDestinations.indexOfFirst { it.route == currentPattern }
    check(currentDefinition?.topLevel != true || matchedTopLevelIndex >= 0) {
        "Toda ruta principal debe existir en TopLevelDestination"
    }
    val selectedTopLevelIndex = matchedTopLevelIndex.coerceAtLeast(0)
    val bottomItems = topLevelDestinations.map { destination ->
        FacturaStockBottomItem(
            label = stringResource(destination.labelRes),
            iconRes = destination.iconRes,
        )
    }
    val showBackNavigation = currentDefinition?.topLevel == false &&
        currentPattern != AppRoutes.ONBOARDING
    val currentTitle = if (currentDefinition?.topLevel == true) {
        stringResource(topLevelDestinations[selectedTopLevelIndex].labelRes)
    } else {
        stringResource(currentDefinition?.titleRes ?: R.string.app_name)
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val spacing = FacturaStockDesign.spacing
        val fontScale = LocalDensity.current.fontScale
        val useNavigationRail = currentDefinition?.topLevel == true &&
            maxWidth >= spacing.expandedNavigationBreakpoint &&
            maxHeight >= spacing.expandedNavigationMinHeight &&
            fontScale < 1.5f
        val useCompactChrome = currentDefinition?.topLevel == true &&
            maxHeight < spacing.expandedNavigationMinHeight
        val scaffoldContentMaxWidth = if (
            useNavigationRail && currentPattern in setOf(
                AppRoutes.HOME,
                AppRoutes.INVOICES,
                AppRoutes.REPORTS,
            )
        ) {
            spacing.contentWideMaxWidth
        } else {
            spacing.contentMaxWidth
        }

        FacturaStockScaffold(
            useNavigationRail = useNavigationRail,
            contentMaxWidth = scaffoldContentMaxWidth,
            contentPaneTitle = currentTitle,
            navigationRail = {
                FacturaStockNavigationRail(
                    items = bottomItems,
                    selectedIndex = selectedTopLevelIndex,
                    brandLabel = stringResource(R.string.app_name),
                    brandIconRes = R.drawable.ic_facturastock,
                    onItemSelected = { selectedIndex ->
                        requestNavigation(
                            PendingNavigation(
                                route = topLevelDestinations[selectedIndex].route,
                                topLevel = true,
                            ),
                        )
                    },
                )
            },
            topBar = {
                FacturaStockTopBar(
                    title = currentTitle,
                    contentMaxWidth = scaffoldContentMaxWidth,
                    compact = useCompactChrome,
                    navigationIconRes = if (showBackNavigation) {
                        R.drawable.ic_back
                    } else {
                        null
                    },
                    navigationContentDescription = if (showBackNavigation) {
                        stringResource(
                            if (currentDefinition.protectsDraftOnExit) {
                                R.string.action_close_purchase_flow
                            } else {
                                R.string.action_back
                            },
                        )
                    } else {
                        null
                    },
                    onNavigationClick = if (showBackNavigation) {
                        ::requestBack
                    } else {
                        null
                    },
                    actionIconRes = if (currentDefinition?.topLevel == true) {
                        R.drawable.ic_settings
                    } else {
                        null
                    },
                    actionContentDescription = if (currentDefinition?.topLevel == true) {
                        stringResource(R.string.navigation_open_settings)
                    } else {
                        null
                    },
                    onActionClick = if (currentDefinition?.topLevel == true) {
                        {
                            requestNavigation(
                                PendingNavigation(AppRoutes.SETTINGS, topLevel = false),
                            )
                        }
                    } else {
                        null
                    },
                )
            },
            bottomBar = {
                if (currentDefinition?.topLevel == true && !useNavigationRail) {
                    FacturaStockBottomNavigation(
                        items = bottomItems,
                        selectedIndex = selectedTopLevelIndex,
                        compact = useCompactChrome,
                        onItemSelected = { selectedIndex ->
                            requestNavigation(
                                PendingNavigation(
                                    route = topLevelDestinations[selectedIndex].route,
                                    topLevel = true,
                                ),
                            )
                        },
                    )
                }
            },
        ) { contentPadding ->
            FacturaStockNavHost(
                navController = navController,
                uuidGenerator = uuidGenerator,
                useInjectedViewModels = useInjectedViewModels,
                startDestination = startDestination,
                onInvalidDestination = ::removeFlowAndOpenInvoices,
                protectedBackEnabled = !showDiscardDialog,
                onProtectedBack = { showDiscardDialog = true },
                draftFlowState = draftFlowState,
                onStartDraft = {
                    draftFlowViewModel?.onAction(DraftFlowContract.Action.StartDraft)
                },
                onSalesExitConfirmed = {
                    val target = pendingSalesExit
                    pendingSalesExit = null
                    if (target != null) navigateTo(target) else navController.popBackStack()
                },
                onSalesExitCancelled = { pendingSalesExit = null },
                onSalesExitRequestAvailable = { request -> salesExitRequest = request },
                onHomeReady = {
                    val application = navController.context.applicationContext as?
                        FacturaStockApplication
                    application?.onFirstAppFrameRendered()
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
            )
        }
    }

    if (showDiscardDialog) {
        FacturaStockDialog(
            title = stringResource(R.string.discard_dialog_title),
            message = stringResource(
                if (draftFlowState.discardFailedDraftId != null) {
                    R.string.home_draft_delete_error
                } else {
                    R.string.discard_dialog_message
                },
            ),
            confirmLabel = stringResource(R.string.action_discard_draft),
            dismissLabel = stringResource(R.string.action_keep_editing),
            onConfirm = {
                if (draftFlowState.discardingDraftId == null) {
                    val draftId = DraftId.parse(
                        backStackEntry?.arguments?.getString(AppRoutes.DRAFT_ID),
                    )
                    if (draftFlowViewModel != null && draftId != null) {
                        draftFlowViewModel.onAction(
                            DraftFlowContract.Action.DiscardDraft(draftId),
                        )
                    } else {
                        draftId?.let(onDiscardDraft)
                        showDiscardDialog = false
                        removeFlowAndOpenInvoices()
                    }
                }
            },
            onDismiss = {
                if (draftFlowState.discardingDraftId == null) showDiscardDialog = false
            },
        )
    }
}

internal fun shouldSignalDeferredStartupFromDestination(route: String?): Boolean =
    route != null && route != AppRoutes.HOME

@Composable
private fun FacturaStockNavHost(
    navController: NavHostController,
    uuidGenerator: UuidGenerator,
    useInjectedViewModels: Boolean,
    startDestination: String,
    onInvalidDestination: () -> Unit,
    protectedBackEnabled: Boolean,
    onProtectedBack: () -> Unit,
    draftFlowState: DraftFlowContract.State,
    onStartDraft: () -> Unit,
    onSalesExitConfirmed: () -> Unit,
    onSalesExitCancelled: () -> Unit,
    onSalesExitRequestAvailable: ((() -> Unit)?) -> Unit,
    onHomeReady: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current

    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
    ) {
        composable(AppRoutes.ONBOARDING) {
            if (useInjectedViewModels) {
                OnboardingRoute(
                    onFinished = {
                        navController.navigate(AppRoutes.HOME) {
                            popUpTo(AppRoutes.ONBOARDING) {
                                inclusive = true
                            }
                        }
                    },
                )
            } else {
                TopLevelPlaceholderScreen(
                    titleRes = R.string.onboarding_title,
                    messageRes = R.string.onboarding_subtitle,
                )
            }
        }
        composable(AppRoutes.HOME) {
            if (useInjectedViewModels) {
                HomeRoute(
                    onContentReady = onHomeReady,
                    onOpenDraftCamera = { draftId ->
                        navController.navigate(AppRoutes.camera(draftId))
                    },
                    onOpenDraftSource = { draftId ->
                        navController.navigate(AppRoutes.source(draftId))
                    },
                    onOpenPreview = { draftId, imageId ->
                        navController.navigate(
                            AppRoutes.imagePreview(
                                draftId,
                                CaptureId.from(UUID.fromString(imageId.value)),
                            ),
                        )
                    },
                    onOpenProcessing = { draftId ->
                        navController.navigate(AppRoutes.processing(draftId))
                    },
                    onOpenHeader = { draftId ->
                        navController.navigate(AppRoutes.invoiceHeader(draftId))
                    },
                    onOpenLines = { draftId ->
                        navController.navigate(AppRoutes.invoiceLines(draftId))
                    },
                    onOpenSummary = { draftId ->
                        navController.navigate(AppRoutes.purchaseSummary(draftId))
                    },
                    onOpenPurchaseDetail = { purchaseId ->
                        navController.navigate(AppRoutes.purchaseDetail(purchaseId))
                    },
                    onOpenSales = {
                        navController.navigateTopLevel(TopLevelDestination.SALES)
                    },
                    onOpenProducts = {
                        navController.navigate(AppRoutes.PRODUCTS) { launchSingleTop = true }
                    },
                    onOpenPurchases = {
                        navController.navigate(AppRoutes.PURCHASES) { launchSingleTop = true }
                    },
                    onOpenDebtors = {
                        navController.navigate(AppRoutes.DEBTORS) { launchSingleTop = true }
                    },
                    onOpenInventory = {
                        navController.navigateTopLevel(TopLevelDestination.INVENTORY)
                    },
                )
            } else {
                HomeScreen(
                    onScanInvoice = {
                        val draftId = DraftId.from(uuidGenerator.newUuid())
                        navController.navigate(AppRoutes.camera(draftId))
                    },
                    onOpenSales = {
                        navController.navigateTopLevel(TopLevelDestination.SALES)
                    },
                    onOpenProducts = {
                        navController.navigate(AppRoutes.PRODUCTS) { launchSingleTop = true }
                    },
                    onOpenPurchases = {
                        navController.navigate(AppRoutes.PURCHASES) { launchSingleTop = true }
                    },
                    onOpenDebtors = {
                        navController.navigate(AppRoutes.DEBTORS) { launchSingleTop = true }
                    },
                    onOpenInventory = {
                        navController.navigateTopLevel(TopLevelDestination.INVENTORY)
                    },
                )
            }
        }
        composable(AppRoutes.SALES) {
            if (useInjectedViewModels) {
                SalesRoute(
                    onBack = onSalesExitConfirmed,
                    onCreditSalePosted = {
                        navController.navigate(AppRoutes.DEBTORS) {
                            popUpTo(AppRoutes.SALES) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                    onExitCancelled = onSalesExitCancelled,
                    onExitRequestAvailable = onSalesExitRequestAvailable,
                )
            } else {
                SalesScreen(
                    state = SalesContract.State(isLoading = false),
                    onAction = { action ->
                        if (action == SalesContract.Action.BackSelected) {
                            onSalesExitConfirmed()
                        }
                    },
                )
            }
        }
        composable(AppRoutes.DEBTORS) {
            if (useInjectedViewModels) {
                DebtorsRoute(
                    onOpenDebt = { debtId ->
                        navController.navigate(AppRoutes.debtDetail(debtId))
                    },
                    onNewDebt = { navController.navigate(AppRoutes.NEW_DEBT) },
                    onBack = navController::popBackStack,
                    onCloseInvalidRoute = {
                        navController.navigate(AppRoutes.DEBTORS) { launchSingleTop = true }
                    },
                )
            } else {
                DebtorsListScreen(
                    state = DebtorsContract.State(isLoading = false),
                    onAction = { action ->
                        if (action == DebtorsContract.Action.NewDebtSelected) {
                            navController.navigate(AppRoutes.NEW_DEBT)
                        }
                    },
                )
            }
        }
        composable(AppRoutes.NEW_DEBT) {
            if (useInjectedViewModels) {
                SalesRoute(
                    entryKind = SalesContract.EntryKind.CREDIT,
                    allowEntryKindSelection = false,
                    onBack = onSalesExitConfirmed,
                    onCreditSalePosted = {
                        if (!navController.popBackStack(AppRoutes.DEBTORS, inclusive = false)) {
                            navController.navigate(AppRoutes.DEBTORS) { launchSingleTop = true }
                        }
                    },
                    onExitCancelled = onSalesExitCancelled,
                    onExitRequestAvailable = onSalesExitRequestAvailable,
                )
            } else {
                SalesScreen(
                    state = SalesContract.State(
                        isLoading = false,
                        entryKind = SalesContract.EntryKind.CREDIT,
                    ),
                    onAction = { action ->
                        if (action == SalesContract.Action.BackSelected) {
                            onSalesExitConfirmed()
                        }
                    },
                    allowEntryKindSelection = false,
                )
            }
        }
        composable(
            route = AppRoutes.DEBT_DETAIL,
            arguments = listOf(stringArgument(AppRoutes.DEBT_ID)),
        ) { entry ->
            val debtId = DebtId.parse(entry.arguments?.getString(AppRoutes.DEBT_ID))
            if (debtId == null) {
                RecoverableError(
                    title = stringResource(R.string.navigation_invalid_title),
                    message = stringResource(R.string.navigation_invalid_message),
                    actionLabel = stringResource(R.string.action_return_debtors),
                    onAction = {
                        navController.navigate(AppRoutes.DEBTORS) { launchSingleTop = true }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (useInjectedViewModels) {
                DebtorsRoute(
                    onOpenDebt = {},
                    onNewDebt = { navController.navigate(AppRoutes.NEW_DEBT) },
                    onBack = navController::popBackStack,
                    onCloseInvalidRoute = {
                        navController.navigate(AppRoutes.DEBTORS) {
                            popUpTo(AppRoutes.DEBTORS) { inclusive = false }
                            launchSingleTop = true
                        }
                    },
                )
            } else {
                TopLevelPlaceholderScreen(
                    titleRes = R.string.debt_detail_title,
                    messageRes = R.string.debt_not_found_message,
                )
            }
        }
        composable(AppRoutes.INVOICES) {
            InvoiceHubScreen(
                isCreating = draftFlowState.isCreatingDraft,
                creationFailed = draftFlowState.draftCreationFailed,
                onRegister = {
                    if (useInjectedViewModels) {
                        onStartDraft()
                    } else {
                        val draftId = DraftId.from(uuidGenerator.newUuid())
                        navController.navigate(AppRoutes.camera(draftId))
                    }
                },
            )
        }
        composable(AppRoutes.REPORTS) {
            if (useInjectedViewModels) {
                ReportsRoute()
            } else {
                TopLevelPlaceholderScreen(
                    titleRes = R.string.navigation_reports,
                    messageRes = R.string.reports_placeholder_message,
                )
            }
        }
        composable(AppRoutes.PRODUCTS) {
            if (useInjectedViewModels) {
                CatalogsRoute(
                    onBack = navController::popBackStack,
                )
            } else {
                TopLevelPlaceholderScreen(
                    titleRes = R.string.products_empty_title,
                    messageRes = R.string.products_empty_message,
                )
            }
        }
        composable(AppRoutes.PURCHASES) {
            if (useInjectedViewModels) {
                PurchasesRoute(
                    onOpenPurchase = { purchaseId ->
                        navController.navigate(AppRoutes.purchaseDetail(purchaseId))
                    },
                    onBack = navController::popBackStack,
                    onCloseInvalidRoute = onInvalidDestination,
                    onNewPurchase = { navController.navigate(AppRoutes.NEW_PURCHASE) },
                )
            } else {
                TopLevelPlaceholderScreen(
                    titleRes = R.string.purchases_empty_title,
                    messageRes = R.string.purchases_empty_message,
                )
            }
        }
        composable(AppRoutes.INVENTORY) {
            if (useInjectedViewModels) {
                InventoryRoute(
                    onOpenProduct = { productId ->
                        navController.navigate(AppRoutes.inventoryDetail(productId))
                    },
                    onOpenPurchase = { purchaseId ->
                        navController.navigate(AppRoutes.purchaseDetail(purchaseId))
                    },
                    onBack = navController::popBackStack,
                    onCloseInvalidRoute = {
                        navController.navigateTopLevel(TopLevelDestination.INVENTORY)
                    },
                )
            } else {
                TopLevelPlaceholderScreen(
                    titleRes = R.string.inventory_empty_title,
                    messageRes = R.string.inventory_empty_message,
                )
            }
        }
        composable(
            route = AppRoutes.INVENTORY_DETAIL,
            arguments = listOf(stringArgument(AppRoutes.PRODUCT_ID)),
        ) { entry ->
            val productId = ProductId.parse(entry.arguments?.getString(AppRoutes.PRODUCT_ID))
            if (productId == null) {
                InvalidNavigationScreen(
                    onReturnToPurchases = {
                        navController.navigateTopLevel(TopLevelDestination.INVENTORY)
                    },
                )
            } else if (useInjectedViewModels) {
                InventoryRoute(
                    onOpenProduct = {},
                    onOpenPurchase = { purchaseId ->
                        navController.navigate(AppRoutes.purchaseDetail(purchaseId))
                    },
                    onBack = navController::popBackStack,
                    onCloseInvalidRoute = {
                        navController.navigateTopLevel(TopLevelDestination.INVENTORY)
                    },
                )
            } else {
                TopLevelPlaceholderScreen(
                    titleRes = R.string.inventory_detail_title,
                    messageRes = R.string.inventory_detail_message,
                )
            }
        }
        composable(AppRoutes.SETTINGS) {
            if (useInjectedViewModels) {
                SettingsRoute(
                    onOpenDemoCapture = { draftId, captureId ->
                        navController.navigate(AppRoutes.imagePreview(draftId, captureId)) {
                            launchSingleTop = true
                        }
                    },
                    onOpenDemoPurchase = { purchaseId ->
                        navController.navigate(AppRoutes.purchaseDetail(purchaseId)) {
                            launchSingleTop = true
                        }
                    },
                    onOpenAccount = {
                        navController.navigate(AppRoutes.ACCOUNT) {
                            launchSingleTop = true
                        }
                    },
                    onOpenSync = {
                        navController.navigate(AppRoutes.SYNC) {
                            launchSingleTop = true
                        }
                    },
                    onOpenPrivacyPolicy = { url ->
                        // La URL proviene de BuildConfig y el gate release exige HTTPS. Fallar al
                        // abrir el navegador no altera configuración ni datos locales.
                        runCatching { uriHandler.openUri(url) }
                    },
                )
            } else {
                TopLevelPlaceholderScreen(
                    titleRes = R.string.settings_empty_title,
                    messageRes = R.string.settings_empty_message,
                )
            }
        }
        composable(AppRoutes.ACCOUNT) {
            if (useInjectedViewModels) {
                AccountRoute(
                    onOpenMembers = {
                        navController.navigate(AppRoutes.ACCOUNT_MEMBERS) {
                            launchSingleTop = true
                        }
                    },
                    onOpenInvitations = {
                        navController.navigate(AppRoutes.ACCOUNT_INVITATIONS) {
                            launchSingleTop = true
                        }
                    },
                    onBack = navController::popBackStack,
                )
            } else {
                TopLevelPlaceholderScreen(
                    titleRes = R.string.navigation_account,
                    messageRes = R.string.account_placeholder_message,
                )
            }
        }
        composable(AppRoutes.ACCOUNT_MEMBERS) {
            if (useInjectedViewModels) {
                MembersRoute(
                    onBack = navController::popBackStack,
                )
            } else {
                TopLevelPlaceholderScreen(
                    titleRes = R.string.navigation_account_members,
                    messageRes = R.string.members_placeholder_message,
                )
            }
        }
        composable(AppRoutes.ACCOUNT_INVITATIONS) {
            if (useInjectedViewModels) {
                InvitationsRoute(
                    onBack = navController::popBackStack,
                )
            } else {
                TopLevelPlaceholderScreen(
                    titleRes = R.string.navigation_account_invitations,
                    messageRes = R.string.invitations_placeholder_message,
                )
            }
        }
        composable(AppRoutes.SYNC) {
            if (useInjectedViewModels) {
                SyncRoute(
                    onBack = navController::popBackStack,
                )
            } else {
                TopLevelPlaceholderScreen(
                    titleRes = R.string.navigation_sync,
                    messageRes = R.string.sync_placeholder_message,
                )
            }
        }
        composable(AppRoutes.NEW_PURCHASE) {
            PurchaseFlowScreen(
                titleRes = R.string.purchase_new_title,
                messageRes = if (draftFlowState.draftCreationFailed) {
                    R.string.home_draft_create_error
                } else {
                    R.string.purchase_new_message
                },
                primaryActionRes = R.string.action_start_purchase,
                onPrimaryAction = {
                    if (useInjectedViewModels) {
                        onStartDraft()
                    } else {
                        val draftId = DraftId.from(uuidGenerator.newUuid())
                        navController.navigate(AppRoutes.camera(draftId))
                    }
                },
                iconRes = R.drawable.ic_add_document,
                tone = if (draftFlowState.draftCreationFailed) {
                    StatusTone.ERROR
                } else {
                    StatusTone.INFO
                },
                primaryActionEnabled = !draftFlowState.isCreatingDraft,
            )
        }
        draftDestination(
            route = AppRoutes.PURCHASE_SOURCE,
            onInvalidDestination = onInvalidDestination,
            protectedBackEnabled = protectedBackEnabled,
            onProtectedBack = onProtectedBack,
            optionalArguments = listOf(AppRoutes.REPLACE_ID),
        ) { draftId, _ ->
            if (useInjectedViewModels) {
                SourceRoute(
                    onOpenCamera = { effectDraftId, replaceImageId ->
                        navController.navigate(
                            AppRoutes.camera(effectDraftId, replaceImageId),
                        )
                    },
                    onOpenPreview = { effectDraftId, captureId ->
                        navController.navigate(
                            AppRoutes.imagePreview(effectDraftId, captureId),
                        )
                    },
                    onBack = navController::popBackStack,
                    onCloseInvalidRoute = onInvalidDestination,
                )
            } else {
                PurchaseFlowScreen(
                    titleRes = R.string.purchase_source_title,
                    messageRes = R.string.purchase_source_message,
                    primaryActionRes = R.string.action_use_camera,
                    onPrimaryAction = {
                        navController.navigate(AppRoutes.camera(draftId))
                    },
                    step = 1,
                    showPreviousAction = true,
                    onPreviousAction = navController::popBackStack,
                )
            }
        }
        draftDestination(
            route = AppRoutes.CAMERA,
            onInvalidDestination = onInvalidDestination,
            protectedBackEnabled = protectedBackEnabled,
            onProtectedBack = onProtectedBack,
            optionalArguments = listOf(AppRoutes.REPLACE_ID),
        ) { draftId, _ ->
            if (useInjectedViewModels) {
                CaptureRoute(
                    onOpenProcessing = { effectDraftId ->
                        navController.navigate(AppRoutes.processing(effectDraftId)) {
                            launchSingleTop = true
                        }
                    },
                    onBack = navController::popBackStack,
                    onCloseInvalidRoute = onInvalidDestination,
                    allowImagePicker = navController.previousBackStackEntry
                        ?.destination
                        ?.route == AppRoutes.PURCHASE_SOURCE,
                )
            } else {
                PurchaseFlowScreen(
                    titleRes = R.string.purchase_camera_title,
                    messageRes = R.string.purchase_camera_message,
                    primaryActionRes = R.string.action_take_photo,
                    onPrimaryAction = {
                        navController.navigate(AppRoutes.processing(draftId))
                    },
                    step = 2,
                    iconRes = R.drawable.ic_add_document,
                    showPreviousAction = true,
                    onPreviousAction = navController::popBackStack,
                )
            }
        }
        draftDestination(
            route = AppRoutes.IMAGE_PREVIEW,
            additionalArguments = listOf(AppRoutes.CAPTURE_ID),
            onInvalidDestination = onInvalidDestination,
            protectedBackEnabled = protectedBackEnabled,
            onProtectedBack = onProtectedBack,
        ) { draftId, entry ->
            val captureId = CaptureId.parse(
                entry.arguments?.getString(AppRoutes.CAPTURE_ID),
            )
            if (captureId == null) {
                InvalidNavigationScreen(onReturnToPurchases = onInvalidDestination)
            } else if (useInjectedViewModels) {
                PreviewRoute(
                    onOpenSource = { effectDraftId, replaceImageId ->
                        navController.navigate(
                            AppRoutes.source(effectDraftId, replaceImageId),
                        )
                    },
                    onOpenProcessing = { effectDraftId ->
                        navController.navigate(AppRoutes.processing(effectDraftId))
                    },
                    onBack = navController::popBackStack,
                    onCloseInvalidRoute = onInvalidDestination,
                )
            } else {
                PurchaseFlowScreen(
                    titleRes = R.string.purchase_preview_title,
                    messageRes = R.string.purchase_preview_message,
                    primaryActionRes = R.string.action_use_photo,
                    onPrimaryAction = {
                        navController.navigate(AppRoutes.processing(draftId))
                    },
                    step = 3,
                    showPreviousAction = true,
                    onPreviousAction = navController::popBackStack,
                )
            }
        }
        draftDestination(
            route = AppRoutes.PROCESSING,
            onInvalidDestination = onInvalidDestination,
            protectedBackEnabled = protectedBackEnabled,
            onProtectedBack = onProtectedBack,
        ) { draftId, _ ->
            val returnToCamera: () -> Unit = {
                val previousRoute = navController.previousBackStackEntry?.destination?.route
                if (previousRoute == AppRoutes.CAMERA) {
                    navController.popBackStack()
                    Unit
                } else {
                    navController.navigate(AppRoutes.camera(draftId)) {
                        popUpTo(AppRoutes.PROCESSING) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            }
            if (useInjectedViewModels) {
                OcrRoute(
                    onOpenReview = { effectDraftId ->
                        navController.navigate(AppRoutes.invoiceHeader(effectDraftId))
                    },
                    onOpenManualReview = { effectDraftId ->
                        navController.navigate(AppRoutes.manualInvoiceReview(effectDraftId))
                    },
                    onOpenProducts = { _, _, _ ->
                        navController.navigate(AppRoutes.PRODUCTS) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = false
                            }
                            launchSingleTop = true
                        }
                    },
                    onCancelled = returnToCamera,
                    onBack = returnToCamera,
                    onCloseInvalidRoute = onInvalidDestination,
                )
            } else {
                PurchaseFlowScreen(
                    titleRes = R.string.purchase_processing_title,
                    messageRes = R.string.purchase_processing_message,
                    primaryActionRes = R.string.action_finish_processing,
                    onPrimaryAction = {
                        navController.navigate(AppRoutes.PRODUCTS) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = false
                            }
                            launchSingleTop = true
                        }
                    },
                    showPreviousAction = true,
                    onPreviousAction = returnToCamera,
                )
            }
        }
        draftDestination(
            route = AppRoutes.INVOICE_HEADER,
            onInvalidDestination = onInvalidDestination,
            protectedBackEnabled = protectedBackEnabled,
            onProtectedBack = onProtectedBack,
        ) { draftId, _ ->
            if (useInjectedViewModels) {
                InvoiceHeaderReviewRoute(
                    onOpenProductsReview = { effectDraftId ->
                        navController.navigate(AppRoutes.invoiceLines(effectDraftId))
                    },
                    onBack = navController::popBackStack,
                    onCloseInvalidRoute = onInvalidDestination,
                )
            } else {
                PurchaseFlowScreen(
                    titleRes = R.string.purchase_header_title,
                    messageRes = R.string.purchase_header_message,
                    primaryActionRes = R.string.action_save_header,
                    onPrimaryAction = {
                        navController.navigate(AppRoutes.invoiceLines(draftId))
                    },
                    step = 5,
                    showPreviousAction = true,
                    onPreviousAction = navController::popBackStack,
                )
            }
        }
        draftDestination(
            route = AppRoutes.INVOICE_LINES,
            onInvalidDestination = onInvalidDestination,
            protectedBackEnabled = protectedBackEnabled,
            onProtectedBack = onProtectedBack,
        ) { draftId, _ ->
            if (useInjectedViewModels) {
                InvoiceLineReviewRoute(
                    onOpenProductLinking = { effectDraftId, firstLineId ->
                        navController.navigate(AppRoutes.productLinking(effectDraftId, firstLineId))
                    },
                    onBack = navController::popBackStack,
                    onCloseInvalidRoute = onInvalidDestination,
                )
            } else {
                PurchaseFlowScreen(
                    titleRes = R.string.purchase_lines_title,
                    messageRes = R.string.purchase_lines_message,
                    primaryActionRes = R.string.action_link_products,
                    onPrimaryAction = {
                        val lineId = LineId.from(uuidGenerator.newUuid())
                        navController.navigate(AppRoutes.productLinking(draftId, lineId))
                    },
                    step = 6,
                    showPreviousAction = true,
                    onPreviousAction = navController::popBackStack,
                )
            }
        }
        draftDestination(
            route = AppRoutes.PRODUCT_LINKING,
            additionalArguments = listOf(AppRoutes.LINE_ID),
            onInvalidDestination = onInvalidDestination,
            protectedBackEnabled = protectedBackEnabled,
            onProtectedBack = onProtectedBack,
        ) { draftId, entry ->
            val lineId = LineId.parse(entry.arguments?.getString(AppRoutes.LINE_ID))
            if (lineId == null) {
                InvalidNavigationScreen(onReturnToPurchases = onInvalidDestination)
            } else if (useInjectedViewModels) {
                ProductLinkingRoute(
                    onOpenSummary = { effectDraftId ->
                        navController.navigate(AppRoutes.purchaseSummary(effectDraftId))
                    },
                    onBack = navController::popBackStack,
                    onCloseInvalidRoute = onInvalidDestination,
                )
            } else {
                PurchaseFlowScreen(
                    titleRes = R.string.purchase_linking_title,
                    messageRes = R.string.purchase_linking_message,
                    primaryActionRes = R.string.action_review_summary,
                    onPrimaryAction = {
                        navController.navigate(AppRoutes.purchaseSummary(draftId))
                    },
                    step = 7,
                    showPreviousAction = true,
                    onPreviousAction = navController::popBackStack,
                )
            }
        }
        draftDestination(
            route = AppRoutes.PURCHASE_SUMMARY,
            onInvalidDestination = onInvalidDestination,
            protectedBackEnabled = protectedBackEnabled,
            onProtectedBack = onProtectedBack,
        ) { draftId, _ ->
            if (useInjectedViewModels) {
                PurchaseSummaryRoute(
                    onPreparedStateEntered = { effectDraftId ->
                        navController.ensurePreparedSummaryIsFlowRoot(effectDraftId)
                    },
                    onOpenLineReview = { effectDraftId ->
                        navController.openLineReviewFromPrepared(effectDraftId)
                    },
                    onOpenConfirmation = { effectDraftId, expectedHash ->
                        navController.navigate(
                            AppRoutes.purchaseConfirmation(effectDraftId, expectedHash),
                        ) {
                            launchSingleTop = true
                        }
                    },
                    onBack = navController::popBackStack,
                    onCloseInvalidRoute = onInvalidDestination,
                )
            } else {
                PurchaseFlowScreen(
                    titleRes = R.string.purchase_summary_title,
                    messageRes = R.string.purchase_summary_message,
                    primaryActionRes = R.string.action_go_to_confirmation,
                    onPrimaryAction = {
                        navController.navigate(
                            AppRoutes.purchaseConfirmation(draftId, "0".repeat(64)),
                        )
                    },
                    step = 8,
                    showPreviousAction = true,
                    onPreviousAction = navController::popBackStack,
                )
            }
        }
        draftDestination(
            route = AppRoutes.PURCHASE_CONFIRMATION,
            onInvalidDestination = onInvalidDestination,
            protectedBackEnabled = protectedBackEnabled,
            onProtectedBack = onProtectedBack,
            additionalArguments = listOf(AppRoutes.EXPECTED_PREPARED_HASH),
        ) { _, _ ->
            if (useInjectedViewModels) {
                PreparationRoute(
                    onOpenPurchase = { purchaseId ->
                        navController.navigate(AppRoutes.purchaseSuccess(purchaseId)) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = false
                            }
                            launchSingleTop = true
                        }
                    },
                    onOpenExistingPurchase = { purchaseId ->
                        navController.navigate(AppRoutes.purchaseDetail(purchaseId)) {
                            launchSingleTop = true
                        }
                    },
                    onBack = navController::popBackStack,
                    onCloseInvalidRoute = onInvalidDestination,
                )
            } else {
                PurchaseFlowScreen(
                    titleRes = R.string.purchase_confirmation_title,
                    messageRes = R.string.purchase_confirmation_message,
                    primaryActionRes = R.string.action_confirm_purchase,
                    onPrimaryAction = {
                        val purchaseId = PurchaseId.from(uuidGenerator.newUuid())
                        navController.navigate(AppRoutes.purchaseSuccess(purchaseId)) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = false
                            }
                            launchSingleTop = true
                        }
                    },
                    step = 9,
                    tone = StatusTone.WARNING,
                    showPreviousAction = true,
                    onPreviousAction = navController::popBackStack,
                )
            }
        }
        composable(
            route = AppRoutes.PURCHASE_SUCCESS,
            arguments = listOf(stringArgument(AppRoutes.PURCHASE_ID)),
        ) { entry ->
            val purchaseId = PurchaseId.parse(
                entry.arguments?.getString(AppRoutes.PURCHASE_ID),
            )
            if (purchaseId == null) {
                InvalidNavigationScreen(onReturnToPurchases = onInvalidDestination)
            } else if (useInjectedViewModels) {
                PurchaseSuccessRoute(
                    onViewDetail = { confirmedPurchaseId ->
                        navController.navigate(AppRoutes.purchaseDetail(confirmedPurchaseId)) {
                            popUpTo(AppRoutes.PURCHASE_SUCCESS) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                    onViewInventory = {
                        navController.navigateTopLevel(TopLevelDestination.INVENTORY)
                    },
                    onCloseInvalidRoute = onInvalidDestination,
                )
            } else {
                PurchaseFlowScreen(
                    titleRes = R.string.purchase_success_title,
                    messageRes = R.string.purchase_success_message,
                    primaryActionRes = R.string.action_view_detail,
                    onPrimaryAction = {
                        navController.navigate(AppRoutes.purchaseDetail(purchaseId)) {
                            popUpTo(AppRoutes.PURCHASE_SUCCESS) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                    iconRes = R.drawable.ic_check_circle,
                    tone = StatusTone.SUCCESS,
                )
            }
        }
        composable(
            route = AppRoutes.PURCHASE_DETAIL,
            arguments = listOf(stringArgument(AppRoutes.PURCHASE_ID)),
        ) { entry ->
            val purchaseId = PurchaseId.parse(
                entry.arguments?.getString(AppRoutes.PURCHASE_ID),
            )
            if (purchaseId == null) {
                InvalidNavigationScreen(onReturnToPurchases = onInvalidDestination)
            } else if (useInjectedViewModels) {
                PurchasesRoute(
                    onOpenPurchase = { selectedPurchaseId ->
                        navController.navigate(AppRoutes.purchaseDetail(selectedPurchaseId)) {
                            launchSingleTop = true
                        }
                    },
                    onBack = {
                        if (!navController.popBackStack()) {
                            navController.navigate(AppRoutes.PURCHASES) { launchSingleTop = true }
                        }
                    },
                    onCloseInvalidRoute = onInvalidDestination,
                    onVoidPurchase = { purchaseToVoid ->
                        navController.navigate(AppRoutes.purchaseVoid(purchaseToVoid)) {
                            launchSingleTop = true
                        }
                    },
                )
            } else {
                PurchaseFlowScreen(
                    titleRes = R.string.purchase_detail_title,
                    messageRes = R.string.purchase_detail_message,
                    primaryActionRes = R.string.action_return_purchases,
                    onPrimaryAction = {
                        navController.navigate(AppRoutes.PURCHASES) { launchSingleTop = true }
                    },
                    tone = StatusTone.NEUTRAL,
                )
            }
        }
        composable(
            route = AppRoutes.PURCHASE_VOID,
            arguments = listOf(stringArgument(AppRoutes.PURCHASE_ID)),
        ) { entry ->
            val purchaseId = PurchaseId.parse(
                entry.arguments?.getString(AppRoutes.PURCHASE_ID),
            )
            if (purchaseId == null) {
                InvalidNavigationScreen(onReturnToPurchases = onInvalidDestination)
            } else if (useInjectedViewModels) {
                PurchaseVoidRoute(
                    onCompleted = { completedPurchaseId ->
                        if (!navController.popBackStack()) {
                            navController.navigate(
                                AppRoutes.purchaseDetail(completedPurchaseId),
                            ) {
                                launchSingleTop = true
                            }
                        }
                    },
                    onBack = {
                        if (!navController.popBackStack()) {
                            navController.navigate(
                                AppRoutes.purchaseDetail(purchaseId),
                            ) {
                                launchSingleTop = true
                            }
                        }
                    },
                    onCloseInvalidRoute = onInvalidDestination,
                )
            } else {
                PurchaseFlowScreen(
                    titleRes = R.string.purchase_void_title,
                    messageRes = R.string.purchase_void_intro,
                    primaryActionRes = R.string.purchase_void_back,
                    onPrimaryAction = navController::popBackStack,
                    tone = StatusTone.WARNING,
                )
            }
        }
    }
}

private fun NavGraphBuilder.draftDestination(
    route: String,
    onInvalidDestination: () -> Unit,
    protectedBackEnabled: Boolean,
    onProtectedBack: () -> Unit,
    additionalArguments: List<String> = emptyList(),
    optionalArguments: List<String> = emptyList(),
    content: @Composable (DraftId, NavBackStackEntry) -> Unit,
) {
    composable(
        route = route,
        arguments = listOf(AppRoutes.DRAFT_ID, *additionalArguments.toTypedArray())
            .map(::stringArgument) + optionalArguments.map(::optionalStringArgument),
    ) { entry ->
        DraftBackHandler(
            enabled = protectedBackEnabled,
            onBack = onProtectedBack,
        )
        val draftId = DraftId.parse(entry.arguments?.getString(AppRoutes.DRAFT_ID))
        if (draftId == null) {
            InvalidNavigationScreen(onReturnToPurchases = onInvalidDestination)
        } else {
            content(draftId, entry)
        }
    }
}

@Composable
private fun DraftBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
) {
    val dispatcherOwner = LocalOnBackPressedDispatcherOwner.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnBack by rememberUpdatedState(onBack)
    val callback = remember {
        object : OnBackPressedCallback(enabled) {
            override fun handleOnBackPressed() {
                currentOnBack()
            }
        }
    }

    SideEffect { callback.isEnabled = enabled }
    DisposableEffect(dispatcherOwner, lifecycleOwner) {
        dispatcherOwner?.onBackPressedDispatcher?.addCallback(lifecycleOwner, callback)
        onDispose { callback.remove() }
    }
}

private fun stringArgument(name: String) = navArgument(name) {
    type = NavType.StringType
    nullable = false
}

/** Argumento de consulta opcional (ausente por defecto): el ID de página a reemplazar. */
private fun optionalStringArgument(name: String) = navArgument(name) {
    type = NavType.StringType
    nullable = true
    defaultValue = null
}

private fun NavHostController.navigateTopLevel(destination: TopLevelDestination) {
    navigate(destination.route) {
        popUpTo(graph.findStartDestination().id) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * Una compra preparada es de solo lectura. Si se preparó dentro del recorrido de captura, se
 * reemplaza ese recorrido por una única entrada de resumen para que Back nunca reactive una
 * pantalla editable con un borrador READY_TO_POST.
 */
internal fun NavHostController.ensurePreparedSummaryIsFlowRoot(draftId: DraftId) {
    if (previousBackStackEntry?.destination?.route !in AppRoutes.editableDraftPatterns) return
    navigate(AppRoutes.purchaseSummary(draftId)) {
        popUpTo(graph.findStartDestination().id) {
            saveState = false
        }
        launchSingleTop = true
    }
}

/**
 * `OpenLineReview` solo se emite después de invalidar la instantánea. Reemplazar el flujo evita
 * conservar un resumen PREPARED obsoleto —o editores anteriores— detrás de la nueva edición.
 */
internal fun NavHostController.openLineReviewFromPrepared(draftId: DraftId) {
    navigate(AppRoutes.invoiceLines(draftId)) {
        popUpTo(graph.findStartDestination().id) {
            saveState = false
        }
        launchSingleTop = true
    }
}
