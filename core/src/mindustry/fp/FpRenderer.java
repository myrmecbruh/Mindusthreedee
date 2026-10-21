package mindustry.fp;

import arc.*;
import arc.graphics.*;
import arc.graphics.Texture.*;
import arc.graphics.g2d.*;
import arc.graphics.g3d.*;
import arc.graphics.gl.*;
import arc.input.*;
import arc.math.*;
import arc.math.geom.*;
import arc.struct.*;
import arc.util.*;
import mindustry.content.*;
import mindustry.core.*;
import mindustry.entities.units.*;
import mindustry.fp.ModelCache.*;
import mindustry.gen.*;
import mindustry.graphics.voxel.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.entities.units.BuildPlan;
import mindustry.input.InputHandler;
import mindustry.input.DesktopInput;
import mindustry.world.blocks.environment.*;
import mindustry.world.blocks.defense.turrets.*;
import mindustry.world.blocks.ConstructBlock;
import mindustry.world.blocks.distribution.Conveyor;
import mindustry.entities.*;

import static mindustry.Vars.*;

/**
 * Draws the world in perspective from the player's eyes. It only reads the simulation: floors are textured quads, everything with
 * volume is a solid model fitted to the game's own sprite (see {@link ModelCache}).
 * F8 switches it on and off.
 */
public class FpRenderer implements Disposable{
    private static final int vertexFloats = 9;
    /** World units per sprite pixel. */
    private static final float scl = 0.25f;

    public boolean enabled;
    /** Look direction in radians: yaw 0 looks along +x, pi/2 along +y; pitch up is positive. */
    public float yaw, pitch;
    public int radius = 40;
    public boolean chase;
    public float chaseDistance = 90f;
    /** Height flying units hover at, in world units. Tall enough to clear the biggest buildings. */
    public static final float flightAltitude = 44f;
    public float lookSensitivity = 0.0028f;
    /** Height of static walls in world units. */
    public static final float wallHeight = 12f;

    private java.awt.Robot robot;
    private boolean robotFailed, captured;
    private int centreX, centreY, lastMx, lastMy;
    private boolean calibrated;
    private int tries;

    private final Camera3D cam = new Camera3D();
    private final ModelCache models = new ModelCache();
    private final ObjectMap<VoxelModel, Mesh> meshes = new ObjectMap<>();
    private final ObjectMap<Texture, FloatSeq> flat = new ObjectMap<>();
    private final Seq<Draw3> queue = new Seq<>();
    private final Vec3 eye = new Vec3();

    private Shader shader;
    private Mesh flatMesh;
    private float whiteU, whiteV;
    private boolean initialised, initialised2;
    private long collectNs;
    private int collectFrames;

    private static class Draw3{
        VoxelModel model;
        float x, y, z, rot, sx = 1f;
    }

    public FpRenderer(){
        cam.fov = 75f;
        cam.near = 0.6f;
        cam.far = 4000f;
        chase = OS.env("FP_CHASE") != null;
        if(chase && Strings.canParseFloat(OS.env("FP_CHASE"))) chaseDistance = Strings.parseFloat(OS.env("FP_CHASE"));
    }

    /** Handles input and the camera. Returns true if the world should be drawn by this renderer this frame. */
    public boolean update(){
        if(Core.input.keyTap(KeyCode.f8)) enabled = !enabled;
        if(OS.env("FP_TEST") != null && state.isGame()) enabled = true;
        if(!enabled || !state.isGame()) return false;

        Unit unit = player.unit();
        if(!initialised && unit != null && !unit.dead){
            initialised = true;
            yaw = unit.rotation * Mathf.degRad;
            pitch = chase ? -0.5f : -0.25f;
        }
        look();
        return true;
    }

    /** True while the player is flying about in first person. */
    public boolean active(){
        return enabled && state.isGame() && !player.dead() && player.unit() != null;
    }

    public float yawDegrees(){
        return yaw * Mathf.radDeg;
    }

    /** The point on the ground the crosshair is over. */
    public final Vec2 aimPoint = new Vec2();

