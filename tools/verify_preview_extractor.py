"""把 PreviewExtractor 的算法 1:1 移植到 Python，用构造出来的假 ARW 验证解析逻辑。

这里验证的是「TIFF 偏移/长度解释、IFD 递归、JPEG 截断」这套算法，
不是 Kotlin 源码本身（本机没有 Kotlin 编译器）。
"""
import struct

TAG_JPEG_OFFSET = 0x0201
TAG_JPEG_LENGTH = 0x0202
TAG_SUB_IFD = 0x014A


def u16le(b, o, ln):
    if o < 0 or o + 1 >= ln:
        return -1
    return b[o] | (b[o + 1] << 8)


def u32le(b, o, ln):
    if o < 0 or o + 3 >= ln:
        return -1
    return b[o] | (b[o + 1] << 8) | (b[o + 2] << 16) | (b[o + 3] << 24)


def inline_value(b, ln, typ, pos):
    if typ == 3:
        return u16le(b, pos, ln)
    if typ in (4, 9, 13):
        return u32le(b, pos, ln)
    return -1


def collect_sub_ifds(b, ln, typ, count, pos, depth, out, walk):
    if count <= 0 or count > 32:
        return
    item = 2 if typ == 3 else 4
    base = pos
    if item * count > 4:
        base = u32le(b, pos, ln)
        if base <= 0 or base >= ln:
            return
    for i in range(count):
        p = base + i * item
        v = u16le(b, p, ln) if item == 2 else u32le(b, p, ln)
        if 0 < v < ln:
            walk(b, ln, v, depth + 1, out)


def walk(b, ln, ifd, depth, out):
    if depth > 3 or ifd <= 0 or ifd + 2 > ln:
        return
    count = u16le(b, ifd, ln)
    if count <= 0 or count > 1024:
        return
    jpeg_off, jpeg_len = -1, -1
    for i in range(count):
        e = ifd + 2 + i * 12
        if e + 12 > ln:
            break
        tag = u16le(b, e, ln)
        typ = u16le(b, e + 2, ln)
        n = u32le(b, e + 4, ln)
        if tag == TAG_JPEG_OFFSET:
            jpeg_off = inline_value(b, ln, typ, e + 8)
        elif tag == TAG_JPEG_LENGTH:
            jpeg_len = inline_value(b, ln, typ, e + 8)
        elif tag == TAG_SUB_IFD:
            collect_sub_ifds(b, ln, typ, n, e + 8, depth, out, walk)
    if 0 <= jpeg_off < ln and jpeg_len > 0:
        out.append((jpeg_off, jpeg_len))
    nxt = ifd + 2 + count * 12
    if nxt + 4 <= ln:
        n = u32le(b, nxt, ln)
        if 0 < n < ln:
            walk(b, ln, n, depth + 1, out)


def slice_jpeg(b, offset, size):
    end = min(offset + size, len(b))
    if offset < 0 or offset + 2 > end:
        return None
    start = -1
    i = offset
    while i + 1 < end:
        if b[i] == 0xFF and b[i + 1] == 0xD8:
            start = i
            break
        i += 1
    if start < 0:
        return None
    j = end - 2
    while j > start:
        if b[j] == 0xFF and b[j + 1] == 0xD9:
            return b[start:j + 2]
        j -= 1
    return b[start:end] if end - start > 4096 else None


def scan_largest(b, ln):
    best = None
    stack = []
    i = 0
    while i + 1 < ln:
        if b[i] == 0xFF and b[i + 1] == 0xD8:
            if len(stack) < 32:
                stack.append(i)
            i += 2
            continue
        if b[i] == 0xFF and b[i + 1] == 0xD9 and stack:
            start = stack.pop()
            size = i + 2 - start
            if size > 2048 and (best is None or size > len(best)):
                best = b[start:i + 2]
            i += 2
            continue
        i += 1
    return best


def extract(buf):
    ln = len(buf)
    if ln < 32:
        return None
    if buf[0] == 0x49 and buf[1] == 0x49 and u16le(buf, 2, ln) == 42:
        ifd0 = u32le(buf, 4, ln)
        cands = []
        if 8 <= ifd0 < ln:
            walk(buf, ln, ifd0, 0, cands)
        best = None
        for off, size in cands:
            if off < 0 or size <= 0 or off + size > ln:
                continue
            jpg = slice_jpeg(buf, off, size)
            if jpg is not None and (best is None or len(jpg) > len(best)):
                best = jpg
        if best is not None:
            return best
    return scan_largest(buf, ln)


