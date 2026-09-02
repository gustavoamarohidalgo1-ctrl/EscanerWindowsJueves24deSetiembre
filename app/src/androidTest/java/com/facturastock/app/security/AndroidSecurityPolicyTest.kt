package com.facturastock.app.security

import android.content.Context
import android.security.NetworkSecurityPolicy
import android.system.Os
import android.system.OsConstants
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidSecurityPolicyTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun localAppRejectsCleartextTrafficGlobally() {
        assertFalse(NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted)
    }

    @Test
    fun privateImageFileHasNoGroupOrOtherReadWriteBits() {
        val file = File(context.filesDir, "security-private-image.bin")
        try {
            file.writeBytes(byteArrayOf(1, 2, 3))
            val publicBits = OsConstants.S_IRGRP or OsConstants.S_IWGRP or
                OsConstants.S_IROTH or OsConstants.S_IWOTH

            assertEquals(0, Os.stat(file.absolutePath).st_mode and publicBits)
        } finally {
            file.delete()
        }
    }
}