    /** Mouse look. The pointer is pinned to the window centre with a Robot, since Arc's SDL binding has no pointer lock. Q and E also turn. */
    private void look(){
        boolean free = Core.scene.hasDialog() || Core.scene.hasField() || ui.chatfrag.shown() || !state.isGame();
        float turn = 0.045f * Time.delta;
        if(!free){
            if(Core.input.keyDown(KeyCode.q)) yaw += turn;
            if(Core.input.keyDown(KeyCode.e)) yaw -= turn;
        }
        if(free || robotFailed){
            captured = false;
            return;
        }
        try{
            if(robot == null) robot = new java.awt.Robot();
            java.awt.Point p = java.awt.MouseInfo.getPointerInfo().getLocation();
            int mx = Core.input.mouseX(), my = Core.input.mouseY();
            if(!captured){
                //where the window centre is on the screen is unknown, so start at the pointer and let the window's own idea of the pointer correct it
                centreX = p.x; centreY = p.y;
                captured = true;
                calibrated = false;
                tries = 0;
                lastMx = Integer.MIN_VALUE;
            }else if(!calibrated){
                if(mx != lastMx || my != lastMy){
                    int errX = mx - Core.graphics.getWidth() / 2, errY = (Core.graphics.getHeight() - my) - Core.graphics.getHeight() / 2;
                    if((errX == 0 && errY == 0) || ++tries > 40) calibrated = true;
                    else{ centreX -= errX; centreY -= errY; }
                }
            }else{
                yaw -= (p.x - centreX) * lookSensitivity;
                pitch = Mathf.clamp(pitch - (p.y - centreY) * lookSensitivity, -1.45f, 1.45f);
            }
            lastMx = mx; lastMy = my;
            if(OS.env("FP_LOG") != null && (int)Time.time % 60 == 0) Log.info("FP_TEST: look p @,@ centre @,@ arc @,@ cal @", p.x, p.y, centreX, centreY, mx, my, calibrated);
            robot.mouseMove(centreX, centreY);
        }catch(Throwable t){
            robotFailed = true;
            Log.warn("First-person mouse look is unavailable (@); use Q and E to turn.", t.toString());
        }
    }

    /**
     * Repurposes the 2D camera as the aim point: it is placed so the game's own idea of the world position under the cursor is the point
     * the crosshair hits on the ground. All of the existing aiming and building code then works unchanged.
     */
    private void placeAimCamera(float ex, float ey, float ez, float fx, float fy, float fz){
        float gx, gy;
        if(fz < -0.02f){
            float t = ez / -fz;
            gx = ex + fx * t; gy = ey + fy * t;
        }else{
            float len = Math.max(0.001f, Mathf.dst(fx, fy));
            gx = ex + fx / len * 480f; gy = ey + fy / len * 480f;
        }
        aimPoint.set(gx, gy);
        Core.camera.position.set(0f, 0f);
        Core.camera.update();
        Vec2 off = Core.input.mouseWorld();
        Core.camera.position.set(gx - off.x, gy - off.y);
        Core.camera.update();
    }

    private void ensureInit(){
        if(!initialised2){ initialised2 = true; Log.info("FP_TEST: depth bits @", Core.graphics.getBufferFormat().depth); }
        if(shader != null) return;
        shader = new Shader(Core.files.internal("shaders/voxel.vert"), Core.files.internal("shaders/voxel.frag"));
        flatMesh = new Mesh(false, 6 * (radius * 2 + 1) * (radius * 2 + 1) * 8, 0,
            VertexAttribute.position3, VertexAttribute.normal, VertexAttribute.color, VertexAttribute.texCoords);
        TextureRegion white = Core.atlas.white();
        whiteU = (white.u + white.u2) / 2f;
        whiteV = (white.v + white.v2) / 2f;
    }

