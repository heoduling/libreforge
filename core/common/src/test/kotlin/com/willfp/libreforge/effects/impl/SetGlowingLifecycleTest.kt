package com.willfp.libreforge.effects.impl

import com.willfp.eco.core.config.interfaces.Config
import com.willfp.libreforge.LibreforgeSpigotPlugin
import com.willfp.libreforge.NoCompileData
import com.willfp.libreforge.SchedulerHelper
import com.willfp.libreforge.getIntFromExpression
import com.willfp.libreforge.plugin
import com.willfp.libreforge.triggers.TriggerData
import io.mockk.*
import org.bukkit.entity.LivingEntity
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import kotlin.random.Random

class SetGlowingLifecycleTest {
    private val owner = mockk<LibreforgeSpigotPlugin>(relaxed = true)
    private val entity = mockk<LivingEntity>()
    private val container = mockk<PersistentDataContainer>()
    private val config = mockk<Config>()
    private val data = mockk<TriggerData>()
    private val tasks = mutableListOf<Runnable>()
    private var state: LongArray? = null
    private var glowing = false
    private var requested = true
    private var duration: Int? = 120

    @BeforeEach fun setup() {
        mockkStatic("com.willfp.libreforge.LibreforgeSpigotPluginKt")
        every { plugin } returns owner
        mockkStatic("com.willfp.libreforge.SeparatorAmbivalentConfigKt")
        every { config.getIntFromExpression("duration", data) } answers { duration!! }
        every { config.has("duration") } answers { duration != null }
        every { config.getBoolOrNull("glowing") } answers { requested }
        every { data.victim } returns entity
        every { entity.isGlowing } answers { glowing }
        every { entity.isGlowing = any() } answers { glowing = firstArg() }
        every { entity.persistentDataContainer } returns container
        every { container.get(any(), PersistentDataType.LONG_ARRAY) } answers { state?.copyOf() }
        every { container.set(any(), PersistentDataType.LONG_ARRAY, any()) } answers { state = thirdArg<LongArray>().copyOf() }
        every { container.remove(any()) } answers { state = null }
        mockkObject(SchedulerHelper)
        every { SchedulerHelper.runTaskLater(owner, entity, any(), any()) } answers {
            tasks.add(thirdArg())
            Any()
        }
    }

    @AfterEach fun cleanup() = unmockkAll()

    private fun apply() {
        val method = EffectSetGlowing::class.java.getDeclaredMethod(
            "onTrigger", Config::class.java, TriggerData::class.java, NoCompileData::class.java
        )
        method.isAccessible = true
        assertEquals(true, method.invoke(EffectSetGlowing, config, data, NoCompileData))
    }

    private fun reloadEntity() {
        val method = EffectSetGlowing::class.java.getDeclaredMethod("restoreLoadedEntity", LivingEntity::class.java)
        method.isAccessible = true
        method.invoke(EffectSetGlowing, entity)
    }

    @Test fun `temporary glow persists its deadline and inverse restoration`() {
        val start = System.currentTimeMillis()
        apply()
        assertTrue(glowing)
        val saved = requireNotNull(state) { "Temporary glow needs a saved recovery record" }
        assertEquals(3, saved.size)
        assertTrue(saved[0] >= start + 6000)
        assertEquals(0L, saved[1])
        tasks.single().run()
        assertFalse(glowing)
        assertNull(state)
    }

    @Test fun `older timer cannot end the refreshed application`() {
        apply()
        apply()
        tasks.first().run()
        assertTrue(glowing)
        tasks.last().run()
        assertFalse(glowing)
    }

    @Test fun `permanent setting supersedes pending temporary restoration`() {
        apply()
        duration = null
        apply()
        tasks.first().run()
        assertTrue(glowing)
        assertNull(state)
    }

    @Test fun `clearing the recovery record invalidates a temporary false timer`() {
        requested = false
        apply()
        state = null
        glowing = false
        tasks.single().run()
        assertFalse(glowing)
    }

    @Test fun `expired saved glow restores immediately after entity reload`() {
        apply()
        state!![0] = System.currentTimeMillis() - 1
        tasks.clear()
        reloadEntity()
        assertFalse(glowing)
        assertNull(state)
        assertTrue(tasks.isEmpty())
    }

    @Test fun `unexpired saved glow resumes its remaining timer`() {
        apply()
        tasks.clear()
        reloadEntity()
        assertTrue(glowing)
        assertNotNull(state)
        tasks.single().run()
        assertFalse(glowing)
        assertNull(state)
    }

    @Test fun `expired temporary false restores the existing inverse behavior`() {
        requested = false
        apply()
        state!![0] = System.currentTimeMillis() - 1
        reloadEntity()
        assertTrue(glowing)
        assertNull(state)
    }

    @Test fun `entity reload leaves an untracked glow alone`() {
        glowing = true
        reloadEntity()
        assertTrue(glowing)
        assertNull(state)
        assertTrue(tasks.isEmpty())
    }

    @Test fun `short false overlay retains the longer true restoration`() {
        apply()
        requested = false
        duration = 20
        apply()
        tasks[1].run()
        assertTrue(glowing)
        assertNotNull(state, "The longer true effect still needs its restoration")
        tasks[0].run()
        assertFalse(glowing)
        assertNull(state)
    }

    @Test fun `short true overlay retains the longer false restoration`() {
        requested = false
        apply()
        requested = true
        duration = 20
        apply()
        tasks[1].run()
        assertFalse(glowing)
        assertNotNull(state)
        tasks[0].run()
        assertTrue(glowing)
        assertNull(state)
    }