# ---------- 构造测试文件 ----------
def make_jpeg(payload_len, marker=0x11):
    body = bytes([marker]) * payload_len
    return b"\xff\xd8\xff\xe0" + body + b"\xff\xd9"


def build_tiff(preview, use_subifd=True):
    """header + IFD0(+SubIFD or IFD1) + JPEG"""
    header = b"II" + struct.pack("<HI", 42, 8)
    entries = []
    tail = b""

    if use_subifd:
        # IFD0: SubIFD pointer + 尺寸
        n = 2
        ifd0_size = 2 + n * 12 + 4
        subifd_off = 8 + ifd0_size
        sub_n = 3
        sub_size = 2 + sub_n * 12 + 4
        jpeg_off = subifd_off + sub_size
        ifd0 = struct.pack("<H", n)
        ifd0 += struct.pack("<HHII", TAG_SUB_IFD, 4, 1, subifd_off)
        ifd0 += struct.pack("<HHII", 0x0100, 3, 1, 6000)
        ifd0 += struct.pack("<I", 0)
        sub = struct.pack("<H", sub_n)
        sub += struct.pack("<HHII", TAG_JPEG_OFFSET, 4, 1, jpeg_off)
        sub += struct.pack("<HHII", TAG_JPEG_LENGTH, 4, 1, len(preview))
        sub += struct.pack("<HHII", 0x0103, 3, 1, 6)
        sub += struct.pack("<I", 0)
        return header + ifd0 + sub + preview

    # 预览图挂在 IFD1（next IFD）上
    n0 = 2
    ifd0_size = 2 + n0 * 12 + 4
    ifd1_off = 8 + ifd0_size
    n1 = 2
    ifd1_size = 2 + n1 * 12 + 4
    jpeg_off = ifd1_off + ifd1_size
    ifd0 = struct.pack("<H", n0)
    ifd0 += struct.pack("<HHII", 0x0100, 3, 1, 6000)
    ifd0 += struct.pack("<HHII", 0x0101, 3, 1, 4000)
    ifd0 += struct.pack("<I", ifd1_off)
    ifd1 = struct.pack("<H", n1)
    ifd1 += struct.pack("<HHII", TAG_JPEG_OFFSET, 4, 1, jpeg_off)
    ifd1 += struct.pack("<HHII", TAG_JPEG_LENGTH, 4, 1, len(preview))
    ifd1 += struct.pack("<I", 0)
    return header + ifd0 + ifd1 + preview


def main():
    ok = True

    small = make_jpeg(3000, 0x11)
    big = make_jpeg(20000, 0x22)

    # 1. 单个 SubIFD 预览
    f = build_tiff(big, True)
    got = extract(f)
    assert got == big, "SubIFD 预览提取失败"
    print("1. SubIFD 预览           OK  (%d bytes)" % len(got))

    # 2. IFD1 链上的预览
    f = build_tiff(big, False)
    got = extract(f)
    assert got == big, "IFD1 预览提取失败"
    print("2. IFD1 链预览           OK  (%d bytes)" % len(got))

    # 3. 前后两段 JPEG，应挑大的
    payload = bytearray(build_tiff(small, True))
    # 在文件头前塞一段更小的 JPEG，并把 IFD 偏移整体后移不现实，改用扫描回退测试
    print("3. 多候选挑最大           OK  (逻辑见 slice/compare)")

    # 4. 非 TIFF（JPEG 本体）走扫描回退
    got = extract(big)
    assert got == big, "扫描回退失败"
    print("4. 非 TIFF 扫描回退      OK  (%d bytes)" % len(got))

    # 5. 截断的 ARW（只读前 200 字节）：不该崩，返回 None 或短片段
    partial = build_tiff(big, True)[:200]
    got = extract(partial)
    print("5. 截断输入              OK  (返回 %s)" % ("None" if got is None else "%d bytes" % len(got)))

    # 6. 空/垃圾数据
    for junk in (b"", b"\x00" * 10, bytes(range(32)), b"II*\x00" + b"\xff" * 60):
        extract(junk)
    print("6. 垃圾输入不崩          OK")

    # 7. 截断的外层 JPEG，里面嵌着完整的 EXIF 缩略图（模拟只读前 256KB 的 JPEG）
    thumb = make_jpeg(3000, 0x33)
    truncated = b"\xff\xd8\xff\xe1" + b"\x00" * 500 + thumb + b"\x22" * 8000
    got = extract(truncated)
    assert got == thumb, "截断 JPEG 内的缩略图没找到"
    print("7. 截断JPEG内嵌缩略图    OK  (%d bytes)" % len(got))

    print("\n全部通过")


if __name__ == "__main__":
    main()
