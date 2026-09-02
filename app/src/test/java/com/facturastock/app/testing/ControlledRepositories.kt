package com.facturastock.app.testing

import com.facturastock.app.domain.model.FeatureArea
import com.facturastock.app.domain.model.FeatureSnapshot
import com.facturastock.app.domain.model.WorkflowRequest
import com.facturastock.app.domain.model.WorkflowSnapshot
import com.facturastock.app.domain.repository.ConfirmPurchaseCommand
import com.facturastock.app.domain.repository.ConfirmPurchaseResult
import com.facturastock.app.domain.repository.DraftWorkflowRepository
import com.facturastock.app.domain.repository.FeatureRepository
import com.facturastock.app.domain.repository.PurchaseConfirmationContext
import com.facturastock.app.domain.repository.PurchasePostingRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.CancellationException
import kotlin.coroutines.ContinuationInterceptor

class ControlledRepositoryCall<Input, Output> internal constructor(
    val input: Input,
    val dispatcher: CoroutineDispatcher?,
) {
    private val result = CompletableDeferred<Output>()

    @Volatile
    var cancellation: CancellationException? = null
        private set

    val wasCancelled: Boolean
        get() = cancellation != null

    fun succeed(output: Output) {
        check(result.complete(output)) { "La llamada ya fue completada" }
    }

    fun fail(error: Throwable) {
        check(result.completeExceptionally(error)) { "La llamada ya fue completada" }
    }

    internal suspend fun awaitResult(): Output = try {
        result.await()
    } catch (error: CancellationException) {
        cancellation = error
        throw error
    }
}

class ControlledFeatureRepository : FeatureRepository {
    private val startedCalls =
        Channel<ControlledRepositoryCall<FeatureArea, FeatureSnapshot>>(Channel.UNLIMITED)
    private val recordedCalls =
        mutableListOf<ControlledRepositoryCall<FeatureArea, FeatureSnapshot>>()

    val calls: List<ControlledRepositoryCall<FeatureArea, FeatureSnapshot>>
        get() = recordedCalls.toList()

    override suspend fun load(area: FeatureArea): FeatureSnapshot {
        val call = ControlledRepositoryCall<FeatureArea, FeatureSnapshot>(
            input = area,
            dispatcher = currentCoroutineContext()[ContinuationInterceptor] as? CoroutineDispatcher,
        )
        recordedCalls += call
        startedCalls.send(call)
        return call.awaitResult()
    }

    suspend fun awaitCall(): ControlledRepositoryCall<FeatureArea, FeatureSnapshot> =
        startedCalls.receive()

    fun takeCall(): ControlledRepositoryCall<FeatureArea, FeatureSnapshot> =
        startedCalls.tryReceive().getOrThrow()
}

class ControlledDraftWorkflowRepository : DraftWorkflowRepository {
    private val startedCalls =
        Channel<ControlledRepositoryCall<WorkflowRequest, WorkflowSnapshot>>(Channel.UNLIMITED)
    private val recordedCalls =
        mutableListOf<ControlledRepositoryCall<WorkflowRequest, WorkflowSnapshot>>()

    val calls: List<ControlledRepositoryCall<WorkflowRequest, WorkflowSnapshot>>
        get() = recordedCalls.toList()

    override suspend fun run(request: WorkflowRequest): WorkflowSnapshot {
        val call = ControlledRepositoryCall<WorkflowRequest, WorkflowSnapshot>(
            input = request,
            dispatcher = currentCoroutineContext()[ContinuationInterceptor] as? CoroutineDispatcher,
        )
        recordedCalls += call
        startedCalls.send(call)
        return call.awaitResult()
    }

    suspend fun awaitCall(): ControlledRepositoryCall<WorkflowRequest, WorkflowSnapshot> =
        startedCalls.receive()

    fun takeCall(): ControlledRepositoryCall<WorkflowRequest, WorkflowSnapshot> =
        startedCalls.tryReceive().getOrThrow()
}

data class PurchasePostingInvocation(
    val command: ConfirmPurchaseCommand,
    val context: PurchaseConfirmationContext,
)

/** Puerto suspendible para probar exclusión mutua, reintentos y resultados idempotentes. */
class ControlledPurchasePostingRepository : PurchasePostingRepository {
    private val startedCalls =
        Channel<ControlledRepositoryCall<PurchasePostingInvocation, ConfirmPurchaseResult>>(
            Channel.UNLIMITED,
        )
    private val recordedCalls =
        mutableListOf<ControlledRepositoryCall<PurchasePostingInvocation, ConfirmPurchaseResult>>()

    val calls: List<ControlledRepositoryCall<PurchasePostingInvocation, ConfirmPurchaseResult>>
        get() = recordedCalls.toList()

    override suspend fun confirm(
        command: ConfirmPurchaseCommand,
        context: PurchaseConfirmationContext,
    ): ConfirmPurchaseResult {
        val call = ControlledRepositoryCall<PurchasePostingInvocation, ConfirmPurchaseResult>(
            input = PurchasePostingInvocation(command, context),
            dispatcher = currentCoroutineContext()[ContinuationInterceptor] as? CoroutineDispatcher,
        )
        recordedCalls += call
        startedCalls.send(call)
        return call.awaitResult()
    }

    suspend fun awaitCall(): ControlledRepositoryCall<PurchasePostingInvocation, ConfirmPurchaseResult> =
        startedCalls.receive()

    fun takeCall(): ControlledRepositoryCall<PurchasePostingInvocation, ConfirmPurchaseResult> =
        startedCalls.tryReceive().getOrThrow()
}
