package com.willfp.libreforge.slot.internal

import org.bukkit.entity.LivingEntity
import org.bukkit.inventory.ItemStack
import java.lang.reflect.Modifier

internal object EquipmentReaders {
    @Volatile
    private var selected: EquipmentReader? = null

    fun initialize(): Boolean {
        selected = resolve(javaClass.classLoader)
        return selected != null
    }

    fun readerFor(entity: LivingEntity): EquipmentReader? {
        val reader = selected ?: return null
        return if (reader.supports(entity)) reader else null
    }

    internal fun resolve(loader: ClassLoader): EquipmentReader? {
        try {
            val living = Class.forName("org.bukkit.craftbukkit.entity.CraftLivingEntity", false, loader)
            Class.forName("org.bukkit.craftbukkit.inventory.CraftEntityEquipment", false, loader)
            val nativeLiving = Class.forName("net.minecraft.world.entity.LivingEntity", false, loader)
            val slot = Class.forName("net.minecraft.world.entity.EquipmentSlot", false, loader)
            val item = Class.forName("net.minecraft.world.item.ItemStack", false, loader)
            val craftItem = Class.forName("org.bukkit.craftbukkit.inventory.CraftItemStack", false, loader)
            if (!signaturesMatch(living, nativeLiving, slot, item, craftItem)) return null
            val implementation = Class.forName(
                "com.willfp.libreforge.slot.internal.CraftEquipmentReader", true, loader
            )
            return implementation.getConstructor().newInstance() as EquipmentReader
        } catch (_: ReflectiveOperationException) {
            return null
        } catch (_: LinkageError) {
            return null
        } catch (_: SecurityException) {
            return null
        }
    }

    internal fun signaturesMatch(
        living: Class<*>, nativeLiving: Class<*>, slot: Class<*>, item: Class<*>, craftItem: Class<*>
    ): Boolean {
        val handle = living.getMethod("getHandle")
        val equipped = nativeLiving.getMethod("getItemBySlot", slot)
        val empty = item.getMethod("isEmpty")
        val copy = craftItem.getMethod("asBukkitCopy", item)
        return handle.returnType == nativeLiving && !Modifier.isStatic(handle.modifiers) &&
                equipped.returnType == item && !Modifier.isStatic(equipped.modifiers) &&
                empty.returnType == Boolean::class.javaPrimitiveType && !Modifier.isStatic(empty.modifiers) &&
                copy.returnType == ItemStack::class.java && Modifier.isStatic(copy.modifiers)
    }
}
