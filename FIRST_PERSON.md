# First-person mode (experimental)

A 3D, first-person view of the unmodified Mindustry simulation. The simulation, input handling and UI are the game's own; only the world drawing is replaced.

- **F8** toggles first-person on and off. It is on by default on this branch.
- **Mouse** looks around (the pointer is pinned to the window centre with `java.awt.Robot`); **Q/E** also turn. **WASD** moves relative to where you look.
- The crosshair's hit point on the ground is the game's cursor position, so shooting, building and breaking use the existing code unchanged.
- Hold **Left Alt** to free the mouse (to click the block menu); dialogs, text fields and the chat free it too.

- **Mouse wheel** raises and lowers the view (when no block is selected).
- Settings > Game has first-person sensitivity, field of view, inverted Y and a fixed view distance.
- The view distance adapts to keep the frame rate above 45, out to the edge of the map, fading into rolling clouds.

## How it draws

- Floor, ores and static walls are textured quads / columns.
- Blocks and units are solid low-poly models fitted automatically from their sprites on a background thread (`mindustry.fp.fit`); until a model is ready, or if the fit is poor, the sprite is drawn flat or as a plain block.
- Turrets: base plus a head that turns. Units: layered parts with the game's own walk and weapon animation.
- Effects and animated blocks the fitter can't model are drawn by the game in 2D into a top-down texture that is laid over the ground.

## Development hooks

`FP_TEST=<map name>` starts a map and writes screenshots (`FP_FRAMES`, `FP_OUT`); `FP_CHASE[=distance]` uses a third-person camera; `FP_SCENE`, `FP_FX`, `FP_BULLETS`, `FP_GALLERY` spawn test content. See `mindustry.fp.TestHarness`.

## Known gaps

No lighting or fog of war, effects are flat on the ground, model quality varies, no touch controls, performance on real hardware is untested.
