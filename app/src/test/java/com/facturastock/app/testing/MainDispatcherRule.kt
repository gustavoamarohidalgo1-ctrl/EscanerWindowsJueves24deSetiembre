package com.facturastock.app.testing

import com.facturastock.app.core.coroutines.DispatcherProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val dispatcher: TestDispatcher = StandardTestDispatcher(),
) : TestWatcher() {
    val scheduler: TestCoroutineScheduler
        get() = dispatcher.scheduler

    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class TestDispatcherProvider(
    override val main: TestDispatcher,
    override val io: TestDispatcher = StandardTestDispatcher(
        scheduler = main.scheduler,
        name = "test-io",
    ),
    override val default: TestDispatcher = StandardTestDispatcher(
        scheduler = main.scheduler,
        name = "test-default",
    ),
) : DispatcherProvider
