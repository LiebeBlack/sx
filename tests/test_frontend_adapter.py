"""Prueba de comportamiento del adaptador del Gist que vive dentro de `frontend/index.html`.

El visor es el único componente del proyecto que no tiene compilador, y `tools/check_frontend.py` solo
comprueba su **sintaxis**. Aquí se comprueba lo otro: que el contrato del Gist se interprete como dice
`tools/gist_schema.py`.

No hay una copia de la lógica en este fichero: se **extraen las funciones reales** del visor y se
ejecutan con `node`. Así la prueba falla cuando alguien cambie el visor, que es justo lo que se quiere.

Cubre los cuatro casos que han aparecido de verdad en producción:

- un Gist con el objeto suelto `{"lat":0.0,"lon":0.0,"timestamp":0}` (una prueba de escritura, sin
  instante) debe descartarse en lugar de pintarse como un punto en el golfo de Guinea;
- el alias `lon`, que el backend ya acepta y que el visor descartaba en silencio;
- los centinelas `-1` del contrato (`-1` = magnitud desconocida) en velocidad y rumbo;
- un instante en segundos, que debe normalizarse a milisegundos como hace el backend.

    python -m unittest tests.test_frontend_adapter -v
"""

from __future__ import annotations

import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
VIEWER = ROOT / "frontend" / "index.html"

INLINE_SCRIPT = re.compile(r"<script(?![^>]*\bsrc=)[^>]*>(.*?)</script>", re.DOTALL | re.IGNORECASE)
# Desde la primera ayuda del adaptador hasta el sondeo del Gist, que marca el final del bloque.
ADAPTER_BLOCK = re.compile(r"(function firstNumber\(.*?)(?=\n  function pollGist\()", re.DOTALL)
# La lectura de la traza filtrada de una fila compacta vive en la sección de ingesta, y el adaptador
# la usa: se extrae igual, hasta la función siguiente.
SMOOTH_BLOCK = re.compile(r"(function smoothFromRows\(.*?)(?=\n  function ingest\()", re.DOTALL)

# Polyfills del arnés: el visor corre en navegadores (y en node), donde `trim` e `indexOf` existen
# desde ES5. Están porque un motor antiguo los echa en falta al ejecutar estas pruebas, no porque el
# visor los necesite.
HARNESS_PRELUDE = """
var STALE_MS = 60000;
var MAX_PLAUSIBLE_MPS = 90;
var SMOOTH_NEAR_DEG = 0.01;
if (!String.prototype.trim) { String.prototype.trim = function () { return this.replace(/^ +| +$/g, ""); }; }
if (!Array.prototype.indexOf) { Array.prototype.indexOf = function (v) { for (var i = 0; i < this.length; i++) { if (this[i] === v) { return i; } } return -1; }; }
if (!Date.now) { Date.now = function () { return new Date().getTime(); }; }
// El visor usa los métodos de array de ES5 (`forEach`, `map`, `filter`) igual que el resto del
// fichero; un motor ES3 los echa en falta al ejecutar la prueba, no al abrir la página.
if (!Array.isArray) { Array.isArray = function (v) { return Object.prototype.toString.call(v) === "[object Array]"; }; }
if (!Array.prototype.forEach) { Array.prototype.forEach = function (fn) { for (var i = 0; i < this.length; i++) { fn(this[i], i, this); } }; }
if (!Array.prototype.map) { Array.prototype.map = function (fn) { var out = []; for (var i = 0; i < this.length; i++) { out.push(fn(this[i], i, this)); } return out; }; }
if (!Array.prototype.filter) { Array.prototype.filter = function (fn) { var out = []; for (var i = 0; i < this.length; i++) { if (fn(this[i], i, this)) { out.push(this[i]); } } return out; }; }
if (!Array.prototype.some) { Array.prototype.some = function (fn) { for (var i = 0; i < this.length; i++) { if (fn(this[i], i, this)) { return true; } } return false; }; }
// `JSON` existe en todos los navegadores y en node; un motor ES3 antiguo no lo trae, y el arnés lo
// necesita para ejecutar la lectura del Gist. Es un respaldo del arnés, no del visor.
if (typeof JSON === "undefined") {
  var JSON = { parse: function (text) { return eval("(" + text + ")"); } };
}
"""

