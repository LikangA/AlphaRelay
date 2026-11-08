import os, re, sys, json
from xml.etree import ElementTree as ET

ROOT = r"C:\Users\li\Documents\deepseek-harness\default-workspace\AlphaRelay"
RES = os.path.join(ROOT, "app", "src", "main", "res")
JAVA = os.path.join(ROOT, "app", "src", "main", "java")

errors = []
warnings = []

# ---------- 1. XML well-formedness ----------
xml_files = []
for base, _, files in os.walk(RES):
    for f in files:
        if f.endswith(".xml"):
            xml_files.append(os.path.join(base, f))
xml_files.append(os.path.join(ROOT, "app", "src", "main", "AndroidManifest.xml"))

for p in xml_files:
    try:
        ET.parse(p)
    except Exception as e:
        errors.append(f"XML 解析失败 {os.path.relpath(p, ROOT)}: {e}")

# ---------- 2. collect declared resources ----------
declared = {"string": set(), "drawable": set(), "color": set(), "style": set(), "mipmap": set(), "menu": set(), "xml": set(), "layout": set()}

for p in xml_files:
    if "values" in p:
        tree = ET.parse(p)
        for child in tree.getroot():
            tag = child.tag
            name = child.get("name")
            if name and tag in ("string", "color", "style", "dimen", "bool", "integer"):
                declared[tag].add(name)

for base, _, files in os.walk(RES):
    folder = os.path.basename(base)
    kind = folder.split("-")[0]
    for f in files:
        name = f.split(".")[0]
        if kind in declared:
            declared[kind].add(name)

# ---------- 3. resolve references ----------
def refs_in(path):
    text = open(path, encoding="utf-8").read()
    return re.findall(r'@(?:\+)?(string|drawable|color|style|mipmap|menu|xml|layout)/([A-Za-z0-9_.]+)', text)

for p in xml_files:
    for kind, name in refs_in(p):
        if kind in ("style",) and name.startswith("Widget.") or name.startswith("Theme.") or name.startswith("TextAppearance."):
            # framework / material styles are not declared locally
            if name.startswith(("Widget.Material3", "Theme.Material3", "TextAppearance")):
                continue
        if name not in declared[kind]:
            errors.append(f"引用缺失 @{kind}/{name}  ({os.path.relpath(p, ROOT)})")

# ---------- 4. Kotlin R.* references ----------
declared_ids = set()
for p in xml_files:
    text = open(p, encoding="utf-8").read()
    declared_ids.update(re.findall(r'@\+id/([A-Za-z0-9_]+)', text))

kt_files = []
for base, _, files in os.walk(JAVA):
    for f in files:
        if f.endswith(".kt"):
            kt_files.append(os.path.join(base, f))

for p in kt_files:
    text = open(p, encoding="utf-8").read()
    rel = os.path.relpath(p, ROOT)
    for kind, name in re.findall(r'(?<![\w.])R\.(id|string|drawable|color|layout|menu|mipmap)\.([A-Za-z0-9_]+)', text):
        if kind == "id":
            if name not in declared_ids:
                errors.append(f"R.id.{name} 在资源里不存在 ({rel})")
        else:
            if name not in declared[kind]:
                errors.append(f"R.{kind}.{name} 在资源里不存在 ({rel})")

# ---------- 5. Kotlin bracket balance (粗略但能抓大错) ----------
def strip_code(src):
    out = []
    i = 0
    n = len(src)
    while i < n:
        c = src[i]
        if c == '/' and i + 1 < n and src[i+1] == '/':
            while i < n and src[i] != '\n':
                i += 1
        elif c == '/' and i + 1 < n and src[i+1] == '*':
            i += 2
            while i + 1 < n and not (src[i] == '*' and src[i+1] == '/'):
                i += 1
            i += 2
        elif c == '"':
            triple = src[i:i+3] == '"""'
            i += 3 if triple else 1
            while i < n:
                if triple and src[i:i+3] == '"""':
                    i += 3
                    break
                if not triple and src[i] == '"':
                    i += 1
                    break
                if src[i] == '\\':
                    i += 1
                i += 1
        elif c == "'":
            i += 1
            while i < n:
                if src[i] == '\\':
                    i += 1
                elif src[i] == "'":
                    i += 1
                    break
                i += 1
        else:
            out.append(c)
            i += 1
    return "".join(out)

pairs = {')': '(', ']': '[', '}': '{'}
for p in kt_files:
    src = strip_code(open(p, encoding="utf-8").read())
    stack = []
    line = 1
    bad = False
    for ch in src:
        if ch == '\n':
            line += 1
        elif ch in '([{':
            stack.append((ch, line))
        elif ch in ')]}':
            if not stack or stack[-1][0] != pairs[ch]:
                errors.append(f"括号不配对 {os.path.relpath(p, ROOT)}: 第 {line} 行 '{ch}'")
                bad = True
                break
            stack.pop()
    if not bad and stack:
        errors.append(f"括号未闭合 {os.path.relpath(p, ROOT)}: {stack[-1][0]} 在第 {stack[-1][1]} 行")

print("XML 文件:", len(xml_files), " Kotlin 文件:", len(kt_files))
print("声明: string=%d drawable=%d color=%d style=%d mipmap=%d id=%d" % (
    len(declared["string"]), len(declared["drawable"]), len(declared["color"]),
    len(declared["style"]), len(declared["mipmap"]), len(declared_ids)))

if errors:
    print("\n=== 错误 %d ===" % len(errors))
    for e in errors:
        print(" -", e)
else:
    print("\n全部检查通过")
sys.exit(1 if errors else 0)
