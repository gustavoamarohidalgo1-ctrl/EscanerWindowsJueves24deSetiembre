package com.facturastock.app.core.coroutines

import javax.inject.Qualifier

/** Scope de vida del proceso para trabajo compartido que no pertenece a una pantalla. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
