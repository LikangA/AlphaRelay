package com.alpharelay.camera

/**
 * 从 RAW / TIFF 容器里挖出内嵌的 JPEG 预览图。
 *
 * Sony ARW 是 TIFF 结构：IFD0 上挂着若干 SubIFD，某个 IFD 用
 * JPEGInterchangeFormat(0x0201) / JPEGInterchangeFormatLength(0x0202) 指向一段 JPEG。
 * 我们递归走一遍所有 IFD，挑最大的那段 JPEG；失败时退化为全 buffer 扫描。
 *
 * 只读文件前几 MB 也能工作，因为预览图通常靠前，这样不用为缩略图下载整张 25MB 的 ARW。
 */
object PreviewExtractor {

    private const val TAG_JPEG_OFFSET = 0x0201
    private const val TAG_JPEG_LENGTH = 0x0202
    private const val TAG_SUB_IFD = 0x014A

    fun extract(buf: ByteArray, length: Int = buf.size): ByteArray? {
        val len = minOf(length, buf.size)
        if (len < 32) return null

        // 小端 TIFF 头：49 49 2A 00
        if (buf[0] == 0x49.toByte() && buf[1] == 0x49.toByte() && u16le(buf, 2, len) == 42) {
            val ifd0 = u32le(buf, 4, len)
            val candidates = ArrayList<LongArray>(8)
            if (ifd0 in 8L until len.toLong()) walk(buf, len, ifd0.toInt(), 0, candidates)
            var best: ByteArray? = null
            for (c in candidates) {
                val start = c[0]
                val size = c[1]
                if (start < 0L || size <= 0L || start + size > len) continue
                val jpeg = sliceJpeg(buf, start.toInt(), size.toInt())
                if (jpeg != null && (best == null || jpeg.size > best.size)) best = jpeg
            }
            if (best != null) return best
        }
        return scanForLargestJpeg(buf, len)
    }

    private fun walk(buf: ByteArray, len: Int, ifd: Int, depth: Int, out: MutableList<LongArray>) {
        if (depth > 3 || ifd <= 0 || ifd + 2 > len) return
        val count = u16le(buf, ifd, len)
        if (count <= 0 || count > 1024) return

        var jpegOffset = -1L
        var jpegLength = -1L

        for (i in 0 until count) {
            val entry = ifd + 2 + i * 12
            if (entry + 12 > len) break
            val tag = u16le(buf, entry, len)
            val type = u16le(buf, entry + 2, len)
            val n = u32le(buf, entry + 4, len)
            when (tag) {
                TAG_JPEG_OFFSET -> jpegOffset = inlineValue(buf, len, type, entry + 8)
                TAG_JPEG_LENGTH -> jpegLength = inlineValue(buf, len, type, entry + 8)
                TAG_SUB_IFD -> collectSubIfds(buf, len, type, n, entry + 8, depth, out)
            }
        }

        if (jpegOffset in 0L until len.toLong() && jpegLength > 0L) {
            out.add(longArrayOf(jpegOffset, jpegLength))
        }

        val nextPos = ifd + 2 + count * 12
        if (nextPos + 4 <= len) {
            val next = u32le(buf, nextPos, len)
            if (next > 0L && next < len) walk(buf, len, next.toInt(), depth + 1, out)
        }
    }

    private fun collectSubIfds(
        buf: ByteArray, len: Int, type: Int, count: Long, pos: Int, depth: Int, out: MutableList<LongArray>
    ) {
        if (count <= 0L || count > 32L) return
        val itemSize = if (type == 3) 2 else 4
        var base = pos
        if (itemSize * count > 4L) {
            base = u32le(buf, pos, len).toInt()
            if (base <= 0 || base >= len) return
        }
        for (i in 0 until count.toInt()) {
            val p = base + i * itemSize
            val v = if (itemSize == 2) u16le(buf, p, len).toLong() else u32le(buf, p, len)
            if (v > 0L && v < len) walk(buf, len, v.toInt(), depth + 1, out)
        }
    }

    private fun inlineValue(buf: ByteArray, len: Int, type: Int, pos: Int): Long = when (type) {
        3 -> u16le(buf, pos, len).toLong()   // SHORT
        4 -> u32le(buf, pos, len)            // LONG
        9 -> u32le(buf, pos, len)            // SLONG
        13 -> u32le(buf, pos, len)           // IFD
        else -> -1L
    }

    private fun sliceJpeg(buf: ByteArray, offset: Int, size: Int): ByteArray? {
        if (offset < 0) return null
        val end = minOf(offset + size, buf.size)
        if (offset + 2 > end) return null

        var start = -1
        var i = offset
        while (i + 1 < end) {
            if ((buf[i].toInt() and 0xFF) == 0xFF && (buf[i + 1].toInt() and 0xFF) == 0xD8) {
                start = i
                break
            }
            i++
        }
        if (start < 0) return null

        var j = end - 2
        while (j > start) {
            if ((buf[j].toInt() and 0xFF) == 0xFF && (buf[j + 1].toInt() and 0xFF) == 0xD9) {
                return buf.copyOfRange(start, j + 2)
            }
            j--
        }
        // 没找到结束标记，可能是数据被截断；交给解码器自己判断
        return if (end - start > 4096) buf.copyOfRange(start, end) else null
    }

    /**
     * 在任意字节流里找最大的完整 JPEG。
     * 用 SOI/EOI 配对栈，而不是「遇到 SOI 就往后找 EOI」——
     * 因为截断的 JPEG（只读了前 256KB）没有外层 EOI，但里面嵌着完整的 EXIF 缩略图。
     */
    private fun scanForLargestJpeg(buf: ByteArray, len: Int): ByteArray? {
        var best: ByteArray? = null
        val stack = IntArray(32)
        var top = 0
        var i = 0
        while (i + 1 < len) {
            val a = buf[i].toInt() and 0xFF
            val b = buf[i + 1].toInt() and 0xFF
            if (a == 0xFF && b == 0xD8) {
                if (top < stack.size) stack[top++] = i
                i += 2
                continue
            }
            if (a == 0xFF && b == 0xD9 && top > 0) {
                val start = stack[--top]
                val size = i + 2 - start
                if (size > 2048 && (best == null || size > best.size)) {
                    best = buf.copyOfRange(start, i + 2)
                }
                i += 2
                continue
            }
            i++
        }
        return best
    }

    private fun u16le(b: ByteArray, o: Int, len: Int): Int {
        if (o < 0 || o + 1 >= len) return -1
        return (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    }

    private fun u32le(b: ByteArray, o: Int, len: Int): Long {
        if (o < 0 || o + 3 >= len) return -1L
        return (b[o].toLong() and 0xFF) or
            ((b[o + 1].toLong() and 0xFF) shl 8) or
            ((b[o + 2].toLong() and 0xFF) shl 16) or
            ((b[o + 3].toLong() and 0xFF) shl 24)
    }
}