    @Test fun `a hidden layer expires without reviving it after the covering layer`() {
        duration = 20
        apply()
        requested = false
        duration = 120
        apply()
        tasks[0].run()
        assertFalse(glowing)
        assertNotNull(state)
        tasks[1].run()
        assertFalse(glowing)
        assertNull(state)
    }

    @Test fun `three opposite layers resume the remaining requests in order`() {
        apply()
        requested = false
        duration = 40
        apply()
        requested = true
        duration = 20
        apply()
        tasks[2].run()
        assertFalse(glowing)
        assertNotNull(state)
        tasks[1].run()
        assertTrue(glowing)
        assertNotNull(state)
        tasks[0].run()
        assertFalse(glowing)
        assertNull(state)
    }

    @Test fun `permanent override invalidates every overlay callback`() {
        apply()
        requested = false
        apply()
        duration = null
        requested = true
        apply()
        tasks.forEach { it.run() }
        assertTrue(glowing)
        assertNull(state)
    }

    @Test fun `administrative clearing cancels the entire overlay history`() {
        apply()
        requested = false
        apply()
        state = null
        glowing = false
        tasks.forEach { it.run() }
        assertFalse(glowing)
        assertNull(state)
    }

    @Test fun `reload between deadlines resumes the remaining longer layer`() {
        apply()
        requested = false
        duration = 20
        apply()
        state!![0] = System.currentTimeMillis() - 1
        state!![5] = System.currentTimeMillis() + 60000
        tasks.clear()
        reloadEntity()
        assertTrue(glowing)
        assertEquals(3, state!!.size)
        tasks.single().run()
        assertFalse(glowing)
        assertNull(state)
    }

    @Test fun `reload after every layer expired restores the first inverse value`() {
        apply()
        requested = false
        apply()
        state!![0] = System.currentTimeMillis() - 1
        state!![5] = System.currentTimeMillis() - 1
        tasks.clear()
        reloadEntity()
        assertFalse(glowing)
        assertNull(state)
        assertTrue(tasks.isEmpty())
    }

    @Test fun `expired hidden layer keeps the final restore value across another reload`() {
        apply()
        requested = false
        apply()
        state!![0] = System.currentTimeMillis() + 60000
        state!![5] = System.currentTimeMillis() - 1
        tasks.clear()
        reloadEntity()
        assertFalse(glowing)
        assertEquals(5, state!!.size)
        tasks.clear()
        reloadEntity()
        tasks.single().run()
        assertFalse(glowing)
        assertNull(state)
    }

    @Test fun `reload before both deadlines restores each layer and tolerates duplicate callbacks`() {
        apply()
        requested = false
        apply()
        tasks.clear()
        reloadEntity()
        assertEquals(2, tasks.size)
        tasks[0].run()
        assertTrue(glowing)
        assertNotNull(state)
        tasks[1].run()
        assertFalse(glowing)
        assertNull(state)
        tasks.forEach { it.run() }
        assertFalse(glowing)
        assertNull(state)
    }

    @Test fun `equal requests refresh only the covering layer`() {
        apply()
        requested = false
        apply()
        apply()
        tasks[1].run()
        assertFalse(glowing)
        assertNotNull(state)
        tasks[2].run()
        assertTrue(glowing)
        assertNotNull(state)
        tasks[0].run()
        assertFalse(glowing)
        assertNull(state)
    }

    @Test fun `unexpired legacy records preserve an externally changed native flag`() {
        apply()
        glowing = false
        tasks.clear()
        reloadEntity()
        assertFalse(glowing)
        assertEquals(3, state!!.size)
        tasks.single().run()
        assertFalse(glowing)
        assertNull(state)
    }

    @Test fun `zero and negative permanent settings invalidate every pending layer`() {
        for (permanentDuration in listOf(0, -1)) {
            requested = true
            duration = 120
            apply()
            requested = false
            apply()
            requested = true
            duration = permanentDuration
            apply()
            tasks.forEach { it.run() }
            assertTrue(glowing)
            assertNull(state)
            tasks.clear()
        }
    }

    @Test fun `randomized applies expiry refresh clear and permanent match active-layer rules`() {
        data class Request(val taskIndex: Int, val value: Boolean)
        val pending = mutableListOf<Request>()
        val random = Random(20261115)
        var expected = false
        var base = false
        repeat(500) { step ->
            when (random.nextInt(6)) {
                0, 1, 2 -> {
                    requested = random.nextBoolean()
                    duration = 3600
                    if (pending.isEmpty()) base = !requested
                    if (pending.firstOrNull()?.value == requested) pending.removeAt(0)
                    val taskIndex = tasks.size
                    apply()
                    pending.add(0, Request(taskIndex, requested))
                    expected = requested
                }
                3 -> if (tasks.isNotEmpty()) {
                    val taskIndex = random.nextInt(tasks.size)
                    val index = pending.indexOfFirst { it.taskIndex == taskIndex }
                    tasks[taskIndex].run()
                    if (index >= 0) {
                        pending.removeAt(index)
                        if (index == 0) expected = pending.firstOrNull()?.value ?: base
                    }
                }
                4 -> {
                    requested = random.nextBoolean()
                    duration = null
                    apply()
                    pending.clear()
                    expected = requested
                }
                else -> {
                    state = null
                    glowing = false
                    pending.clear()
                    expected = false
                }
            }
            assertEquals(expected, glowing, "state at step $step")
            val count = state?.let { if (it.size == 3) 1 else 1 + (it.size - 5) / 3 } ?: 0
            assertEquals(pending.size, count, "pending layers at step $step")
        }
        pending.clear()
        state = null
        glowing = false
        tasks.forEach { it.run() }
        assertFalse(glowing)
        assertNull(state)
    }
}
