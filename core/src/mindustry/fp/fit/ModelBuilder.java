package mindustry.fp.fit;

import mindustry.graphics.voxel.*;

import java.util.*;

/** Turns fitted plan shapes plus heights into a flat-shaded, solid colour mesh. Units: sprite pixels, z up, centred on (cx, cy). */
public class ModelBuilder{
    public enum Mode{
        /** Sits on the floor; tallest in the middle; the tallest part is (width + depth) / 2. */
        grounded,
        /** Hovers or walks: parts share a flat underside at z0 and rise by size and nearness to the centre. */
        layer
    }

    private static float hash(float a, float b){
        double x = Math.sin(a * 7.13 + b * 3.77) * 43758.5453;
        return (float)(x - Math.floor(x));
    }

    /**
     * @param z0 height of the underside of the model
     * @param maxHeight tallest allowed top above z0
     */
    public static VoxelModel build(List<Fitter.Shape> shapes, Fitter.Sym sym, Mode mode, int w, int h, float cx, float cy, float z0, float maxHeight, boolean flatBottom){
        int n = shapes.size();
        float[] zb = new float[n], zt = new float[n];
        float dmax = 1f;
        float[] dist = new float[n];
        for(int i = 0; i < n; i++){
            float[] c = centre(shapes.get(i));
            dist[i] = (float)Math.hypot(c[0] - cx, c[1] - cy);
            dmax = Math.max(dmax, dist[i]);
        }
        float tmax = 0.001f;
        for(int i = 0; i < n; i++){
            Fitter.Shape s = shapes.get(i);
            float[] c = centre(s);
            float dx = Math.abs(c[0] - cx), dy = Math.abs(c[1] - cy);
            float v = sym == Fitter.Sym.d4 ? hash(Math.min(dx, dy), Math.max(dx, dy)) : hash(dx, c[1] - cy);
            float near = 1f - dist[i] / dmax;
            float size = (float)Math.sqrt(area(s));
            float t = mode == Mode.grounded ? (0.3f + 0.7f * near) * (0.75f + 0.5f * v) : Math.max(4f, 1.0f * size * (0.7f + 0.8f * near) * (0.7f + 0.8f * v));
            zb[i] = z0;
            zt[i] = t;
            tmax = Math.max(tmax, t);
        }
        for(int i = 0; i < n; i++){
            float t = mode == Mode.grounded ? Math.max(maxHeight * 0.18f, maxHeight * zt[i] / tmax) : Math.min(maxHeight, zt[i]);
            zt[i] = z0 + t;
        }
        return mesh(shapes, zb, zt, cx, cy, flatBottom);
    }

    static float[] centre(Fitter.Shape s){
        float x = 0, y = 0;
        for(int i = 0; i < 4; i++){ x += s.poly[i * 2]; y += s.poly[i * 2 + 1]; }
        return new float[]{x / 4, y / 4};
    }

    static float area(Fitter.Shape s){
        float a = 0;
        for(int i = 0; i < 4; i++){
            int j = (i + 1) & 3;
            a += s.poly[i * 2] * s.poly[j * 2 + 1] - s.poly[j * 2] * s.poly[i * 2 + 1];
        }
        return Math.abs(a) / 2f;
    }

    public static VoxelModel mesh(List<Fitter.Shape> shapes, float[] zBot, float[] zTop, float cx, float cy, boolean flatBottom){
        List<float[]> verts = new ArrayList<>();      // x,y,z,nx,ny,nz
        List<Integer> cols = new ArrayList<>();
        List<Integer> idx = new ArrayList<>();
        float maxTop = 0;
        for(int k = 0; k < shapes.size(); k++){
            Fitter.Shape s = shapes.get(k);
            float zb = zBot[k], zt = zTop[k];
            maxTop = Math.max(maxTop, zt);
            float[][] e = new float[4][];                                // plan corners, y up and centred
            for(int i = 0; i < 4; i++) e[i] = new float[]{s.poly[i * 2] - cx, cy - s.poly[i * 2 + 1]};
            float mx = 0, my = 0;
            for(float[] p : e){ mx += p[0] / 4; my += p[1] / 4; }
            float zm = (zb + zt) / 2;
            boolean grounded = flatBottom && zb <= 0.01f;
            int rgba = s.rgba;
            float[] ctr = {mx, my, zm};
            if(!s.d8){
                float[][] lo = new float[4][], hi = new float[4][];
                for(int i = 0; i < 4; i++){ lo[i] = new float[]{e[i][0], e[i][1], zb}; hi[i] = new float[]{e[i][0], e[i][1], zt}; }
                quad(verts, cols, idx, hi[0], hi[1], hi[2], hi[3], ctr, rgba);
                if(!grounded) quad(verts, cols, idx, lo[0], lo[1], lo[2], lo[3], ctr, rgba);
                for(int i = 0; i < 4; i++){ int j = (i + 1) & 3; quad(verts, cols, idx, lo[i], lo[j], hi[j], hi[i], ctr, rgba); }
            }else{
                float[] top = {mx, my, zt}, bot = {mx, my, zb};
                float[][] eq = new float[4][];
                for(int i = 0; i < 4; i++) eq[i] = new float[]{e[i][0], e[i][1], zm};
                for(int i = 0; i < 4; i++){
                    int j = (i + 1) & 3;
                    tri(verts, cols, idx, eq[i], eq[j], top, ctr, rgba);
                    if(!grounded) tri(verts, cols, idx, eq[j], eq[i], bot, ctr, rgba);
                }
            }
        }
        float[] v = new float[verts.size() * VoxelModel.stride];
        int[] c = new int[verts.size()];
        for(int i = 0; i < verts.size(); i++){
            System.arraycopy(verts.get(i), 0, v, i * VoxelModel.stride, 6);
            c[i] = cols.get(i);
        }
        short[] id = new short[idx.size()];
        for(int i = 0; i < id.length; i++) id[i] = (short)(int)idx.get(i);
        return new VoxelModel(v, c, id, Math.round(maxTop));
    }

    private static void quad(List<float[]> v, List<Integer> c, List<Integer> idx, float[] a, float[] b, float[] d, float[] e, float[] ctr, int rgba){
        tri(v, c, idx, a, b, d, ctr, rgba);
        tri(v, c, idx, a, d, e, ctr, rgba);
    }

    private static void tri(List<float[]> v, List<Integer> c, List<Integer> idx, float[] a, float[] b, float[] d, float[] ctr, int rgba){
        float[] ab = {b[0] - a[0], b[1] - a[1], b[2] - a[2]}, ad = {d[0] - a[0], d[1] - a[1], d[2] - a[2]};
        float[] n = {ab[1] * ad[2] - ab[2] * ad[1], ab[2] * ad[0] - ab[0] * ad[2], ab[0] * ad[1] - ab[1] * ad[0]};
        float[] m = {(a[0] + b[0] + d[0]) / 3 - ctr[0], (a[1] + b[1] + d[1]) / 3 - ctr[1], (a[2] + b[2] + d[2]) / 3 - ctr[2]};
        if(n[0] * m[0] + n[1] * m[1] + n[2] * m[2] < 0){ float[] t = b; b = d; d = t; n = new float[]{-n[0], -n[1], -n[2]}; }
        float l = (float)Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
        if(l < 1e-6f) return;
        n[0] /= l; n[1] /= l; n[2] /= l;
        int base = v.size();
        for(float[] p : new float[][]{a, b, d}){
            v.add(new float[]{p[0], p[1], p[2], n[0], n[1], n[2]});
            c.add(rgba);
        }
        idx.add(base); idx.add(base + 1); idx.add(base + 2);
    }
}
