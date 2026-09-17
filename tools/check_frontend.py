"""Valida la sintaxis del JavaScript embebido en frontend/index.html usando `node --check`.

Sirve como red de seguridad en CI para el único componente que no tiene compilador propio.

    python tools/check_frontend.py
"""

from __future__ import annotations

import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

HTML_PATH = Path(__file__).resolve().parent.parent / "frontend" / "index.html"
INLINE_SCRIPT = re.compile(r"<script(?![^>]*\bsrc=)[^>]*>(.*?)</script>", re.DOTALL | re.IGNORECASE)


def main() -> int:
    if not HTML_PATH.exists():
        print(f"no se encontró {HTML_PATH}")
        return 1

    blocks = INLINE_SCRIPT.findall(HTML_PATH.read_text(encoding="utf-8"))
    if not blocks:
        print("no hay JavaScript embebido que validar")
        return 1

    node = shutil.which("node")
    if node is None:
        print("node no está instalado: se omite la validación de JavaScript")
        return 0

    failures = 0
    with tempfile.TemporaryDirectory(prefix="telemetria-js-") as tmp:
        for index, code in enumerate(blocks):
            target = Path(tmp) / f"block{index}.js"
            target.write_text(code, encoding="utf-8")
            result = subprocess.run([node, "--check", str(target)], capture_output=True, text=True, check=False)
            if result.returncode == 0:
                print(f"bloque {index}: sintaxis correcta ({len(code.splitlines())} líneas)")
            else:
                failures += 1
                print(f"bloque {index}: ERROR DE SINTAXIS\n{result.stderr.strip()}")

    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
