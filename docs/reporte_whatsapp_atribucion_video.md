# Reporte: WhatsApp Status no muestra la atribución "Ray-Ban Meta" en videos (sí en fotos)

## Resumen
Al publicar en el Estado de WhatsApp una foto capturada con Ray-Ban Meta Smart Glasses 2, el estado muestra correctamente la insignia de atribución "Ray-Ban Meta". Al publicar un **video** capturado con el mismo dispositivo, la insignia **no aparece**, para ningún espectador (confirmado viendo el estado desde un iPhone también).

## Entorno
- Dispositivo: Google Pixel 9 Pro XL (Android 17)
- WhatsApp: 2.26.36.74
- App de captura: Meta AI / Ray-Ban Meta Smart Glasses 2 (paquete `com.facebook.stella`)

## Pasos para reproducir
1. Capturar una foto y un video con Ray-Ban Meta Smart Glasses 2.
2. Publicar la foto en el Estado de WhatsApp (desde la app de Meta AI, desde la galería, o desde el selector de WhatsApp — el resultado es el mismo). La insignia "Ray-Ban Meta" aparece.
3. Publicar el video en el Estado de WhatsApp por cualquiera de esas mismas vías. La insignia no aparece, ni siquiera al ver el estado desde otro dispositivo (iPhone).

## Comportamiento esperado
El video debería mostrar la misma insignia de atribución "Ray-Ban Meta" que muestra la foto, ya que proviene del mismo dispositivo y ambos archivos incluyen metadatos equivalentes identificando el origen (Make/Model en EXIF para la foto; claves `comment`/`copyright`/`composer` con `app=Meta AI&device=Ray-Ban Meta Smart Glasses 2` en el contenedor MP4 para el video).

## Comportamiento actual y evidencia técnica
Se instrumentó el log interno de WhatsApp (`/data/data/com.whatsapp/files/Logs/whatsapp.log`, dispositivo rooteado) capturando el diff exacto generado al publicar cada tipo de medio.

**Al publicar la FOTO**, la secuencia incluye:
```
SendUseCase/sendMedia/type = 1
SendUseCase/sendImage
PeripheralUserEngagementLogger/logUserEngagement: WamPeripheralUserEngagement {engagementCount=1, envelopeStyle=3, envelopeType=photo, peripheralLinkedProductLine=7, sgDeviceType=3, sgProductLine=7, sharingDestination=2, sharingOrigin=2, }
Cross-app communication detected for operation 'QUERY' and authority 'com.facebook.stella.assistant.deviceconnectionstate'
```
Y al reproducir el estado publicado:
```
attributions count = 1, class type: 7BO
```

**Al publicar el VIDEO**, la secuencia equivalente es:
```
SendUseCase/sendMedia/type = 3
UserActionsMediaMessageSending/userActionSendMediaMessages
SendMediaMessageManager/enqueueMediaUpload
```
Sin ningún llamado a `PeripheralUserEngagementLogger` ni ninguna consulta cross-app a `com.facebook.stella`. Al reproducir el estado publicado:
```
attributions count = 0, class type: 7BM
```

Esto indica que la ruta de envío de video (`type=3`) en la app de WhatsApp para Android **no invoca la lógica de clasificación de dispositivo periférico** (`PeripheralUserEngagementLogger` + consulta a la app de Meta AI vía `deviceconnectionstate`) que sí invoca la ruta de envío de foto (`type=1`). No parece ser un problema de metadatos del archivo ni de la app de Meta AI: aun agregando manualmente Make/Model equivalentes al contenedor MP4 del video, el comportamiento no cambia, consistente con que esa rama de código simplemente no se ejecuta para video.

Adicionalmente, se revisó la configuración remota cacheada en el dispositivo (`ab-props.xml`, `network_resources_pref.xml`): existe un ícono ya descargado para la insignia de lentes (`downloadstate/sup_glasses_icon`) y cadenas de texto para "Meta Ray-Ban Display Glasses", lo que sugiere que la infraestructura de atribución de periféricos está activa del lado del servidor — el video simplemente no está conectado a ella en el cliente.

## Causa raíz a nivel de código (confirmado por ingeniería inversa del APK)

Decompilando `com.whatsapp` versión 2.26.36.74 se ubicó la clase responsable (ofuscada como `X.C175477nH` en este build). Contiene dos métodos:

- **Ruta de foto** (`A00`): extrae el tag EXIF `Model` directamente con un parser EXIF estándar.
- **Ruta de video** (`A01`): parsea manualmente la estructura de cajas MP4, navegando `moov → udta → meta → hdlr(tipo "mdta") → ilst`, y busca específicamente un átomo hijo llamado **`©cmt`** (convención clásica de iTunes/QuickTime, donde cada hijo de `ilst` es un átomo tipado por su propio FourCC, ej. `©cmt`). Dentro de ese átomo busca la subcadena `device=...`.

El log confirma exactamente dónde falla para el video de los lentes:
```
MediaIdentification/udta box not found
MediaMetadataUtils/retrieving metadata tags failed
```

Es decir, el parser casero de WhatsApp **no logra localizar la caja `udta` dentro de `moov`** en el archivo generado por Ray-Ban Meta Smart Glasses 2 (aunque el archivo sí contiene una caja `udta`, según análisis independiente con MediaInfo/ffprobe — con una clave `©xyz` de ubicación). Al no encontrar `udta`, nunca llega a leer `meta`/`ilst`.

Adicionalmente, aunque llegara a encontrar `udta`/`meta`/`ilst`, este parser **solo reconoce el esquema clásico de iTunes** (hijos de `ilst` tipados por FourCC como `©cmt`) y no el esquema moderno de **`keys` + `ilst` indexado por número** que usan los archivos de Ray-Ban Meta (donde el nombre real de la clave, ej. `comment`, vive en una caja `keys` separada que este código nunca lee). Por lo tanto habría un segundo punto de incompatibilidad incluso si se resolviera el primero.

**Conclusión:** no es una funcionalidad ausente para video en general, sino un parser de metadatos MP4 escrito a mano en el cliente de WhatsApp que no es compatible con la disposición/esquema de cajas que usan los videos de Ray-Ban Meta Smart Glasses 2.

## Impacto
Los usuarios de Ray-Ban Meta Smart Glasses 2 (y presumiblemente otros modelos) no reciben el crédito/atribución visual esperado al compartir video capturado con los lentes en WhatsApp Status, aunque sí lo reciben con fotos del mismo dispositivo.
