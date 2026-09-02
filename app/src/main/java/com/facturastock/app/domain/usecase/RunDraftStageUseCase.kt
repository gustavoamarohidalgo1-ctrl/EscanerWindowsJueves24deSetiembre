package com.facturastock.app.domain.usecase

import com.facturastock.app.domain.model.WorkflowRequest
import com.facturastock.app.domain.model.WorkflowSnapshot
import com.facturastock.app.domain.repository.DraftWorkflowRepository

class RunDraftStageUseCase(
    private val repository: DraftWorkflowRepository,
) {
    suspend operator fun invoke(request: WorkflowRequest): WorkflowSnapshot =
        repository.run(request)
}
