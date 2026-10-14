package cn.taprunner.engine

import cn.taprunner.model.*
import kotlin.test.*

class ScriptLibraryTest {
    private val original = Script(id="original", name="测试", vars=mapOf("x" to "值"), steps=listOf(
        Step("image", "waitImage", template="local.png"), Step("loop", "loop", body=listOf("image"), times=2)
    ))

    @Test fun copyRetainsReferencesAndLeavesOriginalUnchanged() {
        val copy = ScriptLibrary.duplicate(original, listOf(original))
        assertNotEquals(original.id, copy.id)
        assertEquals(original.steps, copy.steps)
        assertEquals(original.vars, copy.vars)
        assertEquals(original.meta, copy.meta)
        Validator.validate(copy)
        assertEquals("测试", original.name)
    }
    @Test fun repeatedCopiesUseAvailableNames() {
        val copy = ScriptLibrary.duplicate(original, listOf(original, original.copy(id="copy", name="测试 副本")))
        assertEquals("测试 副本 2", copy.name)
    }
    @Test fun copyNamesRespectLengthLimit() {
        assertEquals(100, ScriptLibrary.duplicate(original.copy(name="长".repeat(100)), emptyList()).name.length)
    }
    @Test fun renameTrimsAndPreservesIdentity() {
        assertEquals(original.copy(name="新名"), ScriptLibrary.rename(original, "  新名  "))
    }
    @Test fun invalidNamesAreRejected() {
        listOf("  ", "长".repeat(101), "a\nb").forEach { name ->
            assertFailsWith<IllegalArgumentException> { ScriptLibrary.rename(original, name) }
        }
    }
    @Test fun searchIsTrimmedAndCaseInsensitive() {
        val scripts=listOf(original, original.copy(id="english", name="Tap TEST"))
        assertEquals(listOf("english"), ScriptLibrary.visible(scripts," test ",ScriptSort.NAME_ASC).map {it.id})
        assertTrue(ScriptLibrary.visible(scripts,"不存在",ScriptSort.NAME_ASC).isEmpty())
    }
    @Test fun sortingIsDeterministicAndDoesNotMutateLibrary() {
        val scripts=listOf(original.copy(id="b",name="B",steps=emptyList()),original.copy(id="a",name="A"))
        assertEquals(listOf("a","b"),ScriptLibrary.visible(scripts,"",ScriptSort.NAME_ASC).map {it.id})
        assertEquals(listOf("b","a"),ScriptLibrary.visible(scripts,"",ScriptSort.NAME_DESC).map {it.id})
        assertEquals(listOf("a","b"),ScriptLibrary.visible(scripts,"",ScriptSort.STEP_COUNT).map {it.id})
        assertEquals(listOf("b","a"),scripts.map {it.id})
    }
}
