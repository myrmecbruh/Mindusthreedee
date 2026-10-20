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
import mindustry.world.blocks.environment.*;

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

    private final Camera3D cam = new Camera3D();
    private final ModelCache models = new ModelCache();
    private final ObjectMap<VoxelModel, Mesh> meshes = new ObjectMap<>();
    private final ObjectMap<Texture, FloatSeq> flat = new ObjectMap<>();
    private final Seq<Draw3> queue = new Seq<>();
    private final Vec3 eye = new Vec3();

    private Shader shader;
    private Mesh flatMesh;
    private float whiteU, whiteV;
    private boolean initialised;

    private static class Draw3{
        VoxelModel model;
        float x, y, z, rot, sx = 1f;
    }

    public FpRenderer(){
        cam.fov = 75f;
        cam.near = 0.6f;
        cam.far = 4000f;
        chase = OS.env("FP_CHASE") != null;
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
            pitch = chase ? -0.5f : -0.12f;
        }
        return true;
    }

    private void ensureInit(){
        if(shader != null) return;
        shader = new Shader(Core.files.internal("shaders/voxel.vert"), Core.files.internal("shaders/voxel.frag"));
        flatMesh = new Mesh(false, 6 * (radius * 2 + 1) * (radius * 2 + 1) * 3, 0,
            VertexAttribute.position3, VertexAttribute.normal, VertexAttribute.color, VertexAttribute.texCoords);
        TextureRegion white = Core.atlas.white();
        whiteU = (white.u + white.u2) / 2f;
        whiteV = (white.v + white.v2) / 2f;
    }

    public void render(){
        ensureInit();
        Unit unit = player.unit();
        float px = unit != null && !unit.dead ? unit.x : Core.camera.position.x, py = unit != null && !unit.dead ? unit.y : Core.camera.position.y;

        float eyeZ = unit != null ? Math.max(5f, unit.hitSize * 0.8f) + unit.elevation * 3f : 6f;
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

        Draw.flush();
        Gl.clearColor(0.55f, 0.72f, 0.88f, 1f);
        Gl.clear(Gl.colorBufferBit | Gl.depthBufferBit);
        Gl.enable(Gl.depthTest);
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

        flat.each((t, seq) -> seq.clear());
        queue.clear();
        collectWorld(px, py, unit);
        collectUnits(unit);
        drawFlat();

        Core.atlas.white().texture.bind(0);
        drawQueue();

        Gl.disable(Gl.depthTest);
        Gl.enable(Gl.blend);
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
                if(tile.build != null && !tile.isCenter()) continue;

                float wx = tile.build != null ? tile.build.x : tile.worldx();
                float wy = tile.build != null ? tile.build.y : tile.worldy();
                TextureRegion region = block.region;
                if(block.variantRegions != null && block.variantRegions.length > 0){
                    region = block.variantRegions[Mathf.randomSeed(tile.pos(), 0, block.variantRegions.length - 1)];
                }
                if(region == null || !region.found()) continue;

                float rot = tile.build != null && block.rotate ? tile.build.rotdeg() : 0f;
                VoxelModel model = block.rotate ? null : models.get(region, Kind.grounded, 0f, false);
                if(model == null){
                    //still being fitted, or a part that turns: draw the sprite flat
                    quad(region, wx, wy, 0.1f, block.size * tilesize, block.size * tilesize, rot);
                }else{
                    add(model, wx, wy, 0f, 0f);
                }
            }
        }
    }

    private void collectUnits(Unit self){
        for(Unit u : Groups.unit){
            if(u == self || u.type == null) continue;
            if(Mathf.dst(u.x, u.y, eye.x, eye.y) > radius * tilesize) continue;
            TextureRegion body = u.type.region;
            float z0 = u.type.flying ? 2f + u.elevation * 4f : 1.5f;
            VoxelModel model = models.get(body, Kind.layer, 0f, false);
            if(model != null) add(model, u.x, u.y, z0, (u.rotation - 90f) * Mathf.degRad);
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