    public void render(){
        ensureInit();
        Unit unit = player.unit();
        float px = unit != null && !unit.dead ? unit.x : Core.camera.position.x, py = unit != null && !unit.dead ? unit.y : Core.camera.position.y;

        float eyeZ = unit != null ? (unit.type.flying ? flightAltitude * unit.elevation : 0f) + Math.max(5f, unit.hitSize * 0.8f) : 6f;
        float cy = Mathf.cos(pitch), fx = Mathf.cos(yaw) * cy, fy = Mathf.sin(yaw) * cy, fz = Mathf.sin(pitch);
        if(chase){
            eye.set(px - fx * chaseDistance, py - fy * chaseDistance, eyeZ - fz * chaseDistance);
        }else{
            eye.set(px, py, eyeZ);
        }
        cam.up.set(Vec3.Z);
        cam.position.set(eye);
        cam.direction.set(fx, fy, fz).nor();
        cam.resize(Core.graphics.getWidth(), Core.graphics.getHeight());
        cam.update();
        if(active()) placeAimCamera(eye.x, eye.y, eye.z, fx, fy, fz);

        Draw.flush();
        Gl.depthMask(true);
        Gl.clearColor(0.55f, 0.72f, 0.88f, 1f);
        Gl.clear(Gl.colorBufferBit | Gl.depthBufferBit);
        Gl.enable(Gl.depthTest);
        Gl.depthFunc(Gl.lequal);
        Gl.depthMask(true);
        Gl.disable(Gl.cullFace);
        Gl.enable(Gl.blend);
        Gl.blendFunc(Gl.srcAlpha, Gl.oneMinusSrcAlpha);

        shader.bind();
        shader.setUniformMatrix4("u_proj", cam.combined.val);
        shader.setUniformi("u_texture", 0);
        shader.setUniformf("u_offset", 0f, 0f, 0f);
        shader.setUniformf("u_scale", 1f);
        shader.setUniformf("u_rot", 0f);

        long t0 = System.nanoTime();
        flat.each((t, seq) -> seq.clear());
        queue.clear();
        collectWorld(px, py, unit);
        collectUnits(unit);
        collectBullets();
        collectPlans(unit);
        collectNs += System.nanoTime() - t0;
        if(OS.env("FP_TEST") != null && ++collectFrames % 100 == 0){ Log.info("FP_TEST: collect @ ms/frame", collectNs / 1e6f / 100f); collectNs = 0; }
        drawFlat();

        Core.atlas.white().texture.bind(0);
        drawQueue();

        Gl.disable(Gl.depthTest);
        Gl.enable(Gl.blend);
        if(!chase) crosshair();
    }

