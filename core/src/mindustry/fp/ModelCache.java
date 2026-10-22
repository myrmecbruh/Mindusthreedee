package mindustry.fp;

import arc.*;
import arc.graphics.g2d.*;
import arc.graphics.g2d.TextureAtlas.*;
import arc.struct.*;
import mindustry.fp.fit.*;
import mindustry.fp.fit.Fitter.*;
import mindustry.graphics.voxel.*;

import java.util.concurrent.*;

/** Fits sprites to solid models on a background thread, the first time each is asked for, and keeps the results. */
public class ModelCache{
    public enum Kind{
        /** Buildings and props: on the floor. */
        grounded,
        /** Parts of units: a flat underside at z0. */
        layer
    }

    private static class Slot{
        volatile boolean poor;
        volatile VoxelModel model;
        volatile boolean failed;
    }

    private final ObjectMap<String, Slot> slots = new ObjectMap<>();
    private final ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "fp-model-fit");
        t.setDaemon(true);
        return t;
    });

    /**
     * Returns the model, or null while it is still being fitted.
     * @param z0 height of the underside (parts of units)
     * @param flip mirror the sprite left to right first (for the left-hand copy of a weapon)
     */
    public VoxelModel get(TextureRegion region, Kind kind, float z0, boolean flip){
        if(region == null || !region.found()) return null;
        String key = (region instanceof AtlasRegion a ? a.name : String.valueOf(System.identityHashCode(region))) + "|" + kind + "|" + (int)(z0 * 4) + "|" + flip;
        Slot slot = slots.get(key);
        if(slot != null) return slot.model;

        slot = new Slot();
        slots.put(key, slot);

        var pix = Core.atlas.getPixmap(region);
        if(pix == null){ slot.failed = true; return null; }
        int w = pix.width, h = pix.height;
        int[] rgba = new int[w * h];
        for(int y = 0; y < h; y++){
            for(int x = 0; x < w; x++) rgba[y * w + (flip ? w - 1 - x : x)] = pix.get(x, y);
        }
        Slot s = slot;
        pool.submit(() -> {
            try{
                s.model = fit(rgba, w, h, kind, z0, s);
            }catch(Throwable t){
                s.failed = true;
                arc.util.Log.err("Failed to fit a model", t);
            }
        });
        return null;
    }

    /** True once fitting has finished and found no acceptable model: draw something simpler. */
    public boolean poor(TextureRegion region, Kind kind, float z0, boolean flip){
        String key = (region instanceof AtlasRegion a ? a.name : String.valueOf(System.identityHashCode(region))) + "|" + kind + "|" + (int)(z0 * 4) + "|" + flip;
        Slot s = slots.get(key);
        return s != null && (s.poor || s.failed);
    }

    static Sym detect(int[] px, int w, int h){
        int lr = 0, ud = 0, rot = 0, uni = 0;
        for(int y = 0; y < h; y++) for(int x = 0; x < w; x++){
            boolean a = (px[y * w + x] & 0xff) > 127;
            boolean b = (px[y * w + (w - 1 - x)] & 0xff) > 127;
            boolean c = (px[(h - 1 - y) * w + x] & 0xff) > 127;
            boolean d = w == h && (px[x * w + (h - 1 - y)] & 0xff) > 127;
            if(a || b) uni++;
            if(a && b) lr++;
            if(a && c) ud++;
            if(a && d) rot++;
        }
        if(uni == 0) return Sym.none;
        float l = lr / (float)uni, u = ud / (float)uni, r = rot / (float)uni;
        if(l > 0.93f && u > 0.93f && (w != h || r > 0.93f)) return w == h ? Sym.d4 : Sym.lr;
        if(l > 0.93f) return Sym.lr;
        return Sym.none;
    }

    static VoxelModel fit(int[] px, int w, int h, Kind kind, float z0, Slot slot){
        int opaque = 0, x0 = w, x1 = -1, y0 = h, y1 = -1;
        for(int y = 0; y < h; y++) for(int x = 0; x < w; x++) if((px[y * w + x] & 0xff) > 127){
            opaque++;
            x0 = Math.min(x0, x); x1 = Math.max(x1, x); y0 = Math.min(y0, y); y1 = Math.max(y1, y);
        }
        if(opaque < 6) return null;
        Sym sym = detect(px, w, h);
        int budget = Math.max(12, Math.min(300, Math.round(opaque * 0.15f)));
        Fitter f = new Fitter(px, w, h, sym);
        f.allowOctahedra = kind != Kind.grounded;
        Result r = f.fit(budget, kind == Kind.layer ? 14 : 8);
        if(r.shapes.isEmpty() || (kind == Kind.grounded && r.coverage < 0.6f)){ slot.poor = true; return null; }
        float maxH = ((x1 - x0 + 1) + (y1 - y0 + 1)) / 2f;
        //buildings follow the (width + depth) / 2 rule; parts of units are much flatter
        if(kind == Kind.layer) maxH *= 0.4f;
        return ModelBuilder.build(r.shapes, sym, kind == Kind.grounded ? ModelBuilder.Mode.grounded : ModelBuilder.Mode.layer, w, h, w / 2f, h / 2f, z0, maxH, kind == Kind.grounded);
    }
}
