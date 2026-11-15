package com.willfp.libreforge.slot.internal

import org.bukkit.entity.LivingEntity
import org.bukkit.inventory.ItemStack

internal interface EquipmentReader {
    fun supports(entity: LivingEntity): Boolean

    // Null means a supported native slot is empty. The caller must not read it again.
    fun read(entity: LivingEntity, slot: Int): ItemStack?
}
