package com.willfp.libreforge.triggers.impl

import com.willfp.libreforge.toDispatcher
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent.DamageCause
import org.bukkit.event.entity.EntityDamageEvent.DamageModifier
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

@Suppress("DEPRECATION")
class ShieldBlockTest {
    private class ReachedDispatch : RuntimeException()

    @AfterEach
    fun cleanup() = unmockkAll()

    private fun event(
        reduction: Double = 0.0,
        applicable: Boolean = true,
        usingShield: Boolean = true,
        cancelled: Boolean = false,
        cause: DamageCause = DamageCause.ENTITY_ATTACK
    ): EntityDamageByEntityEvent {
        val event = mockk<EntityDamageByEntityEvent>()
        val player = mockk<Player>()
        every { event.damager } returns mockk<LivingEntity>()
        every { event.entity } returns player
        every { event.isCancelled } returns cancelled
        every { event.cause } returns cause
        every { player.isBlocking } returns usingShield
        every { event.isApplicable(DamageModifier.BLOCKING) } returns applicable
        every { event.getDamage(DamageModifier.BLOCKING) } returns reduction
        every { event.finalDamage } returns 0.0
        mockkStatic("com.willfp.libreforge.DispatcherKt")
        // Stop at the dispatch boundary; rejection must never reach it.
        every { player.toDispatcher() } throws ReachedDispatch()
        return event
    }

    @Test fun `raising shield without blocking does not dispatch even if final damage is zero`() {
        assertDoesNotThrow { TriggerShieldBlock.handle(event()) }
    }

    @Test fun `unavailable blocking modifier does not dispatch`() {
        assertDoesNotThrow { TriggerShieldBlock.handle(event(applicable = false)) }
    }

    @Test fun `positive modifier is not a blocked hit`() {
        assertDoesNotThrow { TriggerShieldBlock.handle(event(reduction = 1.0)) }
    }

    @Test fun `non numeric modifier is not a blocked hit`() {
        assertDoesNotThrow { TriggerShieldBlock.handle(event(reduction = Double.NaN)) }
    }

    @Test fun `full and partial negative reductions reach dispatch`() {
        for (reduction in listOf(-4.0, -1.0)) {
            assertThrows(ReachedDispatch::class.java) { TriggerShieldBlock.handle(event(reduction)) }
        }
    }

    @Test fun `cancelled event remains excluded`() {
        assertDoesNotThrow { TriggerShieldBlock.handle(event(-4.0, cancelled = true)) }
    }

    @Test fun `thorns remains excluded`() {
        assertDoesNotThrow { TriggerShieldBlock.handle(event(-4.0, cause = DamageCause.THORNS)) }
    }

    @Test fun `not using shield remains excluded`() {
        assertDoesNotThrow { TriggerShieldBlock.handle(event(-4.0, usingShield = false)) }
    }
}
