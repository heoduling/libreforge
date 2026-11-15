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
}
