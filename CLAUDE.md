# CLAUDE.md — SuperFurnitures (antes SuperBlocksDisplays)

Plugin Paper 1.21.x (Java 21, **Maven**: `mvn clean package` → `target/SuperFurnitures-*.jar`)
con dos caras: **muebles para jugadores** (items MythicMobs → muebles displayblock, paquete
`furniture/`, IMPLEMENTADO v1.2.0) y gestor admin de modelos block-display.com (`/bde`).
**Diseño y decisiones cerradas en [`docs/SUPERFURNITURES_DESIGN.md`](docs/SUPERFURNITURES_DESIGN.md)
— leerlo antes de tocar nada.** Claves furniture: entities persistentes vanilla + PDC en ancla
Interaction (el mundo es la BD, sin respawn al arrancar); MythicHook por REFLEXIÓN (no hay dep
Maven de MythicMobs); protección vía probe de `BlockPlaceEvent` sintético (respeta WorldGuard y
cualquier protección sin compilar contra ellas); asientos = ArmorStand marker no-persistente;
el plugin renombrado migra `plugins/SuperBlocksDisplays` → `plugins/SuperFurnitures` al arrancar.

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
- Robustez post-v1.2.0: `/sf list [jugador]` (resumen global por dueño o detalle) y
  `/sf purge <jugador>` (`FurnitureManager.purgeOwner`, carga chunks bajo demanda); el índice
  (`placements.json`) se **auto-cura en `EntitiesLoadEvent`** (poda entradas sin anchor — admin
  hizo `/kill` a mano — y re-adopta anchors vivos que no conoce — json perdido); `/bde purge`
  exime SOLO muebles vivos (con entrada en el índice) → los restos huérfanos sí se purgan;
  anti-asfixia al desmontar asiento dentro de mueble sólido (`rescueFromSuffocation`, sube al
  primer hueco libre; también en quit ANTES de que guarde la posición); muebles menu/commands
  con cooldown 1s por jugador (anti-spam de comandos).
