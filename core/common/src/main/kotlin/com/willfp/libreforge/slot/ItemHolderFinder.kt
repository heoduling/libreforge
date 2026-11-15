package com.willfp.libreforge.slot

import ca.spottedleaf.concurrentutil.map.concurrent.objects.ConcurrentChainedObject2ObjectHashTable
import com.willfp.libreforge.Dispatcher
import com.willfp.libreforge.Holder
import com.willfp.libreforge.HolderProvider
import com.willfp.libreforge.TypedHolderProvider
import com.willfp.libreforge.TypedProvidedHolder
import com.willfp.libreforge.get
import com.willfp.libreforge.registerRefreshFunctionOwnedBy
import com.willfp.libreforge.slot.internal.EquipmentReaders
import com.willfp.libreforge.slot.impl.NumericSlotType
import com.willfp.libreforge.slot.impl.SlotTypeBoots
import com.willfp.libreforge.slot.impl.SlotTypeChestplate
import com.willfp.libreforge.slot.impl.SlotTypeHelmet
import com.willfp.libreforge.slot.impl.SlotTypeLeggings
import com.willfp.libreforge.slot.impl.SlotTypeMainhand
import com.willfp.libreforge.slot.impl.SlotTypeOffhand
import org.bukkit.Material
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID

private const val ITEM_HOLDER_CACHE_CAPACITY = 128
private const val ITEM_HOLDER_CACHE_TTL_NANOS = 500_000_000L

private data class ItemHolderCacheEntry<T : Holder>(
    val expiresAt: Long,
    val holders: List<TypedProvidedHolder<T>>,
)

/**
 * Finds holders on items for entities, allows for easy implementation of [HolderProvider].
 */
abstract class ItemHolderFinder<T : Holder> {
    /**
     * The [HolderProvider] for this finder.
     */
    private val provider: TypedHolderProvider<T> = ItemHolderFinderProvider()

    /**
     * Find holders on an [item].
     */
    abstract fun find(item: ItemStack): List<T>

    /**
     * Check if a given [holder] is valid for a given [slot].
     */
    abstract fun isValidInSlot(holder: T, slot: SlotType): Boolean

    /**
     * Find holders on an [entity] for a given [slot].
     */
    fun findHolders(entity: LivingEntity, slot: SlotType): List<TypedProvidedHolder<T>> {
        val holders = ArrayList<TypedProvidedHolder<T>>()
        appendHolders(entity, slot, holders)
        return holders
    }

    private fun appendHolders(
        entity: LivingEntity,
        slot: SlotType,
        destination: MutableCollection<TypedProvidedHolder<T>>
    ) {
        if (entity is Player) {
            appendLegacyHolders(entity, slot, destination)
            return
        }
        val index = when {
            slot === SlotTypeMainhand -> 0
            slot === SlotTypeOffhand -> 1
            slot === SlotTypeHelmet -> 2
            slot === SlotTypeChestplate -> 3
            slot === SlotTypeLeggings -> 4
            slot === SlotTypeBoots -> 5
            else -> -1
        }
        val reader = if (index >= 0) EquipmentReaders.readerFor(entity) else null
        if (reader == null) {
            appendLegacyHolders(entity, slot, destination)
            return
        }
        val item = reader.read(entity, index)
        if (item != null) appendItemHolders(item, slot, destination)
    }

    private fun appendLegacyHolders(
        entity: LivingEntity,
        slot: SlotType,
        destination: MutableCollection<TypedProvidedHolder<T>>
    ) {
        // Match native objects by identity: custom slots may reuse a native id.
        val item = when {
            slot === SlotTypeMainhand -> entity.equipment?.itemInMainHand
            slot === SlotTypeOffhand -> entity.equipment?.itemInOffHand
            slot === SlotTypeHelmet -> entity.equipment?.helmet
            slot === SlotTypeChestplate -> entity.equipment?.chestplate
            slot === SlotTypeLeggings -> entity.equipment?.leggings
            slot === SlotTypeBoots -> entity.equipment?.boots
            else -> {
                for (customItem in slot.getItems(entity)) {
                    appendItemHolders(customItem, slot, destination)
                }
                return
            }
        }
        if (item != null) appendItemHolders(item, slot, destination)
    }

    private fun appendItemHolders(
        item: ItemStack,
        slot: SlotType,
        destination: MutableCollection<TypedProvidedHolder<T>>
    ) {
        // Empty equipment slots cannot provide item effects. Reject them before
        // each finder creates FastItemStack / NBT / PDC wrappers. Do not reject
        // ordinary, unenchanted items: custom items and reforges may use them.
        // Compare the three air materials directly: Material.isAir() resolves
        // a BlockType, which is unnecessary on this hot item-only path.
        val type = item.type
        if (type == Material.AIR || type == Material.CAVE_AIR || type == Material.VOID_AIR) {
            return
        }
        val found = find(item)
        // A single holder has nothing to share; keep that common path allocation-free.
        if (found.size == 1) {
            val holder = found[0]
            if (isValidInSlot(holder, slot)) {
                destination.add(SlotItemProvidedHolder(holder, item, slot))
            }
            return
        }
        var accepted: ArrayList<T>? = null
        for (holder in found) {
            if (isValidInSlot(holder, slot)) {
                if (accepted == null) accepted = ArrayList(found.size)
                accepted.add(holder)
            }
        }
        // Finish extension callbacks before hashing: if a finder updates the item,
        // all wrappers must describe the final item returned by this scan.
        // This value is local to one item occurrence, never cached across refreshes.
        if (accepted != null) {
            val itemHash = item.hashCode()
            for (holder in accepted) {
                destination.add(SlotItemProvidedHolder(holder, item, slot, itemHash))
            }
        }
    }

    /**
     * Convert this finder to a [HolderProvider].
     */
    fun toHolderProvider(): TypedHolderProvider<T> {
        return provider
    }

    private inner class ItemHolderFinderProvider : TypedHolderProvider<T> {
        private val cache = ConcurrentChainedObject2ObjectHashTable
            .createWithExpected<UUID, ItemHolderCacheEntry<T>>(ITEM_HOLDER_CACHE_CAPACITY)

        init {
            registerRefreshFunctionOwnedBy({
                cache.remove(it.uuid)
            }, this@ItemHolderFinder.javaClass.classLoader)
        }

        override fun provide(dispatcher: Dispatcher<*>): Collection<TypedProvidedHolder<T>> {
            val now = System.nanoTime()
            cache.get(dispatcher.uuid)?.takeIf { it.expiresAt > now }?.let { return it.holders }

            val entity = dispatcher.get<LivingEntity>() ?: return emptyList()
            val heldSlot = (entity as? Player)?.inventory?.heldItemSlot
            val holders = ArrayList<TypedProvidedHolder<T>>()

            // Only check for non-combined slot types
            for (slot in SlotTypes.baseTypes) {
                if (slot is NumericSlotType) {
                    // Numeric inventory slots are empty for non-player entities. A player's
                    // selected numeric slot is already represented by the main-hand slot.
                    if (heldSlot == null || slot.slot == heldSlot) {
                        continue
                    }
                }

                appendHolders(entity, slot, holders)
            }

            // The map is intentionally bounded: all entries naturally expire after 500ms, but a burst of unique
            // dispatchers must never retain an unbounded number of item-holder lists between cleanups.
            if (cache.size() >= ITEM_HOLDER_CACHE_CAPACITY) {
                cache.clear()
            }
            cache.put(dispatcher.uuid, ItemHolderCacheEntry(now + ITEM_HOLDER_CACHE_TTL_NANOS, holders))

            return holders
        }
    }
}
