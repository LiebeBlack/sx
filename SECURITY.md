# Seguridad y privacidad

## Qué datos trata

Coordenadas de alta precisión, huellas de redes Wi-Fi (SSID y BSSID), identificadores de celda,
actividad física, estado de batería y modelo del dispositivo. Con `SSID`/`BSSID` y `CID`/`TAC` es
posible geolocalizar a una persona sin GPS: trátalo como dato personal de categoría alta.

## Medidas implementadas

- Autenticación opcional por `X-Api-Key` con comparación en tiempo constante (`hmac.compare_digest`).
- Validación estricta de rangos y tipos; nada de datos del cliente sin sanear.
- El almacén es un JSONL local, sin exposición pública: queda fuera del control de versiones.
- Contenedor Docker con usuario sin privilegios y volumen de datos dedicado.
- En el cliente, `HttpURLConnection` con timeouts y cola acotada: no se filtra ni se pierde en silencio.

## Recomendaciones de despliegue

1. Pon el backend detrás de HTTPS (nginx, Caddy o Cloudflare Tunnel). Sin TLS, la ubicación viaja en claro.
2. Define `TELEMETRY_API_KEY` larga y aleatoria, y sirve el visualizador desde el mismo origen o
   restringe `Access-Control-Allow-Origin` en `_cors()` si no quieres que cualquier web lea la API.
3. No expongas el puerto a Internet sin proxy inverso con límite de tasa.
4. Rota y borra los datos con `DELETE /api/locations/<device_id>` cuando termine el uso previsto.

## Alcance legal

Rastrear a una persona sin su consentimiento informado es ilegal en la mayoría de jurisdicciones
(UE: RGPD y LOPDGDD; España: art. 197 del Código Penal). El administrador de dispositivo y el
servicio de accesibilidad son permisos sensibles y deben usarse solo en dispositivos propios,
de tu organización o con autorización por escrito.

## Informar de una vulnerabilidad

No abras un issue público con detalles explotables. Describe el hallazgo, el impacto y los pasos
de reproducción en un canal privado con el responsable del repositorio; se responderá con una
evaluación y un plan de corrección.