HARNESS_ASSERTIONS = r"""
var fails = 0;
function check(name, condition, detail) {
  if (condition) { console.log("PASA  " + name); }
  else { fails++; console.log("FALLA " + name + "  -> " + detail); }
}
function dump(o) {
  var out = [];
  for (var k in o) { if (o.hasOwnProperty(k)) { out.push(k + "=" + String(o[k])); } }
  return out.join(" ");
}

// 1) Objeto suelto sin instante: se descarta (no se inventa la hora ni se pinta en 0,0).
var live = normalizeGistPoint({ lat: 0.0, lon: 0.0, timestamp: 0 });
check("payload sin instante se descarta", live === null, String(live));

// 2) Alias `lon` del esquema plano.
var flat = normalizeGistPoint({ lat: 41.38, lon: 2.17, timestamp: 1758100000000 });
check("alias lon aceptado", flat !== null && flat.lng === 2.17, dump(flat));
check("instante en milisegundos se respeta", flat && flat.ts === 1758100000000, flat && flat.ts);

// 3) Contrato del Gist: location/telemetry/status con centinelas -1.
var doc = normalizeGistPoint({
  location: { latitude: 41.5, longitude: 2.2, speed_mps: -1, bearing_degrees: -1, accuracy_meters: 8.5,
              provider: "gps", smooth_latitude: 41.5001, smooth_longitude: 2.2001 },
  telemetry: { timestamp_ms: 1758100005000, device_id: "android-aaa", battery_level: 88, activity: "WALKING" },
  status: { is_tracking: true }
});
check("centinela -1 en velocidad y rumbo -> desconocido", doc && doc.speed === null && doc.bearing === null, dump(doc));
check("device_id y actividad del contrato", doc && doc.deviceId === "android-aaa" && doc.activity === "WALKING", dump(doc));
check("precision y traza suavizada conservadas", doc && doc.acc === 8.5 && doc.smoothLat === 41.5001, dump(doc));

// 4) Instante en segundos -> milisegundos.
var secs = normalizeGistPoint({ location: { lat: 1, lon: 2 }, telemetry: { timestamp: 1758100000 } });
check("segundos normalizados a milisegundos", secs && secs.ts === 1758100000000, secs && secs.ts);

// 5) Limites: rango de coordenadas, instante obligatorio y -1 legítimo en coordenadas.
check("latitud fuera de rango se rechaza", normalizeGistPoint({ location: { lat: 99, lon: 2 }, telemetry: { timestamp_ms: 1 } }) === null, "aceptada");
check("sin instante se rechaza", normalizeGistPoint({ location: { lat: 41, lon: 2 } }) === null, "aceptada");
check("latitud -1 es valida (el centinela no aplica a coordenadas)",
  normalizeGistPoint({ location: { lat: -1, lng: -1 }, telemetry: { timestamp_ms: 1758100000000 } }) !== null, "descartada");

// 6) Agrupación por dispositivo y forma que exige `ingest` (diez columnas compactas).
var shape = gistToApiShape([
  doc,
  normalizeGistPoint({ location: { latitude: 41.6, longitude: 2.3 }, telemetry: { timestamp_ms: 1758100006000, device_id: "android-aaa", label: "Moto 3" } }),
  normalizeGistPoint({ location: { latitude: 41.0, longitude: 2.0 }, telemetry: { timestamp_ms: 1758100007000, device_id: "android-bbb" } })
]);
check("dos dispositivos agrupados", shape.devices.length === 2, shape.count);
check("filas de diez columnas (la traza filtrada viaja)", shape.devices[0].points[0].length === 10, shape.devices[0].points[0].length);
check("orden cronologico dentro del dispositivo", shape.devices[0].points[0][0] < shape.devices[0].points[1][0], "desordenado");
check("la etiqueta del mas nuevo gana", shape.devices[0].summary.label === "Moto 3", shape.devices[0].summary.label);
check("latest apunta al punto mas reciente", shape.devices[0].latest.timestamp === 1758100006000, shape.devices[0].latest.timestamp);
check("la forma declara ok=true como la API", shape.ok === true && shape.source === "gist", String(shape.ok));

// 7) Estadísticas deducidas en el navegador: ni el Gist ni el fichero de Pages traen el resumen que
// sí calcula la API, y sin esto las dos fuentes mostrarían «rec. 0 m».
var two = [[1758100000000, 41.0000, 2.0000, 3, 0, 10], [1758100060000, 41.0010, 2.0000, 5, 0, 20]];
var simple = summarizeFeed(two, null);
check("0.001 grados de latitud ~111 m", Math.abs(simple.distance_m - 111) <= 2, simple.distance_m);
check("velocidad maxima tomada de las filas", simple.max_speed_mps === 5, simple.max_speed_mps);
check("precision media redondeada", simple.avg_accuracy_m === 15, simple.avg_accuracy_m);
check("sin traza filtrada no hay ruido", simple.noise_removed_m === 0, simple.noise_removed_m);

// Un salto de GPS (0.01 grados en un segundo, ~1100 m/s) no es un desplazamiento.
var jump = [[1758100000000, 41.0, 2.0, 3, 0, 10], [1758100001000, 41.01, 2.0, 3, 0, 10]];
check("salto de GPS no suma distancia", summarizeRows(jump).distance_m === 0, summarizeRows(jump).distance_m);

// Con traza filtrada, la distancia que manda es la suavizada y el ruido es la diferencia.
var noisy = [[1758100000000, 41.0, 2.0, 3, 0, 10], [1758100060000, 41.0010, 2.0, 3, 0, 10],
             [1758100120000, 41.0000, 2.0, 3, 0, 10]];
var smoothed = [[1758100000000, 41.0, 2.0, 3], [1758100120000, 41.0005, 2.0, 3]];
var mixed = summarizeFeed(noisy, smoothed);
check("la distancia filtrada es la del trazo suavizado",
  mixed.distance_m === summarizeRows(smoothed).distance_m, mixed.distance_m);
check("el ruido quitado es bruta menos filtrada",
  mixed.noise_removed_m === mixed.raw_distance_m - mixed.distance_m, mixed.noise_removed_m);
check("el ruido es mayor que cero cuando el filtro trabaja", mixed.noise_removed_m > 0, mixed.noise_removed_m);

// 8) La traza filtrada de una fila compacta: solo se acepta con sus columnas 9 y 10 y con cada punto
//    suavizado cerca de la medida que lo alimenta.
var compactRows = [[1758100000000, 41.0, 2.0, 3, 0, 10, -1, "", 41.0001, 2.0001],
                   [1758100060000, 41.001, 2.0, 3, 0, 10, -1, "", 41.0011, 2.0001]];
check("filas de diez columnas dan traza filtrada", smoothFromRows(compactRows).length === 2, smoothFromRows(compactRows).length);
check("las seis columnas de la API no inventan traza suavizada",
  smoothFromRows([[1758100000000, 41.0, 2.0, 3, 0, 10]]).length === 0, "inventada");
check("un punto suavizado a mas de 1 km de su medida se descarta",
  smoothFromRows([[1758100000000, 41.0, 2.0, 3, 0, 10, -1, "", 41.5, 2.0]]).length === 0, "aceptado");

// 9) Distancia por día natural: lo que el móvil mide en su propia tarjeta y lo que el visor deduce del
// histórico cargado. Los tres orígenes pasan por aquí, así que el panel del día funciona sin servidor.
var nowMs = Date.now();
var todayNoon = new Date(nowMs); todayNoon.setHours(12, 0, 0, 0);
var yesterdayNoon = new Date(todayNoon.getTime()); yesterdayNoon.setDate(yesterdayNoon.getDate() - 1);
var rowsToday = [[todayNoon.getTime(), 41.0000, 2.0000, 3, 0, 10],
                 [todayNoon.getTime() + 60000, 41.0010, 2.0000, 5, 0, 20]];
var rowsYesterday = [[yesterdayNoon.getTime(), 41.0000, 2.0000, 3, 0, 10],
                     [yesterdayNoon.getTime() + 60000, 41.0005, 2.0000, 3, 0, 10]];
var dayList = summarizeDays([{ id: "x", pts: rowsToday.concat(rowsYesterday), smoothPts: [] }], 7, nowMs);
var today = dayList[dayList.length - 1];
check("la ventana del día a día trae siete días", dayList.length === 7, dayList.length);
check("el último de la ventana es hoy", today.key === dayKey(nowMs), today.key);
check("hoy se etiqueta como hoy", today.label === "hoy", today.label);
check("ayer se etiqueta como ayer", dayList[dayList.length - 2].label === "ayer", dayList[dayList.length - 2].label);
check("0.001 grados de hoy ~111 m", Math.abs(today.distance_m - 111) <= 2, today.distance_m);
check("los puntos de ayer no se cuelan en hoy", today.points === 2, today.points);
check("ayer conserva su propia medida", Math.abs(dayList[dayList.length - 2].distance_m - 56) <= 2, dayList[dayList.length - 2].distance_m);
check("un día sin datos queda a cero", dayList[0].points === 0 && dayList[0].distance_m === 0, dayList[0].points);
var jumpedDay = { id: "y", pts: [[todayNoon.getTime(), 41.0, 2.0, 3, 0, 10],
                                [todayNoon.getTime() + 1000, 41.01, 2.0, 3, 0, 10]], smoothPts: [] };
check("un salto de GPS no engorda el día", summarizeDays([jumpedDay], 7, nowMs)[6].distance_m === 0,
  summarizeDays([jumpedDay], 7, nowMs)[6].distance_m);
var filteredDay = { id: "z", pts: rowsToday,
                    smoothPts: [[todayNoon.getTime(), 41.0000, 2.0000, 3],
                               [todayNoon.getTime() + 60000, 41.0005, 2.0000, 3]] };
check("el día usa la traza filtrada cuando existe",
  summarizeDays([filteredDay], 7, nowMs)[6].distance_m === summarizeRows(filteredDay.smoothPts).distance_m,
  summarizeDays([filteredDay], 7, nowMs)[6].distance_m);
check("una fila sin instante no crea un día",
  summarizeDays([{ id: "w", pts: [[0, 41.0, 2.0, 3, 0, 10]], smoothPts: [] }], 7, nowMs)[6].points === 0, "contada");

// 10) Día a día exportable y por dispositivo. El núcleo `collectDays` suma **cada dispositivo por
// separado**: si mezclara sus filas, el tramo entre el último punto de un teléfono y el primero de
// otro sería un salto inventado de miles de kilómetros.
var pairA = { id: "a", pts: [[todayNoon.getTime(), 41.0, 2.0, 3, 0, 10],
                             [todayNoon.getTime() + 60000, 41.001, 2.0, 3, 0, 10]], smoothPts: [] };
var pairB = { id: "b", pts: [[todayNoon.getTime() + 30000, 10.0, 20.0, 3, 0, 10]], smoothPts: [] };
var todayKey = dayKey(todayNoon.getTime());
check("dos dispositivos lejanos no inventan un tramo",
  collectDays([pairA, pairB])[todayKey].distance_m === summarizeRows(pairA.pts).distance_m,
  collectDays([pairA, pairB])[todayKey].distance_m);
check("los puntos se cuentan de los dos dispositivos",
  collectDays([pairA, pairB])[todayKey].points === 3, collectDays([pairA, pairB])[todayKey].points);
var history = allDaysSummary([pairA, { id: "c", pts: rowsYesterday, smoothPts: [] }]);
check("el histórico exportable va del más antiguo al más reciente",
  history.length === 2 && history[0].key < history[1].key, history.length);
check("el CSV del día a día tiene su cabecera",
  daysCsvText(history).split("\n")[0] === "fecha,distancia_m,distancia_km,puntos", daysCsvText(history).split("\n")[0]);
check("una fila por día con sus kilómetros",
  daysCsvText(history).split("\n").length === 4, daysCsvText(history).split("\n").length);
check("sin días con datos no hay CSV que descargar", daysCsvText([]) === "", daysCsvText([]));
check("la tarjeta del dispositivo dice lo de hoy",
  deviceTodayMeters(pairA, nowMs) === summarizeRows(pairA.pts).distance_m, deviceTodayMeters(pairA, nowMs));
check("un dispositivo que hoy no se movió marca cero",
  deviceTodayMeters({ id: "d", pts: rowsYesterday, smoothPts: [] }, nowMs) === 0,
  deviceTodayMeters({ id: "d", pts: rowsYesterday, smoothPts: [] }, nowMs));

// 11) El Gist informa de lo que descarta: un fichero leído bien sin nada pintable no es «aún no hay
// datos», y ese silencio ya costó una tarde una vez.
var counts = {};
// JSON literal y no `JSON.stringify`: así la prueba fija el texto exacto que llega del Gist.
var mixedText = '[{"location":{"lat":41,"lon":2},"telemetry":{"timestamp_ms":1758100000000}},' +
                '{"location":{"lat":0,"lon":0},"timestamp":0}]';
var mixedList = parseGistPayload(mixedText, counts);
check("el Gist cuenta los puntos válidos", mixedList.length === 1 && counts.valid === 1, dump(counts));
check("el Gist cuenta los descartados", counts.discarded === 1 && counts.total === 2, dump(counts));

// 12) El CSV entrecomilla lo que lo necesita y solo lo que lo necesita.
check("una celda con coma se entrecomilla", csvCell("Sant, Feliu") === '"Sant, Feliu"', csvCell("Sant, Feliu"));
check("una comilla se dobla dentro de las comillas", csvCell('di"jo') === '"di""jo"', csvCell('di"jo'));
check("un numero no se entrecomilla", csvCell(12) === "12", csvCell(12));
check("nulo es celda vacia, no la cadena 'null'", csvCell(null) === "" && csvCell(undefined) === "", csvCell(null));
check("un salto de linea obliga a entrecomillar", csvCell("a\nb") === '"a\nb"', "sin comillas");

// 13) El nucleo del dia es uno solo: por tarjeta (cubos de un dispositivo), sumado (panel) y con la
//     ventana ya calculada. Antes se recalculaba entero por dispositivo y otra vez para el panel.
var perDevice = collectDaysByDevice([pairA, pairB]);
check("los cubos por dispositivo no mezclan telefonos",
  perDevice["a"][todayKey].distance_m === summarizeRows(pairA.pts).distance_m
  && perDevice["b"][todayKey].distance_m === 0, dump(perDevice["b"][todayKey]));
check("sumar los cubos da lo mismo que collectDays",
  mergeDays(perDevice)[todayKey].distance_m === collectDays([pairA, pairB])[todayKey].distance_m
  && mergeDays(perDevice)[todayKey].points === collectDays([pairA, pairB])[todayKey].points,
  mergeDays(perDevice)[todayKey].distance_m);
check("summarizeDays es la ventana de los cubos ya calculados",
  summarizeDays([pairA], 7, nowMs)[6].distance_m === dailyWindow(daysOfDevice(pairA), 7, nowMs)[6].distance_m,
  summarizeDays([pairA], 7, nowMs)[6].distance_m);
check("sin cubos la ventana sigue teniendo siete dias a cero",
  dailyWindow({}, 7, nowMs).length === 7 && dailyWindow({}, 7, nowMs)[0].points === 0, "no");

console.log(fails ? ("RESULTADO: " + fails + " fallo(s)") : "RESULTADO: todo correcto");
"""


