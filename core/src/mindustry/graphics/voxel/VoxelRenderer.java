package mindustry.graphics.voxel;

import arc.*;
import arc.graphics.*;
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
import mindustry.gen.*;
import mindustry.world.*;
import mindustry.world.blocks.environment.*;

import static mindustry.Vars.*;

/**
 * Spike: draws the world in perspective instead of top-down. Floors are textured quads, static blocks are voxelized
 * from their atlas sprite once and cached. Blocks that rotate are drawn as flat sprites until they get their own path.
 * <p>
 * F8 toggles, [ and ] orbit, - and = tilt.
 */
public class VoxelRenderer implements Disposable{
    private static final int vertexFloats = 9;

    public boolean enabled;
    /** Orbit angle in degrees around the camera target. */
    public float yaw = -90f;
    public float pitch = 55f;
    /** Camera distance from the target in world units. */
    public float distance = 150f;
    /** How many tiles around the camera target are drawn. */
    public int radius = 36;

    private final Camera3D cam = new Camera3D();
    private final ObjectMap<TextureRegion, Entry> models = new ObjectMap<>();
    private final ObjectMap<Texture, FloatSeq> flat = new ObjectMap<>();
    private final Vec3 target = new Vec3();

    private Shader shader;
    private Mesh flatMesh;
    private float whiteU, whiteV;

    private static class Entry{
        Mesh mesh;
        int triangles;
    }

    public VoxelRenderer(){
        cam.fov = 60f;
        cam.near = 4f;
        cam.far = 4000f;
    }

    /** Handles input. Returns true if the world should be drawn with this renderer this frame. */
    public boolean update(){
        if(Core.input.keyTap(KeyCode.f8)) enabled = !enabled;
        if(!enabled) return false;

        float turn = Time.delta * 1.5f;
        if(Core.input.keyDown(KeyCode.leftBracket)) yaw -= turn;
        if(Core.input.keyDown(KeyCode.rightBracket)) yaw += turn;
        if(Core.input.keyDown(KeyCode.minus)) pitch -= turn;
        if(Core.input.keyDown(KeyCode.equals)) pitch += turn;
        pitch = Mathf.clamp(pitch, 10f, 89f);
        return true;
    }

    public void render(){
        if(shader == null){
            shader = new Shader(Core.files.internal("shaders/voxel.vert"), Core.files.internal("shaders/voxel.frag"));
            flatMesh = new Mesh(false, 6 * (radius * 2 + 1) * (radius * 2 + 1) * 3, 0,
                VertexAttribute.position3, VertexAttribute.normal, VertexAttribute.color, VertexAttribute.texCoords);
            TextureRegion white = Core.atlas.white();
            whiteU = (white.u + white.u2) / 2f;
            whiteV = (white.v + white.v2) / 2f;
        }

        target.set(Core.camera.position.x, Core.camera.position.y, 0f);
        float yawRad = yaw * Mathf.degRad, pitchRad = pitch * Mathf.degRad;
        cam.up.set(Vec3.Z);
        cam.position.set(
            target.x + Mathf.cos(yawRad) * Mathf.cos(pitchRad) * distance,
            target.y + Mathf.sin(yawRad) * Mathf.cos(pitchRad) * distance,
            Mathf.sin(pitchRad) * distance
        );
        cam.lookAt(target);
        cam.resize(Core.graphics.getWidth(), Core.graphics.getHeight());
        cam.update();

        Draw.flush();
        Gl.clear(Gl.depthBufferBit);
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
        collect();
        drawFlat();

        Core.atlas.white().texture.bind(0);
        drawModels();

        Gl.disable(Gl.depthTest);
        Gl.enable(Gl.blend);
    }

    /** Walks the tiles near the target. Floors and flat blocks are batched, static blocks are drawn as voxel models. */
    private void collect(){
        int cx = World.toTile(target.x), cy = World.toTile(target.y);
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

                Entry entry = block.rotate ? null : entry(region);
                if(entry == null){
                    quad(region, wx, wy, 0.1f, block.size * tilesize, block.size * tilesize, tile.build != null ? tile.build.rotdeg() : 0f);
                }else{
                    draw(entry, wx, wy, 0f);
                }
            }
        }
    }

    private void quad(TextureRegion r, float x, float y, float z, float w, float h, float rot){
        FloatSeq seq = flat.get(r.texture);
        if(seq == null) flat.put(r.texture, seq = new FloatSeq());

        float cos = Mathf.cosDeg(rot), sin = Mathf.sinDeg(rot);
        float hw = w / 2f, hh = h / 2f;
        float white = Color.whiteFloatBits;
        //corners: bottom-left, bottom-right, top-right, top-left
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
            flatMesh.setVertices(e.value.items, 0, e.value.size);
            flatMesh.render(shader, Gl.triangles, 0, e.value.size / vertexFloats);
        }
    }

    private final Seq<Object[]> queued = new Seq<>();

    private void draw(Entry entry, float x, float y, float rot){
        queued.add(new Object[]{entry, x, y, rot});
    }

    private void drawModels(){
        shader.setUniformf("u_scale", Draw.scl);
        for(Object[] q : queued){
            shader.setUniformf("u_offset", (float)q[1], (float)q[2], 0f);
            shader.setUniformf("u_rot", (float)q[3] * Mathf.degRad);
            ((Entry)q[0]).mesh.render(shader, Gl.triangles);
        }
        queued.clear();
    }

    /** Voxelizes the region the first time it is requested. Returns null if the sprite cannot be voxelized. */
    private Entry entry(TextureRegion region){
        if(models.containsKey(region)) return models.get(region);

        Entry entry = null;
        try{
            PixmapRegion pix = Core.atlas.getPixmap(region);
            if(pix != null){
                int w = pix.width, h = pix.height;
                int[] rgba = new int[w * h];
                for(int y = 0; y < h; y++){
                    for(int x = 0; x < w; x++) rgba[y * w + x] = pix.get(x, y);
                }
                VoxelModel model = Voxelizer.build(rgba, w, h);
                if(model != null && model.triangleCount() > 0) entry = upload(model);
            }
        }catch(Throwable t){
            Log.err("Failed to voxelize a sprite", t);
        }
        models.put(region, entry);
        return entry;
    }

    private Entry upload(VoxelModel model){
        int count = model.vertexCount();
        float[] data = new float[count * vertexFloats];
        for(int i = 0; i < count; i++){
            int o = i * vertexFloats, s = i * VoxelModel.stride, c = model.colors[i];
            data[o] = model.vertices[s];
            data[o + 1] = model.vertices[s + 1];
            data[o + 2] = model.vertices[s + 2];
            data[o + 3] = model.vertices[s + 3];
            data[o + 4] = model.vertices[s + 4];
            data[o + 5] = model.vertices[s + 5];
            data[o + 6] = Color.toFloatBits((c >>> 24) & 0xff, (c >>> 16) & 0xff, (c >>> 8) & 0xff, c & 0xff);
            data[o + 7] = whiteU;
            data[o + 8] = whiteV;
        }
        Entry e = new Entry();
        e.mesh = new Mesh(true, count, model.indices.length,
            VertexAttribute.position3, VertexAttribute.normal, VertexAttribute.color, VertexAttribute.texCoords);
        e.mesh.setVertices(data);
        e.mesh.setIndices(model.indices);
        e.triangles = model.triangleCount();
        return e;
    }

    @Override
    public void dispose(){
        for(var e : models){
            if(e.value != null) e.value.mesh.dispose();
        }
        models.clear();
        if(flatMesh != null) flatMesh.dispose();
        if(shader != null) shader.dispose();
    }
}
