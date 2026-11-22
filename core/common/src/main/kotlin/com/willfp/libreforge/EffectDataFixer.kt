package com.willfp.libreforge

import com.willfp.libreforge.effects.arguments.impl.ArgumentCooldown
import com.willfp.libreforge.effects.arguments.impl.ArgumentEvery
import com.willfp.libreforge.effects.impl.EffectAddHolder
import com.willfp.libreforge.effects.impl.EffectGlowNearbyBlocks
import com.willfp.libreforge.effects.impl.EffectPermanentPotionEffect
import com.willfp.libreforge.effects.impl.EffectTraceback
import com.willfp.libreforge.effects.impl.EffectVictimSpeedMultiplier
import com.willfp.libreforge.integrations.paper.impl.EffectDropPickupItem
import com.willfp.libreforge.integrations.paper.impl.TriggerTridentAttack
import com.willfp.libreforge.triggers.impl.TriggerTridentHit
import com.willfp.libreforge.triggers.placeholders.impl.TriggerPlaceholderHits
import org.bukkit.Registry
import org.bukkit.attribute.Attribute
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityRemoveEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent

private val transientMetadataKeys = setOf(
    "spawn-mobs-target",
    "spawn-mobs-avoid",
    "libreforge-homing-arrows-distance",
    "libreforge-homing-arrows-targets",
    "libreforge-homing-arrows-tracked",
    "libreforge-ignite",
    "libreforge-damaged-twice",
    "ignore-nearby-damage"
)

object EffectDataFixer : Listener {
    @EventHandler(priority = EventPriority.LOWEST)
    fun clearOnQuit(event: PlayerQuitEvent) {
        val player = event.player
        val dispatcher = player.toDispatcher()

        for ((effect, holder) in dispatcher.providedActiveEffects) {
            effect.disable(dispatcher, holder)
        }

        // Extra fix for pre-4.2.3
        SchedulerHelper.runTask(plugin, player) {
            player.fixAttributes()
        }

        dispatcher.updateHolders()
        dispatcher.purgePreviousHolders()
        ArgumentEvery.clearDispatcher(dispatcher.uuid)
        ArgumentCooldown.clearDispatcher(dispatcher.uuid)
        EffectAddHolder.clearDispatcher(dispatcher.uuid)
        EffectPermanentPotionEffect.clear(player)
        EffectTraceback.clear(player)
        EffectVictimSpeedMultiplier.cleanup(player)
        TriggerPlaceholderHits.clearEntity(player.uniqueId)
        transientMetadataKeys.forEach { player.removeMetadata(it, plugin) }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun scanOnJoin(event: PlayerJoinEvent) {
        val player = event.player
        val dispatcher = player.toDispatcher()

        // Extra fix for pre-4.2.3
        SchedulerHelper.runTask(plugin, player) {
            player.fixAttributes()
        }

        dispatcher.updateHolders()

        SchedulerHelper.runTask(plugin, player) {
            dispatcher.updateEffects()
        }
    }

    private fun Player.fixAttributes() {
        try {
            for (attribute in Registry.ATTRIBUTE) {
                val inst = this.getAttribute(attribute) ?: continue
                for (mod in inst.modifiers) {
                    // Bukkit's short name omits the namespace and can match another plugin's equipment.
                    if (mod.key.namespace == "libreforge") {
                        inst.removeModifier(mod)
                    }
                }
            }

            // Extra fix
            val maxHealth = this.getAttribute(Attribute.MAX_HEALTH)?.value ?: 0.0
            if (this.health > maxHealth) {
                this.health = maxHealth
            }
        } catch (_: Exception) {
            // Folia: player attributes may be in a partially destroyed state during quit
        }
    }
}

object PaperEffectDataFixer : Listener {
    @EventHandler(priority = EventPriority.HIGHEST)
    fun purgeOnRemove(event: EntityRemoveEvent) {
        val entity = event.entity
        if (entity is Player) {
            return
        }

        val dispatcher = entity.toDispatcher()
        for ((effect, holder) in dispatcher.providedActiveEffects) {
            runCatching { effect.disable(dispatcher, holder) }
        }

        ArgumentEvery.clearDispatcher(dispatcher.uuid)
        ArgumentCooldown.clearDispatcher(dispatcher.uuid)
        EffectAddHolder.clearDispatcher(dispatcher.uuid)
        EffectDropPickupItem.cleanupRemovedEntity(entity)
        EffectGlowNearbyBlocks.cleanupRemovedEntity(entity)
        if (entity is LivingEntity) {
            EffectVictimSpeedMultiplier.cleanup(entity)
        }
        transientMetadataKeys.forEach { entity.removeMetadata(it, plugin) }
        if (entity !is LivingEntity) {
            entity.removeMetadata("libreforge-vms", plugin)
        }
        TriggerTridentHit.clearSnapshot(entity.uniqueId)
        TriggerTridentAttack.clearSnapshot(entity.uniqueId)
        TriggerPlaceholderHits.clearVictim(entity.uniqueId)
        dispatcher.purgePreviousHolders()
    }
}