def viewer_source() -> str:
    """Las funciones del visor tal y como están en el fichero (no una copia)."""
    html = VIEWER.read_text(encoding="utf-8")
    scripts = INLINE_SCRIPT.findall(html)
    if not scripts:
        raise AssertionError(f"{VIEWER} no tiene JavaScript embebido")
    blocks = []
    for name, pattern, marker in (
        ("adaptador", ADAPTER_BLOCK, "firstNumber / pollGist"),
        ("traza filtrada", SMOOTH_BLOCK, "smoothFromRows / ingest"),
    ):
        match = None
        for script in scripts:
            match = pattern.search(script)
            if match:
                break
        if match is None:
            raise AssertionError(
                f"no se encontró el bloque de {name} en el visor: si se renombraron "
                f"{marker}, actualiza el patrón de esta prueba"
            )
        blocks.append(match.group(1))
    return "\n\n".join(blocks)


class GistAdapterTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.node = shutil.which("node")

    def test_contract(self) -> None:
        if self.node is None:
            self.skipTest("node no está instalado: se omite la prueba de comportamiento del visor")

        harness = HARNESS_PRELUDE + viewer_source() + HARNESS_ASSERTIONS
        with tempfile.TemporaryDirectory(prefix="telemetria-adapter-") as tmp:
            target = Path(tmp) / "adapter.js"
            target.write_text(harness, encoding="utf-8")
            result = subprocess.run(
                [self.node, str(target)], capture_output=True, text=True, check=False
            )

        report = (result.stdout or "") + (result.stderr or "")
        self.assertEqual(result.returncode, 0, f"el arnés no terminó bien:\n{report}")
        self.assertNotIn("FALLA", report, report)
        self.assertIn("RESULTADO: todo correcto", report, report)


if __name__ == "__main__":
    unittest.main()
