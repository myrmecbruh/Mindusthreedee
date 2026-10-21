package mindustry.fp;

import arc.*;
import arc.graphics.g2d.*;
import arc.graphics.g2d.TextureAtlas.*;
import arc.math.*;
import arc.struct.*;
import mindustry.fp.fit.*;
import mindustry.graphics.voxel.*;

import java.util.*;

/** Boulders: a blob of eight octahedra of varying sizes (64 faces), coloured from the sprite. */
public class RockModels{
    public static final int variants = 8, shapes = 8;
    private final ObjectMap<String, VoxelModel> cache = new ObjectMap<>();

    public VoxelModel get(TextureRegion region, int seed){
        String name = region instanceof AtlasRegion a ? a.name : String.valueOf(System.identityHashCode(region));
        String key = name + "|" + Math.floorMod(seed, variants);
        VoxelModel m = cache.get(key);
        if(m == null) cache.put(key, m = build(region, Math.floorMod(seed, variants)));
        return m;
    }

    private static VoxelModel build(TextureRegion region, int variant){
        int r = 128, g = 128, b = 128, n = 0;
        var pix = Core.atlas.getPixmap(region);
        if(pix != null){
            long sr = 0, sg = 0, sb = 0;
            for(int y = 0; y < pix.height; y++) for(int x = 0; x < pix.width; x++){
                int v = pix.get(x, y);
                if((v & 0xff) < 128) continue;
                sr += (v >>> 24) & 0xff; sg += (v >>> 16) & 0xff; sb += (v >>> 8) & 0xff; n++;
            }
            if(n > 0){ r = (int)(sr / n); g = (int)(sg / n); b = (int)(sb / n); }
        }

        Random rng = new Random(variant * 7919L + 17);
        float w = pix != null ? pix.width : 48f, cx = w / 2f, cy = (pix != null ? pix.height : 48f) / 2f;
        List<Fitter.Shape> list = new ArrayList<>();
        float[] zb = new float[shapes], zt = new float[shapes];
        for(int i = 0; i < shapes; i++){
            float rad, x, y;
            if(i == 0){
                rad = w * (0.34f + rng.nextFloat() * 0.08f); x = cx; y = cy;
            }else{
                rad = w * (0.14f + rng.nextFloat() * 0.16f);
                float ang = (float)(i / (float)(shapes - 1) * Math.PI * 2 + rng.nextFloat() * 0.8f), dist = w * (0.12f + rng.nextFloat() * 0.14f);
                x = cx + (float)Math.cos(ang) * dist; y = cy + (float)Math.sin(ang) * dist;
            }
            float rot = rng.nextFloat() * (float)Math.PI;
            Fitter.Shape s = new Fitter.Shape();
            s.d8 = true;
            for(int k = 0; k < 4; k++){
                float a = rot + k * (float)(Math.PI / 2);
                s.poly[k * 2] = x + (float)Math.cos(a) * rad;
                s.poly[k * 2 + 1] = y + (float)Math.sin(a) * rad;
            }
            float shade = 1.0f + rng.nextFloat() * 0.4f;
            int rr = Math.min(255, (int)(r * shade)), gg = Math.min(255, (int)(g * shade)), bb = Math.min(255, (int)(b * shade));
            s.rgba = (rr << 24) | (gg << 16) | (bb << 8) | 0xff;
            list.add(s);
            //sits on the floor with its equator half way up, so the apex rises about 0.8 of the radius above the equator
            zb[i] = 0f;
            zt[i] = rad * 1.25f * (0.85f + rng.nextFloat() * 0.3f);
        }
        return ModelBuilder.mesh(list, zb, zt, cx, cy, true);
    }
}
