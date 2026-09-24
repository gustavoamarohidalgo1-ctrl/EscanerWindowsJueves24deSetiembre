package com.facturastock.app.navigation

import com.facturastock.app.resources.*
import com.facturastock.app.ui.navigation.BackHandler
import com.facturastock.app.ui.navigation.BackPressedDispatcher
import com.facturastock.app.ui.navigation.LocalBackPressedDispatcher
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
import com.facturastock.app.startup.LocalDesktopStartup
import androidx.savedstate.SavedState
import androidx.savedstate.read
import androidx.savedstate.write
import androidx.compose.ui.platform.LocalDensity
import org.jetbrains.compose.resources.stringResource
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import com.facturastock.app.di.appViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import com.facturastock.app.domain.model.ReportPdfKind
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.core.id.RandomUuidGenerator
import com.facturastock.app.core.id.UuidGenerator
import com.facturastock.app.feature.capture.CaptureRoute
import com.facturastock.app.feature.catalogs.CatalogsRoute
import com.facturastock.app.feature.common.CollectUiEffects
import com.facturastock.app.feature.debtors.DebtorsContract
import com.facturastock.app.feature.debtors.DebtorsListScreen
import com.facturastock.app.feature.debtors.DebtorsRoute
import com.facturastock.app.feature.inventory.InventoryContract
import com.facturastock.app.feature.inventory.InventoryListScreen
import com.facturastock.app.feature.inventory.InventoryRegistrationRoute
import com.facturastock.app.feature.inventory.InventoryRegistrationScreen
import com.facturastock.app.feature.inventory.InventoryRoute
import com.facturastock.app.feature.headerreview.InvoiceHeaderReviewRoute
import com.facturastock.app.feature.linereview.InvoiceLineReviewRoute
import com.facturastock.app.feature.linking.ProductLinkingRoute
import com.facturastock.app.feature.matching.InvoiceMatchingRoute
import com.facturastock.app.feature.ocr.OcrRoute
import com.facturastock.app.feature.onboarding.OnboardingRoute
import com.facturastock.app.feature.preparation.PreparationRoute
import com.facturastock.app.feature.preview.PreviewRoute
import com.facturastock.app.feature.purchase.InvalidNavigationScreen
import com.facturastock.app.feature.purchase.PurchaseFlowScreen
import com.facturastock.app.feature.purchases.PurchasesRoute
import com.facturastock.app.feature.purchases.PurchaseSuccessRoute
import com.facturastock.app.feature.purchases.PurchaseVoidRoute
import com.facturastock.app.feature.reports.ReportsPdfTopBarAction
import com.facturastock.app.feature.reports.ReportsRoute
import com.facturastock.app.feature.reports.ReportsTestTags
import com.facturastock.app.feature.root.AppGateViewModel
import com.facturastock.app.feature.root.GateState
import com.facturastock.app.feature.sales.SalesContract
import com.facturastock.app.feature.sales.SalesRoute
import com.facturastock.app.feature.sales.SalesScreen
import com.facturastock.app.feature.sales.SalesTestTags
import com.facturastock.app.feature.settings.SettingsRoute
import com.facturastock.app.feature.source.SourceRoute
import com.facturastock.app.feature.summary.PurchaseSummaryRoute
import com.facturastock.app.feature.top.TopLevelPlaceholderScreen
import com.facturastock.app.ui.components.FacturaStockBottomItem
import com.facturastock.app.ui.components.FacturaStockBottomNavigation
import com.facturastock.app.ui.components.FacturaStockDialog
import com.facturastock.app.ui.components.FacturaStockNavigationRail
import com.facturastock.app.ui.components.FacturaStockScaffold
import com.facturastock.app.ui.components.FacturaStockTopBar
import com.facturastock.app.ui.components.FacturaStockTopBarAction
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
        appViewModel<AppGateViewModel>()
    } else {
        null
    }
    val gateState = if (gateViewModel != null) {
        gateViewModel.uiState.collectAsStateWithLifecycle().value
    } else {
        GateState.Complete
    }
    val draftFlowViewModel = if (shouldCreateDraftFlowViewModel(gateState, useInjectedViewModels)) {
        appViewModel<DraftFlowViewModel>()
    } else {
        null
    }

    when (gateState) {
        GateState.Loading -> LoadingState(
            message = stringResource(Res.string.feature_loading_message),
            modifier = Modifier.fillMaxSize(),
        )

        GateState.Unavailable -> {
            DeferredStartupAfterFrameEffect()
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                RecoverableError(
                    title = stringResource(Res.string.app_configuration_error_title),
                    message = stringResource(Res.string.app_configuration_error_message),
                    actionLabel = stringResource(Res.string.action_retry),
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
                startDestination = AppRoutes.SALES,
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
    val startup = LocalDesktopStartup.current
    LaunchedEffect(startup) {
        startup ?: return@LaunchedEffect
        // LaunchedEffect puede entrar antes del draw del frame que acaba de componer este destino.
        // El primer pulso permite que ese frame se dibuje; el segundo garantiza que el trabajo
        // diferido empieza cuando al menos un frame completo ya fue presentado.
        withFrameNanos { }
        withFrameNanos { }
        startup.onFirstAppFrameRendered()
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
    val backPressedDispatcher = LocalBackPressedDispatcher.current
    DisposableEffect(backPressedDispatcher, navController) {
        backPressedDispatcher?.fallback = { navController.popBackStack() }
        onDispose { backPressedDispatcher?.fallback = null }
    }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentPattern = backStackEntry?.destination?.route
    if (shouldSignalDeferredStartupFromDestination(currentPattern)) {
        // Vender y los demás destinos despiertan el mantenimiento después de su primer frame.
        // El alias antiguo de Inicio espera a redirigir al destino vigente.
        DeferredStartupAfterFrameEffect()
    }
    val currentDefinition = AppRoutes.definitionFor(currentPattern)
    val protectedDraft = currentDefinition?.protectsDraftOnExit == true
    var showDiscardDialog by rememberSaveable { mutableStateOf(false) }
    var handledDeepLinkRequestId by rememberSaveable { mutableStateOf<Long?>(null) }
    var pendingSalesExit by remember { mutableStateOf<PendingNavigation?>(null) }
    var salesExitRequest by remember { mutableStateOf<(() -> Unit)?>(null) }
    var reportsPdfAction by remember { mutableStateOf<ReportsPdfTopBarAction?>(null) }
    var inventoryTopBarActions by remember { mutableStateOf<List<FacturaStockTopBarAction>>(emptyList()) }
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
            // Igual que OnBackPressedDispatcher: primero los BackHandler de la pantalla y, si
            // ninguno lo atiende, el NavHost retrocede. En la raíz no se cierra la ventana.
            if (backPressedDispatcher?.dispatch() != true) navController.popBackStack()
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

    fun removeFlowAndOpenSales() {
        navController.navigate(AppRoutes.SALES) {
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
                    removeFlowAndOpenSales()
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
    val currentTitle = if (currentPattern == AppRoutes.PRODUCTS_PATTERN &&
        backStackEntry?.stringArgument(AppRoutes.MANUAL_PRODUCT) == "true"
    ) {
        stringResource(Res.string.inventory_register_manual)
    } else if (currentDefinition?.topLevel == true) {
        stringResource(topLevelDestinations[selectedTopLevelIndex].labelRes)
    } else {
        stringResource(currentDefinition?.titleRes ?: Res.string.app_name)
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
                AppRoutes.SALES,
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
                    brandLabel = stringResource(Res.string.app_name),
                    brandIconRes = Res.drawable.ic_facturastock,
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
                val pdfAction = reportsPdfAction.takeIf { currentPattern == AppRoutes.REPORTS }
                val pdfDescription = pdfAction?.let {
                    stringResource(
                        if (it.kind == ReportPdfKind.DEBTORS) {
                            Res.string.reports_pdf_debtors_action
                        } else {
                            Res.string.reports_pdf_daily_action
                        },
                    )
                }
                val creditSaleDescription = stringResource(Res.string.sales_open_credit_sale)
                val extraActions = when {
                    pdfAction != null && pdfDescription != null -> listOf(
                        FacturaStockTopBarAction(
                            iconRes = Res.drawable.ic_pdf,
                            contentDescription = pdfDescription,
                            onClick = pdfAction.onClick,
                            enabled = pdfAction.enabled,
                            testTag = if (pdfAction.kind == ReportPdfKind.DEBTORS) {
                                ReportsTestTags.PDF_DEBTORS
                            } else {
                                ReportsTestTags.PDF_DAILY
                            },
                        ),
                    )
                    currentPattern == AppRoutes.SALES && useInjectedViewModels -> listOf(
                        FacturaStockTopBarAction(
                            iconRes = Res.drawable.ic_debtors,
                            contentDescription = creditSaleDescription,
                            onClick = {
                                requestNavigation(PendingNavigation(AppRoutes.NEW_DEBT, topLevel = false))
                            },
                            testTag = SalesTestTags.OPEN_CREDIT_SALE,
                        ),
                    )
                    currentPattern == AppRoutes.INVENTORY -> inventoryTopBarActions
                    else -> emptyList()
                }
                FacturaStockTopBar(
                    extraActions = extraActions,
                    title = currentTitle,
                    contentMaxWidth = scaffoldContentMaxWidth,
                    compact = useCompactChrome,
                    navigationIconRes = if (showBackNavigation) {
                        Res.drawable.ic_back
                    } else {
                        null
                    },
                    navigationContentDescription = if (showBackNavigation) {
                        stringResource(
                            if (currentDefinition.protectsDraftOnExit) {
                                Res.string.action_close_purchase_flow
                            } else {
                                Res.string.action_back
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
                    // Ajustes mínimos (perfil del negocio y exportación de datos).
                    actionIconRes = if (currentDefinition?.topLevel == true) {
                        Res.drawable.ic_settings
                    } else {
                        null
                    },
                    actionContentDescription = if (currentDefinition?.topLevel == true) {
                        stringResource(Res.string.navigation_open_settings)
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
                onInvalidDestination = ::removeFlowAndOpenSales,
                protectedBackEnabled = !showDiscardDialog,
                onProtectedBack = { showDiscardDialog = true },
                draftFlowState = draftFlowState,
                onStartDraft = {
                    draftFlowViewModel?.onAction(DraftFlowContract.Action.StartDraft)
                },
                onSalesExitConfirmed = {
                    val target = pendingSalesExit
                    pendingSalesExit = null
                    when {
                        target != null -> navigateTo(target)
                        navController.previousBackStackEntry != null -> navController.popBackStack()
                        // Android cerraba la Activity; en Windows la ventana sigue abierta en Vender.
                        else -> Unit
                    }
                },
                onSalesExitCancelled = { pendingSalesExit = null },
                onSalesExitRequestAvailable = { request -> salesExitRequest = request },
                onOpenDebtorsFromSales = {
                    requestNavigation(PendingNavigation(AppRoutes.DEBTORS, topLevel = false))
                },
                onReportsPdfActionAvailable = { reportsPdfAction = it },
                onInventoryTopBarActionsAvailable = { inventoryTopBarActions = it },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
            )
        }
    }

    if (showDiscardDialog) {
        FacturaStockDialog(
            title = stringResource(Res.string.discard_dialog_title),
            message = stringResource(
                if (draftFlowState.discardFailedDraftId != null) {
                    Res.string.home_draft_delete_error
                } else {
                    Res.string.discard_dialog_message
                },
            ),
            confirmLabel = stringResource(Res.string.action_discard_draft),
            dismissLabel = stringResource(Res.string.action_keep_editing),
            onConfirm = {
                if (draftFlowState.discardingDraftId == null) {
                    val draftId = DraftId.parse(
                        backStackEntry?.stringArgument(AppRoutes.DRAFT_ID),
                    )
                    if (draftFlowViewModel != null && draftId != null) {
                        draftFlowViewModel.onAction(
                            DraftFlowContract.Action.DiscardDraft(draftId),
                        )
                    } else {
                        draftId?.let(onDiscardDraft)
                        showDiscardDialog = false
                        removeFlowAndOpenSales()
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
    onOpenDebtorsFromSales: () -> Unit,
    onReportsPdfActionAvailable: (ReportsPdfTopBarAction?) -> Unit,
    onInventoryTopBarActionsAvailable: (List<FacturaStockTopBarAction>) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
        // El valor por defecto es un fundido cruzado de 700 ms: durante ~42 cuadros se componen y
        // dibujan las dos pantallas en capas semitransparentes. Entre Vender, Inventario y Reportes
        // el cambio es inmediato; hacia pantallas secundarias queda un fundido breve.
        enterTransition = { navigationEnterTransition(initialState, targetState) },
        exitTransition = { navigationExitTransition(initialState, targetState) },
        popEnterTransition = { navigationEnterTransition(initialState, targetState) },
        popExitTransition = { navigationExitTransition(initialState, targetState) },
    ) {
        composable(AppRoutes.ONBOARDING) {
            if (useInjectedViewModels) {
                OnboardingRoute(
                    onFinished = {
                        navController.navigate(AppRoutes.SALES) {
                            popUpTo(AppRoutes.ONBOARDING) {
                                inclusive = true
                            }
                        }
                    },
                )
            } else {
                TopLevelPlaceholderScreen(
                    titleRes = Res.string.onboarding_title,
                    messageRes = Res.string.onboarding_subtitle,
                )
            }
        }
        composable(AppRoutes.HOME) {
            // Una pila restaurada de una versión anterior también abre Vender.
            LaunchedEffect(navController) {
                navController.navigate(AppRoutes.SALES) {
                    popUpTo(AppRoutes.HOME) { inclusive = true }
                    launchSingleTop = true
                }
            }
        }
        composable(AppRoutes.SALES) { entry ->
            if (useInjectedViewModels) {
                val registrationResult = salesRegistrationResult(entry)
                // Vender abre directamente la venta al contado; la venta a crédito se abre con el
                // icono de la barra superior.
                SalesRoute(
                    allowEntryKindSelection = false,
                    showStepBack = false,
                    onBack = onSalesExitConfirmed,
                    onOpenDebtors = onOpenDebtorsFromSales,
                    onCreditSalePosted = {
                        navController.navigate(AppRoutes.DEBTORS) {
                            popUpTo(AppRoutes.SALES) { inclusive = false }
                            launchSingleTop = true
                        }
                    },
                    onExitCancelled = onSalesExitCancelled,
                    onExitRequestAvailable = onSalesExitRequestAvailable,
                    onRegisterProduct = { request ->
                        if (navController.currentBackStackEntry?.id == entry.id) {
                            navController.navigate(
                                AppRoutes.salesProductRegistration(request.barcode, request.requestId, request.businessId),
                            ) { launchSingleTop = true }
                        }
                    },
                    registrationResult = registrationResult,
                    onRegistrationResultConsumed = { entry.savedStateHandle[SALES_REGISTRATION_RESULT_KEY] = null },
                )
            } else {
                SalesScreen(
                    state = SalesContract.State(isLoading = false),
                    onAction = { action ->
                        if (action == SalesContract.Action.BackSelected) {
                            onSalesExitConfirmed()
                        } else if (action == SalesContract.Action.OpenDebtorsSelected) {
                            onOpenDebtorsFromSales()
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
        composable(AppRoutes.NEW_DEBT) { entry ->
            if (useInjectedViewModels) {
                val registrationResult = salesRegistrationResult(entry)
                SalesRoute(
                    entryKind = SalesContract.EntryKind.CREDIT,
                    allowEntryKindSelection = false,
                    onBack = onSalesExitConfirmed,
                    onCreditSalePosted = {
                        // Vuelve a la lista de deudores desde la que se abrió: la pantalla propia
                        // o la pestaña Deudores de Reportes.
                        if (!navController.popBackStack(AppRoutes.DEBTORS, inclusive = false) &&
                            !navController.popBackStack(AppRoutes.REPORTS, inclusive = false)
                        ) {
                            navController.navigate(AppRoutes.DEBTORS) { launchSingleTop = true }
                        }
                    },
                    onExitCancelled = onSalesExitCancelled,
                    onExitRequestAvailable = onSalesExitRequestAvailable,
                    onRegisterProduct = { request ->
                        if (navController.currentBackStackEntry?.id == entry.id) {
                            navController.navigate(
                                AppRoutes.salesProductRegistration(request.barcode, request.requestId, request.businessId),
                            ) { launchSingleTop = true }
                        }
                    },
                    registrationResult = registrationResult,
                    onRegistrationResultConsumed = { entry.savedStateHandle[SALES_REGISTRATION_RESULT_KEY] = null },
                )
            } else {
                SalesScreen(
                    state = SalesContract.State(
                        isLoading = false,
                        entryKind = SalesContract.EntryKind.CREDIT,
                        entryStep = SalesContract.EntryStep.SELECT_MODE,
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
            arguments = listOf(detailIdArgument(AppRoutes.DEBT_ID, siblingLiteralRoute = AppRoutes.NEW_DEBT)),
        ) { entry ->
            val debtId = DebtId.parse(entry.stringArgument(AppRoutes.DEBT_ID))
            if (debtId == null) {
                RecoverableError(
                    title = stringResource(Res.string.navigation_invalid_title),
                    message = stringResource(Res.string.navigation_invalid_message),
                    actionLabel = stringResource(Res.string.action_return_debtors),
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
                    onDebtDeleted = {
                        if (navController.currentBackStackEntry?.id == entry.id &&
                            !navController.popBackStack(AppRoutes.DEBTORS, inclusive = false)
                        ) {
                            navController.navigate(AppRoutes.DEBTORS) {
                                popUpTo(entry.destination.id) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    },
                    onFullPaymentSaved = {
                        if (navController.currentBackStackEntry?.id == entry.id) {
                            // Un cobro recién guardado abre siempre el día actual, incluso si
                            // la última visita a Reportes mostraba semana o mes.
                            navController.clearBackStack(AppRoutes.REPORTS)
                            navController.navigate(AppRoutes.REPORTS) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = false
                                }
                                launchSingleTop = true
                                restoreState = false
                            }
                        }
                    },
                )
            } else {
                TopLevelPlaceholderScreen(
                    titleRes = Res.string.debt_detail_title,
                    messageRes = Res.string.debt_not_found_message,
                )
            }
        }
        composable(AppRoutes.INVOICES) {
            // Una navegación restaurada de Facturas vuelve al destino principal vigente.
            LaunchedEffect(navController) {
                navController.navigate(AppRoutes.SALES) {
                    popUpTo(AppRoutes.INVOICES) { inclusive = true }
                    launchSingleTop = true
                }
            }
        }
        composable(AppRoutes.REPORTS) {
            if (useInjectedViewModels) {
                ReportsRoute(
                    onOpenDebtors = {
                        navController.navigate(AppRoutes.DEBTORS) { launchSingleTop = true }
                    },
                    onPdfActionAvailable = onReportsPdfActionAvailable,
                    debtorsContent = { debtorsModifier ->
                        DebtorsRoute(
                            onOpenDebt = { debtId -> navController.navigate(AppRoutes.debtDetail(debtId)) },
                            onNewDebt = { navController.navigate(AppRoutes.NEW_DEBT) },
                            onBack = {},
                            onCloseInvalidRoute = {},
                            modifier = debtorsModifier,
                        )
                    },
                )
            } else {
                TopLevelPlaceholderScreen(
                    titleRes = Res.string.navigation_reports,
                    messageRes = Res.string.reports_placeholder_message,
                )
            }
        }
        composable(
            route = AppRoutes.PRODUCTS_PATTERN,
            arguments = listOf(
                optionalStringArgument(AppRoutes.PREFILL_BARCODE),
                optionalStringArgument(AppRoutes.EDIT_PRODUCT_ID),
                optionalStringArgument(AppRoutes.SPECIAL_PRODUCT),
                optionalStringArgument(AppRoutes.MANUAL_PRODUCT),
            ),
        ) { entry ->
            if (useInjectedViewModels) {
                CatalogsRoute(
                    onBack = navController::popBackStack,
                    isManualRegistration = entry.stringArgument(AppRoutes.MANUAL_PRODUCT) == "true",
                    isSpecialRegistration = entry.stringArgument(AppRoutes.SPECIAL_PRODUCT) == "true",
                )
            } else {
                TopLevelPlaceholderScreen(
                    titleRes = Res.string.products_empty_title,
                    messageRes = Res.string.products_empty_message,
                )
            }
        }
        composable(
            route = AppRoutes.SALES_PRODUCT_REGISTRATION,
            arguments = listOf(
                stringArgument(AppRoutes.PREFILL_BARCODE),
                stringArgument(AppRoutes.REGISTRATION_REQUEST_ID),
                stringArgument(AppRoutes.REGISTRATION_BUSINESS_ID),
            ),
        ) { entry ->
            val requestId = entry.stringArgument(AppRoutes.REGISTRATION_REQUEST_ID).orEmpty()
            val businessId = BusinessId.parse(entry.stringArgument(AppRoutes.REGISTRATION_BUSINESS_ID))
            val cancelRegistration = {
                navController.finishSalesRegistration(entry, SalesContract.ProductRegistrationResult(requestId))
            }
            if (requestId.isBlank() || requestId.length > 128 || businessId == null) {
                LaunchedEffect(entry) { cancelRegistration() }
            } else if (useInjectedViewModels) {
                CatalogsRoute(
                    onBack = cancelRegistration,
                    isSalesRegistration = true,
                    onProductSaved = { saved ->
                        if (saved.requestId == requestId && saved.businessId == businessId) {
                            navController.finishSalesRegistration(
                                entry,
                                SalesContract.ProductRegistrationResult(saved.requestId, saved.productId, saved.businessId),
                            )
                        }
                    },
                )
            } else {
                BackHandler(onBack = cancelRegistration)
                TopLevelPlaceholderScreen(
                    titleRes = Res.string.inventory_register_products,
                    messageRes = Res.string.products_empty_message,
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
                    titleRes = Res.string.purchases_empty_title,
                    messageRes = Res.string.purchases_empty_message,
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
                    onRegisterProduct = { barcode ->
                        if (navController.currentDestination?.route == AppRoutes.INVENTORY) {
                            val route = if (barcode.isNullOrBlank()) {
                                AppRoutes.manualProductRegistration()
                            } else {
                                AppRoutes.productsWithBarcode(barcode)
                            }
                            navController.navigate(route) {
                                launchSingleTop = true
                            }
                        }
                    },
                    onTopBarActionsAvailable = onInventoryTopBarActionsAvailable,
                    onRegisterProducts = { navController.navigate(AppRoutes.INVENTORY_REGISTER) },
                    onRegisterSpecialProduct = {
                        if (navController.currentDestination?.route == AppRoutes.INVENTORY) {
                            navController.navigate(AppRoutes.specialProductRegistration()) { launchSingleTop = true }
                        }
                    },
                    onEditProduct = { productId ->
                        if (navController.currentDestination?.route == AppRoutes.INVENTORY) {
                            navController.navigate(AppRoutes.editInventoryProduct(productId)) {
                                launchSingleTop = true
                            }
                        }
                    },
                    onBack = navController::popBackStack,
                    onCloseInvalidRoute = {
                        navController.navigateTopLevel(TopLevelDestination.INVENTORY)
                    },
                )
            } else {
                InventoryListScreen(
                    items = emptyList(), query = "", diagnosticReport = null,
                    isLoading = false, isDiagnosing = false, diagnosticFailed = false,
                    onQueryChange = {}, onProductClick = {}, onRunDiagnostic = {},
                    onRegisterProducts = { navController.navigate(AppRoutes.INVENTORY_REGISTER) },
                )
            }
        }
        composable(AppRoutes.INVENTORY_REGISTER) {
            if (useInjectedViewModels) {
                InventoryRegistrationRoute(
                    onRegisterProduct = { barcode ->
                        if (navController.currentDestination?.route == AppRoutes.INVENTORY_REGISTER) {
                            navController.navigate(AppRoutes.productsWithBarcode(barcode)) {
                                launchSingleTop = true
                            }
                        }
                    },
                    onOpenProduct = { productId ->
                        if (navController.currentDestination?.route == AppRoutes.INVENTORY_REGISTER) {
                            navController.navigate(AppRoutes.inventoryDetail(productId))
                        }
                    },
                    onBack = navController::popBackStack,
                )
            } else {
                InventoryRegistrationScreen(
                    state = InventoryContract.State(scannerActive = true),
                )
            }
        }
        composable(
            route = AppRoutes.INVENTORY_DETAIL,
            arguments = listOf(detailIdArgument(AppRoutes.PRODUCT_ID, siblingLiteralRoute = AppRoutes.INVENTORY_REGISTER)),
        ) { entry ->
            val productId = ProductId.parse(entry.stringArgument(AppRoutes.PRODUCT_ID))
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
                    onRegisterProduct = {},
                    onEditProduct = { selectedProductId ->
                        if (navController.currentDestination?.route == AppRoutes.INVENTORY_DETAIL) {
                            navController.navigate(AppRoutes.editInventoryProduct(selectedProductId)) {
                                launchSingleTop = true
                            }
                        }
                    },
                    onBack = navController::popBackStack,
                    onCloseInvalidRoute = {
                        navController.navigateTopLevel(TopLevelDestination.INVENTORY)
                    },
                )
            } else {
                TopLevelPlaceholderScreen(
                    titleRes = Res.string.inventory_detail_title,
                    messageRes = Res.string.inventory_detail_message,
                )
            }
        }
        composable(AppRoutes.SETTINGS) {
            if (useInjectedViewModels) {
                SettingsRoute()
            } else {
                TopLevelPlaceholderScreen(
                    titleRes = Res.string.settings_empty_title,
                    messageRes = Res.string.settings_empty_message,
                )
            }
        }
        composable(AppRoutes.NEW_PURCHASE) {
            PurchaseFlowScreen(
                titleRes = Res.string.purchase_new_title,
                messageRes = if (draftFlowState.draftCreationFailed) {
                    Res.string.home_draft_create_error
                } else {
                    Res.string.purchase_new_message
                },
                primaryActionRes = Res.string.action_start_purchase,
                onPrimaryAction = {
                    if (useInjectedViewModels) {
                        onStartDraft()
                    } else {
                        val draftId = DraftId.from(uuidGenerator.newUuid())
                        navController.navigate(AppRoutes.camera(draftId))
                    }
                },
                iconRes = Res.drawable.ic_add_document,
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
                    titleRes = Res.string.purchase_source_title,
                    messageRes = Res.string.purchase_source_message,
                    primaryActionRes = Res.string.action_use_camera,
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
            optionalArguments = listOf(AppRoutes.REPLACE_ID, AppRoutes.SCAN_RETAKE),
        ) { draftId, entry ->
            val isInvoiceScanRetake =
                entry.stringArgument(AppRoutes.SCAN_RETAKE)?.toBooleanStrictOrNull() == true
            if (useInjectedViewModels) {
                CaptureRoute(
                    onOpenProcessing = { effectDraftId ->
                        navController.navigate(AppRoutes.processing(effectDraftId)) {
                            launchSingleTop = true
                        }
                    },
                    onBack = if (isInvoiceScanRetake) {
                        onInvalidDestination
                    } else {
                        {
                            navController.popBackStack()
                            Unit
                        }
                    },
                    onCloseInvalidRoute = onInvalidDestination,
                    allowImagePicker = navController.previousBackStackEntry
                        ?.destination
                        ?.route == AppRoutes.PURCHASE_SOURCE,
                )
            } else {
                PurchaseFlowScreen(
                    titleRes = Res.string.purchase_camera_title,
                    messageRes = Res.string.purchase_camera_message,
                    primaryActionRes = Res.string.action_take_photo,
                    onPrimaryAction = {
                        navController.navigate(AppRoutes.processing(draftId))
                    },
                    step = 2,
                    iconRes = Res.drawable.ic_add_document,
                    showPreviousAction = true,
                    onPreviousAction = if (isInvoiceScanRetake) {
                        onInvalidDestination
                    } else {
                        {
                            navController.popBackStack()
                            Unit
                        }
                    },
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
                entry.stringArgument(AppRoutes.CAPTURE_ID),
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
                    titleRes = Res.string.purchase_preview_title,
                    messageRes = Res.string.purchase_preview_message,
                    primaryActionRes = Res.string.action_use_photo,
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
                navController.navigate(AppRoutes.invoiceScanRetakeCamera(draftId)) {
                    // En el flujo directo quita también la cámara anterior; una entrada legacy
                    // conserva su pila pero recibe igualmente la intención segura de página única.
                    popUpTo(
                        if (previousRoute == AppRoutes.CAMERA) {
                            AppRoutes.CAMERA
                        } else {
                            AppRoutes.PROCESSING
                        },
                    ) { inclusive = true }
                    launchSingleTop = true
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
                    onOpenMatching = { effectDraftId ->
                        val restoreReview = navController.currentBackStackEntry?.savedStateHandle
                            ?.remove<String>("matching.restoreForDraft") == effectDraftId.value
                        navController.navigate(AppRoutes.invoiceMatching(effectDraftId)) {
                            launchSingleTop = true
                            restoreState = restoreReview
                        }
                    },
                    onOpenProducts = { _, _, _ ->
                        navController.navigate(AppRoutes.invoiceMatching(draftId))
                    },
                    onCancelled = returnToCamera,
                    onBack = returnToCamera,
                    onCloseInvalidRoute = onInvalidDestination,
                )
            } else {
                PurchaseFlowScreen(
                    titleRes = Res.string.purchase_processing_title,
                    messageRes = Res.string.purchase_processing_message,
                    primaryActionRes = Res.string.action_finish_processing,
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
                    titleRes = Res.string.purchase_header_title,
                    messageRes = Res.string.purchase_header_message,
                    primaryActionRes = Res.string.action_save_header,
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
                    titleRes = Res.string.purchase_lines_title,
                    messageRes = Res.string.purchase_lines_message,
                    primaryActionRes = Res.string.action_link_products,
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
            route = AppRoutes.INVOICE_MATCHING,
            onInvalidDestination = onInvalidDestination,
            protectedBackEnabled = protectedBackEnabled,
            onProtectedBack = onProtectedBack,
        ) { draftId, _ ->
            if (useInjectedViewModels) {
                InvoiceMatchingRoute(
                    onMatchingConfirmed = { _ ->
                        navController.navigate(AppRoutes.PRODUCTS) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = false
                            }
                            launchSingleTop = true
                        }
                    },
                    onBack = {
                        navController.previousBackStackEntry?.takeIf {
                            it.destination.route == AppRoutes.PROCESSING
                        }?.savedStateHandle?.set("matching.restoreForDraft", draftId.value)
                        if (!navController.popBackStack(AppRoutes.PROCESSING, inclusive = false, saveState = true)) {
                            navController.popBackStack()
                        }
                    },
                )
            } else {
                PurchaseFlowScreen(
                    titleRes = Res.string.matching_title,
                    messageRes = Res.string.matching_subtitle,
                    primaryActionRes = Res.string.action_save,
                    onPrimaryAction = {
                        navController.navigate(AppRoutes.PRODUCTS)
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
            val lineId = LineId.parse(entry.stringArgument(AppRoutes.LINE_ID))
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
                    titleRes = Res.string.purchase_linking_title,
                    messageRes = Res.string.purchase_linking_message,
                    primaryActionRes = Res.string.action_review_summary,
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
                    titleRes = Res.string.purchase_summary_title,
                    messageRes = Res.string.purchase_summary_message,
                    primaryActionRes = Res.string.action_go_to_confirmation,
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
                    titleRes = Res.string.purchase_confirmation_title,
                    messageRes = Res.string.purchase_confirmation_message,
                    primaryActionRes = Res.string.action_confirm_purchase,
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
                entry.stringArgument(AppRoutes.PURCHASE_ID),
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
                    titleRes = Res.string.purchase_success_title,
                    messageRes = Res.string.purchase_success_message,
                    primaryActionRes = Res.string.action_view_detail,
                    onPrimaryAction = {
                        navController.navigate(AppRoutes.purchaseDetail(purchaseId)) {
                            popUpTo(AppRoutes.PURCHASE_SUCCESS) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                    iconRes = Res.drawable.ic_check_circle,
                    tone = StatusTone.SUCCESS,
                )
            }
        }
        composable(
            route = AppRoutes.PURCHASE_DETAIL,
            arguments = listOf(stringArgument(AppRoutes.PURCHASE_ID)),
        ) { entry ->
            val purchaseId = PurchaseId.parse(
                entry.stringArgument(AppRoutes.PURCHASE_ID),
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
                    titleRes = Res.string.purchase_detail_title,
                    messageRes = Res.string.purchase_detail_message,
                    primaryActionRes = Res.string.action_return_purchases,
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
                entry.stringArgument(AppRoutes.PURCHASE_ID),
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
                    titleRes = Res.string.purchase_void_title,
                    messageRes = Res.string.purchase_void_intro,
                    primaryActionRes = Res.string.purchase_void_back,
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
        val draftId = DraftId.parse(entry.stringArgument(AppRoutes.DRAFT_ID))
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
    BackHandler(enabled = enabled, onBack = onBack)
}

private const val SALES_REGISTRATION_RESULT_KEY = "sales.productRegistration.navigationResult"

@Composable
private fun salesRegistrationResult(entry: NavBackStackEntry): SalesContract.ProductRegistrationResult? {
    val fields by remember(entry) {
        entry.savedStateHandle.getStateFlow<ArrayList<String>?>(SALES_REGISTRATION_RESULT_KEY, null)
    }.collectAsStateWithLifecycle(lifecycleOwner = entry, minActiveState = Lifecycle.State.RESUMED)
    return salesRegistrationResultFromFields(fields)
}

internal fun salesRegistrationResultFromFields(fields: List<String>?): SalesContract.ProductRegistrationResult? {
    if (fields == null || fields.size != 3) return null
    val requestId = fields[0].takeIf { it.isNotBlank() && it.length <= 128 } ?: return null
    if (fields[1].isEmpty() && fields[2].isEmpty()) return SalesContract.ProductRegistrationResult(requestId)
    val productId = ProductId.parse(fields[1]) ?: return null
    val businessId = BusinessId.parse(fields[2]) ?: return null
    return SalesContract.ProductRegistrationResult(requestId, productId, businessId)
}

private fun NavHostController.finishSalesRegistration(
    source: NavBackStackEntry,
    result: SalesContract.ProductRegistrationResult,
) {
    // Un efecto repetido o de una entrada que ya salió no puede modificar otro carrito.
    if (currentBackStackEntry?.id != source.id) return
    previousBackStackEntry?.takeIf {
        it.destination.route == AppRoutes.SALES || it.destination.route == AppRoutes.NEW_DEBT
    }?.savedStateHandle?.set(
        SALES_REGISTRATION_RESULT_KEY,
        arrayListOf(result.requestId, result.productId?.value.orEmpty(), result.businessId?.value.orEmpty()),
    )
    popBackStack()
}

private fun stringArgument(name: String) = navArgument(name) {
    type = NavType.StringType
    nullable = false
}

/**
 * Identificador de un destino de detalle ("debtors/{debtId}") que no captura el segmento de su
 * ruta literal hermana ("debtors/new"). En escritorio (org.jetbrains.androidx.navigation 2.9.2)
 * `NavDeepLink` nunca marca una ruta como exacta (busca `Regex(".*")`, que coincide con cualquier
 * patrón, donde Android busca el texto literal ".*"), así que entre las
 * dos gana la que tiene más argumentos: "Venta a crédito" abría el detalle de la deuda "new" y
 * "Registrar productos" el del producto "register". Rechazar el valor en `parseValue` hace que el
 * patrón no coincida y la ruta literal vuelva a ganar; cualquier otro valor se acepta como antes.
 */
private fun detailIdArgument(name: String, siblingLiteralRoute: String) = navArgument(name) {
    type = DetailIdNavType(reservedSegment = siblingLiteralRoute.substringAfterLast('/'))
    nullable = false
}

private class DetailIdNavType(private val reservedSegment: String) : NavType<String>(isNullableAllowed = false) {
    override val name: String = "string"

    override fun put(bundle: SavedState, key: String, value: String) {
        bundle.write { putString(key, value) }
    }

    override fun get(bundle: SavedState, key: String): String? =
        bundle.read { if (contains(key)) getStringOrNull(key) else null }

    override fun parseValue(value: String): String {
        require(value != reservedSegment) { "'$value' es la ruta literal hermana, no un identificador" }
        return value
    }

    // NavHost compara el grafo reconstruido en cada recomposición (NavArgument compara su tipo):
    // sin igualdad por valor, cada recomposición instalaría un grafo "distinto" y reiniciaría la pila.
    override fun equals(other: Any?): Boolean =
        other is DetailIdNavType && other.reservedSegment == reservedSegment

    override fun hashCode(): Int = reservedSegment.hashCode()
}

/** Argumento de consulta opcional (ausente por defecto): el ID de página a reemplazar. */
private fun optionalStringArgument(name: String) = navArgument(name) {
    type = NavType.StringType
    nullable = true
    defaultValue = null
}

private fun NavHostController.navigateTopLevel(destination: TopLevelDestination) {
    val startDestination = graph.findStartDestination()
    navigate(destination.route) {
        popUpTo(startDestination.id) {
            saveState = true
        }
        launchSingleTop = true
        // La raíz sigue en la pila. Restaurarla recuperaría la sección que acabamos de salir.
        restoreState = destination.route != startDestination.route
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

private val TopLevelRoutePatterns: Set<String> = AppRoutes.topLevel.mapTo(HashSet()) { it.pattern }

/** Duración del fundido hacia y desde pantallas secundarias; entre pestañas no hay animación. */
internal const val SECONDARY_NAVIGATION_FADE_MILLIS = 150

private fun isTopLevelSwitch(
    initial: NavBackStackEntry,
    target: NavBackStackEntry,
): Boolean =
    initial.destination.route in TopLevelRoutePatterns && target.destination.route in TopLevelRoutePatterns

private fun navigationEnterTransition(
    initial: NavBackStackEntry,
    target: NavBackStackEntry,
): EnterTransition =
    if (isTopLevelSwitch(initial, target)) {
        EnterTransition.None
    } else {
        fadeIn(animationSpec = tween(SECONDARY_NAVIGATION_FADE_MILLIS))
    }

private fun navigationExitTransition(
    initial: NavBackStackEntry,
    target: NavBackStackEntry,
): ExitTransition =
    if (isTopLevelSwitch(initial, target)) {
        ExitTransition.None
    } else {
        fadeOut(animationSpec = tween(SECONDARY_NAVIGATION_FADE_MILLIS))
    }

/** Lee un argumento de ruta como texto (en escritorio `arguments` es un `SavedState`). */
private fun NavBackStackEntry.stringArgument(name: String): String? =
    arguments?.read { if (contains(name)) getStringOrNull(name) else null }
