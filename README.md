# SuperBlocksDisplays

**Minecraft Plugin (Paper 1.21.x)** to spawn, animate, and manage models from [block-display.com](https://block-display.com) directly on your server.

**Created by Melonzio**

---

## ✨ Features

- 🎭 **Model Spawning** — Import any model from block-display.com using its ID with a custom name
- 🎬 **Animations** — Play native model animations with loop/once modes
- ⚡ **Speed Control** — Adjust animation playback speed (0.25x to 4x)
- 🔄 **Rotation** — Rotate models to any angle
- 💾 **Persistence** — Models survive server restarts without duplication
- 📛 **Custom Names** — Every model gets a mandatory name for easy management
- 📋 **Interactive List** — Clickable chat interface to manage active models
- 🔍 **Smart Targeting** — Reference models by name, `nearest`, or partial UUID
- 🧹 **Purge Command** — Kill all display entities in a radius for quick cleanup
- 🔇 **Silent Operation** — All command feedback (console & in-game) is automatically silenced
- 🔍 **Tab Completion** — Full autocomplete with model name suggestions

## 📦 Installation

1. Download `SuperBlocksDisplays-1.0.0.jar` from [Releases](https://github.com/Scrulius/SuperBlocksDisplays/releases)
2. Place it in your Paper server's `plugins/` folder
3. Restart the server
4. Use `/bde help` to see available commands

## 🎮 Commands

| Command | Description |
|---------|------------|
| `/bde spawn <id> <name>` | Spawns a model with a custom name |
| `/bde remove [name\|nearest]` | Removes a model by name or the nearest one |
| `/bde list` | Lists all active models with their names |
| `/bde rotate <yaw> [name\|nearest]` | Rotates a model (0-360°) |
| `/bde anim play <loop\|once> [name]` | Starts animation playback |
| `/bde anim stop [name\|nearest]` | Stops animation playback |
| `/bde speed <0.25-4.0> [name]` | Sets animation playback speed |
| `/bde info [name\|nearest]` | Shows detailed model information |
| `/bde purge <1-10>` | Kills all display entities within radius |
| `/bde clearcache` | Clears the downloaded models cache |
| `/bde help` | Displays the help menu |

> **Tip:** If no model is specified, commands like `remove`, `rotate`, `info`, etc. will target the **nearest** model within 15 blocks.

## 🔑 Permissions

| Permission | Description | Default |
|---------|------------|---------| 
| `superblocksdisp.use` | Allows the use of all commands | OP |

## ⚙️ Requirements

- **Paper** 1.21.x
- **Java** 21+

## 📝 Aliases

The main command `/bde` can also be used as `/sbd` and `/blockdisplay`.

## 📄 License

This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for more details.
