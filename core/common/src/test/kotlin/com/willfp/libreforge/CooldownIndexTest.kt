package com.willfp.libreforge

import com.willfp.eco.core.config.interfaces.Config
import com.willfp.libreforge.effects.arguments.impl.ArgumentCooldown
import com.willfp.libreforge.triggers.DispatchedTrigger
import com.willfp.libreforge.triggers.TriggerData
import io.mockk.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class CooldownIndexTest {
    @Suppress("UNCHECKED_CAST")
    private fun tracker() = ArgumentCooldown::class.java.getDeclaredField("cooldownTracker").let {
        it.isAccessible = true
        it.get(null) as ConcurrentHashMap<Any, ConcurrentHashMap<Any, Long>>
    }
    @BeforeEach fun setup() {
        mockkStatic("com.willfp.libreforge.SeparatorAmbivalentConfigKt")
        every { any<Config>().getDoubleFromExpression("cooldown", any<TriggerData>()) } returns 60.0
        tracker().clear()
    }
    @AfterEach fun cleanup() { tracker().clear(); unmockkAll() }

    private fun element(group: String?): ConfigurableElement {
        val config = mockk<Config>()
        every { config.getStringOrNull("cooldown_group") } returns group
        return mockk<ConfigurableElement>().also {
            every { it.config } returns config
            every { it.uuid } returns UUID.randomUUID()
        }
    }
    private fun trigger(uuid: UUID) = mockk<DispatchedTrigger>().also {
        val dispatcher = mockk<Dispatcher<*>>()
        every { dispatcher.uuid } returns uuid
        every { it.dispatcher } returns dispatcher
        every { it.data } returns mockk<TriggerData>()
    }

    @Test fun `records are indexed by dispatcher for direct cleanup`() {
        val uuid = UUID.randomUUID()
        ArgumentCooldown.ifMet(element("shared"), trigger(uuid), null)
        assertTrue(tracker().containsKey(uuid))
        ArgumentCooldown.clearDispatcher(uuid)
        assertTrue(tracker().isEmpty())
    }

    @Test fun `shared groups isolated entities and unrelated groups retain behavior`() {
        val first = trigger(UUID.randomUUID())
        val other = trigger(UUID.randomUUID())
        val shared = element("shared")
        ArgumentCooldown.ifMet(shared, first, null)
        assertFalse(ArgumentCooldown.isMet(element("shared"), first, null))
        assertTrue(ArgumentCooldown.isMet(element("different"), first, null))
        assertTrue(ArgumentCooldown.isMet(shared, other, null))
        ArgumentCooldown.ifMet(shared, other, null)
        ArgumentCooldown.clearDispatcher(first.dispatcher.uuid)
        assertTrue(ArgumentCooldown.isMet(shared, first, null))
        assertFalse(ArgumentCooldown.isMet(shared, other, null))
    }

    @Test fun `zero duration expires and releases empty containers`() {
        every { any<Config>().getDoubleFromExpression("cooldown", any<TriggerData>()) } returns -1.0
        val element = element("expired")
        val trigger = trigger(UUID.randomUUID())
        ArgumentCooldown.ifMet(element, trigger, null)
        assertTrue(ArgumentCooldown.isMet(element, trigger, null))
        assertTrue(tracker().isEmpty())
    }

    @Test fun `default groups remain specific to element uuid`() {
        val first = element(null)
        val second = element(null)
        val trigger = trigger(UUID.randomUUID())
        ArgumentCooldown.ifMet(first, trigger, null)
        assertFalse(ArgumentCooldown.isMet(first, trigger, null))
        assertTrue(ArgumentCooldown.isMet(second, trigger, null))
    }

    @Test fun `cooldown expression can apply another group for the same entity`() {
        val outer = element("outer")
        val inner = element("inner")
        val trigger = trigger(UUID.randomUUID())
        val outerConfig = outer.config
        val triggerData = trigger.data
        every { outerConfig.getDoubleFromExpression("cooldown", triggerData) } answers {
            ArgumentCooldown.ifMet(inner, trigger, null)
            60.0
        }
        ArgumentCooldown.ifMet(outer, trigger, null)
        assertFalse(ArgumentCooldown.isMet(outer, trigger, null))
        assertFalse(ArgumentCooldown.isMet(inner, trigger, null))
    }

    @Test fun `expired read cannot delete a concurrently renewed cooldown`() {
        val observed = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val pauseOnce = AtomicBoolean(true)
        val times = object : ConcurrentHashMap<Any, Long>() {
            override fun get(key: Any): Long? {
                val value = super.get(key)
                if (Thread.currentThread().name == "expiry-reader" && pauseOnce.getAndSet(false)) {
                    observed.countDown()
                    assertTrue(resume.await(10, TimeUnit.SECONDS))
                }
                return value
            }
        }
        val uuid = UUID.randomUUID()
        times["expiry-race"] = 1L
        tracker()[uuid] = times
        val element = element("expiry-race")
        val trigger = trigger(uuid)
        val executor = Executors.newSingleThreadExecutor { Thread(it, "expiry-reader") }
        try {
            val read = executor.submit<Boolean> { ArgumentCooldown.isMet(element, trigger, null) }
            assertTrue(observed.await(10, TimeUnit.SECONDS))
            ArgumentCooldown.ifMet(element, trigger, null)
            resume.countDown()
            assertTrue(read.get(10, TimeUnit.SECONDS))
            assertFalse(ArgumentCooldown.isMet(element, trigger, null))
        } finally { resume.countDown(); executor.shutdownNow() }
    }

    @Test fun `concurrent group updates and clears preserve other entities and final cleanup`() {
        val shared = trigger(UUID.randomUUID())
        val survivor = trigger(UUID.randomUUID())
        val elements = (1..4).map { element("group_$it") }
        ArgumentCooldown.ifMet(elements.first(), survivor, null)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(5)
        try {
            val tasks = elements.map { element -> executor.submit {
                assertTrue(start.await(10, TimeUnit.SECONDS))
                repeat(1000) { ArgumentCooldown.ifMet(element, shared, null) }
            } } + executor.submit {
                assertTrue(start.await(10, TimeUnit.SECONDS))
                repeat(1000) { ArgumentCooldown.clearDispatcher(shared.dispatcher.uuid) }
            }
            start.countDown()
            tasks.forEach { it.get(30, TimeUnit.SECONDS) }
            ArgumentCooldown.clearDispatcher(shared.dispatcher.uuid)
            elements.forEach { assertTrue(ArgumentCooldown.isMet(it, shared, null)) }
            assertFalse(ArgumentCooldown.isMet(elements.first(), survivor, null))
            ArgumentCooldown.clearDispatcher(survivor.dispatcher.uuid)
            assertTrue(tracker().isEmpty())
        } finally { executor.shutdownNow() }
    }
}
