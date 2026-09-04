package com.deskforge.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskforge.app.display.DirectDisplayCapabilityProbe
import com.deskforge.app.display.DirectDisplayService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DirectDisplayServiceTest {
    @Test
    fun appBrokerQualifiesPublicHardwareBufferContract() {
        val bufferCapability = DirectDisplayCapabilityProbe.probe()
        assertTrue(bufferCapability, bufferCapability.startsWith("available:"))
        assertTrue(
            bufferCapability,
            bufferCapability.contains("hardware-buffer and Unix transfer contract qualified"),
        )
    }

    @Test
    fun isolatedServiceQualifiesValidationAndResourceAccounting() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val completed = CountDownLatch(1)
        val status = AtomicInteger()
        val detail = AtomicReference("")
        val serviceUid = AtomicInteger()
        val reply = Messenger(Handler(Looper.getMainLooper()) { message ->
            status.set(message.what)
            detail.set(message.data.getString(DirectDisplayService.KEY_DETAIL).orEmpty())
            serviceUid.set(message.data.getInt(DirectDisplayService.KEY_SERVICE_UID))
            completed.countDown()
            true
        })
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                Messenger(binder).send(
                    Message.obtain(null, DirectDisplayService.MSG_PROBE).apply { replyTo = reply },
                )
            }

            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        val bound = context.bindService(
            Intent(context, DirectDisplayService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
        assertTrue("Direct-display service did not bind", bound)
        try {
            assertTrue("Direct-display probe timed out", completed.await(10, TimeUnit.SECONDS))
            assertEquals(detail.get(), DirectDisplayService.MSG_AVAILABLE, status.get())
            assertNotEquals(context.applicationInfo.uid, serviceUid.get())
            assertTrue(detail.get().contains("validation and resource accounting qualified"))
        } finally {
            context.unbindService(connection)
        }
    }
}
