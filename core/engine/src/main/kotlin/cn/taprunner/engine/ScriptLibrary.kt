package cn.taprunner.engine

import cn.taprunner.model.Script
import java.util.Locale
import java.util.UUID

enum class ScriptSort { NAME_ASC, NAME_DESC, STEP_COUNT }

object ScriptLibrary {
    fun rename(script: Script, name: String): Script {
        val normalized = name.trim()
        require(normalized.isNotEmpty() && normalized.length <= 100) { "名称需为 1～100 个字符" }
        require(normalized.none { it.isISOControl() }) { "名称不能包含控制字符" }
        return script.copy(name = normalized)
    }

    /** Step IDs and template references are local to the copied script and remain valid. */
    fun duplicate(script: Script, scripts: List<Script>): Script {
        val names = scripts.map { it.name.lowercase(Locale.ROOT) }.toSet()
        var number = 1
        var name: String
        do {
            val suffix = if (number == 1) " 副本" else " 副本 $number"
            name = script.name.take(100 - suffix.length) + suffix
            number++
        } while (name.lowercase(Locale.ROOT) in names)
        return script.copy(id = UUID.randomUUID().toString(), name = name)
    }

    fun visible(scripts: List<Script>, query: String, sort: ScriptSort): List<Script> {
        val filtered = scripts.filter { it.name.contains(query.trim(), ignoreCase = true) }
        val byName = compareBy<Script> { it.name.lowercase(Locale.ROOT) }.thenBy { it.name }.thenBy { it.id }
        return filtered.sortedWith(when (sort) {
            ScriptSort.NAME_ASC -> byName
            ScriptSort.NAME_DESC -> byName.reversed()
            ScriptSort.STEP_COUNT -> compareByDescending<Script> { it.steps.size }.then(byName)
        })
    }
}