    private void collectWorld(float px, float py, Unit self){
        int cx = World.toTile(px), cy = World.toTile(py);
        for(int x = cx - radius; x <= cx + radius; x++){
            for(int y = cy - radius; y <= cy + radius; y++){
                Tile tile = world.tile(x, y);
                if(tile == null) continue;

                Floor floor = tile.floor();
                TextureRegion[] variants = floor.variantRegions;
                TextureRegion fr = variants != null && variants.length > 0 ?
                    variants[Mathf.randomSeed(tile.pos(), 0, variants.length - 1)] : floor.region;
                if(fr != null) quad(fr, tile.worldx(), tile.worldy(), 0f, tilesize, tilesize, 0f);

                if(tile.overlay() != Blocks.air && tile.overlay().region != null){
                    quad(tile.overlay().region, tile.worldx(), tile.worldy(), 0.05f, tilesize, tilesize, 0f);
                }

                Block block = tile.block();
                if(block == Blocks.air) continue;
                if(block.isStatic()){
                    column(tile, block);
                    continue;
                }
                if(tile.build != null && !tile.isCenter()) continue;

                float wx = tile.build != null ? tile.build.x : tile.worldx();
                float wy = tile.build != null ? tile.build.y : tile.worldy();
                TextureRegion region = block.region;
                if(block.variantRegions != null && block.variantRegions.length > 0){
                    region = block.variantRegions[Mathf.randomSeed(tile.pos(), 0, block.variantRegions.length - 1)];
                }
                if(region == null || !region.found()) continue;

                if(tile.build instanceof Conveyor.ConveyorBuild cv && block instanceof Conveyor cvb){
                    int frame = cv.enabled ? (int)((Time.time * cvb.speed * 8f * cv.timeScale() * cv.efficiency) % 4) : 0;
                    quad(cvb.regions[cv.blendbits][frame], cv.x, cv.y, 0.12f, tilesize * cv.blendsclx, tilesize * cv.blendscly, cv.rotation * 90f);
                    for(int i = 0; i < cv.len; i++){
                        float ix = cv.x + Angles.trnsx(cv.rotation * 90f, tilesize * cv.ys[i] - tilesize / 2f, cv.xs[i] * tilesize / 2f);
                        float iy = cv.y + Angles.trnsy(cv.rotation * 90f, tilesize * cv.ys[i] - tilesize / 2f, cv.xs[i] * tilesize / 2f);
                        quad(cv.ids[i].fullIcon, ix, iy, 0.5f, 4f, 4f, 0f);
                    }
                    continue;
                }
                if(tile.build instanceof ConstructBlock.ConstructBuild cb && cb.current != null && cb.current.region != null){
                    region = cb.current.region;
                    quad(region, wx, wy, 0.1f, block.size * tilesize, block.size * tilesize, 0f);
                    continue;
                }
                if(tile.build instanceof BaseTurret.BaseTurretBuild && block instanceof Turret t){
                    float rotd = tile.build instanceof Turret.TurretBuild tb ? tb.rotation - 90f : 0f;
                    float rec = tile.build instanceof Turret.TurretBuild tb2 ? tb2.curRecoil * t.recoil : 0f;
                    float bh = block.size * tilesize * 0.3f;
                    TextureRegion baseR = t.drawer instanceof mindustry.world.draw.DrawTurret dt ? dt.base : null;
                    if(baseR != null && baseR.found()){
                        VoxelModel bm = models.get(baseR, Kind.grounded, 0f, false);
                        if(bm != null) add(bm, wx, wy, 0f, 0f); else quad(baseR, wx, wy, 0.1f, block.size * tilesize, block.size * tilesize, 0f);
                    }
                    VoxelModel hm = models.get(t.region, Kind.layer, 0f, false);
                    float hx = wx + Angles.trnsx(rotd + 90f, 0f, -rec), hy = wy + Angles.trnsy(rotd + 90f, 0f, -rec);
                    if(hm != null) add(hm, hx, hy, bh, rotd * Mathf.degRad);
                    else quad(t.region, hx, hy, bh, block.size * tilesize, block.size * tilesize, rotd);
                    continue;
                }
                float rot = tile.build != null && block.rotate ? tile.build.rotdeg() : 0f;
                VoxelModel model = block.rotate ? null : models.get(region, Kind.grounded, 0f, false);
                if(model == null){
                    if(!block.rotate && models.poor(region, Kind.grounded, 0f, false)){
                        box(region, wx, wy, block.size * tilesize, Math.max(3f, block.size * tilesize * 0.45f));
                    }else{
                        //still being fitted, or a part that turns: draw the sprite flat
                        quad(region, wx, wy, 0.1f, block.size * tilesize, block.size * tilesize, rot);
                    }
                }else{
                    add(model, wx, wy, 0f, 0f);
                }
            }
        }
    }

    /** Static walls are columns: the sprite on top, and a darkened side wherever the neighbouring tile is lower. */
    private void column(Tile tile, Block block){
        TextureRegion region = block.variantRegions != null && block.variantRegions.length > 0 ?
            block.variantRegions[Mathf.randomSeed(tile.pos(), 0, block.variantRegions.length - 1)] : block.region;
        if(region == null || !region.found()) return;
        float x = tile.worldx(), y = tile.worldy(), h = wallHeight, r = tilesize / 2f;
        quad(region, x, y, h, tilesize, tilesize, 0f);
        float side = sideColor(region);
        for(int d = 0; d < 4; d++){
            Tile n = world.tile(tile.x + Geometry.d4x(d), tile.y + Geometry.d4y(d));
            if(n != null && n.block().isStatic()) continue;
            float nx = Geometry.d4x(d), ny = Geometry.d4y(d);
            float ax = x + nx * r - ny * r, ay = y + ny * r + nx * r, bx = x + nx * r + ny * r, by = y + ny * r - nx * r;
            sideQuad(ax, ay, bx, by, 0f, h, nx, ny, side);
        }
    }

    /** A plain block: the sprite on top, dark sides. */
    private void box(TextureRegion region, float x, float y, float size, float h){
        quad(region, x, y, h, size, size, 0f);
        float side = sideColor(region), r = size / 2f;
        for(int d = 0; d < 4; d++){
            float nx = Geometry.d4x(d), ny = Geometry.d4y(d);
            sideQuad(x + nx * r - ny * r, y + ny * r + nx * r, x + nx * r + ny * r, y + ny * r - nx * r, 0f, h, nx, ny, side);
        }
    }

    private final ObjectMap<TextureRegion, Float> sideColors = new ObjectMap<>();

