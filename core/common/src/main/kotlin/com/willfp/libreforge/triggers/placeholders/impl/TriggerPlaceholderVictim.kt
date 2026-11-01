package com.willfp.libreforge.triggers.placeholders.impl

import com.willfp.libreforge.NamedValue
import com.willfp.libreforge.SchedulerHelper
import com.willfp.libreforge.triggers.TriggerData
import com.willfp.libreforge.triggers.placeholders.TriggerPlaceholder
import org.bukkit.Bukkit
import org.bukkit.attribute.Attribute

object TriggerPlaceholderVictim : TriggerPlaceholder("victim") {
    override fun createPlaceholders(data: TriggerData): Collection<NamedValue> {
        val victim = data.victim ?: return emptyList()
        // A projectile's shooter can belong to another region. Replace prior config
        // injections explicitly: omitting these leaves the previous victim's health.
        // A nonnumeric value also avoids pretending that unavailable health is zero.
        if (SchedulerHelper.isFolia && !Bukkit.isOwnedByCurrentRegion(victim)) {
            return listOf(
                NamedValue("victim_health", "unavailable"),
                NamedValue("victim_max_health", "unavailable")
            )
        }

        return listOf(
            NamedValue(
                "victim_health",
                victim.health
            ),
            NamedValue(
                "victim_max_health",
                victim.getAttribute(Attribute.MAX_HEALTH)?.value ?: 0.0
            )
        )
    }
}
