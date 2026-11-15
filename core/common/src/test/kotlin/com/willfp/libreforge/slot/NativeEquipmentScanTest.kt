package com.willfp.libreforge.slot

import com.willfp.libreforge.*
import com.willfp.libreforge.conditions.ConditionList
import com.willfp.libreforge.effects.EffectList
import com.willfp.libreforge.slot.impl.*
import com.willfp.libreforge.slot.internal.EquipmentReader
import com.willfp.libreforge.slot.internal.EquipmentReaders
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
import java.util.UUID

class NativeEquipmentScanTest {
    private class Item : ItemStack() {
        override fun getType() = Material.STONE
        override fun hashCode() = 17
    }

    private val entity = mockk<LivingEntity>()
    private val reader = mockk<EquipmentReader>()
    private val dispatcher = mockk<Dispatcher<LivingEntity>>()
    private val item = Item()
    private val holder = object : Holder {
        override val id = NamespacedKey("native_test", "holder")
        override val effects = EffectList(emptyList())
        override val conditions = ConditionList(emptyList())
    }
    private val seen = mutableListOf<ItemStack>()
    private var invalidate: ((Dispatcher<*>) -> Unit)? = null
    private val nativeSlots = linkedSetOf<SlotType>(
        SlotTypeMainhand, SlotTypeOffhand, SlotTypeHelmet,
        SlotTypeChestplate, SlotTypeLeggings, SlotTypeBoots
    )

    @BeforeEach fun setup() {
        mockkStatic("com.willfp.libreforge.HolderProviderKt")
        every { registerRefreshFunctionOwnedBy(any(), any()) } answers { invalidate = firstArg(); Unit }
        mockkObject(EquipmentReaders)
        every { EquipmentReaders.readerFor(entity) } returns reader
        mockkObject(SlotTypes)
        every { SlotTypes.baseTypes } returns nativeSlots
        every { dispatcher.uuid } returns UUID.randomUUID()
        every { dispatcher.dispatcher } returns entity
        every { reader.read(entity, any()) } returns null
    }

    @AfterEach fun cleanup() = unmockkAll()

    private fun finder() = object : ItemHolderFinder<Holder>() {
        override fun find(item: ItemStack): List<Holder> { seen += item; return listOf(holder) }
        override fun isValidInSlot(holder: Holder, slot: SlotType) = true
    }

    @Test fun `supported empty slots skip both Bukkit reads and finder callbacks`() {
        assertTrue(finder().toHolderProvider().provide(dispatcher).isEmpty())
        verify(exactly = 6) { reader.read(entity, any()) }
        verify(exactly = 0) { entity.equipment }
        assertTrue(seen.isEmpty())
    }

    @Test fun `nonempty native copy retains original item and holder construction`() {
        every { reader.read(entity, 2) } returns item
        val result = finder().toHolderProvider().provide(dispatcher).single()
        assertSame(item, result.provider)
        assertSame(holder, result.holder)
        assertSame(item, seen.single())
    }

    @Test fun `unsupported reader uses all original equipment getters`() {
        every { EquipmentReaders.readerFor(entity) } returns null
        every { entity.equipment?.itemInMainHand } returns item
        every { entity.equipment?.itemInOffHand } returns null
        every { entity.equipment?.helmet } returns null
        every { entity.equipment?.chestplate } returns null
        every { entity.equipment?.leggings } returns null
        every { entity.equipment?.boots } returns null
        assertSame(item, finder().toHolderProvider().provide(dispatcher).single().provider)
        verify(exactly = 0) { reader.read(any(), any()) }
    }

    @Test fun `custom slot with a native name retains its getter in an accelerated scan`() {
        val custom = object : SlotType("helmet") {
            override fun addToSlot(player: Player, item: ItemStack) = false
            override fun getItems(entity: LivingEntity) = listOf(item)
            override fun getItemSlots(player: Player) = emptyList<Int>()
        }
        every { SlotTypes.baseTypes } returns linkedSetOf(custom)
        assertSame(item, finder().toHolderProvider().provide(dispatcher).single().provider)
        verify(exactly = 0) { reader.read(any(), any()) }
    }

    @Test fun `later equipment is read after finder callback mutation`() {
        var added = false
        every { reader.read(entity, 0) } returns item
        every { reader.read(entity, 2) } answers { if (added) item else null }
        val mutating = object : ItemHolderFinder<Holder>() {
            override fun find(item: ItemStack): List<Holder> { added = true; return listOf(holder) }
            override fun isValidInSlot(holder: Holder, slot: SlotType) = true
        }
        assertEquals(2, mutating.toHolderProvider().provide(dispatcher).size)
    }

    @Test fun `cache hit reuses results and refresh invalidation rereads newly equipped items`() {
        val provider = finder().toHolderProvider()
        val first = provider.provide(dispatcher)
        every { reader.read(entity, 2) } returns item
        assertSame(first, provider.provide(dispatcher))
        verify(exactly = 6) { reader.read(entity, any()) }
        invalidate!!(dispatcher)
        assertEquals(1, provider.provide(dispatcher).size)
        verify(exactly = 12) { reader.read(entity, any()) }
    }

    @Test fun `players take the legacy entry without consulting a native reader`() {
        val player = mockk<Player>()
        every { dispatcher.dispatcher } returns player
        every { player.inventory.heldItemSlot } returns 0
        every { player.equipment?.itemInMainHand } returns item
        every { SlotTypes.baseTypes } returns linkedSetOf(SlotTypeMainhand, NumericSlotType(0))
        assertSame(item, finder().toHolderProvider().provide(dispatcher).single().provider)
        verify(exactly = 0) { EquipmentReaders.readerFor(any()) }
    }

    @Test fun `startup probing missing native classes returns fallback without initializing the adapter`() {
        unmockkObject(EquipmentReaders)
        val attempted = mutableListOf<String>()
        val absent = object : ClassLoader(javaClass.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                attempted += name
                if (name.startsWith("org.bukkit.craftbukkit.")) throw ClassNotFoundException(name)
                return super.loadClass(name, resolve)
            }
        }
        assertNull(EquipmentReaders.resolve(absent))
        assertFalse(attempted.any { it.endsWith("CraftEquipmentReader") })
    }

    @Test fun `startup probing catches native linkage failure and returns fallback`() {
        unmockkObject(EquipmentReaders)
        val broken = object : ClassLoader(javaClass.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name.startsWith("org.bukkit.craftbukkit.")) throw NoClassDefFoundError("incompatible test core")
                return super.loadClass(name, resolve)
            }
        }
        assertNull(EquipmentReaders.resolve(broken))
    }

    class TestSlot
    class TestItem { fun isEmpty() = true }
    class TestLiving { fun getItemBySlot(slot: TestSlot) = TestItem() }
    class TestCraft { fun getHandle() = TestLiving() }
    class TestCopy {
        companion object { @JvmStatic fun asBukkitCopy(item: TestItem): ItemStack? = null }
    }
    class InstanceCopy { fun asBukkitCopy(item: TestItem): ItemStack? = null }

    @Test fun `startup signature check validates invocation kinds as well as return types`() {
        unmockkObject(EquipmentReaders)
        assertTrue(EquipmentReaders.signaturesMatch(TestCraft::class.java, TestLiving::class.java,
            TestSlot::class.java, TestItem::class.java, TestCopy::class.java))
        assertFalse(EquipmentReaders.signaturesMatch(TestCraft::class.java, TestLiving::class.java,
            TestSlot::class.java, TestItem::class.java, InstanceCopy::class.java))
    }
}
