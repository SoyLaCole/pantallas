# Pantallas La Cole para televisores LG (webOS)

Reproductor para TV LG con webOS (diseñado para webOS 6.x, p. ej. el modelo 70UP7500PSC). Hace lo mismo que la app de Android:
se registra en el panel con un código, el panel lo aprueba y reproduce el contenido programado (video, imagen, web),
con la orientación elegida en el panel. Habla con el mismo servidor (`https://pantallas.lacole.edu.co`).

Versión actual: **1.2.0**. Probada en un LG 70UP7500PSC (webOS 6.5.3, firmware 03.53.45): se instala, se registra, muestra imágenes y videos.

## Archivos
- `appinfo.json` — datos de la app (id `co.edu.lacole.pantallas`, versión).
- `index.html`, `style.css`, `app.js` — la aplicación.
- `icon.png`, `largeIcon.png` — iconos.
- `../webos-ipk/` — instalador `.ipk` ya construido.
- El servidor publica este mismo reproductor en `https://pantallas.lacole.edu.co/player/` (sirve para abrirlo desde el navegador de un TV sin instalar nada).

## Qué hace
- **Copia local:** descarga los videos e imágenes al almacenamiento del TV (IndexedDB, por trozos de 4 MB) y los reproduce desde ahí; si no hay espacio suficiente (deja 300 MB libres) o falla la descarga, reproduce por internet. Elimina lo que ya no está programado.
- **Páginas web:** el servidor (`/tv/<id>/embed/<contenido>`) entrega una copia sin la restricción de «no mostrar en marco» cuando el sitio la tiene. No funciona con sitios que se basan en su propio dominio para cargar datos.
- **IP y modelo:** se envían al panel (IP de la red local, modelo del TV) mediante servicios internos de webOS; si el TV no los entrega, simplemente no aparecen.
- Botón OK del control: muestra la información de la pantalla por 6 segundos.

## Lo aprendido en la primera instalación real (octubre 2026)
- **Videos: máximo 720p, H.264 Main nivel 3.1.** Un video 1080p (nivel 4.0) se quedó en negro; el mismo contenido en 720p funcionó. Convertir con: `ffmpeg -i entrada.mp4 -vf scale=1280:720 -c:v libx264 -profile:v main -level 3.1 -crf 22 -an -movflags +faststart salida.mp4`. Las imágenes no tienen este límite.
- El servidor ya mueve el índice (moov) del MP4 al inicio al subirlo (`server/faststart.py`).
- **Se abre a mano:** al encender el TV no abre la app sola (ni siquiera con «última entrada»). Alguien debe abrirla cada día.
- **App Developer Mode:** en LG Content Store (país Colombia) aparece al buscarla por nombre; hay que iniciar sesión con la cuenta de la página de desarrolladores de LG (distinta de la cuenta de servicios del TV). Con el modo desarrollador activo la sesión dura unas 999 horas; hay que pulsar «Extend» antes de que venza, o la app se desinstala.
- Dos registros distintos en el panel si se abren la app y el reproductor del navegador (`/player/`): son pantallas independientes.

## Limitaciones que siguen existiendo
- **No se abre sola al encender el TV** (probado: no existe ajuste que lo haga). Opciones: que alguien la abra, o programar `ares-launch` desde un PC de la red tras el temporizador de encendido del TV.
- **Modo desarrollador caduca:** hay que abrir cada cierto tiempo la app *Developer Mode* del TV y pulsar «Extend Time». No existe (en la herramienta actual) un comando para hacerlo desde el PC. La solución definitiva sería publicar la app en LG Content Store.
- Las páginas web «interactivas» y algunos sitios no se muestran igual que en Android.
- Videos muy grandes pueden no caber en el almacenamiento del TV (el 70UP7500PSC tiene unos 2 GB).

## Construir el instalador
```
npm install -g @webos-tools/cli
ares-package webos -o webos-ipk
```
Al cambiar el código, subir el número de `version` en `appinfo.json`. Después de cambiar `webos/`, subir los cambios a GitHub y hacer *Redeploy* en Coolify (el servidor también publica una copia en `/player/`).

## Instalar en un TV
1. En el TV: instalar la app **Developer Mode** desde LG Content Store, abrirla, iniciar sesión con una cuenta LG Developer y activar «Dev Mode Status» y «Key Server».
2. En el PC (con Node.js y `npm install -g @webos-tools/cli`):
   - `ares-setup-device -a tv -i "host=IP_DEL_TV" -i "port=9922" -i "username=prisoner"`
   - `ares-novacom --device tv --getkey` (pide la clave que muestra la app Developer Mode)
   - `ares-install --device tv webos-ipk\co.edu.lacole.pantallas_1.2.0_all.ipk`
   - `ares-launch --device tv co.edu.lacole.pantallas`
3. Aprobar la pantalla en el panel.
