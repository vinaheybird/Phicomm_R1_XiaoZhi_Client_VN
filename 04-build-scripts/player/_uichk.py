# -*- coding: utf-8 -*-
import io, re, sys

if len(sys.argv) < 2:
    sys.exit(2)

needle = sys.stdin.buffer.read().decode("utf-8", errors="replace")
raw = io.open(sys.argv[1], encoding="utf-8").read()
if needle in raw:
    sys.exit(0)

# Reconstruct chunked Java string literals in Ui.java
joined = re.sub(r'"\s*,\s*\n\s*"', "", raw)
if needle in joined:
    sys.exit(0)

# Also check unescaped HTML/JS content
unesc = joined.replace(r"\n", "\n").replace(r'\"', '"').replace(r"\\", "\\")
sys.exit(0 if needle in unesc else 1)
