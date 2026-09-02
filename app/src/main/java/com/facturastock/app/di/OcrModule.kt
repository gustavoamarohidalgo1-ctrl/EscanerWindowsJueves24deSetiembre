package com.facturastock.app.di

import com.facturastock.app.data.ocr.DemoAwareInvoiceTextRecognizer
import com.facturastock.app.domain.repository.InvoiceTextRecognizer
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Módulo separado para que pruebas Hilt sustituyan el motor sin tocar los demás repositorios. */
@Module
@InstallIn(SingletonComponent::class)
abstract class OcrModule {
    @Binds
    @Singleton
    abstract fun bindInvoiceTextRecognizer(
        implementation: DemoAwareInvoiceTextRecognizer,
    ): InvoiceTextRecognizer
}
