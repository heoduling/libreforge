package com.willfp.libreforge.effects.impl

import com.willfp.eco.core.config.interfaces.Config
import com.willfp.libreforge.ArgType
import com.willfp.libreforge.NoCompileData
import com.willfp.libreforge.SchedulerHelper
import com.willfp.libreforge.arguments
import com.willfp.libreforge.getIntFromExpression
import com.willfp.libreforge.getOrNull
import com.willfp.libreforge.plugin
import com.willfp.libreforge.effects.Effect
import com.willfp.libreforge.triggers.TriggerData
import com.willfp.libreforge.triggers.TriggerParameter
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.entity.LivingEntity
import org.bukkit.event.EventHandler
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.world.EntitiesLoadEvent
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.IllegalPluginAccessException
import java.util.concurrent.ThreadLocalRandom

object EffectSetGlowing : Effect<NoCompileData>("set_glowing") {
    private val recoveryKey = NamespacedKey("libreforge", "temporary_glowing")
    private const val LAYERED_RECORD_VERSION = 1L

    override val description = "Makes the target entity glow, optionally reverting after a duration."
    override val categories = setOf("visual")

    override val arguments = arguments {
        optional(
            "glowing",
            description = "Whether to enable or disable the glowing effect.",
            type = ArgType.BOOLEAN,
            default = "true"
        )
        optional(
            "duration",
            description = "How long to apply the glow, in ticks. Reverts when expired. Supports expressions.",
            type = ArgType.EXPRESSION
        )
    }
    override val parameters = setOf(
        TriggerParameter.VICTIM
    )

