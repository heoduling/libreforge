package com.willfp.libreforge

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Registry
import org.bukkit.attribute.AttributeInstance
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.world.ChunkLoadEvent
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Consumer

object EntityRefreshListener : Listener {

    private val MODIFIER_PATTERN = Regex("\\d+_\\d+")
    private val trackedEntities = ConcurrentHashMap<UUID, WeakReference<LivingEntity>>()

    @EventHandler
    fun onEntityAdded(event: EntityAddToWorldEvent) {
        track(event.entity as? LivingEntity ?: return)
    }

    @EventHandler
    fun onEntityRemoved(event: EntityRemoveFromWorldEvent) {
        trackedEntities.remove(event.entity.uniqueId)
    }

    @EventHandler
    fun onChunkLoad(event: ChunkLoadEvent) {
        event.chunk.entities.filterIsInstance<LivingEntity>()
            .filterNot { it is Player }
            .forEach { entity ->
                track(entity)
                SchedulerHelper.runTask(plugin, entity) {
                    removeEcoAttributeModifiers(entity)
                }
            }
    }

    private fun track(entity: LivingEntity) {
        if (entity is Player) {
            return
        }
        trackedEntities[entity.uniqueId] = WeakReference(entity)
    }

    internal fun backfillLoadedEntities() {
        for (world in Bukkit.getWorlds()) {
            for (chunk in world.loadedChunks) {
                val chunkX = chunk.x
                val chunkZ = chunk.z
                val location = Location(world, (chunkX shl 4).toDouble(), 0.0, (chunkZ shl 4).toDouble())

                SchedulerHelper.runTask(plugin, location) {
                    if (!world.isChunkLoaded(chunkX, chunkZ)) {
                        return@runTask
                    }

                    for (entity in world.getChunkAt(chunkX, chunkZ).entities) {
                        if (entity is LivingEntity && entity !is Player) {
                            track(entity)
                        }
                    }
                }
            }
        }
    }

    internal fun pollTrackedEntities() {
        trackedEntities.forEach { uuid, reference ->
            val entity = reference.get()
            if (entity == null) {
                retireTrackedEntity(uuid, reference)
                return@forEach
            }

            if (SchedulerHelper.isFolia) {
                val task = EntityPollTask(entity, uuid, reference)
                entity.scheduler.run(plugin, task, task)
            } else {
                SchedulerHelper.runTaskForEntity(plugin, entity, Runnable {
                    pollTrackedEntity(entity, uuid, reference)
                }, null)
            }
        }
    }

    @JvmStatic
    private fun pollTrackedEntity(entity: LivingEntity, uuid: UUID, reference: WeakReference<LivingEntity>) {
        if (!entity.isValid || entity.isDead) {
            retireTrackedEntity(uuid, reference)
            return
        }
        entity.toDispatcher().pollEffects()
    }

    @JvmStatic
    private fun retireTrackedEntity(uuid: UUID, reference: WeakReference<LivingEntity>) {
        trackedEntities.remove(uuid, reference)
    }

    private class EntityPollTask(
        private val entity: LivingEntity,
        private val uuid: UUID,
        private val reference: WeakReference<LivingEntity>
    ) : Consumer<ScheduledTask>, Runnable {
        override fun accept(task: ScheduledTask) = pollTrackedEntity(entity, uuid, reference)
        override fun run() = retireTrackedEntity(uuid, reference)
    }

    internal fun clear() {
        trackedEntities.clear()
    }

    private fun removeEcoAttributeModifiers(entity: LivingEntity) {
        for (attribute in Registry.ATTRIBUTE) {
            val attributeInstance: AttributeInstance = entity.getAttribute(attribute) ?: continue

            @Suppress("USELESS_ELVIS")
            val modifiers = attributeInstance.modifiers ?: continue

            modifiers.filter { it.name.matches(MODIFIER_PATTERN) }
                .forEach { modifier ->
                    attributeInstance.removeModifier(modifier)
                }
        }
    }
}
