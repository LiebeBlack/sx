"""Guarda contra el fallo que ya costó una CI entera: un workflow inválido no ejecuta **nada**.

GitHub no comparte parser con nosotros, así que esto no es un validador de YAML completo: es el
subconjunto de reglas que un `.github/workflows/*.yml` de este proyecto puede violar sin que nadie lo
note hasta después de empujar. El día del run #4, `- name: SDK de Android: licencias y paquetes`
convirtió el fichero en inválido (L45) porque un escalar simple de YAML no admite «: » dentro del
valor: GitHub creó un run con cero jobs, el job de Android no llegó a compilar y el fallo real quedó
tapado por un error de sintaxis de dos puntos. El lint se calibró contra ese fichero: lo marca.
"""

from __future__ import annotations

import re
import unittest
from pathlib import Path

WORKFLOW_DIR = Path(__file__).resolve().parent.parent / ".github" / "workflows"
WORKFLOWS = sorted(WORKFLOW_DIR.glob("*.yml"))

# `run: |`, `run: >-`…: a partir de ahí todo es texto literal y las reglas de escalares no aplican.
BLOCK_SCALAR = re.compile(r":\s*[|>][+-]?\s*$")
# `clave: valor` y `- clave: valor` (el valor puede estar vacío, como en `on:`).
MAPPING = re.compile(r"^(?:- )?([^:]+):\s*(.*)$")


def _sin_cadenas(texto: str) -> str:
    """Devuelve el texto sin el contenido de las comillas, respetando los escapes.

    Sin esto, `run: echo "a: b"` daría un falso positivo: dentro de comillas, «: » es un carácter
    cualquiera y el valor es perfectamente válido.
    """
    salida: list[str] = []
    comilla = ""
    escapado = False
    for caracter in texto:
        if comilla:
            if caracter == comilla and not escapado:
                comilla = ""
            escapado = caracter == "\\" and not escapado
            continue
        if caracter in "\"'":
            comilla = caracter
            continue
        salida.append(caracter)
    return "".join(salida)


def _lint(ruta: Path) -> list[str]:
    problemas: list[str] = []
    bloque = None
    for numero, cruda in enumerate(ruta.read_text(encoding="utf-8").splitlines(), start=1):
        if "\t" in cruda:
            problemas.append(
                f"{ruta.name}#L{numero}: tabulador en la indentación (YAML no los admite)"
            )
        indentacion = len(cruda) - len(cruda.lstrip(" "))
        if bloque is not None:
            # El contenido del bloque escalar llega mientras siga más indentado que `run:`.
            if not cruda.strip() or indentacion > bloque:
                continue
            bloque = None
        if not cruda.strip():
            continue
        cuerpo = cruda[indentacion:]
        if cuerpo.startswith("#"):
            continue
        if BLOCK_SCALAR.search(cuerpo):
            bloque = indentacion
            continue
        coincidencia = MAPPING.match(_sin_cadenas(cuerpo))
        if coincidencia and ": " in coincidencia.group(2):
            problemas.append(
                f"{ruta.name}#L{numero}: escalar simple con «: » dentro del valor, que YAML lee "
                f"como un mapeo anidado: {cuerpo.strip()}"
            )
    return problemas


class WorkflowSyntaxTest(unittest.TestCase):
    def test_hay_workflows_que_revisar(self):
        """Si el directorio cambiara de sitio, la comprobación no debe pasar en silencio."""
        self.assertTrue(WORKFLOWS, f"no se encontró ningún workflow en {WORKFLOW_DIR}")

    def test_los_workflows_no_tienen_errores_de_sintaxis_conocidos(self):
        problemas = [aviso for ruta in WORKFLOWS for aviso in _lint(ruta)]
        self.assertEqual(
            problemas,
            [],
            "un workflow inválido no ejecuta ningún job (GitHub crea un run vacío):\n"
            + "\n".join(problemas),
        )


if __name__ == "__main__":
    unittest.main()
