package com.willfp.libreforge

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.World
import org.bukkit.attribute.AttributeInstance
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.WorldLoadEvent
import org.bukkit.event.world.WorldUnloadEvent
import org.bukkit.plugin.IllegalPluginAccessException
import org.bukkit.scheduler.BukkitTask
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Consumer

object EntityRefreshListener : Listener {

    private val MODIFIER_PATTERN = Regex("\\d+_\\d+")
    @Volatile
    private var pollState: PollState? = null

    @EventHandler
    fun onEntityAdded(event: EntityAddToWorldEvent) {
        pollState?.track(event.entity as? LivingEntity ?: return, event.world)
    }

    @EventHandler
    fun onEntityRemoved(event: EntityRemoveFromWorldEvent) {
        pollState?.remove(event.entity as? LivingEntity ?: return, event.world)
    }

    @EventHandler
    fun onChunkLoad(event: ChunkLoadEvent) {
        val chunk = event.chunk
        chunk.entities.filterIsInstance<LivingEntity>()
            .filterNot { it is Player }
            .forEach { entity ->
                pollState?.track(entity, chunk.world)
                SchedulerHelper.runTask(plugin, entity) {
                    removeEcoAttributeModifiers(entity)
                }
            }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onWorldLoaded(event: WorldLoadEvent) {
        val state = pollState ?: return
        if (!SchedulerHelper.isFolia || Bukkit.isGlobalTickThread()) {
            state.openWorld(event.world)
        } else {
            val world = WeakReference(event.world)
            whileEnabled {
                SchedulerHelper.runTask(plugin, Runnable { world.get()?.let(state::openWorld) })
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onWorldUnloaded(event: WorldUnloadEvent) {
        if (event.isCancelled) return
        val state = pollState ?: return
        val world = state.generationOf(event.world) ?: return
        whileEnabled {
            SchedulerHelper.runTaskLater(plugin, Runnable { state.confirmUnload(world) }, 1L)
        }
    }

    internal fun pollingTask(enabled: Boolean): Runnable? {
        if (!enabled) {
            clear()
            return null
        }

        val state = synchronized(this) { pollState ?: PollState().also { pollState = it } }
        return Runnable { state.poll() }
    }

    internal fun pollTrackedEntities() {
        pollState?.poll()
    }

    internal fun clear() {
        val old = synchronized(this) {
            pollState.also { pollState = null }
        }
        old?.close()
    }

    private inline fun whileEnabled(action: () -> Unit) {
        try {
            action()
        } catch (failure: IllegalPluginAccessException) {
            if (plugin.isEnabled) throw failure
        }
    }

    private class WorldGeneration(world: World) {
        val id: UUID = world.uid
        val name: String = world.name
        val key: NamespacedKey = world.key
        val reference = WeakReference(world)
    }

    private class TrackedEntity(val id: UUID, val world: WorldGeneration, entity: LivingEntity) {
        val reference = WeakReference(entity)
        @Volatile
        var pending: EntityPollTask? = null
    }

    // The monitor protects only map and task state. Bukkit calls and cancellations stay outside it.
    private class PollState {
        private val worlds = ConcurrentHashMap<UUID, WorldGeneration>()
        private val trackedEntities = ConcurrentHashMap<UUID, TrackedEntity>()
        @Volatile
        private var initialized = false

        fun openWorld(world: World) {
            val generation = WorldGeneration(world)
            if (pollState !== this || Bukkit.getWorld(generation.name) !== world) return
            val old = synchronized(this) {
                if (pollState !== this || worlds[generation.id]?.reference?.get() === world) return
                worlds.put(generation.id, generation)
            }
            if (old != null) revokeWorld(old)
            backfill(world, generation)
        }

        private fun backfill(world: World, generation: WorldGeneration) {
            // CraftWorld supplies a concurrent snapshot of loaded chunk coordinates.
            for (chunk in world.loadedChunks) {
                if (!isCurrent(generation)) return
                val x = chunk.x
                val z = chunk.z
                val location = Location(world, (x shl 4).toDouble(), 0.0, (z shl 4).toDouble())
                whileEnabled {
                    SchedulerHelper.runTask(plugin, location) {
                        val loaded = generation.reference.get() ?: return@runTask
                        if (!isCurrent(generation) || !loaded.isChunkLoaded(x, z)) return@runTask
                        for (entity in loaded.getChunkAt(x, z).entities) {
                            if (entity is LivingEntity) track(entity, generation)
                        }
                    }
                }
            }
        }

        private fun isCurrent(world: WorldGeneration): Boolean =
            pollState === this && worlds[world.id] === world

        fun generationOf(world: World): WorldGeneration? =
            worlds[world.uid]?.takeIf { it.reference.get() === world }

        fun track(entity: LivingEntity, world: World) {
            val generation = generationOf(world) ?: return
            track(entity, generation)
        }

        private fun track(entity: LivingEntity, world: WorldGeneration) {
            if (entity is Player) return
            val id = entity.uniqueId
            val cancel = synchronized(this) {
                if (!isCurrent(world)) return
                val previous = trackedEntities[id]
                if (previous?.world === world && previous.reference.get() === entity) return
                trackedEntities[id] = TrackedEntity(id, world, entity)
                previous?.let(::detach)
            }
            cancel?.let { SchedulerHelper.cancelTask(plugin, it) }
        }

        fun remove(entity: LivingEntity, world: World) {
            val generation = generationOf(world) ?: return
            val id = entity.uniqueId
            val cancel = synchronized(this) {
                val entry = trackedEntities[id] ?: return
                if (entry.world !== generation || entry.reference.get() !== entity) return
                trackedEntities.remove(id, entry)
                detach(entry)
            }
            cancel?.let { SchedulerHelper.cancelTask(plugin, it) }
        }

        fun confirmUnload(world: WorldGeneration) {
            if (!isCurrent(world)) return
            val old = world.reference.get()
            // Name and UUID consult Bukkit's registry. The dimension key also checks the core registry.
            if (old != null && (
                    Bukkit.getWorld(world.name) === old ||
                        Bukkit.getWorld(world.id) === old ||
                        Bukkit.getWorld(world.key) === old
                    )
            ) return
            revokeWorld(world)
        }

        private fun revokeWorld(world: WorldGeneration) {
            synchronized(this) {
                worlds.remove(world.id, world)
            }
            for (entry in trackedEntities.values) {
                if (entry.world !== world) continue
                val cancel = synchronized(this) {
                    if (!trackedEntities.remove(entry.id, entry)) null else detach(entry)
                }
                cancel?.let { SchedulerHelper.cancelTask(plugin, it) }
            }
        }

        fun poll() {
            if (pollState !== this || !plugin.isEnabled) return
            // Bootstrap in the first active timer so a task-rebuilding reload cannot cancel it.
            if (!initialized) {
                Bukkit.getWorlds().forEach(::openWorld)
                initialized = true
            }
            // Unregistration may finish after the first post-event check, or have no event.
            worlds.values.forEach(::confirmUnload)
            for (entry in trackedEntities.values) {
                val entity = entry.reference.get()
                if (entity == null) {
                    val cancel = synchronized(this) {
                        if (trackedEntities.remove(entry.id, entry)) detach(entry) else null
                    }
                    cancel?.let { SchedulerHelper.cancelTask(plugin, it) }
                    continue
                }
                val pending = entry.pending
                if (pending != null) {
                    val published = pending.handle ?: continue
                    val task = published.get()
                    if (task != null && !isCancelled(task)) continue
                    finish(pending, retire = false)
                }
                val task = synchronized(this) {
                    if (!isCurrent(entry.world) || trackedEntities[entry.id] !== entry || entry.pending != null) continue
                    EntityPollTask(this, entry).also { entry.pending = it }
                }
                submit(entity, task)
            }
        }

        private fun submit(entity: LivingEntity, ticket: EntityPollTask) {
            val handle = try {
                if (SchedulerHelper.isFolia) {
                    entity.scheduler.run(plugin, ticket, ticket)
                } else {
                    SchedulerHelper.runTaskForEntity(plugin, entity, Runnable { ticket.refresh() }, null)
                }
            } catch (failure: Throwable) {
                finish(ticket, retire = false)
                if (failure !is IllegalPluginAccessException || plugin.isEnabled) throw failure
                return
            }
            if (handle == null || isCancelled(handle)) {
                finish(ticket, retire = handle == null)
                return
            }
            val cancel = synchronized(this) {
                if (ticket.entry.pending === ticket) ticket.handle = WeakReference(handle)
                ticket.detached
            }
            if (cancel) SchedulerHelper.cancelTask(plugin, handle)
        }

        private fun isCancelled(handle: Any): Boolean = when (handle) {
            is ScheduledTask -> handle.isCancelled
            is BukkitTask -> handle.isCancelled
            else -> false
        }

        fun liveEntity(ticket: EntityPollTask): LivingEntity? = synchronized(this) {
            val entry = ticket.entry
            if (!isCurrent(entry.world) || trackedEntities[entry.id] !== entry || entry.pending !== ticket) null
            else entry.reference.get()
        }

        fun finish(ticket: EntityPollTask, retire: Boolean) {
            synchronized(this) {
                val entry = ticket.entry
                if (entry.pending !== ticket) return
                entry.pending = null
                ticket.handle = null
                if (retire) trackedEntities.remove(entry.id, entry)
            }
        }

        private fun detach(entry: TrackedEntity): Any? {
            val ticket = entry.pending ?: return null
            entry.pending = null
            ticket.detached = true
            val handle = ticket.handle?.get()
            ticket.handle = null
            return handle
        }

        fun close() {
            val handles = synchronized(this) {
                val tasks = trackedEntities.values.mapNotNull(::detach)
                trackedEntities.clear()
                worlds.clear()
                tasks
            }
            handles.forEach { SchedulerHelper.cancelTask(plugin, it) }
        }
    }

    private class EntityPollTask(private val state: PollState, val entry: TrackedEntity) :
        Consumer<ScheduledTask>, Runnable {
        @Volatile
        var handle: WeakReference<Any>? = null
        var detached = false

        override fun accept(task: ScheduledTask) = refresh()
        override fun run() = state.finish(this, retire = true)

        fun refresh() {
            var invalid = false
            try {
                if (!plugin.isEnabled) return
                val entity = state.liveEntity(this)
                invalid = entity == null || !entity.isValid || entity.isDead
                if (!invalid) entity?.toDispatcher()?.pollEffects()
            } finally {
                state.finish(this, retire = invalid)
            }
        }
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
