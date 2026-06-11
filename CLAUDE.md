# CLAUDE.md — SuperFurnitures (antes SuperBlocksDisplays)

Plugin Paper 1.21.x (Java 21, **Maven**: `mvn clean package` → `target/SuperBlocksDisplays-*.jar`)
para spawnear/animar modelos de block-display.com, en evolución a sistema de **muebles para
jugadores** (items MythicMobs → muebles displayblock). **Diseño completo y decisiones cerradas en
[`docs/SUPERFURNITURES_DESIGN.md`](docs/SUPERFURNITURES_DESIGN.md) — leerlo antes de tocar nada.**

- Repo: **https://github.com/Scrulius/SuperFurnitures** (renombrado; push directo a `main`).
  Sube a GitHub al terminar cualquier trabajo. Mensajes de commit en español.
- **🚫 SIN co-autoría de Claude**: nunca añadas `Co-Authored-By: Claude ...` a los commits ni PRs
  (norma GLOBAL del autor — aplica a todos sus proyectos, presentes y futuros).
- Deploy: copiar el jar a `C:\Users\Knopp\Desktop\server paper testeos\plugins\` (borrar el jar de
  versión anterior si cambia el nombre). Reinicio completo, NUNCA hot-swap.
- Motor de animación NATIVO por API de Paper (`AnimationManager`): keyframes compilados a
  `setTransformationMatrix`/`setInterpolationDuration`/`setBlock` por UUID; matriz row-major de MC
  → `new Matrix4f().set(floats).transpose()` (⚠️ único punto pendiente de verificación visual; si
  un modelo sale deformado, quitar el transpose). Fallback por comando silencioso solo para
  payloads no mapeables (`item:`, residuo desconocido).
- Persistencia admin (`/bde`): snapshot local completo en `data/<group-id>.json` — el re-spawn
  NUNCA depende de la API. Los muebles de jugadores usarán otra arquitectura: entities
  persistentes vanilla + metadata en PDC (ver diseño).
- Piezas trackeadas por **UUID** (no referencias `Entity`, quedan stale al recargar chunk).
