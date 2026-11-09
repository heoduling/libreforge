package com.willfp.libreforge

import com.willfp.libreforge.conditions.ConditionList
import com.willfp.libreforge.effects.EffectList
import com.willfp.libreforge.slot.ItemHolderFinder
import com.willfp.libreforge.slot.SlotType
import com.willfp.libreforge.slot.impl.*
import io.mockk.*
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class SingleSlotScanTest {
    private class Item(private val material: Material) : ItemStack() {
        override fun getType() = material
        override fun hashCode() = material.ordinal
        override fun equals(other: Any?) = this === other
    }
    private val holder = object : Holder {
        override val id = NamespacedKey("scan_test", "holder")
        override val effects = EffectList(emptyList())
        override val conditions = ConditionList(emptyList())
    }
    private val entity = mockk<LivingEntity>()
    private val stone = Item(Material.STONE)
    private val diamond = Item(Material.DIAMOND)
    private val seen = mutableListOf<ItemStack>()
    private val validated = mutableListOf<SlotType>()

    @BeforeEach fun setup() {
        mockkStatic("com.willfp.libreforge.LibreforgeSpigotPluginKt")
        every { plugin } returns mockk<LibreforgeSpigotPlugin>(relaxed = true)
        mockkStatic("com.willfp.libreforge.HolderProviderKt")
        every { registerRefreshFunctionOwnedBy(any(), any()) } returns Unit
    }
    @AfterEach fun cleanup() = unmockkAll()

    private fun finder() = object : ItemHolderFinder<Holder>() {
        override fun find(item: ItemStack): List<Holder> { seen.add(item); return listOf(holder) }
        override fun isValidInSlot(holder: Holder, slot: SlotType): Boolean { validated.add(slot); return true }
    }

    @Test fun `all six native slots bypass list construction and retain item identity`() {
        val slots = listOf(SlotTypeMainhand, SlotTypeOffhand, SlotTypeHelmet, SlotTypeChestplate, SlotTypeLeggings, SlotTypeBoots)
        every { entity.equipment?.itemInMainHand } returns stone
        every { entity.equipment?.itemInOffHand } returns diamond
        every { entity.equipment?.helmet } returns stone
        every { entity.equipment?.chestplate } returns diamond
        every { entity.equipment?.leggings } returns stone
        every { entity.equipment?.boots } returns diamond
        val finder = finder()
        for ((index, slot) in slots.withIndex()) {
            mockkObject(slot)
            every { slot.getItems(entity) } throws AssertionError("temporary native slot List path")
            val result = finder.findHolders(entity, slot).single()
            assertSame(if (index % 2 == 0) stone else diamond, result.provider)
            assertSame(slot, validated.last())
        }
        assertEquals(6, seen.size)
    }

    @Test fun `custom slot sharing a native id retains its own getter`() {
        val custom = object : SlotType("mainhand") {
            override fun addToSlot(player: Player, item: ItemStack) = false
            override fun getItemSlots(player: Player) = emptyList<Int>()
            override fun getItems(entity: LivingEntity) = listOf(diamond, stone)
        }
        assertEquals(SlotTypeMainhand, custom)
        val result = finder().findHolders(entity, custom)
        assertEquals(listOf(diamond, stone), result.map { it.provider })
        assertTrue(validated.all { it === custom })
        verify(exactly = 0) { entity.equipment }
    }

    @Test fun `native empty equipment and air never enter finder callback`() {
        every { entity.equipment?.helmet } returns null
        every { entity.equipment?.itemInMainHand } returns Item(Material.AIR)
        val finder = finder()
        assertTrue(finder.findHolders(entity, SlotTypeHelmet).isEmpty())
        assertTrue(finder.findHolders(entity, SlotTypeMainhand).isEmpty())
        assertTrue(seen.isEmpty())
    }

    @Test fun `public native slot getter remains available`() {
        every { entity.equipment?.itemInMainHand } returns stone
        assertSame(stone, SlotTypeMainhand.getItems(entity).single())
    }

    @Test fun `later direct scan sees equipment mutation from prior callback`() {
        var current: ItemStack = stone
        every { entity.equipment?.itemInMainHand } answers { current }
        val first = object : ItemHolderFinder<Holder>() {
            override fun find(item: ItemStack): List<Holder> { current = diamond; return emptyList() }
            override fun isValidInSlot(holder: Holder, slot: SlotType) = true
        }
        first.findHolders(entity, SlotTypeMainhand)
        assertSame(diamond, finder().findHolders(entity, SlotTypeMainhand).single().provider)
    }
}
