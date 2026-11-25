<div style="text-align: center;">
<h1>CoruñaBus </h1>
<p>La app de los buses creada por quien usa el bus diariamente. Lo que necesitas, lo tiene </p>
<img src="public/logo.png" width="auto" height="50px"> 

---
</div>


CoruñaBus es una aplicación para los buses urbanos de La Coruña que desarrollé tras la experiencia con la app oficial (imposible de usar) y la no oficial (BusCoruña, con anuncios por doquier y sin o que necesitaba).  
Esta aplicación, disponible tanto en web, PWA como en aplicacion nativa para Android (ya que en el bus nadie usa iPhone), ofrece una gran cantidad de funciones para hacer más sencillo el uso de los buses. Listadas a continuación:

## Funciones

Junto con las funciones habituales de una app de paradas (mostrar tiempos, lineas, paradas, etc...), incluye adicionalmente:  

- **Planificador de rutas**: en el cual indicas de donde a dónde quieres ir, la hora de llegada (o lo dejas en blanco) y te indica qué lineas cojer y que bus concreto, permitiéndote acceder a su parada y añadir un aviso de llegada del bus

- **Avisos llegada de bus**: Te avisa cuando queda X minutos para la llegada de bus a la parada (el tiempo de antelación que hayas configurado) y detecta mediante ubicacion si te encuentras en una de tus ubicaciones preconfiguradas las cuales añaden tiempo extra de antelación que tu hayas establecido según la ubicación (por ejemplo, si estoy en casa tardo 3 minutos en llegar a la parada)

- **Cálculo de hora de llegada a destino + tiempo restante**: Calcula cuanto queda para que llegues al destino y a qué hora llegarás (perfecto para que puedas avisar si llegas tarde, el cálculo es muy bueno)

- **Notificación de avisar de parada**: La aplicación detecta tu ubicación durante el trayecto y emite un sonido para que puedas avisar de parada para que el bus pare y puedas bajarte. Nunca más te olvidarás de avisar.

- **Notificaciones en pantalla de bloqueo**: Para que no tengas que desbloquear el telefono para ver la información

- **Detector de paradas cercanas**: Incluye un buscador de paradas que te muestra automaticamente las paradas cercanas a tu ubicación y te las situúa en un mapa

- **Nombres personalizados para paradas favoritas**: Añade un nombre descriptivo a tus paradas favoritas

- **Presets de cálculo de tiempo**: Calcula cada día el bus que debes cojer con datos reales y sin tener que rellenar el formulario cada vez.

- **Mapa satelital de paradas + Street View**: Para que sepas llegar a una parada qu eno te es conocida

- **Noticias locales + tiempo y temperatura** Para que te entretengas durante el trayecto

- **Actualizador integrado y compatibilidad con Obtainium**: Próximamente en la PLay Store (cuando cumpla 18 años, jeje)

- **Español e inglés**: Para que los extranjeros tengan una app que usar que esté en el idioma internacional y que no sea la incómoda oficial
---

## Especificaciones y análisis de la API

Para desarrollar esta aplicación se ha analizado de forma exhaustiva la API abierta de iTranvías. Toda la información se encuentra en [/research](/research).

## Tecnologías

- **Astro**: framework web orientado a rendimiento.
- **TypeScript**: robustez y seguridad en el código.
- **Leaflet**: mapas interactivos para visualizar paradas y rutas.
- **CSS**: estilos personalizados sin frameworks pesados.
- **iTranvías API**: integración con el sistema oficial de transporte de A Coruña.
- **Capacitor**: empaquetado de la app para Android.
- **Cloudflare Workers**: despliegue de la web en el edge.

## Instalación y desarrollo

1. Clonar el repositorio y entrar en el directorio:

   ```bash
   git clone <repositorio>/corunabus.git
   cd corunabus
   ```

2. Instalar dependencias:

   ```bash
   npm install
   ```

