package mindustry.fp;

import arc.*;
import arc.func.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.graphics.g2d.TextureAtlas.*;
import arc.math.*;
import arc.struct.*;
import arc.util.*;
import mindustry.*;
import mindustry.fp.ModelCache.*;
import mindustry.gen.*;
import mindustry.graphics.voxel.*;

/**
 * Draws a building in 3D by letting the game draw it, into a {@link RecordingBatch}, and then turning each sprite it asked for into a solid
 * model (or a flat quad, for things that are not solid), stacked in the order the game layered them. Animation, rotation, tinting and
 * every layer a block has therefore come for free, from the game's own drawing code.
 */
public class BuildingRenderer{
    private final RecordingBatch batch = new RecordingBatch();
    private final ModelCache models;
    private final ObjectIntMap<Texture> textureIds = new ObjectIntMap<>();
    private final LongMap<AtlasRegion> byUv = new LongMap<>();
    private boolean indexed;

    private final Func<Texture, FloatSeq> solid, glow;
    private final float whiteU, whiteV;
    private final Texture whiteTexture;

    /** What a building drew the last time it was recorded; distant buildings replay it for a few frames instead of being drawn again. */
    private static class Cache{
        final Seq<Texture> textures = new Seq<>();
        final Seq<FloatSeq> data = new Seq<>();
        final Seq<Boolean> glowing = new Seq<>();
        int used, refreshed;
        boolean empty;

        FloatSeq slot(Texture t, boolean glow){
            for(int i = 0; i < used; i++) if(textures.get(i) == t && glowing.get(i) == glow) return data.get(i);
            if(used >= data.size){ data.add(new FloatSeq()); textures.add(t); glowing.add(glow); }
            else{ textures.set(used, t); glowing.set(used, glow); data.get(used).clear(); }
            return data.get(used++);
        }

        void clear(){
            for(int i = 0; i < used; i++) data.get(i).clear();
            used = 0;
        }
    }

    private final IntMap<Cache> caches = new IntMap<>();
    private int frame;
    private Cache cache;

    /** Call once per frame before drawing any buildings. */
    public void beginFrame(){
        frame++;
        if(frame % 600 == 0){
            //forget buildings that have not been drawn for a while
            var it = caches.entries();
            while(it.hasNext()){
                var e = it.next();
                if(frame - e.value.refreshed > 300) it.remove();
            }
        }
    }

    /** Footprints already placed for the building being drawn: x, y, radius, top. */
    private final FloatSeq placed = new FloatSeq();

    public BuildingRenderer(ModelCache models, Func<Texture, FloatSeq> solid, Func<Texture, FloatSeq> glow){
        this.models = models;
        this.solid = solid;
        this.glow = glow;
        TextureRegion white = Core.atlas.white();
        whiteTexture = white.texture;
        whiteU = (white.u + white.u2) / 2f;
        whiteV = (white.v + white.v2) / 2f;
    }

    private long key(Texture t, float u0, float v0, float u1, float v1){
        int id = textureIds.get(t, -1);
        if(id < 0){ id = textureIds.size; textureIds.put(t, id); }
        long a = Math.round(Math.min(u0, u1) * 16384f), b = Math.round(Math.min(v0, v1) * 16384f), c = Math.round(Math.max(u0, u1) * 16384f), d = Math.round(Math.max(v0, v1) * 16384f);
        return (((((long)id * 16411L + a) * 16411L + b) * 16411L + c) * 16411L) + d;
    }

    private void index(){
        indexed = true;
        for(AtlasRegion r : Core.atlas.getRegions()){
            byUv.put(key(r.texture, r.u, r.v, r.u2, r.v2), r);
        }
    }

