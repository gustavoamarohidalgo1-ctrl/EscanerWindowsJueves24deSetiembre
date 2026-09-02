package com.facturastock.app.testing

import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.repository.CloudBusinessBindingRepository
import com.facturastock.app.domain.repository.CloudBusinessBindingResult
import java.time.Instant

class FakeCloudBusinessBindingRepository : CloudBusinessBindingRepository {
    private val byLocal = linkedMapOf<BusinessId, BusinessId>()
    var nextResult: CloudBusinessBindingResult? = null
    var bindCalls: Int = 0
        private set

    fun seed(localBusinessId: BusinessId, cloudBusinessId: BusinessId) {
        byLocal[localBusinessId] = cloudBusinessId
    }

    override suspend fun bindOnce(
        localBusinessId: BusinessId,
        cloudBusinessId: BusinessId,
        boundAt: Instant,
    ): CloudBusinessBindingResult {
        bindCalls++
        nextResult?.let { return it }
        val localTarget = byLocal[localBusinessId]
        if (localTarget != null) {
            return if (localTarget == cloudBusinessId) {
                CloudBusinessBindingResult.AlreadyBound
            } else {
                CloudBusinessBindingResult.LocalBusinessAlreadyBound
            }
        }
        if (byLocal.any { (local, cloud) -> local != localBusinessId && cloud == cloudBusinessId }) {
            return CloudBusinessBindingResult.CloudBusinessAlreadyBound
        }
        byLocal[localBusinessId] = cloudBusinessId
        return CloudBusinessBindingResult.Bound
    }

    override suspend fun targetFor(localBusinessId: BusinessId): BusinessId? =
        byLocal[localBusinessId]

    override suspend fun matches(
        localBusinessId: BusinessId,
        cloudBusinessId: BusinessId,
    ): Boolean = byLocal[localBusinessId] == cloudBusinessId
}
