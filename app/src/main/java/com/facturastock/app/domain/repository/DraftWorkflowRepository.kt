package com.facturastock.app.domain.repository

import com.facturastock.app.domain.model.WorkflowRequest
import com.facturastock.app.domain.model.WorkflowSnapshot

interface DraftWorkflowRepository {
    suspend fun run(request: WorkflowRequest): WorkflowSnapshot
}
