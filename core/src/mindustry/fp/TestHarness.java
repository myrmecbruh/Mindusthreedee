package mindustry.fp;

import arc.*;
import arc.files.*;
import arc.graphics.*;
import arc.util.*;
import mindustry.game.*;
import mindustry.game.EventType.*;
import mindustry.maps.*;

import static mindustry.Vars.*;

/**
 * Development aid, only active when the FP_TEST environment variable is set. Starts a map, waits for the given frames after the world
 * has loaded, writes a screenshot of the finished frame for each, then exits.
 * <p>
 * FP_TEST=<map name>  FP_FRAMES=240,300  FP_OUT=/path/prefix
 */
public class TestHarness implements ApplicationListener{
    private final int[] frames;
    private final String out;
    private int counter = -1, next = 0;

    private TestHarness(int[] frames, String out){
        this.frames = frames;
        this.out = out;
    }

    public static void install(){
        String mapName = OS.env("FP_TEST");
        if(mapName == null || mapName.isEmpty()) return;

        String[] parts = (OS.env("FP_FRAMES") == null ? "240" : OS.env("FP_FRAMES")).split(",");
        int[] frames = new int[parts.length];
        for(int i = 0; i < parts.length; i++) frames[i] = Strings.parseInt(parts[i].trim(), 240);
        String out = OS.env("FP_OUT") == null ? "fp_shot" : OS.env("FP_OUT");

        Events.on(ClientLoadEvent.class, e -> {
            Core.app.addListener(new TestHarness(frames, out));
            Time.runTask(60f, () -> {
                Map map = maps.all().find(m -> m.name().equalsIgnoreCase(mapName) || m.file.nameWithoutExtension().equalsIgnoreCase(mapName));
                if(map == null){
                    Log.err("FP_TEST: no map named @, have: @", mapName, maps.all().map(m -> m.name()));
                    Core.app.exit();
                    return;
                }
                Log.info("FP_TEST: starting @", mapName);
                control.playMap(map, Gamemode.survival.apply(map.applyRules(Gamemode.survival)));
                Events.on(WorldLoadEvent.class, w -> {});
            });
        });
        Events.on(WorldLoadEvent.class, e -> Log.info("FP_TEST: world loaded"));
        Events.on(PlayEvent.class, e -> Log.info("FP_TEST: play started"));
    }

    @Override
    public void update(){
        if(!state.isGame()) return;
        counter++;
        if(next < frames.length && counter >= frames[next]){
            Pixmap pix = ScreenUtils.getFrameBufferPixmap(0, 0, Core.graphics.getBackBufferWidth(), Core.graphics.getBackBufferHeight(), true);
            Fi file = new Fi(out + "_" + frames[next] + ".png");
            PixmapIO.writePng(file, pix);
            pix.dispose();
            Log.info("FP_TEST: wrote @", file.absolutePath());
            next++;
            if(next >= frames.length){
                Core.app.post(() -> Core.app.exit());
            }
        }
    }
}
