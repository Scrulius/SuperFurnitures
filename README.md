# SuperBlocksDisplays

**Plugin de Minecraft (Paper 1.21.x)** para spawnear, animar y gestionar modelos de [block-display.com](https://block-display.com) directamente en tu servidor.

**Creado por Melonzio**

---

## ✨ Características

- 🎭 **Spawn de modelos** — Importa cualquier modelo de block-display.com con su ID
- 🎬 **Animaciones** — Reproduce las animaciones nativas de los modelos con control play/stop
- ⚡ **Control de velocidad** — Ajusta la velocidad de animación (0.25x a 4x)
- 🔄 **Rotación** — Rota los modelos en cualquier ángulo
- 💾 **Persistencia** — Los modelos sobreviven reinicios del servidor
- 📋 **Lista interactiva** — Chat clickeable para gestionar modelos
- 🔍 **Tab completion** — Autocompletado completo en todos los comandos
- 🔇 **Sin spam en consola** — Los mensajes de data merge se silencian automáticamente

## 📦 Instalación

1. Descarga `SuperBlocksDisplays-1.0.0.jar` de [Releases](https://github.com/Scrulius/SuperBlocksDisplays/releases)
2. Colócalo en la carpeta `plugins/` de tu servidor Paper
3. Reinicia el servidor
4. ¡Listo! Usa `/bde help` para ver los comandos

## 🎮 Comandos

| Comando | Descripción |
|---------|------------|
| `/bde spawn <id>` | Spawnea un modelo por ID |
| `/bde remove [grupo]` | Elimina el modelo más cercano o por ID |
| `/bde list` | Lista todos los modelos activos |
| `/bde rotate <yaw> [grupo]` | Rota un modelo (0-360°) |
| `/bde anim <play\|stop> [grupo]` | Controla la animación |
| `/bde speed <0.25-4.0> [grupo]` | Ajusta velocidad de animación |
| `/bde info [grupo]` | Muestra detalles del modelo |
| `/bde clearcache` | Limpia la caché de modelos |
| `/bde help` | Muestra la ayuda |

## 🔑 Permisos

| Permiso | Descripción | Default |
|---------|------------|---------|
| `superblocksdisp.use` | Permite usar todos los comandos | OP |

## ⚙️ Requisitos

- **Paper** 1.21.x
- **Java** 21+

## 📝 Aliases

El comando principal `/bde` también funciona como `/sbd` y `/blockdisplay`.

## 📄 Licencia

MIT License - Haz lo que quieras con él.
