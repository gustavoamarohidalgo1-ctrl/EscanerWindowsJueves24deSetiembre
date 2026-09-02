package com.facturastock.app.data.demo

import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.domain.repository.DemoInvoiceSource
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

@Singleton
class LocalDemoInvoiceSource @Inject constructor(
    private val dispatchers: DispatcherProvider,
) : DemoInvoiceSource {
    override suspend fun jpegBytes(): ByteArray = withContext(dispatchers.default) {
        currentCoroutineContext().ensureActive()
        DemoInvoiceImageGenerator.jpegBytes()
    }
}
