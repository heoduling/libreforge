package com.willfp.libreforge.effects.impl

import com.willfp.eco.core.config.interfaces.Config
import com.willfp.eco.core.integrations.antigrief.AntigriefManager
import com.willfp.eco.core.map.listMap
import com.willfp.libreforge.ArgType
import com.willfp.libreforge.Dispatcher
import com.willfp.libreforge.NoCompileData
import com.willfp.libreforge.ProvidedHolder
import com.willfp.libreforge.SchedulerHelper
import com.willfp.libreforge.arguments
import com.willfp.libreforge.effects.Effect
import com.willfp.libreforge.effects.Identifiers
import com.willfp.libreforge.plugin
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.data.Ageable
import org.bukkit.event.EventHandler
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.inventory.ItemStack
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

object EffectReplantCrops : Effect<NoCompileData>("replant_crops") {
    override val description = "Automatically replants harvested crops at age 0 when the player breaks them."
    override val categories = setOf("world")

    override val arguments = arguments {
        require(
            "consume_seeds",
            "You must specify if seeds should be consumed!",
            description = "Whether seeds should be taken from the player's inventory when replanting.",
            type = ArgType.BOOLEAN
        )
        require(
            "only_fully_grown",
            "You must specify if only fully grown crops should be replanted!",
            description = "Whether to only replant crops that are fully grown.",
            type = ArgType.BOOLEAN
        )
    }

    private val players = ConcurrentHashMap<UUID, CopyOnWriteArrayList<ReplantConfig>>()

    override fun onEnable(
        dispatcher: Dispatcher<*>,
        config: Config,
        identifiers: Identifiers,
        holder: ProvidedHolder,
        compileData: NoCompileData
    ) {
        players.computeIfAbsent(dispatcher.uuid) { CopyOnWriteArrayList() }.add(ReplantConfig(
            identifiers.uuid,
            config.getBool("consume_seeds"),
            config.getBool("only_fully_grown")
        ))
    }

    override fun onDisable(dispatcher: Dispatcher<*>, identifiers: Identifiers, holder: ProvidedHolder) {
        players.computeIfPresent(dispatcher.uuid) { _, configs ->
            configs.removeIf { it.uuid == identifiers.uuid }
            configs.takeIf { it.isNotEmpty() }
        }
    }

    @EventHandler(
        ignoreCancelled = true
    )
    fun handle(event: BlockBreakEvent) {
        val player = event.player

        val playerConfigs = players[player.uniqueId] ?: return

        if (playerConfigs.isEmpty()) {
            return
        }

        val block = event.block
        val type = block.type

        if (!AntigriefManager.canPlaceBlock(player, block)) {
            return
        }

        if (type in arrayOf(
                Material.GLOW_BERRIES,
                Material.SWEET_BERRY_BUSH,
                Material.CACTUS,
                Material.BAMBOO,
                Material.CHORUS_FLOWER,
                Material.SUGAR_CANE
            )
        ) {
            return
        }

        val data = block.blockData

        if (data !is Ageable) {
            return
        }

        val consumeSeeds = playerConfigs.any { it.consumeSeeds }
        val onlyFullyGrown = playerConfigs.all { it.onlyFullyGrown }

        if (onlyFullyGrown && data.age != data.maximumAge) {
            return
        }

        val seed = if (consumeSeeds) {
            ItemStack(
                when (type) {
                    Material.WHEAT -> Material.WHEAT_SEEDS
                    Material.POTATOES -> Material.POTATO
                    Material.CARROTS -> Material.CARROT
                    Material.BEETROOTS -> Material.BEETROOT_SEEDS
                    Material.COCOA -> Material.COCOA_BEANS
                    else -> type
                }
            )
        } else {
            null
        }

        if (seed != null && !player.inventory.containsAtLeast(seed, 1)) return

        if (data.age != data.maximumAge) {
            if (onlyFullyGrown) {
                return
            }

            event.isDropItems = false
            event.expToDrop = 0
        }

        data.age = 0

        // The break event completes before the crop can be restored. Resolve the live
        // block on its region, and recheck player ownership before accessing inventory.
        val location = block.location
        val replantedData = data.clone() as Ageable
        SchedulerHelper.runTaskLater(plugin, location, Runnable {
            val target = location.block
            // Another interaction or a cancelled break may have left a block here.
            // Check the live position before charging for or performing the replant.
            if (!target.type.isAir || !target.canPlace(replantedData)) return@Runnable
            if (seed != null) {
                // The player may have moved to another region during the delay.
                if (SchedulerHelper.isFolia && !Bukkit.isOwnedByCurrentRegion(player)) return@Runnable
                if (!player.isOnline || player.inventory.removeItem(seed).isNotEmpty()) return@Runnable
            }
            target.type = type
            target.blockData = replantedData
        }, 1L)
    }

    private data class ReplantConfig(
        val uuid: UUID,
        val consumeSeeds: Boolean,
        val onlyFullyGrown: Boolean
    )
}
