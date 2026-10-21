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
    private boolean sceneDone, rockDone;
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

    /** Puts a chase camera close to the nearest boulder. */
    private void rock(){
        var u = player.unit();
        mindustry.world.Tile best = null; float bd = 1e9f;
        for(var t : world.tiles){
            if(t.block().name.contains("boulder")){
                float d = arc.math.Mathf.dst(t.worldx(), t.worldy(), u.x, u.y);
                if(d < bd){ bd = d; best = t; }
            }
        }
        if(best == null) return;
        u.set(best.worldx() - 22f, best.worldy());
        renderer.fp.yaw = 0f; renderer.fp.pitch = -0.35f;
        Log.info("FP_TEST: rock @ at @,@", best.block().name, best.x, best.y);
    }

    private void scene(){
        var u = player.unit();
        if(u == null) return;
        renderer.fp.yaw = 1.2f; renderer.fp.pitch = -0.45f;
        control.input.block = mindustry.content.Blocks.duo;
        int tx = arc.math.geom.Geometry.d4x(0) + (int)(u.x / tilesize), ty = (int)(u.y / tilesize);
        var team = mindustry.game.Team.sharded;
        Log.info("FP_TEST: scene at @,@ unit @ yaw @ units @", tx, ty, u.type.name, renderer.fp.yaw, mindustry.gen.Groups.unit.size());
        mindustry.type.UnitType[] types = {mindustry.content.UnitTypes.dagger, mindustry.content.UnitTypes.mace, mindustry.content.UnitTypes.flare, mindustry.content.UnitTypes.horizon, mindustry.content.UnitTypes.nova, mindustry.content.UnitTypes.crawler, mindustry.content.UnitTypes.atrax};
        for(int i = 0; i < types.length; i++){
            var e = types[i].spawn(team, (tx + 5 + i * 2.2f) * tilesize, (ty + 0) * tilesize);
            e.rotation = 180f;
        }
        mindustry.type.UnitType[] foes = {mindustry.content.UnitTypes.dagger, mindustry.content.UnitTypes.flare};
        for(int i = 0; i < foes.length; i++) foes[i].spawn(mindustry.game.Team.crux, (tx + 12 + i) * tilesize, (ty - 4) * tilesize);
        mindustry.world.Block[] bs = {mindustry.content.Blocks.duo, mindustry.content.Blocks.hail, mindustry.content.Blocks.scatter, mindustry.content.Blocks.graphitePress, mindustry.content.Blocks.mechanicalDrill, mindustry.content.Blocks.copperWall, mindustry.content.Blocks.copperWallLarge, mindustry.content.Blocks.lancer};
        for(int i = 0; i < bs.length; i++){
            var t = world.tile(tx + 10 + i * 4, ty - 6);
            if(t != null) t.setNet(bs[i], team, 0);
        }
        for(int i = 0; i < 8; i++){ var t = world.tile(tx + 10 + i, ty - 10); if(t != null) t.setNet(mindustry.content.Blocks.conveyor, team, 0); }
    }

    @Override
    public void update(){
        if(!state.isGame()) return;
        counter++;
        if(!rockDone && OS.env("FP_ROCK") != null && counter > 30 && player.unit() != null && !player.dead() && player.unit().type != null){
            rockDone = true;
            rock();
        }
        if(!sceneDone && OS.env("FP_SCENE") != null && counter > 30 && player.unit() != null && !player.dead() && player.unit().type != null){
            sceneDone = true;
            scene();
        }
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
