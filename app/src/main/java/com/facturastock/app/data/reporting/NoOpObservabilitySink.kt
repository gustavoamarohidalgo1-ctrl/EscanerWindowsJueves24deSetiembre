package com.facturastock.app.data.reporting

import javax.inject.Inject
import javax.inject.Singleton

/** Flavor local: no existe SDK ni canal externo, aun cuando la preferencia esté activada. */
@Singleton
class NoOpObservabilitySink @Inject constructor() : ObservabilitySink {
    override fun setCollectionEnabled(enabled: Boolean) = Unit

    override fun emit(
        eventName: String,
        attributes: Map<String, String>,
        isFailure: Boolean,
    ) = Unit
}
