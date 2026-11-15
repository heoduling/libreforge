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
    private const val LAYERED_RECORD_VERSION = 1L

    private data class Application(val deadline: Long, val glowing: Boolean, val token: Long)
    private data class Recovery(val restoreGlowing: Boolean, val applications: List<Application>)
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
            val application = Application(
                System.currentTimeMillis() + duration * 50L,
                glowing,
                ThreadLocalRandom.current().nextLong()
            )
            val history = previous?.applications.orEmpty()
            // Consecutive equal requests refresh the current layer. Opposite requests cover it.
            val remaining = if (history.firstOrNull()?.glowing == glowing) history.drop(1) else history
            val recovery = Recovery(previous?.restoreGlowing ?: !glowing, listOf(application) + remaining)
            // Save recovery alongside the flag before a quit or world save can persist it.
            saveRecovery(victim, recovery)
            victim.isGlowing = glowing
            scheduleRestore(victim, application, duration.toLong())
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
        val recovery = readRecovery(entity) ?: return
        val now = System.currentTimeMillis()
        val active = recovery.applications.filter { it.deadline > now }
        if (active.isEmpty()) {
            entity.isGlowing = recovery.restoreGlowing
            entity.persistentDataContainer.remove(recoveryKey)
            return
        }
        if (active.size != recovery.applications.size) {
            saveRecovery(entity, Recovery(recovery.restoreGlowing, active))
            if (active.first().token != recovery.applications.first().token) {
                entity.isGlowing = active.first().glowing
            }
        }
        for (application in active) {
            scheduleRestore(entity, application, (application.deadline - now + 49) / 50)
        }
    }

    private fun scheduleRestore(entity: LivingEntity, application: Application, ticks: Long) {
        val token = application.token
        SchedulerHelper.runTaskLater(plugin, entity, { restore(entity, token) }, ticks)
    }

    private fun restore(entity: LivingEntity, token: Long) {
        val recovery = readRecovery(entity) ?: return
        val index = recovery.applications.indexOfFirst { it.token == token }
        if (index < 0) return
        val remaining = recovery.applications.filterIndexed { position, _ -> position != index }
        if (remaining.isEmpty()) {
            entity.isGlowing = recovery.restoreGlowing
            entity.persistentDataContainer.remove(recoveryKey)
        } else {
            saveRecovery(entity, Recovery(recovery.restoreGlowing, remaining))
            if (index == 0) entity.isGlowing = remaining.first().glowing
        }
    }

    private fun readRecovery(entity: LivingEntity): Recovery? {
        val record = entity.persistentDataContainer.get(recoveryKey, PersistentDataType.LONG_ARRAY) ?: return null
        if (record.size == 3) {
            val restoreGlowing = record[1] != 0L
            return Recovery(restoreGlowing, listOf(Application(record[0], !restoreGlowing, record[2])))
        }
        if (record.size < 5 || (record.size - 5) % 3 != 0 || record[3] != LAYERED_RECORD_VERSION) return null
        val applications = mutableListOf(Application(record[0], record[4] != 0L, record[2]))
        for (offset in 5 until record.size step 3) {
            applications += Application(record[offset], record[offset + 1] != 0L, record[offset + 2])
        }
        return Recovery(record[1] != 0L, applications)
    }

    private fun saveRecovery(entity: LivingEntity, recovery: Recovery) {
        val container = entity.persistentDataContainer
        val current = recovery.applications.first()
        val restoreValue = if (recovery.restoreGlowing) 1L else 0L
        if (recovery.applications.size == 1 && current.glowing != recovery.restoreGlowing) {
            container.set(recoveryKey, PersistentDataType.LONG_ARRAY, longArrayOf(current.deadline, restoreValue, current.token))
            return
        }
        // Extended record: deadline, final restoration, token, version, current value, then older triples.
        val record = LongArray(5 + (recovery.applications.size - 1) * 3)
        record[0] = current.deadline
        record[1] = restoreValue
        record[2] = current.token
        record[3] = LAYERED_RECORD_VERSION
        record[4] = if (current.glowing) 1L else 0L
        for (index in 1 until recovery.applications.size) {
            val application = recovery.applications[index]
            val offset = 5 + (index - 1) * 3
            record[offset] = application.deadline
            record[offset + 1] = if (application.glowing) 1L else 0L
            record[offset + 2] = application.token
        }
        container.set(recoveryKey, PersistentDataType.LONG_ARRAY, record)
    }
}
