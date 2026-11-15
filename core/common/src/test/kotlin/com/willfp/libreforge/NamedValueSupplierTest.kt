package com.willfp.libreforge

import com.willfp.eco.core.placeholder.StaticPlaceholder
import io.mockk.*
import com.willfp.eco.core.Eco
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import java.util.LinkedList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier

class NamedValueSupplierTest {
    @BeforeEach fun setup() {
        mockkStatic(Eco::class)
        val eco = mockk<Eco>(relaxed = true)
        every { Eco.get() } returns eco
        every { eco.ecoPlugin.configYml.getInt("literal-cache-ttl") } returns 5
    }
    @AfterEach fun cleanup() = unmockkAll()

    private fun supplier(placeholder: StaticPlaceholder): Any {
        val field = StaticPlaceholder::class.java.declaredFields.single { Supplier::class.java.isAssignableFrom(it.type) }
        field.isAccessible = true
        return field.get(placeholder)
    }

    @Test fun `aliases preserve placeholder types order values equality and hash`() {
        for (size in listOf(0, 1, 2, 3, 4, 8)) {
            for (value in listOf("", "中文%测试%", "-1.5", "$\\{}[]")) {
                val ids = LinkedList((0 until size).map { "alias_$it" })
                val expected = ids.map { StaticPlaceholder(it) { value } }
                val actual = NamedValue(ids, value).placeholders
                assertEquals(expected.javaClass, actual.javaClass)
                assertEquals(expected.size, actual.size)
                expected.zip(actual).forEach { (a, b) ->
                    assertEquals(a.javaClass, b.javaClass)
                    assertEquals(a, b)
                    assertEquals(a.hashCode(), b.hashCode())
                    assertEquals(a.patternString, b.patternString)
                    assertEquals(a.value, (b as StaticPlaceholder).value)
                }
            }
        }
    }

    @Test fun `one expansion shares its supplier across aliases but independent values have independent suppliers`() {
        val first = NamedValue(listOf("a", "b", "a"), "first").placeholders.map { it as StaticPlaceholder }
        assertSame(supplier(first[0]), supplier(first[1]))
        assertSame(supplier(first[0]), supplier(first[2]))
        val next = NamedValue(listOf("a", "b"), "second").placeholders.first() as StaticPlaceholder
        assertNotSame(supplier(first[0]), supplier(next))
        assertEquals("first", first[0].value)
        assertEquals("second", next.value)
    }

    @Test fun `lazy expansion observes mutations before first access and then retains its result`() {
        val ids = mutableListOf("before")
        val value = NamedValue(ids, "text")
        ids[0] = "after"
        ids.add("second")
        val expanded = value.placeholders
        assertEquals(listOf("after", "second"), expanded.map { it.patternString })
        ids.clear()
        assertSame(expanded, value.placeholders)
    }

    @Test fun `first actual alias creates a supplier even when the collection reported zero size`() {
        val ids = object : AbstractCollection<String>() {
            override val size = 0
            override fun iterator() = listOf("late").iterator()
        }
        val result = NamedValue(ids, "text").placeholders.single() as StaticPlaceholder
        assertEquals("late", result.patternString)
        assertEquals("text", result.value)
    }

    @Test fun `lazy initialization is shared by concurrent readers`() {
        val iterations = AtomicInteger()
        val ids = object : AbstractCollection<String>() {
            override val size = 3
            override fun iterator(): Iterator<String> {
                iterations.incrementAndGet()
                return listOf("a", "b", "c").iterator()
            }
        }
        val value = NamedValue(ids, "text")
        val workers = Executors.newFixedThreadPool(4)
        try {
            val results = workers.invokeAll(List(64) { java.util.concurrent.Callable { value.placeholders } }).map { it.get() }
            assertEquals(1, iterations.get())
            results.forEach { assertSame(results.first(), it) }
        } finally {
            workers.shutdown()
        }
    }

    @Test fun `dynamic numeric placeholders preserve their value-dependent equality and hash`() {
        val first = DynamicNumericValue(listOf("number"), 1).placeholders.single()
        assertEquals(first, DynamicNumericValue(listOf("number"), 1).placeholders.single())
        val different = DynamicNumericValue(listOf("number"), 2).placeholders.single()
        assertNotEquals(first, different)
        assertNotEquals(first.hashCode(), different.hashCode())
    }
}
