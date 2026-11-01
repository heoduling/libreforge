package com.willfp.libreforge.triggers.placeholders

import com.willfp.libreforge.SchedulerHelper
import com.willfp.libreforge.triggers.TriggerData
import com.willfp.libreforge.triggers.placeholders.impl.TriggerPlaceholderVictim
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.bukkit.Bukkit
import org.bukkit.entity.LivingEntity
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertEquals
import com.willfp.eco.core.placeholder.context.PlaceholderContext
import org.junit.jupiter.api.Test

class VictimPlaceholderOwnershipTest {
    @AfterEach
    fun cleanup() = unmockkAll()

    private fun data(victim: LivingEntity?) = mockk<TriggerData>().also {
        every { it.victim } returns victim
    }

    @Test
    fun `foreign victim does not abort placeholder generation with owner violation`() {
        mockkObject(SchedulerHelper)
        every { SchedulerHelper.isFolia } returns true
        mockkStatic(Bukkit::class)
        val victim = mockk<LivingEntity>()
        every { Bukkit.isOwnedByCurrentRegion(victim) } returns false
        every { victim.health } throws IllegalStateException("off owner health")
        val values = TriggerPlaceholderVictim.createPlaceholders(data(victim))
        assertEquals(listOf("victim_health", "victim_max_health"), values.flatMap { it.identifiers })
        assertEquals(listOf("unavailable", "unavailable"), values.flatMap { it.placeholders }.map {
            it.getValue("", PlaceholderContext())
        })
    }

    @Test
    fun `absent victim still supplies no placeholders`() {
        assertTrue(TriggerPlaceholderVictim.createPlaceholders(data(null)).isEmpty())
    }
}
