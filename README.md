# RayBan Fix

Hace que los **videos** grabados con los lentes Ray-Ban Meta muestren la insignia «Ray-Ban Meta» al publicarlos en un estado de WhatsApp, igual que ya pasa con las fotos.

Incluye dos formas de hacerlo:

- **App de Android** (`src/`, `res/`): elige un video o compártelo desde la galería con «Agregar etiqueta Ray-Ban Meta» y se reenvía ya arreglado. No necesita root.
- **Script de Python** (`python/`): arregla un archivo MP4 en la PC.

## El problema

Con una foto de los lentes, WhatsApp lee el `Model` del EXIF y muestra la insignia. Con un video no aparece nunca, para ningún espectador.

La causa (detalle en [docs/reporte_whatsapp_atribucion_video.md](docs/reporte_whatsapp_atribucion_video.md), analizado en WhatsApp 2.26.36.74): para los videos, WhatsApp recorre a mano las cajas del MP4 y solo busca este camino, al estilo clásico de iTunes:

```
moov → udta → meta (handler mdir/appl) → ilst → ©cmt → "…device=<modelo>…"
```

Los videos actuales de los lentes no traen esa caja `udta`. Guardan los mismos datos (`app=Meta AI&device=Ray-Ban Meta Smart Glasses 2`) con el esquema moderno `keys` + `ilst` indexado, que ese lector no entiende. Por eso la insignia nunca se activa.

## La solución

Se agrega al `moov` una caja `udta` con el comentario `©cmt` que WhatsApp espera. El espacio sale de la caja `free` que los lentes dejan justo después de `moov`, así que **no se mueve ni un byte de `mdat`** ni hay que tocar las tablas de offsets: el archivo queda del mismo tamaño y el video es idéntico.

```
antes:   ftyp | moov            | free (relleno) | mdat
después: ftyp | moov + udta     | free (menor)   | mdat   ← mismo offset
```

Para videos que ya pasaron por WhatsApp, Instagram, Telegram u otra app (que borran los metadatos de los lentes y casi nunca dejan una caja `free`), la app reescribe el `moov`, mueve lo que va detrás y actualiza los offsets de cada pista (`stco`/`co64`). En ese caso el modelo de los lentes lo eliges tú.

## App de Android

### Instalarla

1. Descarga `RayBanFix-x.y.apk` de la página de [Releases](../../releases).
2. Ábrelo en el teléfono y permite instalar apps de esa fuente. Requiere Android 10 o superior.

### Usarla

**Desde la galería (lo más rápido):**

1. Abre un video de los lentes en Google Fotos o en la galería.
2. Compartir → **Agregar etiqueta Ray-Ban Meta**.
3. Se abre el menú de compartir con el video ya arreglado: elige WhatsApp → Mi estado.

**Desde la app:**

- **Elegir video**: arregla un video grabado con los lentes. Queda en la lista como acceso directo al original, para volver a compartirlo en un toque.
- **Agregar video de otra app**: para la copia de un video de los lentes que ya pasó por otra app. Confirma el modelo (por defecto `Ray-Ban Meta Smart Glasses 2`) y se le vuelven a poner los metadatos.
- **Opciones → Guardar también una copia**: además del acceso directo, guarda el video arreglado en `Movies/RayBanFix`.

Solo funciona con videos que de verdad grabaste con los lentes: si el video no trae `device=…`, la app lo dice y no lo toca (salvo en «Agregar video de otra app», donde el modelo lo confirmas tú).

### Compilarla

No usa Gradle: `build.ps1` llama directamente a `aapt2`, `javac`, `d8`, `zipalign` y `apksigner`.

Requisitos: Android SDK con `platforms/android-36` y `build-tools/37.0.0`, y un JDK (por defecto el `jbr` de Android Studio).

```powershell
.\build.ps1
# APK listo: build\RayBanFix.apk

# Con otras rutas o versión de build-tools:
$env:ANDROID_HOME = 'D:\Android\Sdk'; $env:BUILD_TOOLS = '36.0.0'; $env:JAVA_HOME = 'C:\jdk-21'
.\build.ps1
```

La primera vez crea una clave de firma local (`raybanfix.keystore`), que no se sube al repositorio.

## Script de Python

Necesita Python 3.8 o superior, sin dependencias.

```bash
python python/raybanmeta_whatsapp_video_fix.py entrada.mp4 salida.mp4
```

Salida de ejemplo:

```
OK: salida.mp4 escrito (5276 bytes, igual al original).
    mdat permanece en el offset 1172 sin cambios.
    Se agrego udta/meta/hdlr/ilst/©cmt con device='Ray-Ban Meta Smart Glasses 2' (132 bytes).
```

Con otro modelo de lentes, pásalo como tercer argumento:

```bash
python python/raybanmeta_whatsapp_video_fix.py entrada.mp4 salida.mp4 "Ray-Ban Meta Smart Glasses"
```

El script es la versión mínima del arreglo: solo funciona con el video original de los lentes (necesita la caja `free` después de `moov`) y comprueba al final que `mdat` no se movió. Para copias que pasaron por otras apps, usa la app.

## Limitaciones

- MP4 fragmentado o de más de 4 GB: no soportado.
- Video de los lentes editado por otra app con el `moov` al final: no soportado en «Elegir video»; usa «Agregar video de otra app».
- Depende de cómo lee WhatsApp los metadatos; si cambia ese lector, el arreglo puede dejar de hacer falta o de funcionar.

## Estructura

- `src/com/elcesarmaat/raybanfix/`: `Mp4Patcher` (arreglo sin mover `mdat`), `StrippedVideoPatcher` (videos de otras apps), `FixActivity` (Compartir), `MainActivity` (lista y accesos directos).
- `res/`: interfaz (Material, tema claro y oscuro).
- `python/`: script de línea de comandos.
- `docs/`: reporte técnico del fallo de WhatsApp.

## Licencia

[MIT](LICENSE). Ray-Ban, Meta y WhatsApp son marcas de sus respectivos dueños; este proyecto no está afiliado a ellos.
