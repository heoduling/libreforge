package com.willfp.libreforge.effects.templates

import com.willfp.eco.util.runExempted
import com.willfp.libreforge.LibreforgeSpigotPlugin
import com.willfp.libreforge.NoCompileData
import com.willfp.libreforge.plugin
import io.mockk.*
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.util.UUID

class MiningHeightTest {
    private class Mining : MineBlockEffect<NoCompileData>("height_test") {
        fun breakTargets(player: Player, blocks: Collection<Block>, prevent: Boolean = false) {
            player.breakBlocksSafely(blocks, prevent)
        }
    }
    private val owner = mockk<LibreforgeSpigotPlugin>(relaxed = true)
    private val player = mockk<Player>()
    private val mining = Mining()
    private val touched = mutableListOf<Int>()
    private var setBlock = false

    @BeforeEach fun setup() {
        mockkStatic("com.willfp.libreforge.LibreforgeSpigotPluginKt")
        every { plugin } returns owner
        every { owner.configYml.getBool("effects.use-setblock-break") } answers { setBlock }
        every { player.uniqueId } returns UUID.randomUUID()
        mockkStatic("com.willfp.eco.util.PlayerUtilsExtensions")
        every { player.runExempted(any()) } answers { secondArg<() -> Unit>().invoke() }
    }
    @AfterEach fun cleanup() = unmockkAll()

    private fun world(min: Int, max: Int) = mockk<World>().also {
        every { it.minHeight } returns min
        every { it.maxHeight } returns max
        every { player.world } returns it
    }
    private fun block(world: World, y: Int, min: Int, max: Int) = mockk<Block>().also { block ->
        every { block.world } returns world
        every { block.y } returns y
        val accept = {
            if (y < min || y >= max) throw AssertionError("out-of-world mining API at $y")
            touched.add(y)
        }
        every { player.breakBlock(block) } answers { accept(); true }
        every { block.breakNaturally() } answers { accept(); true }
        every { block.type = Material.AIR } answers { accept() }
        every { block.setMetadata(any(), any()) } just Runs
        every { block.removeMetadata(any(), any()) } just Runs
    }
    private fun mixed(prevent: Boolean) {
        for ((min, max) in listOf(-64 to 320, -64 to 640, 0 to 256)) {
            touched.clear()
            val world = world(min, max)
            val targets = listOf(min - 2, min - 1, min, min + 1, max - 1, max, max + 1)
                .map { block(world, it, min, max) }
            mining.breakTargets(player, targets, prevent)
            assertEquals(listOf(min, min + 1, max - 1), touched)
        }
    }
    @Test fun `ordinary mining filters world heights and retains legal order`() = mixed(false)
    @Test fun `natural mining filters world heights and retains legal order`() = mixed(true)
    @Test fun `set block mining filters world heights and retains legal order`() {
        setBlock = true
        mixed(false)
    }
    @Test fun `all invalid normal targets never receive recursion metadata`() {
        val world = world(-64, 640)
        val low = block(world, -65, -64, 640)
        val high = block(world, 640, -64, 640)
        mining.breakTargets(player, listOf(low, high))
        verify(exactly = 0) { low.setMetadata(any(), any()) }
        verify(exactly = 0) { high.setMetadata(any(), any()) }
        assertTrue(touched.isEmpty())
    }
    @Test fun `ordinary cross world target remains skipped`() {
        val world = world(-64, 320)
        val target = block(mockk(), 20, -64, 320)
        every { player.world } returns world
        mining.breakTargets(player, listOf(target))
        assertTrue(touched.isEmpty())
    }
    @Test fun `normal failure clears metadata and permits subsequent mining`() {
        val world = world(-64, 320)
        val first = block(world, 10, -64, 320)
        val second = block(world, 11, -64, 320)
        every { player.breakBlock(first) } throws IllegalStateException("controlled break failure")
        assertThrows(IllegalStateException::class.java) { mining.breakTargets(player, listOf(first)) }
        verify(exactly = 1) { first.removeMetadata("blockbreakevent-ignore", owner) }
        mining.breakTargets(player, listOf(second))
        assertEquals(listOf(11), touched)
    }
    @Test fun `empty input is accepted`() {
        mining.breakTargets(player, emptyList())
        assertTrue(touched.isEmpty())
    }
}