3. Iniciar el servidor de desarrollo:

   ```bash
   npm run dev
   ```

   La web queda disponible en `http://localhost:4321`.

4. Ejecutar los tests:

   ```bash
   npm test
   ```

5. Verificar tipos y construir:

   ```bash
   npm run astro check
   npm run build
   ```

## Despliegue

### Web (PWA) en Cloudflare Workers

El proyecto se despliega en el worker de Cloudflare llamado `buscoruna`:

```bash
npm run deploy:web
```

Este comando construye la web y la sube con Wrangler. La configuración de despliegue está en `wrangler.toml` y en `dist/server/wrangler.json`, donde se enlaza el namespace de KV `buscoruna-session` (binding `SESSION`) usado por las sesiones de Astro. El dominio de producción es `https://xn--coruabus-g3a.inled.es`.

### App Android (APK)

Con Capacitor se genera el APK de la app; el `server.url` de `capacitor.config.json` decide de dónde carga el contenido la app.

- **APK de producción** (apunta a `https://xn--coruabus-g3a.inled.es`):

  ```bash
  npm run app:prod
  ```

  Compila, sincroniza Android, genera `android/app/build/outputs/apk/debug/app-debug.apk`, lo copia a `~/Descargas/coruna-bus.apk` y, si hay un dispositivo conectado por ADB, lo instala y lo abre.

- **APK de desarrollo** (apunta a `http://localhost:4321`):

  ```bash
  npm run app:local
  ```

  Además configura el túnel ADB reverse del puerto `4321` para probar contra el servidor de desarrollo local.

#### Releases automáticas del APK (GitHub Actions)

El workflow `.github/workflows/build-apk.yml` solo construye el APK y crea una release de GitHub (la que la app consulta para auto-actualizarse) cuando un commit de `main` contiene `[RELEASE]` en su mensaje. Un commit normal **no** genera versión nueva ni APK.

- `[RELEASE]` → sube la versión de patch (`0.0.3` → `0.0.4`).
- `[RELEASE][1.2]` → fija la versión `1.2.0` (o usa `[RELEASE][2.3.4]` para una versión exacta).

También se puede lanzar manualmente desde la pestaña *Actions* → *Build Android APK* → *Run workflow* (marcando `release` y, opcionalmente, indicando una versión). El despliegue de la web (worker de Cloudflare) es independiente y manual (`npm run deploy:web`).  

### Aplicación J2ME (.jar) para Symbian S60 (Nokia, Sony Ericcson...) + Sitio web para moviles y navegadores antiguos [http://buscoruna.inled.es/m/](http://buscoruna.inled.es/m/)  

Recientemente he adquirido un Nokia con Symbian para revivir aquellos buenos tiempos y hacer un poco de detox digital y viaje en el tiempo. Por ello he creado una aplicacion especifica para estos telefonos y una web adaptada, en caso de que no quieras instalar la app. Están diseñadas, tanto la web como la app, para consumir la menor memoria posible. Es por ellos que todo calculo se realiza en el Worker de CF y los datos se muestran escuetos.  
Si alguien que esté leyendo este readme tiene un telefono en el que probar, que me escriba o  [se una a nuestro Telegram](https://link.inled.es/telegram) o [Discord](https://link.inled.es/discord) pues estoy interesado en conocer su experiencia.  

**Por tu seguridad, la aplicacion j2me se construye en un Github Actions, asegurando la reproducibilidad y manteniendo la cadena de confianza, al no subirse de mi PC** Puedes incluso mirar el codigo del action,q ue es asombroso que la IA haya conseguido construir una app JavaME en un action :)

## Aviso legal

Los datos se obtienen a través de un endpoint abierto de los servidores de iTranvías, no se scrapea nada ni se hace nada ilegal. Respetamos el rate limiting de los servidores de iTranvías.  
No asociado con la Compañía de Tranvías de La Coruña SA ni el Ayuntamiento.  
<!-- build counter bump -->