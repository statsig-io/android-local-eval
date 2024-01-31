package com.statsig.androidlocalevalsdk

import io.mockk.coVerify
import io.mockk.spyk
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import java.lang.Exception

class ErrorBoundaryTest {
    private lateinit var boundary: ErrorBoundary

    @Before
    fun setup() {
        boundary = spyk(ErrorBoundary())
    }

    @Test
    fun testCapture() {
        var recovered = false
        boundary.capture({
            throw Exception()
        }, recover = {
            // Recover should be called
            recovered = true
        })
        coVerify {
            boundary.logException(any(), any(), any())
        }
        assert(recovered)
    }

    @Test
    fun testCaptureAsync() = runBlocking {
        val job = GlobalScope.async {
            delay(1000)
            throw IndexOutOfBoundsException() // Will be printed to the console by Thread.defaultUncaughtExceptionHandler
        }
        var recovered = false
        boundary.captureAsync({
            job.await()
            assert(false) // never called
        }, recover = {
            recovered = true
        })
        coVerify {
            boundary.logException(any(), any(), any())
        }
        assert(recovered)
    }
}
