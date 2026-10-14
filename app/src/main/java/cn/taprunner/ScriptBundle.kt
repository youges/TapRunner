package cn.taprunner

import cn.taprunner.engine.Validator
import cn.taprunner.model.*
import kotlinx.serialization.encodeToString
import java.io.*
import java.util.UUID
import java.util.zip.*

object ScriptBundle {
    fun export(app: TapApp, script: Script, output: OutputStream) {
        ZipOutputStream(output).use { zip ->
            fun entry(name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name));zip.write(bytes);zip.closeEntry() }
            entry("script.json",ScriptJson.format.encodeToString(script).toByteArray())
            val base=File(app.filesDir,"templates").canonicalFile
            script.steps.filter{it.type=="waitImage"}.map{it.template}.distinct().forEach{ name ->
                require(name.matches(Regex("[A-Za-z0-9_.-]+"))){"打包模板只能使用文件名"}
                val image=File(base,name);require(image.exists()){"模板 $name 不存在"}
                entry("templates/$name",image.readBytes());File(base,"$name.width").takeIf{it.exists()}?.let{entry("templates/$name.width",it.readBytes())}
            }
        }
    }
    fun import(app: TapApp, input: InputStream) {
        val items=linkedMapOf<String,ByteArray>();var total=0
        ZipInputStream(input).use{ zip -> while(true){val entry=zip.nextEntry?:break;if(entry.isDirectory)continue;require(entry.name=="script.json"||entry.name.matches(Regex("templates/[A-Za-z0-9_.-]+"))){"压缩包含无效路径"};val bytes=zip.readBounded(20_000_001);total+=bytes.size;require(bytes.size<=20_000_000&&total<=50_000_000&&items.size<200){"压缩包过大"};items[entry.name]=bytes} }
        val raw=items["script.json"]?:error("缺少 script.json")
        val script=ScriptJson.format.decodeFromString<Script>(raw.toString(Charsets.UTF_8));Validator.validate(script)
        val base=File(app.filesDir,"templates").apply{mkdirs()};val mapping=mutableMapOf<String,String>()
        script.steps.filter{it.type=="waitImage"}.forEach{ step ->
            require(step.template.matches(Regex("[A-Za-z0-9_.-]+")))
            val bytes=items["templates/${step.template}"]?:error("模板缺失")
            val name=mapping.getOrPut(step.template){"${UUID.randomUUID()}.png"}
            File(base,name).writeBytes(bytes);items["templates/${step.template}.width"]?.let{File(base,"$name.width").writeBytes(it)}
        }
        app.store.save(script.copy(id=UUID.randomUUID().toString(),steps=script.steps.map{if(it.type=="waitImage")it.copy(template=mapping.getValue(it.template))else it}))
    }
}
