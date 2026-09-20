"""Valida la sintaxis del JavaScript embebido en los HTML de `frontend/` con `node --check`.

Es la red de seguridad en CI del único componente que no tiene compilador propio: el visualizador.
Cubre los dos visores —`index.html` (mapa Leaflet, API propia) y `gist.html` (Gist como base de
datos, sin librerías externas)— porque un error de sintaxis en cualquiera deja la página en blanco.

    python tools/check_frontend.py
"""

from __future__ import annotations

import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

FRONTEND_DIR = Path(__file__).resolve().parent.parent / "frontend"
HTML_PATHS = [FRONTEND_DIR / "index.html", FRONTEND_DIR / "gist.html"]
INLINE_SCRIPT = re.compile(r"<script(?![^>]*\bsrc=)[^>]*>(.*?)</script>", re.DOTALL | re.IGNORECASE)


def check(node: str, html_path: Path, tmp: str) -> int:
    """Devuelve el número de bloques con error de sintaxis en un fichero."""
    if not html_path.exists():
        print(f"no se encontró {html_path}")
        return 1

    blocks = INLINE_SCRIPT.findall(html_path.read_text(encoding="utf-8"))
    if not blocks:
        print(f"{html_path.name}: no hay JavaScript embebido que validar")
        return 1

    failures = 0
    for index, code in enumerate(blocks):
        target = Path(tmp) / f"{html_path.stem}-block{index}.js"
        target.write_text(code, encoding="utf-8")
        result = subprocess.run(
            [node, "--check", str(target)], capture_output=True, text=True, check=False
        )
        if result.returncode == 0:
            print(f"{html_path.name} bloque {index}: sintaxis correcta ({len(code.splitlines())} líneas)")
        else:
            failures += 1
            print(f"{html_path.name} bloque {index}: ERROR DE SINTAXIS\n{result.stderr.strip()}")
    return failures


def main() -> int:
    node = shutil.which("node")
    if node is None:
        print("node no está instalado: se omite la validación de JavaScript")
        return 0

    failures = 0
    with tempfile.TemporaryDirectory(prefix="telemetria-js-") as tmp:
        for html_path in HTML_PATHS:
            failures += check(node, html_path, tmp)

    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
