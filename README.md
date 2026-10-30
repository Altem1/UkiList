<p align="center"><img src="docs/assets/logo.png" width="140" alt="Logo de UkiList"></p>

# UkiList

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Fork de UKIKU](https://img.shields.io/badge/Fork%20de-jordyamc%2FUKIKU-orange)](https://github.com/jordyamc/UKIKU)
[![Android](https://img.shields.io/badge/Android-8.0%2B%20(API%2026)-3DDC84?logo=android&logoColor=white)](https://developer.android.com)
[![Descargar](https://img.shields.io/github/v/release/Altem1/UkiList?include_prereleases&label=Descargar)](https://github.com/Altem1/UkiList/releases)

**UkiList** (`com.altem1.ukilist`) es un fork no oficial de [UKIKU](https://github.com/jordyamc/UKIKU) que conecta la app con tu cuenta de [AniList](https://anilist.co): importa tu biblioteca, marca lo que ya viste y registra cada episodio que terminas, sin que tengas que hacer nada.

**Sitio web y descarga:** https://ukilist.altem.site/

<p align="center">
  <img src="docs/assets/shots/ficha.webp" width="200" alt="Nueva ficha de anime">
  <img src="docs/assets/shots/episodios-hub.webp" width="200" alt="Episodios con título y sinopsis">
  <img src="docs/assets/shots/openings.webp" width="200" alt="Openings y endings">
  <img src="docs/assets/shots/inicio-viendo.webp" width="200" alt="Inicio">
</p>

---

## Funciones

### Sincronización con AniList
- **Conexión con AniList (OAuth2)** desde el menú lateral o en Ajustes > Sincronización. El token se guarda cifrado (`EncryptedSharedPreferences`) y el inicio de sesión valida `state` contra CSRF.
- **Importar biblioteca (AniList → app)**: trae tus listas y marca los episodios vistos. La primera sincronización muestra una ventana con la carátula del anime en curso, la barra de avance y el contador de sincronizados y no sincronizados. Se repite sola cada 12 horas.
- **Scrobble automático (app → AniList)**: al ver el 85 % de un episodio o terminarlo, se registra en AniList con `WorkManager`, aunque cierres el reproductor de golpe.
- **Rewatch**: si vuelves a ver un anime completado, AniList lo marca como *Rewatching*; al terminarlo vuelve a *Completado* y suma la repetición.
- **Vistos desde AniList**: al abrir un anime se marcan como vistos los episodios que ya tienes en tu progreso de AniList.
- **Marcar y desmarcar a mano**: marcar un episodio lo sube; desmarcarlo baja el progreso en AniList al mayor episodio visto que quede.
- **Estados de la lista**: Viendo, Considerando, Completado, Abandonado y Pausado se suben a AniList, con aviso al confirmarse.
- **Nube por episodio**: indica si cada episodio ya está en AniList o sigue pendiente.
- **Sin sincronizar**: si un anime no se encontró en AniList o algún episodio no se pudo subir, la ficha muestra un botón junto a *Viendo* para elegir a mano el anime correcto o reintentar. No desaparece hasta resolverse.
- **Emparejamiento por título**: busca el anime por rōmaji, inglés y sinónimos, tolera variantes como "Season 2", "2nd Season" o "II", distingue películas y OVAs y usa el año de estreno. Guarda el resultado para no repetir búsquedas.
- **Favoritos sincronizados**: el corazón de la app y los personajes favoritos se guardan también en AniList.

### Ficha del anime (nueva)
- **Estilo hub**: portada de AniList que pasa sola al **tráiler** a los 5 segundos, botón **Continuar · Episodio N** con el siguiente que te falta y botón para descargarlo.
- **Acciones**: Favoritos, Agregar a viendo, Volver a ver (si ya lo terminaste) y Compartir con un enlace que abre la app.
- **Descripción, géneros, temporada, año, estado y estudio**.
- **Detalles**: puntuación de AniList, director, estudio, idiomas de doblaje, personajes y **openings/endings** con foto del artista y enlaces a YouTube, Spotify, Apple Music y YouTube Music.
- **Episodios**: tarjetas con miniatura, **título y sinopsis en español** (AniList y Kitsu, traducidos en el teléfono), descarga y nube de sincronización. Tocar la tarjeta reproduce el episodio.
- **Podría gustarte**: recomendaciones de AniList sin los animes que ya terminaste.
- Se puede volver a la ficha clásica en Configuración > Personalización.

### Navegación e inicio
- **Barra inferior flotante**: Recientes, Favoritos (*Para ti*), Perfil, Directorio y Emisión.
- **Perfil**: portada y foto de AniList, acceso a Configuración y Acerca de, y pestañas **AniList**, **Descargas** e **Historial**.
- **Inicio**: *Últimos actualizados (viendo)* con todos tus animes en emisión, próxima emisión y puntuación; *Últimos vistos* y las secciones de siempre.
- **% de AniList** junto al nombre de cada portada (Inicio, Directorio y Emisión).
- **Búsqueda avanzada** en Directorio: temporada, año, estado, tipo, géneros y orden.
- **Pestaña Para ti**: recomendaciones de AniList según tus favoritos.
- **Modo AMOLED**: fondo negro puro con el tema oscuro.
- Menú lateral y descargas rediseñados.

### Sección AniList
- **Mi lista**: historial, viendo, pendientes, completados y demás listas de AniList, con progreso y puntuación. Desde ahí abres el anime en la app o en AniList.
- **Estadísticas**: animes, episodios, días y horas vistos, media, distribución de puntuaciones, géneros, años, formatos y estados.
- **Perfil**: avatar, portada, biografía, medallas por lo que has visto, logros de la app y favoritos de anime, personajes, staff y estudios.
- **Menú lateral**: muestra tu avatar, tu nombre y la portada de tu perfil de AniList.

### Reproductor
- **Autoplay estilo Netflix**: al terminar un episodio, el actual se encoge y aparece el siguiente con cuenta regresiva. Usa el mismo servidor y, si no tiene el episodio, prueba con los demás. Funciona en el reproductor básico y en el avanzado.
- **Saltar opening** y **miniventana al empezar el ending** con los tiempos de [AniSkip](https://aniskip.com), y botón de **siguiente episodio**.

### Además
- Todo lo de UKIKU: recientes, emisión, catálogo, descargas, cola, historial, logros y temas.
- Sin anuncios: se quitó la opción de anuncios de Configuración.
- Logo propio (mago en pixel art) y color azul-morado por defecto.
- Se quitaron Noticias, FAQ, Sugerencias, Random y Explorador del menú lateral (Explorador vive ahora en Perfil > Descargas).
- Identificador propio (`com.altem1.ukilist`): se instala junto a UKIKU sin conflicto.

---

## Novedades técnicas respecto a UKIKU

- Se quitaron las integraciones de anuncios y Dropbox del proyecto original.
- Módulo `:dtpv` ([DoubleTapPlayerView](https://github.com/vkay94/DoubleTapPlayerView)) integrado localmente y portado a **AndroidX Media3**.
- Valores de Firebase Remote Config servidos localmente, sin depender del backend original.
- Room con migraciones para el mapeo de AniList (`anilist_mapping`) y el estado de sync por episodio (`anilist_scrobble`).

---

## Compilación

1. Clona este repositorio:
   ```bash
   git clone https://github.com/Altem1/UkiList.git
   cd UkiList
   ```
2. Crea un cliente de API en [AniList Developer Settings](https://anilist.co/settings/developer) con el **Redirect URL**:
   ```
   ukiku://anilist-callback
   ```
3. En la raíz del proyecto, asegúrate de definir tu `ANILIST_CLIENT_ID` en `local.properties`:
   ```properties
   ANILIST_CLIENT_ID=tu_client_id
   ```
4. Compila el APK con Gradle (requiere JDK 17):
   ```bash
   ./gradlew assembleDebug
   ```

---

## Créditos y agradecimientos

Este proyecto existe gracias al trabajo de código abierto de sus autores originales:

- **[UKIKU](https://github.com/jordyamc/UKIKU)** — Proyecto original creado y desarrollado por **Jordy Alexis Mendoza Caballero** ([@jordyamc](https://github.com/jordyamc)).
- **[DoubleTapPlayerView](https://github.com/vkay94/DoubleTapPlayerView)** — Librería de gestos de doble toque creada por **Viktor Krez** ([@vkay94](https://github.com/vkay94)), incluida en este repositorio bajo el módulo `:dtpv` (portada a Media3).
- **[AniList](https://anilist.co)** — Sincronización y metadatos de seguimiento proporcionados a través de la [API pública GraphQL de AniList](https://github.com/AniList/ApiV2-GraphQL-Docs).
- **Fork (`UkiList`)** — Mantenido por **Altem01** ([@Altem1](https://github.com/Altem1)).

---

## Licencia

Este proyecto se distribuye bajo la **Licencia MIT**, conservando íntegros los derechos de autor y avisos de licencia del proyecto original [UKIKU](https://github.com/jordyamc/UKIKU) ([`LICENSE`](LICENSE)):

```text
MIT License

Copyright (c) 2018 Jordy Alexis Mendoza Caballero

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