    /** Darkened average colour of a sprite, as packed float bits. */
    private float sideColor(TextureRegion region){
        Float c = sideColors.get(region);
        if(c != null) return c;
        var pix = Core.atlas.getPixmap(region);
        float r = 0, g = 0, b = 0;
        int n = 0;
        if(pix != null){
            for(int y = 0; y < pix.height; y++) for(int x = 0; x < pix.width; x++){
                int v = pix.get(x, y);
                if((v & 0xff) < 128) continue;
                r += ((v >>> 24) & 0xff); g += ((v >>> 16) & 0xff); b += ((v >>> 8) & 0xff); n++;
            }
        }
        float bits = n == 0 ? Color.toFloatBits(128, 128, 128, 255) : Color.toFloatBits((int)(r / n * 0.62f), (int)(g / n * 0.62f), (int)(b / n * 0.62f), 255);
        sideColors.put(region, bits);
        return bits;
    }

    private void sideQuad(float ax, float ay, float bx, float by, float z0, float z1, float nx, float ny, float color){
        TextureRegion w = Core.atlas.white();
        FloatSeq seq = flat.get(w.texture);
        if(seq == null) flat.put(w.texture, seq = new FloatSeq());
        float[][] pts = {{ax, ay, z0}, {bx, by, z0}, {bx, by, z1}, {ax, ay, z1}};
        int[] order = {0, 1, 2, 0, 2, 3};
        for(int i : order){
            seq.add(pts[i][0], pts[i][1], pts[i][2]);
            seq.add(nx, ny, 0f);
            seq.add(color);
            seq.add(whiteU, whiteV);
        }
    }

    private void ghost(Block b, int tx, int ty, int rot, boolean valid, float alpha){
        if(b == null || b.region == null || !b.region.found()) return;
        if(OS.env("FP_TEST") != null && Time.time % 120 < 1) Log.info("FP_TEST: ghost @ at @,@ valid @", b.name, tx, ty, valid);
        float ox = tx * tilesize + b.offset, oy = ty * tilesize + b.offset;
        int a = (int)(alpha * 255);
        float tint = valid ? Color.toFloatBits(150, 255, 160, a) : Color.toFloatBits(255, 110, 110, a);
        tintQuad(b.region, ox, oy, 1.0f, b.size * tilesize, b.rotate ? rot * 90f - 90f : 0f, tint);
    }

    private void collectPlans(Unit self){
        if(self == null) return;
        for(BuildPlan p : self.plans){
            if(p.block != null && !p.breaking) ghost(p.block, p.x, p.y, p.rotation, true, 0.55f);
        }
        InputHandler in = control.input;
        for(BuildPlan p : in.selectPlans){
            if(p.block != null && !p.breaking) ghost(p.block, p.x, p.y, p.rotation, Build.validPlace(p.block, player.team(), p.x, p.y, p.rotation), 0.6f);
        }
        if(in.block != null && in.isPlacing()){
            Vec2 v = aimPoint;
            int tx = World.toTile(v.x - in.block.offset), ty = World.toTile(v.y - in.block.offset);
            ghost(in.block, tx, ty, in.rotation, Build.validPlace(in.block, player.team(), tx, ty, in.rotation), 0.7f);
        }
    }

    private void tintQuad(TextureRegion r, float x, float y, float z, float size, float rot, float color){
        FloatSeq seq = flat.get(r.texture);
        if(seq == null) flat.put(r.texture, seq = new FloatSeq());
        float cos = Mathf.cosDeg(rot), sin = Mathf.sinDeg(rot), h = size / 2f;
        float[] cxs = {-h, h, h, -h}, cys = {-h, -h, h, h};
        float[] us = {r.u, r.u2, r.u2, r.u}, vs = {r.v2, r.v2, r.v, r.v};
        int[] order = {0, 1, 2, 0, 2, 3};
        for(int i : order){
            seq.add(x + cxs[i] * cos - cys[i] * sin, y + cxs[i] * sin + cys[i] * cos, z);
            seq.add(0f, 0f, 1f);
            seq.add(color);
            seq.add(us[i], vs[i]);
        }
    }

