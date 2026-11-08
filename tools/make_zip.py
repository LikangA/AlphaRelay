import os
import zipfile

ROOT = r"C:\Users\li\Documents\deepseek-harness\default-workspace\AlphaRelay"
OUT = r"C:\Users\li\Documents\deepseek-harness\default-workspace\AlphaRelay-src.zip"

include_dirs = ["app", "gradle", "tools", "docs", ".github"]
include_files = ["settings.gradle.kts", "build.gradle.kts", "gradle.properties",
                 "README.md", "BUILD-NOW.md", ".gitignore"]

items = []
for name in include_files:
    p = os.path.join(ROOT, name)
    if os.path.exists(p):
        items.append(p)
    else:
        print("MISSING", name)

for d in include_dirs:
    base = os.path.join(ROOT, d)
    for cur, _, files in os.walk(base):
        for f in files:
            if f.endswith((".pyc",)) or "\\build\\" in cur:
                continue
            items.append(os.path.join(cur, f))

with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED) as z:
    for p in items:
        rel = os.path.relpath(p, ROOT).replace("\\", "/")
        z.write(p, rel)

size = os.path.getsize(OUT)
print("zip:", OUT, "%.1f KB" % (size / 1024.0), "files:", len(items))
with zipfile.ZipFile(OUT) as z:
    for n in sorted(z.namelist()):
        print("  ", n)
