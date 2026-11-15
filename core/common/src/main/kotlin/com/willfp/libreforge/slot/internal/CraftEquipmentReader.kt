package com.willfp.libreforge.slot.internal

import net.minecraft.world.entity.EquipmentSlot
import org.bukkit.craftbukkit.entity.CraftLivingEntity
import org.bukkit.craftbukkit.inventory.CraftEntityEquipment
import org.bukkit.craftbukkit.inventory.CraftItemStack
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

internal class CraftEquipmentReader : EquipmentReader {
    private val slots = arrayOf(
        EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.HEAD,
        EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    )

    override fun supports(entity: LivingEntity): Boolean {
        if (entity is Player || entity !is CraftLivingEntity) return false
        if (entity.javaClass.classLoader !== CraftLivingEntity::class.java.classLoader) return false
        return entity.equipment?.javaClass === CraftEntityEquipment::class.java
    }

    override fun read(entity: LivingEntity, slot: Int): ItemStack? {
        // getHandle keeps the core's owner-thread check on every individual read.
        val original = (entity as CraftLivingEntity).handle.getItemBySlot(slots[slot])
        return if (original.isEmpty) null else CraftItemStack.asBukkitCopy(original)
    }
}