    private void crosshair(){
        float w = Core.graphics.getWidth(), h = Core.graphics.getHeight();
        Draw.proj(0, 0, w, h);
        Draw.color(Color.white, 0.9f);
        float cx = w / 2f, cy = h / 2f;
        Lines.stroke(2f);
        Lines.line(cx - 12, cy, cx - 4, cy); Lines.line(cx + 4, cy, cx + 12, cy);
        Lines.line(cx, cy - 12, cx, cy - 4); Lines.line(cx, cy + 4, cx, cy + 12);
        Draw.flush();
        Draw.reset();
    }

    private void collectBullets(){
        float rx = Mathf.sin(yaw), ry = -Mathf.cos(yaw);
        for(Bullet b : Groups.bullet){
            if(b.type == null || Mathf.dst(b.x, b.y, eye.x, eye.y) > radius * tilesize) continue;
            float size = b.type instanceof mindustry.entities.bullet.BasicBulletType bb ? Math.max(1.2f, Math.min(5f, bb.height * 0.22f)) : 1.6f;
            float z = b.type.collidesGround && !b.type.collidesAir ? 2f : (b.type.collidesAir && !b.type.collidesGround ? flightAltitude : 6f);
            Color c = b.type instanceof mindustry.entities.bullet.BasicBulletType bb2 && bb2.frontColor != null ? bb2.frontColor : Color.white;
            billboard(b.x, b.y, z, size, rx, ry, c.toFloatBits());
        }
    }

    private void billboard(float x, float y, float z, float s, float rx, float ry, float color){
        TextureRegion w = Core.atlas.white();
        FloatSeq seq = flat.get(w.texture);
        if(seq == null) flat.put(w.texture, seq = new FloatSeq());
        float[][] pts = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
        int[] order = {0, 1, 2, 0, 2, 3};
        for(int i : order){
            seq.add(x + rx * pts[i][0] * s, y + ry * pts[i][0] * s, z + pts[i][1] * s);
            seq.add(0f, 0f, 1f);
            seq.add(color);
            seq.add(whiteU, whiteV);
        }
    }

    private void part(TextureRegion region, boolean flip, float x, float y, float z, float rotDeg, float flatSize){
        if(region == null || !region.found()) return;
        VoxelModel model = models.get(region, Kind.layer, 0f, flip);
        if(model != null) add(model, x, y, z, rotDeg * Mathf.degRad);
    }

    private void collectUnits(Unit self){
        for(Unit u : Groups.unit){
            if(u == self || u.type == null || u.dead) continue;
            if(Mathf.dst(u.x, u.y, eye.x, eye.y) > radius * tilesize) continue;
            drawUnit(u);
        }
    }

    private void drawUnit(Unit u){
        UnitType type = u.type;
        float z = type.flying || u.elevation > 0.01f ? flightAltitude * u.elevation : 0f;
        float rot = u.rotation - 90f;

        if(u instanceof Mechc m){
            float e = u.elevation, base = m.baseRotation();
            float ext = Mathf.lerp(m.walkExtend(false), 0f, e);
            for(int i : Mathf.signs){
                float lx = u.x + Angles.trnsx(base, ext * i, 0f), ly = u.y + Angles.trnsy(base, ext * i, 0f);
                part(type.legRegion, i < 0, lx, ly, z, base - 90f + 35f * i * e, 0f);
            }
            part(type.baseRegion, false, u.x, u.y, z, base - 90f, 0f);
            part(type.region, false, u.x, u.y, z + 1.2f, rot, 0f);
        }else{
            if(type.baseRegion != null && type.baseRegion.found() && u instanceof Tankc) part(type.baseRegion, false, u.x, u.y, z, rot, 0f);
            part(type.region, false, u.x, u.y, z, rot, 0f);
        }

        if(u instanceof Legsc lg){
            float hipZ = z + Math.max(2f, type.legLength * 0.12f);
            float c = Color.toFloatBits(70, 72, 82, 255);
            float rx = Mathf.sin(yaw), ry = -Mathf.cos(yaw);
            for(Leg l : lg.legs()){
                if(l == null) continue;
                float lift = l.moving ? Mathf.sin(l.stage * Mathf.PI) * 2.5f : 0f;
                for(int k = 0; k <= 8; k++){
                    float f = k / 8f;
                    //hip -> joint -> foot
                    float ax = f < 0.5f ? u.x : l.joint.x, ay = f < 0.5f ? u.y : l.joint.y, az = f < 0.5f ? hipZ : hipZ * 0.7f;
                    float bx = f < 0.5f ? l.joint.x : l.base.x, by = f < 0.5f ? l.joint.y : l.base.y, bz = f < 0.5f ? hipZ * 0.7f : lift;
                    float t = f < 0.5f ? f * 2f : (f - 0.5f) * 2f;
                    billboard(Mathf.lerp(ax, bx, t), Mathf.lerp(ay, by, t), Mathf.lerp(az, bz, t) + 0.6f, 0.55f, rx, ry, c);
                }
            }
        }

        float wz = z + (u instanceof Mechc ? 2.6f : 1.4f);
        for(WeaponMount mount : u.mounts){
            Weapon w = mount.weapon;
            if(w.region == null || !w.region.found()) continue;
            float wrot = rot + (w.rotate ? mount.rotation : 0f);
            float rx = u.x + Angles.trnsx(rot, w.x, w.y) + Angles.trnsx(wrot + 90f, 0f, -mount.recoil);
            float ry = u.y + Angles.trnsy(rot, w.x, w.y) + Angles.trnsy(wrot + 90f, 0f, -mount.recoil);
            part(w.region, w.flipSprite, rx, ry, wz + w.layerOffset * 4f, wrot, 0f);
        }
    }