    override fun onTrigger(config: Config, data: TriggerData, compileData: NoCompileData): Boolean {
        val victim = data.victim ?: return false
        val glowing = config.getBoolOrNull("glowing") ?: true
        val duration = config.getOrNull("duration") { getIntFromExpression(it, data) }

        if (duration != null && duration > 0) {
            val previous = readRecovery(victim)
            val previousCount = previous?.let { layerCount(it) } ?: 0
            val refresh = previous != null && glowingAt(previous, 0) == glowing
            val remainingCount = previousCount - if (refresh) 1 else 0
            val restoreGlowing = previous?.let { it[1] != 0L } ?: !glowing
            val deadline = System.currentTimeMillis() + duration * 50L
            val token = ThreadLocalRandom.current().nextLong()
            val record = createRecord(remainingCount + 1, restoreGlowing, glowing)
            writeLayer(record, 0, deadline, glowing, token)
            for (index in 0 until remainingCount) {
                copyLayer(previous!!, index + if (refresh) 1 else 0, record, index + 1)
            }
            // Save recovery alongside the flag before a quit or world save can persist it.
            saveRecovery(victim, record)
            victim.isGlowing = glowing
            scheduleRestore(victim, token, duration.toLong())
        } else {
            victim.persistentDataContainer.remove(recoveryKey)
            victim.isGlowing = glowing
        }

        return true
    }

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        restoreOnOwner(event.player)
    }

    @EventHandler
    fun onEntitiesLoad(event: EntitiesLoadEvent) {
        for (entity in event.entities) {
            if (entity is LivingEntity) restoreOnOwner(entity)
        }
    }

    private fun restoreOnOwner(entity: LivingEntity) {
        if (SchedulerHelper.isFolia && !Bukkit.isOwnedByCurrentRegion(entity)) {
            SchedulerHelper.runTask(plugin, entity) { restoreLoadedEntity(entity) }
        } else {
            restoreLoadedEntity(entity)
        }
    }

    private fun restoreLoadedEntity(entity: LivingEntity) {
        var record = readRecovery(entity) ?: return
        val now = System.currentTimeMillis()
        val count = layerCount(record)
        var activeCount = 0
        var firstActive = -1
        for (index in 0 until count) {
            if (deadlineAt(record, index) > now) {
                if (firstActive == -1) firstActive = index
                activeCount++
            }
        }
        if (activeCount == 0) {
            entity.isGlowing = record[1] != 0L
            entity.persistentDataContainer.remove(recoveryKey)
            return
        }
        if (activeCount < count) {
            val remaining = createRecord(activeCount, record[1] != 0L, glowingAt(record, firstActive))
            var destination = 0
            for (index in 0 until count) {
                if (deadlineAt(record, index) > now) {
                    copyLayer(record, index, remaining, destination++)
                }
            }
            saveRecovery(entity, remaining)
            // Match the existing token comparison, including saved duplicate tokens.
            if (tokenAt(remaining, 0) != tokenAt(record, 0)) {
                entity.isGlowing = glowingAt(remaining, 0)
            }
            record = remaining
        }
        for (index in 0 until layerCount(record)) {
            scheduleRestore(entity, tokenAt(record, index), (deadlineAt(record, index) - now + 49) / 50)
        }
    }

    private fun scheduleRestore(entity: LivingEntity, token: Long, ticks: Long) {
        val owner = plugin
        if (SchedulerHelper.isFolia) {
            if (!owner.isEnabled) {
                throw IllegalPluginAccessException("Plugin attempted to register task while disabled")
            }
            entity.scheduler.execute(owner, { restore(entity, token) }, null, ticks)
        } else {
            SchedulerHelper.runTaskLater(owner, entity, { restore(entity, token) }, ticks)
        }
    }

    private fun restore(entity: LivingEntity, token: Long) {
        val record = readRecovery(entity) ?: return
        val count = layerCount(record)
        var index = 0
        while (index < count && tokenAt(record, index) != token) index++
        if (index == count) return
        if (count == 1) {
            entity.isGlowing = record[1] != 0L
            entity.persistentDataContainer.remove(recoveryKey)
            return
        }
        val current = if (index == 0) 1 else 0
        val remaining = createRecord(count - 1, record[1] != 0L, glowingAt(record, current))
        var destination = 0
        for (source in 0 until count) {
            if (source != index) copyLayer(record, source, remaining, destination++)
        }
        saveRecovery(entity, remaining)
        if (index == 0) entity.isGlowing = glowingAt(remaining, 0)
    }

    private fun readRecovery(entity: LivingEntity): LongArray? {
        val record = entity.persistentDataContainer.get(recoveryKey, PersistentDataType.LONG_ARRAY) ?: return null
        if (record.size == 3) return record
        if (record.size < 5 || (record.size - 5) % 3 != 0 || record[3] != LAYERED_RECORD_VERSION) return null
        return record
    }

    private fun saveRecovery(entity: LivingEntity, record: LongArray) {
        entity.persistentDataContainer.set(recoveryKey, PersistentDataType.LONG_ARRAY, record)
    }

    private fun layerCount(record: LongArray): Int = if (record.size == 3) 1 else (record.size - 2) / 3

    private fun deadlineAt(record: LongArray, index: Int): Long = record[if (index == 0) 0 else 5 + (index - 1) * 3]

    private fun tokenAt(record: LongArray, index: Int): Long = record[if (index == 0) 2 else 7 + (index - 1) * 3]

    private fun glowingAt(record: LongArray, index: Int): Boolean {
        if (record.size == 3) return record[1] == 0L
        return record[if (index == 0) 4 else 6 + (index - 1) * 3] != 0L
    }

    private fun createRecord(count: Int, restoreGlowing: Boolean, currentGlowing: Boolean): LongArray {
        val size = if (count == 1 && currentGlowing != restoreGlowing) 3 else 3 * count + 2
        val record = LongArray(size)
        record[1] = if (restoreGlowing) 1L else 0L
        if (size > 3) record[3] = LAYERED_RECORD_VERSION
        return record
    }

    private fun writeLayer(record: LongArray, index: Int, deadline: Long, glowing: Boolean, token: Long) {
        if (index == 0) {
            record[0] = deadline
            record[2] = token
            if (record.size > 3) record[4] = if (glowing) 1L else 0L
        } else {
            val offset = 5 + (index - 1) * 3
            record[offset] = deadline
            record[offset + 1] = if (glowing) 1L else 0L
            record[offset + 2] = token
        }
    }

    private fun copyLayer(source: LongArray, sourceIndex: Int, target: LongArray, targetIndex: Int) {
        writeLayer(target, targetIndex, deadlineAt(source, sourceIndex), glowingAt(source, sourceIndex), tokenAt(source, sourceIndex))
    }
}
