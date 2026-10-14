package cn.taprunner

import java.io.ByteArrayOutputStream
import java.io.InputStream

fun InputStream.readBounded(limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (output.size() < limit) {
        val read = read(buffer, 0, minOf(buffer.size, limit-output.size()))
        if (read < 0) break
        output.write(buffer,0,read)
    }
    return output.toByteArray()
}