    private void add(VoxelModel model, float x, float y, float z, float rot){
        Draw3 d = new Draw3();
        d.model = model; d.x = x; d.y = y; d.z = z; d.rot = rot;
        queue.add(d);
    }

    private void quad(TextureRegion r, float x, float y, float z, float w, float h, float rot){
        FloatSeq seq = flat.get(r.texture);
        if(seq == null) flat.put(r.texture, seq = new FloatSeq());

        float cos = Mathf.cosDeg(rot), sin = Mathf.sinDeg(rot);
        float hw = w / 2f, hh = h / 2f;
        float white = Color.whiteFloatBits;
        float[] cxs = {-hw, hw, hw, -hw}, cys = {-hh, -hh, hh, hh};
        float[] us = {r.u, r.u2, r.u2, r.u}, vs = {r.v2, r.v2, r.v, r.v};
        int[] order = {0, 1, 2, 0, 2, 3};
        for(int i : order){
            seq.add(x + cxs[i] * cos - cys[i] * sin, y + cxs[i] * sin + cys[i] * cos, z);
            seq.add(0f, 0f, 1f);
            seq.add(white);
            seq.add(us[i], vs[i]);
        }
    }

    private void drawFlat(){
        for(var e : flat){
            if(e.value.size == 0) continue;
            e.key.bind(0);
            flatMesh.setVertices(e.value.items, 0, Math.min(e.value.size, flatMesh.getMaxVertices() * vertexFloats));
            flatMesh.render(shader, Gl.triangles, 0, Math.min(e.value.size / vertexFloats, flatMesh.getMaxVertices()));
        }
    }

    private void drawQueue(){
        shader.setUniformf("u_scale", scl);
        for(Draw3 d : queue){
            Mesh mesh = mesh(d.model);
            shader.setUniformf("u_offset", d.x, d.y, d.z);
            shader.setUniformf("u_rot", d.rot);
            mesh.render(shader, Gl.triangles);
        }
        queue.clear();
    }

    private Mesh mesh(VoxelModel model){
        Mesh m = meshes.get(model);
        if(m != null) return m;
        int count = model.vertexCount();
        float[] data = new float[count * vertexFloats];
        for(int i = 0; i < count; i++){
            int o = i * vertexFloats, s = i * VoxelModel.stride, c = model.colors[i];
            System.arraycopy(model.vertices, s, data, o, 6);
            data[o + 6] = Color.toFloatBits((c >>> 24) & 0xff, (c >>> 16) & 0xff, (c >>> 8) & 0xff, c & 0xff);
            data[o + 7] = whiteU;
            data[o + 8] = whiteV;
        }
        m = new Mesh(true, count, model.indices.length, VertexAttribute.position3, VertexAttribute.normal, VertexAttribute.color, VertexAttribute.texCoords);
        m.setVertices(data);
        m.setIndices(model.indices);
        meshes.put(model, m);
        return m;
    }

    @Override
    public void dispose(){
        for(var e : meshes) e.value.dispose();
        meshes.clear();
        if(flatMesh != null) flatMesh.dispose();
        if(shader != null) shader.dispose();
    }
}
