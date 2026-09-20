"""Comprueba que los tres sitios donde vive una clave de configuración dicen lo mismo.

Una clave de configuración está escrita en tres sitios a la vez:

* el **importador** (`AppConfig.apply()`), que es quien la lee de verdad del fichero;
* la **plantilla** (`config.ejemplo.json`), que es lo que se copia al teléfono;
* la **guía** (`docs/CONFIGURACION.md`), que es lo que se lee para rellenarla.

Si uno de los tres se queda atrás, el fallo es mudo, que es la peor forma de fallar: una clave que
el importador entiende y la plantilla no trae es un ajuste que nunca se aplica, y una clave que la
guía promete y el importador no lee es una hora perdida delante de un fichero que no hace nada. El
caso real que originó esto: la plantilla no traía `device_id`, `gist.token` ni `security.pin`, así
que configurar el código de seguridad por fichero —lo que la guía explica— no era posible copiando
lo que la guía manda copiar.

Comprueba además que la copia de la plantilla que la guía muestra sea idéntica al fichero, y que
todos los bloques ```json de la guía sean JSON válido.

Se **autocalibra**: ejecuta los mismos detectores contra casos de prueba con fallos conocidos y
falla si no los reconoce. Sin eso, un analizador roto (una expresión que ya no encaja con nada)
daría un verde vacío, que es exactamente el error que este proyecto ya ha cometido.

    python tools/check_config.py
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path
from typing import Dict, List, NamedTuple, Sequence, Set, Tuple

ROOT = Path(__file__).resolve().parent.parent
APP_CONFIG = ROOT / "android/app/src/main/java/com/example/telemetry/AppConfig.java"
TEMPLATE = ROOT / "config.ejemplo.json"
GUIDE = ROOT / "docs/CONFIGURACION.md"

APPLY_BODY = re.compile(
    r"private static Result apply\(Context context, JSONObject root\) \{(.*?)\n    \}", re.DOTALL
)
GROUP_DECL = re.compile(r"JSONObject (\w+) = group\(root, ([^)]*)\);")
READ_CALL = re.compile(r'\b(text|number|flag)\(\s*(\w+)\s*,\s*root\s*,\s*((?:"[^"]*"\s*,?\s*)+)\)')
LITERAL = re.compile(r'"([^"]*)"')
GUIDE_BLOCK = re.compile(r"^### Bloque `(\w+)`")
GUIDE_ROW = re.compile(r"^\|\s*`([\w.]+)`\s*\|")
# Hasta tres espacios de sangría, igual que el lector de la documentación (`docs/doc.html`,
# `FENCE = /^ {0,3}(?:```|~~~)/`) y que el Markdown de verdad: la guía tiene bloques dentro de
# listas numeradas, y una valla que no se reconoce hace que su contenido se lea como tabla —y, si
# solo se reconoce la de cierre, que el análisis se desincronice y se trague el resto del fichero.
FENCE = re.compile(r"^ {0,3}```(\w*)\s*$")

# Frase que anuncia la plantilla dentro de la guía. Si desaparece, la comprobación de la copia
# falla en voz alta en vez de quedarse sin nada que comparar.
GUIDE_TEMPLATE_CUE = "Copia la plantilla"

# Excepciones explícitas, cada una con su motivo. Se declaran a mano a propósito: sin motivo
# escrito no se admite nada, porque una lista opaca de «esto no hace falta» es como no comprobar.
TEMPLATE_EXCEPTIONS: Dict[str, str] = {
    "security.lock": (
        "con «false» BORRARÍA el código de un teléfono que ya lo tenga, y esto es el fichero que "
        "la guía manda copiar tal cual; se documenta en el apartado del código de seguridad"
    ),
    "tracking.min_time_ms": "sinónimo de interval_seconds (en milisegundos), ya presente en la plantilla",
}
GUIDE_EXCEPTIONS: Dict[str, str] = {
    "tracking.min_time_ms": "se explica en la propia fila de interval_seconds, que sí tiene fila",
}
# Claves de la raíz que el importador no lee: hoy solo la marca de formato que escribe Exportar.
ROOT_KEYS_ALLOWED: Dict[str, str] = {
    "version": "la escribe Exportar JSON; el importador no necesita leerla para aplicar nada",
}


class Field(NamedTuple):
    """Un campo que `apply()` lee: el nombre canónico, y sus alternativas."""

    name: str
    kind: str
    groups: Tuple[str, ...]
    keys: Tuple[str, ...]


def parse_fields(source: str) -> List[Field]:
    """Los campos que lee el importador, con los alias de grupo y de clave que acepta."""
    body = APPLY_BODY.search(source)
    if body is None:
        raise ValueError("no se encontró AppConfig.apply(): ¿cambió de forma el método?")

    text = body.group(1)
    aliases: Dict[str, Tuple[str, ...]] = {}
    for match in GROUP_DECL.finditer(text):
        aliases[match.group(1)] = tuple(LITERAL.findall(match.group(2)))

    fields: List[Field] = []
    for match in READ_CALL.finditer(text):
        kind, var = match.group(1), match.group(2)
        keys = tuple(LITERAL.findall(match.group(3)))
        if var not in aliases:
            raise ValueError(f"apply() lee del grupo «{var}», que no está declarado con group()")
        if not keys:
            raise ValueError(f"apply() lee de «{var}» sin ninguna clave")
        fields.append(Field(f"{aliases[var][0]}.{keys[0]}", kind, aliases[var], keys))

    if not fields:
        raise ValueError("no se reconoció ninguna lectura de clave en apply()")
    return fields


def parse_json_keys(data: object) -> Tuple[Set[Tuple[str, str]], Set[str]]:
    """Separa las claves de la raíz de las que van dentro de un bloque."""
    if not isinstance(data, dict):
        raise ValueError("la configuración no es un objeto JSON")
    groups: Set[Tuple[str, str]] = set()
    root: Set[str] = set()
    for name, value in data.items():
        if isinstance(value, dict):
            groups |= {(name, key) for key in value}
        else:
            root.add(name)
    return groups, root


def parse_guide(text: str) -> Tuple[Set[Tuple[str, str]], List[Tuple[str, str, int]], List[str]]:
    """Devuelve las claves de las tablas, los bloques de código y las líneas del texto."""
    lines = text.splitlines()
    rows: Set[Tuple[str, str]] = set()
    blocks: List[Tuple[str, str, int]] = []
    group = None
    lang = None
    start = 0
    buffer: List[str] = []

    for index, line in enumerate(lines):
        fence = FENCE.match(line)
        if fence:
            if lang is None:
                lang, start, buffer = fence.group(1), index, []
            else:
                blocks.append((lang, "\n".join(buffer), start))
                lang = None
            continue
        if lang is not None:
            buffer.append(line)
            continue
        heading = GUIDE_BLOCK.match(line)
        if heading:
            group = heading.group(1)
            continue
        if line.startswith("## "):
            group = None
            continue
        if group:
            row = GUIDE_ROW.match(line)
            if row:
                rows.add((group, row.group(1)))

    return rows, blocks, lines


def covered(field: Field, pairs: Set[Tuple[str, str]]) -> bool:
    """¿Alguna de las formas válidas de este campo está en el conjunto?"""
    return any((group, key) in pairs for group in field.groups for key in field.keys)


def recognized(group: str, key: str, fields: Sequence[Field]) -> bool:
    """¿El importador entiende esta clave escrita en este bloque?"""
    return any(group in field.groups and key in field.keys for field in fields)


def missing_from_template(fields: Sequence[Field], pairs: Set[Tuple[str, str]]) -> Set[str]:
    return {field.name for field in fields if not covered(field, pairs)}


def unknown_template_keys(pairs: Set[Tuple[str, str]], fields: Sequence[Field]) -> Set[str]:
    return {f"{group}.{key}" for group, key in pairs if not recognized(group, key, fields)}


def guide_promises_unknown(rows: Set[Tuple[str, str]], fields: Sequence[Field]) -> Set[str]:
    return {f"{group}.{key}" for group, key in rows if not recognized(group, key, fields)}


def undocumented(fields: Sequence[Field], rows: Set[Tuple[str, str]]) -> Set[str]:
    return {field.name for field in fields if not covered(field, rows)}


def calibrate() -> List[str]:
    """Ejecuta los detectores contra fallos conocidos y devuelve lo que no se haya detectado.

    El mismo código que analiza los ficheros reales, sobre casos de prueba: si esto pasa, el
    análisis de verdad no está vacío.
    """
    problems: List[str] = []
    fields = [
        Field("srv.endpoint", "text", ("srv", "servidor"), ("endpoint", "url")),
        Field("otro.total", "number", ("otro",), ("total",)),
    ]
    complete = {("servidor", "url"), ("otro", "total")}   # con alias a propósito

    if missing_from_template(fields, complete):
        problems.append("un campo cubierto por un alias se marca como ausente")
    if missing_from_template(fields, {("servidor", "url")}) != {"otro.total"}:
        problems.append("no detecta un campo que falta en la plantilla")
    if unknown_template_keys(complete | {("srv", "inventada")}, fields) != {"srv.inventada"}:
        problems.append("no detecta una clave desconocida en la plantilla")
    if guide_promises_unknown(complete | {("srv", "mentira")}, fields) != {"srv.mentira"}:
        problems.append("no detecta una clave que la guía promete y el importador no lee")
    if undocumented(fields, {("servidor", "url")}) != {"otro.total"}:
        problems.append("no detecta un campo del importador sin documentar")

    return problems


def main() -> int:
    try:
        fields = parse_fields(APP_CONFIG.read_text(encoding="utf-8"))
        template_data = json.loads(TEMPLATE.read_text(encoding="utf-8"))
        rows, blocks, lines = parse_guide(GUIDE.read_text(encoding="utf-8"))
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"no se pudo analizar la configuración: {error}")
        return 1

    template_pairs, template_root = parse_json_keys(template_data)
    failures = 0

    print(f"importador: {len(fields)} campos leídos")
    print(f"plantilla:  {len(template_pairs)} claves en {len({g for g, _ in template_pairs})} bloques")
    print(f"guía:       {len(rows)} filas en {len({g for g, _ in rows})} bloques")

    print("\n1. campos del importador que la plantilla no trae")
    for name in sorted(missing_from_template(fields, template_pairs)):
        if name in TEMPLATE_EXCEPTIONS:
            print(f"     omitido a propósito · {name}: {TEMPLATE_EXCEPTIONS[name]}")
            continue
        print(f"     FALTA {name}: quien copie la plantilla no lo configura")
        failures += 1

    print("\n2. claves de la plantilla que el importador no entiende")
    for name in sorted(unknown_template_keys(template_pairs, fields)):
        print(f"     DESCONOCIDA {name}: se copia y no hace nada")
        failures += 1
    for name in sorted(template_root):
        if name in ROOT_KEYS_ALLOWED:
            print(f"     raíz · {name}: {ROOT_KEYS_ALLOWED[name]}")
        else:
            print(f"     DESCONOCIDA {name} (en la raíz): se copia y no hace nada")
            failures += 1

    print("\n3. claves que la guía documenta y el importador no lee")
    for name in sorted(guide_promises_unknown(rows, fields)):
        print(f"     PROMESA ROTA {name}: la guía lo explica y el importador lo ignora")
        failures += 1

    print("\n4. campos del importador que la guía no documenta en ninguna tabla")
    for name in sorted(undocumented(fields, rows)):
        if name in GUIDE_EXCEPTIONS:
            print(f"     sin fila a propósito · {name}: {GUIDE_EXCEPTIONS[name]}")
            continue
        print(f"     SIN DOCUMENTAR {name}: nadie que lea la guía sabrá que existe")
        failures += 1

    print("\n5. la copia de la plantilla que muestra la guía")
    cue = next((i for i, line in enumerate(lines) if GUIDE_TEMPLATE_CUE in line), None)
    if cue is None:
        print(f"     FALLO: no se encontró «{GUIDE_TEMPLATE_CUE}» en la guía; no hay nada que comparar")
        failures += 1
    else:
        copied = [block for block in blocks if block[0] == "json" and block[2] > cue]
        if not copied:
            print("     FALLO: la guía anuncia la plantilla y no muestra ningún bloque JSON")
            failures += 1
        else:
            try:
                shown = json.loads(copied[0][1])
            except json.JSONDecodeError as error:
                print(f"     FALLO: la plantilla de la guía no es JSON válido ({error})")
                shown = None
                failures += 1
            if shown is not None and shown != template_data:
                print("     DESINCRONIZADA: la plantilla de la guía ya no es la de config.ejemplo.json")
                print(f"     (la guía muestra {shown})")
                failures += 1
            elif shown is not None:
                print("     ok  idéntica a config.ejemplo.json")

    print("\n6. bloques JSON de la guía")
    json_blocks = [block for block in blocks if block[0] == "json"]
    if not json_blocks:
        print("     FALLO: la guía no tiene ningún bloque JSON")
        failures += 1
    for _, code, start in json_blocks:
        try:
            json.loads(code)
            print(f"     ok  bloque de la línea {start + 1}")
        except json.JSONDecodeError as error:
            print(f"     JSON INVÁLIDO en la línea {start + 1}: {error}")
            failures += 1

    print("\n7. autocalibración")
    calibration = calibrate()
    for problem in calibration:
        print(f"     CALIBRACIÓN FALLIDA: {problem}")
        failures += 1
    if not calibration:
        print("     ok  detecta el campo ausente, la clave desconocida y la promesa rota")

    print("")
    if failures:
        print(f"RESULTADO: {failures} problema(s)")
        return 1
    print("RESULTADO: la plantilla, la guía y el importador dicen lo mismo")
    return 0


if __name__ == "__main__":
    sys.exit(main())
