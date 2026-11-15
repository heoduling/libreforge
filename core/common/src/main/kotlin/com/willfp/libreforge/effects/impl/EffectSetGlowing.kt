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
import java.util.concurrent.ThreadLocalRandom

object EffectSetGlowing : Effect<NoCompileData>("set_glowing") {
    private val recoveryKey = NamespacedKey("libreforge", "temporary_glowing")
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
            val recovery = longArrayOf(
                System.currentTimeMillis() + duration * 50L,
                if (glowing) 0L else 1L,
                ThreadLocalRandom.current().nextLong()
            )
            // Save recovery alongside the flag before a quit or world save can persist it.
            victim.persistentDataContainer.set(recoveryKey, PersistentDataType.LONG_ARRAY, recovery)
            victim.isGlowing = glowing
            scheduleRestore(victim, recovery, duration.toLong())
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
        val recovery = entity.persistentDataContainer.get(recoveryKey, PersistentDataType.LONG_ARRAY) ?: return
        if (recovery.size != 3) return
        val remaining = recovery[0] - System.currentTimeMillis()
        if (remaining <= 0) {
            restore(entity, recovery)
        } else {
            scheduleRestore(entity, recovery, (remaining + 49) / 50)
        }
    }

    private fun scheduleRestore(entity: LivingEntity, recovery: LongArray, ticks: Long) {
        SchedulerHelper.runTaskLater(plugin, entity, { restore(entity, recovery) }, ticks)
    }

    private fun restore(entity: LivingEntity, recovery: LongArray) {
        val container = entity.persistentDataContainer
        if (!recovery.contentEquals(container.get(recoveryKey, PersistentDataType.LONG_ARRAY))) return
        entity.isGlowing = recovery[1] != 0L
        container.remove(recoveryKey)
    }
}
