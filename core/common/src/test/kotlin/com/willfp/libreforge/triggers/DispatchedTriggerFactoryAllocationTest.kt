package com.willfp.libreforge.triggers

import com.willfp.eco.core.EcoPlugin
import com.willfp.libreforge.Dispatcher
import com.willfp.libreforge.GlobalDispatcher
import io.mockk.*
import com.willfp.libreforge.LibreforgeSpigotPlugin
import com.willfp.libreforge.plugin
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class DispatchedTriggerFactoryAllocationTest {
    @BeforeEach fun setup() {
        mockkStatic("com.willfp.libreforge.LibreforgeSpigotPluginKt")
        every { plugin } returns mockk<LibreforgeSpigotPlugin>(relaxed = true)
    }
    @AfterEach fun cleanup() = unmockkAll()

    private fun factory() = DispatchedTriggerFactory(mockk<EcoPlugin>())
    private fun trigger(hash: Int = 17) = mockk<Trigger>().also {
        every { it.isEnabled } returns true
        every { it.hashCode() } returns hash
    }
    @Suppress("UNCHECKED_CAST")
    private fun entries(factory: DispatchedTriggerFactory): MutableMap<UUID, Set<Int>> {
        val field = DispatchedTriggerFactory::class.java.getDeclaredField("dispatcherTriggers")
        field.isAccessible = true
        return field.get(factory) as MutableMap<UUID, Set<Int>>
    }

    @Test fun `duplicates reject and larger private sets are updated without copying`() {
        val factory = factory()
        val trigger = trigger()
        val first = TriggerData(text = "one")
        assertSame(first, factory.create(GlobalDispatcher, trigger, first)!!.data)
        assertNull(factory.create(GlobalDispatcher, trigger, first))
        factory.create(GlobalDispatcher, trigger, TriggerData(text = "two"))
        val stored = entries(factory).values.single()
        assertEquals(2, stored.size)
        factory.create(GlobalDispatcher, trigger, TriggerData(text = "three"))
        assertSame(stored, entries(factory).values.single())
        assertEquals(3, stored.size)
        entries(factory).clear()
        assertNotNull(factory.create(GlobalDispatcher, trigger, first))
    }

    @Test fun `UUID isolation trigger hash collision and dispatcher copy preserve previous rules`() {
        val factory = factory()
        val trigger = trigger()
        val original = TriggerData(text = "Aa", value = -2.0)
        val collision = TriggerData(text = "BB", value = -2.0)
        assertEquals(original.hashCode(), collision.hashCode())
        assertNotNull(factory.create(GlobalDispatcher, trigger, original))
        assertNull(factory.create(GlobalDispatcher, trigger, collision))
        val other = mockk<Dispatcher<String>>()
        every { other.uuid } returns UUID.randomUUID()
        val copy = factory.create(other, trigger, original)!!.data
        assertNotSame(original, copy)
        assertSame(other, copy.dispatcher)
        assertSame(original.text, copy.text)
        assertEquals(original.value, copy.value)
        assertEquals(2, entries(factory).size)
    }

    @Test fun `disabled triggers leave the original map empty`() {
        val factory = factory()
        val disabled = trigger()
        every { disabled.isEnabled } returns false
        assertNull(factory.create(GlobalDispatcher, disabled, TriggerData()))
        assertTrue(entries(factory).isEmpty())
    }

    @Test fun `concurrent identical submissions accept once and distinct inputs remain in one set`() {
        val factory = factory()
        val trigger = trigger()
        val duplicate = TriggerData(text = "duplicate").also { it.hashCode() }
        val values = (0 until 128).map { TriggerData(text = "unique-$it").also { data -> data.hashCode() } }
        val accepted = AtomicInteger()
        val workers = Executors.newFixedThreadPool(4)
        try {
            workers.invokeAll(List(64) { java.util.concurrent.Callable {
                if (factory.create(GlobalDispatcher, trigger, duplicate) != null) accepted.incrementAndGet()
            } }).forEach { it.get() }
            assertEquals(1, accepted.get())
            entries(factory).clear()
            workers.invokeAll(values.map { value -> java.util.concurrent.Callable {
                assertNotNull(factory.create(GlobalDispatcher, trigger, value))
            } }).forEach { it.get() }
            assertEquals(values.size, entries(factory).values.single().size)
            values.forEach { assertNull(factory.create(GlobalDispatcher, trigger, it)) }
            entries(factory).clear()
            assertTrue(entries(factory).isEmpty())
        } finally {
            workers.shutdown()
        }
    }
}
