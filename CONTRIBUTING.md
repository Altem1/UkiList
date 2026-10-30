# Cómo contribuir a UkiList

¡Gracias por querer ayudar! UkiList es un fork de UKIKU con integración de AniList.

## Reportar errores o pedir funciones
- Abre un [issue](https://github.com/Altem1/UkiList/issues) usando las plantillas.
- Indica la versión de UkiList, el modelo de tu celular y la versión de Android.
- Si es un error, describe los pasos para reproducirlo y, si puedes, adjunta capturas o el logcat.

## Enviar cambios (pull requests)
1. Haz un fork del repositorio y crea una rama a partir de `master`.
2. Mantén el estilo del código existente (Kotlin, Room, ViewModel, LiveData/Coroutines).
3. Compila y prueba antes de enviar: `./gradlew :app:assembleDebug`.
4. Un pull request por cambio, con una descripción clara de qué hace y por qué.
5. No incluyas claves, contraseñas, keystores ni archivos locales (`local.properties`, `google-services.json`).

## Consultas a AniList
Si agregas o cambias una consulta GraphQL, pruébala antes contra `https://graphql.anilist.co`.

## Código de conducta
Al participar aceptas el [Código de Conducta](CODE_OF_CONDUCT.md).
