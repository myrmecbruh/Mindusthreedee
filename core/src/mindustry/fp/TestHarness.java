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
    private boolean sceneDone, rockDone, galleryDone, closeDone, blocksDone;
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
        renderer.fp.yaw = 0f; renderer.fp.pitch = OS.env("FP_PITCH") != null ? Strings.parseFloat(OS.env("FP_PITCH")) : -0.62f;
        Log.info("FP_TEST: rock @ at @,@", best.block().name, best.x, best.y);
    }

    private void gallery(){
        var u = player.unit();
        var team = mindustry.game.Team.sharded;
        mindustry.type.UnitType[] ts = {mindustry.content.UnitTypes.dagger, mindustry.content.UnitTypes.mace, mindustry.content.UnitTypes.fortress, mindustry.content.UnitTypes.scepter,
            mindustry.content.UnitTypes.nova, mindustry.content.UnitTypes.pulsar, mindustry.content.UnitTypes.quasar, mindustry.content.UnitTypes.crawler,
            mindustry.content.UnitTypes.atrax, mindustry.content.UnitTypes.spiroct, mindustry.content.UnitTypes.arkyid, mindustry.content.UnitTypes.flare,
            mindustry.content.UnitTypes.horizon, mindustry.content.UnitTypes.zenith, mindustry.content.UnitTypes.mono, mindustry.content.UnitTypes.poly,
            mindustry.content.UnitTypes.mega, mindustry.content.UnitTypes.risso, mindustry.content.UnitTypes.retusa, mindustry.content.UnitTypes.stell};
        renderer.fp.yaw = 0f; renderer.fp.pitch = -0.85f;
        for(int i = 0; i < ts.length; i++){
            var e = ts[i].spawn(team, u.x + 80f + (i % 5) * 34f, u.y - 60f + (i / 5) * 34f);
            e.rotation = 90f;
        }
    }

    /** One unit of the named type, close up, seen from the front and a little above. */
    private void closeup(){
        var u = player.unit();
        var type = mindustry.content.UnitTypes.dagger;
        for(var f : mindustry.content.UnitTypes.class.getFields()){
            if(f.getName().equals(OS.env("FP_UNIT"))){
                try{ type = (mindustry.type.UnitType)f.get(null); }catch(Exception ignored){}
            }
        }
        var e = type.spawn(mindustry.game.Team.sharded, u.x + 22f, u.y);
        e.rotation = 180f;
        e.vel.setZero();
        renderer.fp.yaw = 0f; renderer.fp.pitch = -0.95f;
    }

    /** Every block there is, in a grid, seen from high above. */
    private void blocks(){
        var u = player.unit();
        int tx = (int)(u.x / tilesize) + 30, ty = (int)(u.y / tilesize) - 12;
        var team = mindustry.game.Team.sharded;
        int col = 0, row = 0, rowH = 1, count = 0, perRow = Strings.parseInt(OS.env("FP_PERROW") == null ? "10" : OS.env("FP_PERROW"), 10), skip = Strings.parseInt(OS.env("FP_SKIP") == null ? "0" : OS.env("FP_SKIP"), 0);
        int seen = 0;
        for(mindustry.world.Block b : content.blocks()){
            if(b.isStatic() || b.isFloor() || b.isOverlay() || b instanceof mindustry.world.blocks.environment.Prop || b.buildVisibility == mindustry.world.meta.BuildVisibility.hidden || b.size > 5) continue;
            if(b instanceof mindustry.world.blocks.storage.CoreBlock) continue;
            if(seen++ < skip) continue;
            if(col + b.size > perRow * 4){ col = 0; row += rowH + 1; rowH = 1; }
            var t = world.tile(tx + col + (b.size - 1) / 2, ty + row + (b.size - 1) / 2);
            if(t != null){ t.setNet(b, team, 0); count++; }
            col += b.size + 1; rowH = Math.max(rowH, b.size);
            if(count >= 60) break;
        }
        Log.info("FP_TEST: placed @ blocks, skipped @", count, skip);
        u.set((tx + 18) * tilesize - 20f, (ty + 12) * tilesize);
        renderer.fp.flightAltitude = 235f;
        renderer.fp.yaw = 0f; renderer.fp.pitch = -1.45f;
    }

    private mindustry.type.UnitType[] showList;
    private mindustry.gen.Unit shown;
    private int showStart = -1;
    private float waterX, waterY, landX, landY;

    /** One unit type at a time, close up from the same side, each saved as its own screenshot: FP_UNITSHOW=1 (optionally FP_SHOWFROM / FP_SHOWCOUNT). */
    private void unitShow(){
        var u = player.unit();
        if(showList == null){
            var all = new arc.struct.Seq<mindustry.type.UnitType>();
            for(var t : content.units()) if(!t.internal && !t.isHidden() && t != mindustry.content.UnitTypes.alpha) all.add(t);
            int from = Strings.parseInt(OS.env("FP_SHOWFROM") == null ? "0" : OS.env("FP_SHOWFROM"), 0), count = Strings.parseInt(OS.env("FP_SHOWCOUNT") == null ? "12" : OS.env("FP_SHOWCOUNT"), 12);
            showList = new mindustry.type.UnitType[Math.max(0, Math.min(count, all.size - from))];
            for(int i = 0; i < showList.length; i++) showList[i] = all.get(from + i);
            //somewhere open: a clear patch of ground, the nearest to the player
            float bx = u.x, by = u.y, bd = 1e9f;
            for(int x = 12; x < world.width() - 12; x += 3) for(int y = 12; y < world.height() - 12; y += 3){
                boolean clear = true;
                for(int dx = -9; dx <= 9 && clear; dx += 3) for(int dy = -9; dy <= 9 && clear; dy += 3){
                    var t = world.tile(x + dx, y + dy);
                    clear = t != null && !t.solid() && !t.floor().isLiquid && t.build == null;
                }
                float d = arc.math.Mathf.dst(x * tilesize, y * tilesize, u.x, u.y);
                if(clear && d > 40f && d < bd){ bd = d; bx = x * tilesize; by = y * tilesize; }
            }
            //and, for the boats, a pond
            float wx = bx, wy = by, wd = 1e9f;
            for(int x = 6; x < world.width() - 6; x += 2) for(int y = 6; y < world.height() - 6; y += 2){
                boolean water = true;
                for(int dx = -5; dx <= 5 && water; dx += 5) for(int dy = -5; dy <= 5 && water; dy += 5){
                    var t = world.tile(x + dx, y + dy);
                    water = t != null && t.floor().isLiquid && !t.solid();
                }
                float d = arc.math.Mathf.dst(x * tilesize, y * tilesize, u.x, u.y);
                if(water && d < wd){ wd = d; wx = x * tilesize; wy = y * tilesize; }
            }
            waterX = wx; waterY = wy; landX = bx; landY = by;
            u.set(bx, by);
            showStart = counter;
            Log.info("FP_TEST: showing @ units from @: @", showList.length, from, arc.struct.Seq.with(showList).map(t -> t.name));
        }
        int k = (counter - showStart) / 100, phase = (counter - showStart) % 100;
        if(k >= showList.length){ Core.app.post(() -> Core.app.exit()); return; }
        var type = showList[k];
        if(phase == 0){
            if(shown != null) shown.remove();
            boolean boat = type.naval;
            u.set(boat ? waterX - 14f - type.hitSize * 1.4f : landX, boat ? waterY : landY);
            shown = type.spawn(mindustry.game.Team.sharded, u.x + 14f + type.hitSize * 1.4f, u.y);
            shown.controller(new mindustry.entities.units.AIController(){ @Override public void updateMovement(){} @Override public void updateTargeting(){} });
            shown.rotation = 180f;
            renderer.fp.chaseDistance = 12f + type.hitSize * 1.6f;
            renderer.fp.yaw = 0f; renderer.fp.pitch = type.flying ? -0.12f : -0.7f;
        }
        if(shown != null && !shown.dead){ shown.vel.setZero(); }
        if(phase == 80){
            Pixmap pix = ScreenUtils.getFrameBufferPixmap(0, 0, Core.graphics.getBackBufferWidth(), Core.graphics.getBackBufferHeight(), true);
            Fi file = new Fi(out + "_" + String.format("%02d", Strings.parseInt(OS.env("FP_SHOWFROM") == null ? "0" : OS.env("FP_SHOWFROM"), 0) + k) + "_" + type.name + ".png");
            PixmapIO.writePng(file, pix);
            pix.dispose();
            Log.info("FP_TEST: unit @ -> @ (added @ dead @ at @,@ player @,@ yaw @ pitch @ chase @)", type.name, file.name(), shown != null && shown.isAdded(), shown != null && shown.dead, shown == null ? 0 : (int)shown.x, shown == null ? 0 : (int)shown.y, (int)u.x, (int)u.y, renderer.fp.yaw, renderer.fp.pitch, renderer.fp.chase);
        }
    }

    private void scene(){
        var u = player.unit();
        if(u == null) return;
        renderer.fp.yaw = 0f; renderer.fp.pitch = OS.env("FP_PITCH") != null ? Strings.parseFloat(OS.env("FP_PITCH")) : -0.62f;
        control.input.block = mindustry.content.Blocks.duo;
        int tx = arc.math.geom.Geometry.d4x(0) + (int)(u.x / tilesize), ty = (int)(u.y / tilesize);
        var team = mindustry.game.Team.sharded;
        Log.info("FP_TEST: scene at @,@ unit @ yaw @ units @", tx, ty, u.type.name, renderer.fp.yaw, mindustry.gen.Groups.unit.size());
        mindustry.type.UnitType[] types = {mindustry.content.UnitTypes.dagger, mindustry.content.UnitTypes.mace, mindustry.content.UnitTypes.flare, mindustry.content.UnitTypes.horizon, mindustry.content.UnitTypes.nova, mindustry.content.UnitTypes.crawler, mindustry.content.UnitTypes.atrax};
        for(int i = 0; i < types.length; i++){
            var e = types[i].spawn(team, (tx + 5 + i * 2.2f) * tilesize, (ty + 8) * tilesize);
            e.rotation = 180f; e.health = e.maxHealth * (0.3f + 0.1f * i);
        }
        mindustry.type.UnitType[] foes = {mindustry.content.UnitTypes.dagger, mindustry.content.UnitTypes.flare};
        for(int i = 0; i < foes.length; i++) foes[i].spawn(mindustry.game.Team.crux, (tx + 12 + i) * tilesize, (ty - 4) * tilesize);
        mindustry.world.Block[] bs = {mindustry.content.Blocks.duo, mindustry.content.Blocks.hail, mindustry.content.Blocks.scatter, mindustry.content.Blocks.graphitePress, mindustry.content.Blocks.mechanicalDrill, mindustry.content.Blocks.copperWall, mindustry.content.Blocks.copperWallLarge, mindustry.content.Blocks.lancer};
        for(int i = 0; i < bs.length; i++){
            var t = world.tile(tx + 6 + i * 3, ty + 2);
            if(t != null) t.setNet(bs[i], team, 0);
        }
        Time.run(40f, () -> { for(var b : mindustry.gen.Groups.build){ if(b instanceof mindustry.world.blocks.defense.turrets.ItemTurret.ItemTurretBuild it){ for(int k = 0; k < 12; k++) it.handleItem(it, mindustry.content.Items.graphite); } } });
        for(int i = 0; i < 8; i++){ var t = world.tile(tx + 6 + i, ty - 3); if(t != null) t.setNet(mindustry.content.Blocks.conveyor, team, 0); }
        var n1 = world.tile(tx + 8, ty - 8); var n2 = world.tile(tx + 14, ty - 8);
        if(n1 != null && n2 != null){ n1.setNet(mindustry.content.Blocks.powerNode, team, 0); n2.setNet(mindustry.content.Blocks.powerNode, team, 0); Time.run(30f, () -> { if(n1.build != null && n2.build != null) n1.build.configureAny(n2.pos()); }); }
    }

    @Override
    public void update(){
        if(!state.isGame()) return;
        counter++;
        if(OS.env("FP_LOG") != null && counter % 60 == 0 && player.unit() != null) Log.info("FP_TEST: build plans " + player.unit().plans.size + " duos " + mindustry.gen.Groups.build.count(b -> b.block == mindustry.content.Blocks.duo) + " block " + (control.input.block == null ? "none" : control.input.block.name));
        if(OS.env("FP_PLACE") != null && counter == 100) control.input.block = mindustry.content.Blocks.duo;
        if(OS.env("FP_BULLETS") != null && counter > 60 && counter % 8 == 0 && player.unit() != null){ var pu = player.unit(); for(int i = -2; i <= 2; i++) mindustry.content.UnitTypes.dagger.weapons.first().bullet.create(pu, pu.team, pu.x + (float)(Math.cos(renderer.fp.yaw) * 30f - Math.sin(renderer.fp.yaw) * i * 10f), pu.y + (float)(Math.sin(renderer.fp.yaw) * 30f + Math.cos(renderer.fp.yaw) * i * 10f), renderer.fp.yawDegrees(), 1f, 0.3f); }
        if(OS.env("FP_SHOOT") != null && player.unit() != null && counter > 60){ var pu = player.unit(); pu.isShooting = true; pu.aim(renderer.fp.aimPoint.x, renderer.fp.aimPoint.y); if(counter % 20 == 0) Log.info("FP_TEST: bullets @ mounts @ ammo @ aim @,@ pos @,@", mindustry.gen.Groups.bullet.size(), pu.mounts.length, pu.type.name, (int)pu.aimX, (int)pu.aimY, (int)pu.x, (int)pu.y); }
        if(OS.env("FP_FX") != null && counter % 50 == 0 && player.unit() != null){ var pu = player.unit(); mindustry.content.Fx.bigShockwave.at(pu.x + 60f, pu.y); mindustry.content.Fx.explosion.at(pu.x + 50f, pu.y + 10f); mindustry.content.Fx.blastExplosion.at(pu.x + 70f, pu.y - 10f); }
        if(OS.env("FP_LOG") != null && counter % 60 == 0 && player.unit() != null) Log.info("FP_TEST: pos @,@ yaw @ pitch @ mouse @,@ bullets " + mindustry.gen.Groups.bullet.size(), (int)player.unit().x, (int)player.unit().y, renderer.fp.yaw, renderer.fp.pitch, Core.input.mouseX(), Core.input.mouseY());
        if(!rockDone && OS.env("FP_ROCK") != null && counter > 30 && player.unit() != null && !player.dead() && player.unit().type != null){
            rockDone = true;
            rock();
        }
        if(!galleryDone && OS.env("FP_GALLERY") != null && counter > 30 && player.unit() != null && !player.dead() && player.unit().type != null){
            galleryDone = true;
            gallery();
        }
        if(OS.env("FP_UNITSHOW") != null && counter > 60 && player.unit() != null && !player.dead() && player.unit().type != null){
            unitShow();
        }
        if(!closeDone && OS.env("FP_UNIT") != null && counter > 30 && player.unit() != null && !player.dead() && player.unit().type != null){
            closeDone = true;
            closeup();
        }
        if(!blocksDone && OS.env("FP_BLOCKS") != null && counter > 30 && player.unit() != null && !player.dead() && player.unit().type != null){
            blocksDone = true;
            blocks();
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
