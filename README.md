# Golden Dragon XML6609 · Dashboard

Panel para la radio Android de la furgoneta escolar. Muestra el modelo 3D, la telemetría OBD-II,
el GPS, la autonomía, el registro de mantenimientos y el asistente J.A.R.V.I.S.

## Instalación en la radio (Termux)

```bash
bash setup.sh
```

Luego abra `http://localhost:8080` en el navegador de la radio y deje Termux abierto.

## Leer el vehículo (OBD-II)

1. Conecte un adaptador **ELM327 Wi-Fi** al puerto OBD-II.
2. Conecte la radio a la red Wi-Fi del adaptador (suele llamarse `WiFi_OBDII`).
3. Ejecute `bash setup.sh`. El servidor `bridge.py` lee el adaptador en `192.168.0.10:35000`.
   Si su adaptador usa otra dirección: `ELM_HOST=192.168.4.1 ELM_PORT=35000 bash setup.sh`.

En **Ajustes → OBD-II** se elige la fuente de datos (adaptador, simulación de demostración o
desactivado) y la capacidad del tanque, que se usa para calcular la autonomía.

Si un valor aparece como `--`, significa que no hay conexión o que el vehículo no reporta ese dato.

## Sin internet

Las librerías (three.js, Leaflet, Lucide, fuentes y CSS) están en `vendor/` y funcionan sin conexión.
Solo necesitan internet los mapas, el clima y J.A.R.V.I.S.

## Desarrollo

`vendor/tailwind.css` está precompilado. Si cambia clases en `index.html`, regénerelo con:

```bash
npx tailwindcss@3.4.16 -i tailwind.in.css -o vendor/tailwind.css --minify
```
