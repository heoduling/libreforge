package com.willfp.libreforge.triggers

import com.willfp.libreforge.Dispatcher
import com.willfp.libreforge.SchedulerHelper
import com.willfp.libreforge.LibreforgeSpigotPlugin
import com.willfp.libreforge.plugin
import io.mockk.*
import org.bukkit.Bukkit
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class TriggerDataItemOwnershipTest {
    private val dispatcher = mockk<Dispatcher<*>>()

    @BeforeEach fun setup() {
        mockkStatic("com.willfp.libreforge.LibreforgeSpigotPluginKt")
        every { plugin } returns mockk<LibreforgeSpigotPlugin>(relaxed = true)
        mockkObject(SchedulerHelper)
        every { SchedulerHelper.isFolia } returns true
        mockkStatic(Bukkit::class)
    }

    @AfterEach fun cleanup() = unmockkAll()

    // Explicit unrelated defaults isolate the real constructor's item inference.
    private fun data(player: Player? = null, victim: LivingEntity? = null) = TriggerData(
        dispatcher = dispatcher, player = player, victim = victim,
        location = null, velocity = null
    )

    @Test fun `foreign shooter equipment is never read`() {
        val shooter = mockk<LivingEntity>()
        every { Bukkit.isOwnedByCurrentRegion(shooter) } returns false
        every { shooter.equipment } throws IllegalStateException("off owner equipment")
        assertNull(data(victim = shooter).item)
        verify(exactly = 0) { shooter.equipment }
    }

    @Test fun `owned shooter item retains identity`() {
        val shooter = mockk<LivingEntity>()
        val item = mockk<ItemStack>()
        every { Bukkit.isOwnedByCurrentRegion(shooter) } returns true
        every { shooter.equipment?.itemInMainHand } returns item
        assertSame(item, data(victim = shooter).item)
    }

    @Test fun `owned player item retains precedence over foreign shooter`() {
        val player = mockk<Player>()
        val shooter = mockk<LivingEntity>()
        val item = mockk<ItemStack>()
        every { Bukkit.isOwnedByCurrentRegion(player) } returns true
        every { player.inventory.itemInMainHand } returns item
        every { shooter.equipment } throws IllegalStateException("off owner equipment")
        assertSame(item, data(player, shooter).item)
        verify(exactly = 0) { shooter.equipment }
    }

    @Test fun `foreign player inventory is never read`() {
        val player = mockk<Player>()
        every { Bukkit.isOwnedByCurrentRegion(player) } returns false
        every { player.inventory } throws IllegalStateException("off owner inventory")
        assertNull(data(player).item)
        verify(exactly = 0) { player.inventory }
    }

    @Test fun `foreign player can fall back to owned victim item`() {
        val player = mockk<Player>()
        val victim = mockk<LivingEntity>()
        val item = mockk<ItemStack>()
        every { Bukkit.isOwnedByCurrentRegion(player) } returns false
        every { player.inventory } throws IllegalStateException("off owner inventory")
        every { Bukkit.isOwnedByCurrentRegion(victim) } returns true
        every { victim.equipment?.itemInMainHand } returns item
        assertSame(item, data(player, victim).item)
    }

    @Test fun `absent entities have no inferred item`() {
        assertNull(data().item)
    }

    @Test fun `explicit item bypasses entity inventory inference`() {
        val shooter = mockk<LivingEntity>()
        val item = mockk<ItemStack>()
        val result = TriggerData(dispatcher = dispatcher, victim = shooter,
            location = null, velocity = null, item = item)
        assertSame(item, result.item)
        verify(exactly = 0) { shooter.equipment }
    }

    @Test fun `explicit null item remains null`() {
        val shooter = mockk<LivingEntity>()
        val result = TriggerData(dispatcher = dispatcher, victim = shooter,
            location = null, velocity = null, item = null)
        assertNull(result.item)
        verify(exactly = 0) { shooter.equipment }
    }

    @Test fun `owned entity without equipment remains supported`() {
        val victim = mockk<LivingEntity>()
        every { Bukkit.isOwnedByCurrentRegion(victim) } returns true
        every { victim.equipment } returns null
        assertNull(data(victim = victim).item)
    }

    @Test fun `non Folia keeps original item inference without region API`() {
        every { SchedulerHelper.isFolia } returns false
        val victim = mockk<LivingEntity>()
        val item = mockk<ItemStack>()
        every { victim.equipment?.itemInMainHand } returns item
        assertSame(item, data(victim = victim).item)
        verify(exactly = 0) { Bukkit.isOwnedByCurrentRegion(victim) }
    }
}