    /**
     * Draws a block's own sprite stack for this building. Returns false if it drew nothing (the caller falls back to something simpler).
     * @param interval how many frames a recording may be reused for (1 = record every frame)
     */
    public boolean draw(Building build, int interval){
        if(!indexed) index();

        Cache c = caches.get(build.id);
        if(c == null) caches.put(build.id, c = new Cache());
        boolean due = interval <= 1 || c.refreshed == 0 || (frame + build.id) % interval == 0 || frame - c.refreshed >= interval;
        if(due){
            c.clear();
            cache = c;
            c.empty = !record(build);
            cache = null;
            c.refreshed = frame;
        }
        if(c.empty) return false;
        for(int i = 0; i < c.used; i++){
            FloatSeq out = (c.glowing.get(i) ? glow : solid).get(c.textures.get(i));
            out.addAll(c.data.get(i));
        }
        return true;
    }

    private boolean record(Building build){

        batch.begin();
        Batch previous = Core.batch;
        Core.batch = batch;
        try{
            build.draw();
        }catch(Throwable t){
            //one building's drawing bug must not take the frame down
        }finally{
            Core.batch = previous;
            Draw.reset();
        }
        if(batch.sprites.isEmpty()) return false;

        batch.sprites.sort((a, b) -> a.z != b.z ? Float.compare(a.z, b.z) : a.order - b.order);
        placed.clear();
        float footprint = build.block.size * Vars.tilesize;
        boolean flatBlock = build.block.underBullets;
        boolean wall = build.block.group == mindustry.world.meta.BlockGroup.walls;
        int rank = 0, modelsPlaced = 0;

        for(RecordingBatch.Sprite s : batch.sprites){
            int bits = Float.floatToRawIntBits(s.color);
            int cr = bits & 0xff, cg = (bits >>> 8) & 0xff, cb = (bits >>> 16) & 0xff, ca = (bits >>> 24) & 0xff;
            int mixBits = Float.floatToRawIntBits(s.mix), ma = (mixBits >>> 24) & 0xff;
            if(ma > 4){
                float t = ma / 255f;
                cr = (int)Mathf.lerp(cr, mixBits & 0xff, t);
                cg = (int)Mathf.lerp(cg, (mixBits >>> 8) & 0xff, t);
                cb = (int)Mathf.lerp(cb, (mixBits >>> 16) & 0xff, t);
            }
            float tint = Color.toFloatBits(cr, cg, cb, ca);

            float cx = (s.x[0] + s.x[1] + s.x[2] + s.x[3]) / 4f, cy = (s.y[0] + s.y[1] + s.y[2] + s.y[3]) / 4f;
            float rx = s.x[3] - s.x[0], ry = s.y[3] - s.y[0];           //bottom left to bottom right
            float ux = s.x[1] - s.x[0], uy = s.y[1] - s.y[0];           //bottom left to top left
            float w = Mathf.len(rx, ry), h = Mathf.len(ux, uy);
            float radius = Math.max(w, h) * 0.42f;

            AtlasRegion region = byUv.get(key(s.texture, s.u[0], s.v[0], s.u[2], s.v[2]));
            boolean translucent = ca < 242 || s.additive;
            boolean isWhite = region == null || region == Core.atlas.white() || (region.name != null && region.name.equals("white"));

            float top = stackTop(cx, cy, radius);

            if(translucent){
                emitQuad(cache.slot(s.texture, true), s, top + 0.12f + rank * 0.01f, tint);
                rank++;
                continue;
            }
            if(flatBlock || isWhite || w < 1f || h < 1f){
                emitQuad(cache.slot(s.texture, false), s, (flatBlock ? 0.1f : top + 0.06f) + rank * 0.02f, tint);
                rank++;
                continue;
            }

            VoxelModel model = models.get(region, Kind.layer, 0f, false);
            if(model == null){
                //still being fitted: a thin textured slab for now
                emitQuad(cache.slot(s.texture, false), s, top + 0.1f, tint);
                addPlaced(cx, cy, radius, top + 0.1f);
                continue;
            }

            //a detail on a big block (a lamp, a rotor hub) stays thin; the big parts get their full thickness
            float thickness = Math.max(0.3f, model.maxHeight * 0.25f);
            //the first solid layer is the body of the block; everything above it is kept thin
            thickness = Math.min(thickness, modelsPlaced == 0 ? footprint * (wall ? 0.9f : 0.35f) : footprint * 0.16f);
            //walls are stretched up to stand about as tall as they are wide
            if(wall && modelsPlaced == 0) thickness = footprint * 0.8f;
            modelsPlaced++;
            float sz = 0.25f * thickness / Math.max(0.3f, model.maxHeight * 0.25f);

            //the sprite's own orientation: which way its image runs, allowing for a region drawn mirrored
            boolean flipX = (s.u[3] - s.u[0]) * (region.u2 - region.u) < 0f, flipY = (s.v[1] - s.v[0]) * (region.v - region.v2) < 0f;
            float irx = flipX ? -rx : rx, iry = flipX ? -ry : ry, iux = flipY ? -ux : ux, iuy = flipY ? -uy : uy;
            float theta = Mathf.atan2(iry, irx);
            float cos = Mathf.cos(theta), sin = Mathf.sin(theta);
            float pw = Math.max(1f, region.width), ph = Math.max(1f, region.height);
            float sx = w / pw;
            float sy = (iux * -sin + iuy * cos >= 0f ? 1f : -1f) * h / ph;

            emitModel(cache.slot(whiteTexture, false), model, cx, cy, top, cos, sin, sx, sy, sz, tint);
            addPlaced(cx, cy, radius, top + thickness);
            rank++;
        }
        return true;
    }

    private float stackTop(float x, float y, float r){
        float top = 0f;
        for(int i = 0; i < placed.size; i += 4){
            float dx = placed.items[i] - x, dy = placed.items[i + 1] - y, rr = placed.items[i + 2] + r;
            if(dx * dx + dy * dy < rr * rr * 0.8f) top = Math.max(top, placed.items[i + 3]);
        }
        return top;
    }

    private void addPlaced(float x, float y, float r, float top){
        placed.add(x, y, r, top);
    }

    /** The sprite's four corners as a quad at one height, textured as the game drew it. */
    private void emitQuad(FloatSeq seq, RecordingBatch.Sprite s, float z, float color){
        int[] order = {0, 1, 2, 0, 2, 3};
        for(int i : order){
            seq.add(s.x[i], s.y[i], z);
            seq.add(0f, 0f, 1f);
            seq.add(color);
            seq.add(s.u[i], s.v[i]);
        }
    }

    private void emitModel(FloatSeq seq, VoxelModel m, float cx, float cy, float z0, float cos, float sin, float sx, float sy, float sz, float tint){
        int tb = Float.floatToRawIntBits(tint);
        int tr = tb & 0xff, tg = (tb >>> 8) & 0xff, tbl = (tb >>> 16) & 0xff, ta = (tb >>> 24) & 0xff;
        float[] v = m.vertices;
        int[] colors = m.colors;
        short[] idx = m.indices;
        float nsx = Math.signum(sx), nsy = Math.signum(sy);
        for(int k = 0; k < idx.length; k++){
            int i = idx[k], o = i * VoxelModel.stride;
            float px = v[o] * sx, py = v[o + 1] * sy, pz = v[o + 2] * sz;
            float nx = v[o + 3] * nsx, ny = v[o + 4] * nsy, nz = v[o + 5];
            int c = colors[i];
            int r = ((c >>> 24) & 0xff) * tr / 255, g = ((c >>> 16) & 0xff) * tg / 255, b = ((c >>> 8) & 0xff) * tbl / 255, a = (c & 0xff) * ta / 255;
            seq.add(cx + px * cos - py * sin, cy + px * sin + py * cos, z0 + pz);
            seq.add(nx * cos - ny * sin, nx * sin + ny * cos, nz);
            seq.add(Color.toFloatBits(r, g, b, a));
            seq.add(whiteU, whiteV);
        }
    }
}
