package mindustry.fp.fit;

import java.util.*;

/**
 * Fits a few large, simple solids (skewed boxes and octahedra) to a flat sprite.
 * <p>
 * Rules: every shape stays inside the sprite's opaque pixels (plus a one pixel margin), shapes belong to one colour area each,
 * the result is symmetric, and the total number of die faces stays within a budget (a box costs 6, an octahedron 8).
 * Shapes are returned as plan polygons; heights are decided later, by whoever assembles the model.
 */
public class Fitter{
    public enum Sym{ none, lr, d4 }

    public boolean allowOctahedra = true;
    public static final int margin = 1;
    public static final float spillWeight = 0.2f, facePenalty = 0.08f;

    public static class Shape{
        public boolean d8;
        /** Four plan vertices x0,y0,...,x3,y3, in sprite pixels with y down. For an octahedron these are its equator corners. */
        public float[] poly = new float[8];
        public int rgba;
        /** Fraction of the shape's footprint over opaque pixels of its own colour. */
        public float purity;

        public int faces(){
            return d8 ? 8 : 6;
        }
    }

    public static class Result{
        public final List<Shape> shapes = new ArrayList<>();
        public int faces;
        public float coverage;
    }

    /** Parameters of a single shape while it is being searched for. */
    private static class P{
        boolean d8;
        float cx, cy, hu, hv, ang, skew;

        P copy(){
            P p = new P();
            p.d8 = d8; p.cx = cx; p.cy = cy; p.hu = hu; p.hv = hv; p.ang = ang; p.skew = skew;
            return p;
        }

        float[] poly(){
            float c = (float)Math.cos(ang), s = (float)Math.sin(ang);
            float[] u = d8 ? new float[]{-hu, 0, 0, hu, 0, 0, 0, 0} : new float[]{-hu, hu, hu, -hu, 0, 0, 0, 0};
            float[] v = d8 ? new float[]{0, -hv, 0, hv, 0, 0, 0, 0} : new float[]{-hv, -hv, hv, hv, 0, 0, 0, 0};
            float[] out = new float[8];
            if(d8){
                u = new float[]{-hu, 0, hu, 0}; v = new float[]{0, -hv, 0, hv};
            }else{
                u = new float[]{-hu, hu, hu, -hu}; v = new float[]{-hv, -hv, hv, hv};
            }
            for(int i = 0; i < 4; i++){
                float u2 = u[i] + skew * v[i];
                out[i * 2] = cx + u2 * c - v[i] * s;
                out[i * 2 + 1] = cy + u2 * s + v[i] * c;
            }
            return ccw(out);
        }
    }

    static float[] ccw(float[] p){
        float s = 0;
        for(int i = 0; i < 4; i++){
            int j = (i + 1) % 4;
            s += p[i * 2] * p[j * 2 + 1] - p[j * 2] * p[i * 2 + 1];
        }
        if(s >= 0) return p;
        float[] r = new float[8];
        for(int i = 0; i < 4; i++){
            r[i * 2] = p[(3 - i) * 2];
            r[i * 2 + 1] = p[(3 - i) * 2 + 1];
        }
        return r;
    }

    // ---- input and masks ----
    private final int w, h;
    private final int[] px;
    private final boolean[] solid;
    private boolean[] allowed;      // symmetric: opaque plus margin, for every member of an orbit
    private int[] label;            // colour area of each pixel, -1 outside; symmetric
    private final List<Integer> colors = new ArrayList<>();
    private final float axisX, axisY;
    private final Sym sym;
    private final float[][] group;  // 2x2 matrices (a, b, c, d)
    private final Random rng = new Random(7);
    private boolean[] covered;
    private int[] dist;

    // scratch for scanning
    private int gain, spill, touched;

    public Fitter(int[] rgba, int w, int h, Sym sym){
        this(rgba, w, h, sym, w / 2f, h / 2f);
    }

    public Fitter(int[] rgba, int w, int h, Sym sym, float axisX, float axisY){
        this.px = rgba; this.w = w; this.h = h; this.sym = sym; this.axisX = axisX; this.axisY = axisY;
        solid = new boolean[w * h];
        for(int i = 0; i < w * h; i++) solid[i] = (rgba[i] & 0xff) > 127;
        group = sym == Sym.none ? new float[][]{{1, 0, 0, 1}} :
            sym == Sym.lr ? new float[][]{{1, 0, 0, 1}, {-1, 0, 0, 1}} :
            new float[][]{{1, 0, 0, 1}, {-1, 0, 0, 1}, {0, -1, 1, 0}, {0, 1, 1, 0}, {0, 1, -1, 0}, {0, -1, -1, 0}, {1, 0, 0, -1}, {-1, 0, 0, -1}};
        buildMasks();
    }

    // ---- colour areas ----
    /** Pieces of one exact colour; thin ridges (no pixel with two neighbours on every side) are absorbed into the nearest real area. */
    private int[] areas(){
        int n = w * h;
        int[] piece = new int[n];
        Arrays.fill(piece, -1);
        int pieces = 0;
        int[] stack = new int[n];
        for(int s = 0; s < n; s++){
            if(!solid[s] || piece[s] >= 0) continue;
            int id = pieces++, sp = 0, color = px[s];
            stack[sp++] = s; piece[s] = id;
            while(sp > 0){
                int i = stack[--sp], x = i % w, y = i / w;
                for(int d = 0; d < 4; d++){
                    int xx = x + DX[d], yy = y + DY[d];
                    if(xx < 0 || yy < 0 || xx >= w || yy >= h) continue;
                    int j = yy * w + xx;
                    if(solid[j] && piece[j] < 0 && px[j] == color){ piece[j] = id; stack[sp++] = j; }
                }
            }
        }
        // distance to the edge of the piece
        int[] dt = new int[n];
        int[] q = new int[n]; int qh = 0, qt = 0;
        for(int i = 0; i < n; i++){
            if(!solid[i]) continue;
            int x = i % w, y = i / w;
            boolean edge = false;
            for(int d = 0; d < 4 && !edge; d++){
                int xx = x + DX[d], yy = y + DY[d];
                edge = xx < 0 || yy < 0 || xx >= w || yy >= h || piece[yy * w + xx] != piece[i];
            }
            if(edge){ dt[i] = 1; q[qt++] = i; }
        }
        while(qh < qt){
            int i = q[qh++], x = i % w, y = i / w;
            for(int d = 0; d < 4; d++){
                int xx = x + DX[d], yy = y + DY[d];
                if(xx < 0 || yy < 0 || xx >= w || yy >= h) continue;
                int j = yy * w + xx;
                if(solid[j] && piece[j] == piece[i] && dt[j] == 0){ dt[j] = dt[i] + 1; q[qt++] = j; }
            }
        }
        int[] lab = new int[n];
        Arrays.fill(lab, -1);
        qh = qt = 0;
        for(int i = 0; i < n; i++) if(solid[i] && dt[i] >= 2){ lab[i] = piece[i]; q[qt++] = i; }
        if(qt == 0){ // nothing thick enough: every piece counts
            for(int i = 0; i < n; i++) if(solid[i]){ lab[i] = piece[i]; q[qt++] = i; }
        }
        while(qh < qt){
            int i = q[qh++], x = i % w, y = i / w;
            for(int d = 0; d < 4; d++){
                int xx = x + DX[d], yy = y + DY[d];
                if(xx < 0 || yy < 0 || xx >= w || yy >= h) continue;
                int j = yy * w + xx;
                if(solid[j] && lab[j] < 0){ lab[j] = lab[i]; q[qt++] = j; }
            }
        }
        // colour of each area
        int[] colorOf = new int[pieces];
        for(int i = 0; i < n; i++) if(solid[i]) colorOf[piece[i]] = px[i];
        for(int p = 0; p < pieces; p++) colors.add(colorOf[p]);
        return lab;
    }

    private static final int[] DX = {1, -1, 0, 0}, DY = {0, 0, 1, -1};

    private float[] apply(float[] m, float x, float y){
        float u = x - axisX, v = y - axisY;
        return new float[]{axisX + m[0] * u + m[1] * v, axisY + m[2] * u + m[3] * v};
    }

    private void buildMasks(){
        int n = w * h;
        int[] lab0 = areas();
        boolean[] raw = solid.clone();
        for(int m = 0; m < margin; m++){
            boolean[] nx = raw.clone();
            for(int y = 0; y < h; y++) for(int x = 0; x < w; x++) if(raw[y * w + x]){
                for(int dy = -1; dy <= 1; dy++) for(int dx = -1; dx <= 1; dx++){
                    int xx = x + dx, yy = y + dy;
                    if(xx >= 0 && yy >= 0 && xx < w && yy < h) nx[yy * w + xx] = true;
                }
            }
            raw = nx;
        }
        allowed = new boolean[n];
        label = new int[n];
        Arrays.fill(label, -1);
        for(int i = 0; i < n; i++){
            int x = i % w, y = i / w, min = i;
            boolean ok = true, sol = true;
            for(float[] g : group){
                float[] c = apply(g, x + 0.5f, y + 0.5f);
                int xx = (int)Math.floor(c[0]), yy = (int)Math.floor(c[1]);
                if(xx < 0 || yy < 0 || xx >= w || yy >= h){ ok = false; sol = false; break; }
                int j = yy * w + xx;
                if(!raw[j]) ok = false;
                if(!solid[j]) sol = false;
                min = Math.min(min, j);
            }
            allowed[i] = ok;
            if(sol) label[i] = lab0[min];
        }
        // distance to the edge of the own colour area, for choosing big seeds first
        dist = new int[n];
        int[] q = new int[n]; int qh = 0, qt = 0;
        for(int i = 0; i < n; i++){
            if(label[i] < 0) continue;
            int x = i % w, y = i / w;
            boolean edge = false;
            for(int d = 0; d < 4 && !edge; d++){
                int xx = x + DX[d], yy = y + DY[d];
                edge = xx < 0 || yy < 0 || xx >= w || yy >= h || label[yy * w + xx] != label[i];
            }
            if(edge){ dist[i] = 1; q[qt++] = i; }
        }
        while(qh < qt){
            int i = q[qh++], x = i % w, y = i / w;
            for(int d = 0; d < 4; d++){
                int xx = x + DX[d], yy = y + DY[d];
                if(xx < 0 || yy < 0 || xx >= w || yy >= h) continue;
                int j = yy * w + xx;
                if(label[j] == label[i] && dist[j] == 0){ dist[j] = dist[i] + 1; q[qt++] = j; }
            }
        }
    }

    // ---- scanning ----
    private static float[] span(float[] p, float y){
        float xl = 1e9f, xr = -1e9f;
        for(int i = 0; i < 4; i++){
            int j = (i + 1) & 3;
            float ay = p[i * 2 + 1], by = p[j * 2 + 1];
            if((ay <= y && by > y) || (by <= y && ay > y)){
                float x = p[i * 2] + (y - ay) * (p[j * 2] - p[i * 2]) / (by - ay);
                if(x < xl) xl = x;
                if(x > xr) xr = x;
            }
        }
        return xl <= xr ? new float[]{xl, xr} : null;
    }

    private static final float[] FRACS = {0.03f, 0.5f, 0.97f};

    /** Returns false if the polygon touches any pixel outside the allowed mask. Otherwise counts new and wasted pixels of the given colour area. */
    private boolean scan(float[] p, int own){
        gain = 0; spill = 0;
        float y0 = 1e9f, y1 = -1e9f;
        for(int i = 0; i < 4; i++){ y0 = Math.min(y0, p[i * 2 + 1]); y1 = Math.max(y1, p[i * 2 + 1]); }
        for(int r = (int)Math.floor(y0); r < (int)Math.ceil(y1); r++){
            for(float f : FRACS){
                float[] s = span(p, r + f);
                if(s == null) continue;
                int a = (int)Math.floor(s[0] + 1e-6f), b = (int)Math.ceil(s[1] - 1e-6f) - 1;
                for(int x = a; x <= b; x++){
                    if(x < 0 || r < 0 || x >= w || r >= h || !allowed[r * w + x]) return false;
                }
            }
            if(r < 0 || r >= h) continue;
            float[] s = span(p, r + 0.5f);
            if(s == null) continue;
            int a = (int)Math.ceil(s[0] - 0.5f), b = (int)Math.floor(s[1] - 0.5f);
            for(int x = Math.max(0, a); x <= Math.min(w - 1, b); x++){
                int i = r * w + x;
                if(label[i] == own){ if(!covered[i]) gain++; }else spill++;
            }
        }
        return true;
    }

    // ---- symmetry ----
    private P snap(P p){
        P q = p.copy();
        float near = 1.6f, half = (float)(Math.PI / 2);
        float dx = q.cx - axisX, dy = q.cy - axisY;
        if(sym == Sym.lr){
            if(Math.abs(dx) < near){ q.cx = axisX; q.ang = Math.round(q.ang / half) * half; q.skew = 0; }
        }else if(sym == Sym.d4){
            boolean onX = Math.abs(dx) < near, onY = Math.abs(dy) < near;
            if(onX && onY){ q.cx = axisX; q.cy = axisY; q.hu = q.hv = (q.hu + q.hv) / 2; q.ang = 0; q.skew = 0; }
            else if(onX){ q.cx = axisX; q.ang = Math.round(q.ang / half) * half; q.skew = 0; }
            else if(onY){ q.cy = axisY; q.ang = Math.round(q.ang / half) * half; q.skew = 0; }
            else if(Math.abs(Math.abs(dx) - Math.abs(dy)) < near * 1.2f){
                float m = (Math.abs(dx) + Math.abs(dy)) / 2;
                q.cx = axisX + Math.signum(dx) * m; q.cy = axisY + Math.signum(dy) * m;
                q.ang = (float)(Math.round((q.ang - Math.PI / 4) / half) * half + Math.PI / 4);
                q.skew = 0;
            }
        }
        return q;
    }

    /** Distinct images of a polygon under the symmetry group. */
    private List<float[]> orbit(float[] poly){
        List<float[]> out = new ArrayList<>();
        for(float[] g : group){
            float[] t = new float[8];
            for(int i = 0; i < 4; i++){
                float[] c = apply(g, poly[i * 2], poly[i * 2 + 1]);
                t[i * 2] = c[0]; t[i * 2 + 1] = c[1];
            }
            t = ccw(t);
            boolean dup = false;
            for(float[] o : out) if(same(o, t)){ dup = true; break; }
            if(!dup) out.add(t);
        }
        return out;
    }

    private static boolean same(float[] a, float[] b){
        for(int i = 0; i < 4; i++){
            boolean found = false;
            for(int j = 0; j < 4; j++) if(Math.abs(a[i * 2] - b[j * 2]) < 0.4f && Math.abs(a[i * 2 + 1] - b[j * 2 + 1]) < 0.4f){ found = true; break; }
            if(!found) return false;
        }
        return true;
    }

    private static class Eval{
        float score; int gain, spill, faces; List<float[]> orbit;
    }

    private Eval eval(P p, int own){
        float[] poly = p.poly();
        if(!scan(poly, own)) return null;
        List<float[]> orb = orbit(poly);
        Eval e = new Eval();
        e.orbit = orb;
        e.gain = gain * orb.size(); e.spill = spill * orb.size();
        e.faces = (p.d8 ? 8 : 6) * orb.size();
        e.score = e.gain - spillWeight * e.spill;
        return e;
    }

    // ---- the search ----
    public Result fit(int budget, int minGain){
        covered = new boolean[w * h];
        Result res = new Result();
        int total = 0;
        for(int i = 0; i < w * h; i++) if(label[i] >= 0) total++;
        if(total == 0) return res;

        float cap = Math.max(w, h) * 0.45f;
        for(int guard = 0; guard < 400 && res.faces < budget; guard++){
            // uncovered pixels, biggest areas first
            List<int[]> unc = new ArrayList<>();
            for(int i = 0; i < w * h; i++) if(label[i] >= 0 && !covered[i]) unc.add(new int[]{dist[i], i});
            if(unc.size() < minGain) break;
            unc.sort((a, b) -> b[0] - a[0]);
            int top = Math.max(8, unc.size() / 6);

            P bestP = null; Eval bestE = null; float bestV = -1e9f;
            for(int t = 0; t < 8; t++){
                int i = unc.get(rng.nextInt(Math.min(top, unc.size())))[1];
                int own = label[i];
                for(int type = 0; type < (allowOctahedra ? 2 : 1); type++){
                    P p = new P();
                    p.d8 = type == 1;
                    p.cx = i % w + 0.5f; p.cy = i / w + 0.5f;
                    p.hu = 3; p.hv = 3.5f;
                    p.ang = (float)(rng.nextInt(4) * Math.PI / 4);
                    p.skew = sym == Sym.d4 ? 0 : (rng.nextFloat() - 0.5f) * 1.2f;
                    p = snap(p);
                    Eval cur = null;
                    for(int k = 0; k < 12 && cur == null; k++){
                        cur = eval(p, own);
                        if(cur == null){ p.hu *= 0.85f; p.hv *= 0.85f; if(p.hu < 0.8f) break; }
                    }
                    if(cur == null) continue;
                    int iters = 300;
                    for(int it = 0; it < iters; it++){
                        float kk = 1 - it / (float)iters * 0.85f;
                        P q = p.copy();
                        q.cx += (rng.nextFloat() - 0.5f) * 2 * kk; q.cy += (rng.nextFloat() - 0.5f) * 2 * kk;
                        q.hu = Math.min(cap, Math.max(1f, q.hu * (1 + (rng.nextFloat() - 0.5f) * 0.3f * kk)));
                        q.hv = Math.min(cap, Math.max(1f, q.hv * (1 + (rng.nextFloat() - 0.5f) * 0.3f * kk)));
                        q.ang += (rng.nextFloat() - 0.5f) * 0.35f * kk;
                        if(sym != Sym.d4) q.skew += (rng.nextFloat() - 0.5f) * 0.4f * kk;
                        q = snap(q);
                        Eval e = eval(q, own);
                        if(e != null && e.score > cur.score){ p = q; cur = e; }
                    }
                    if(cur.gain >= minGain && res.faces + cur.faces <= budget){
                        float v = (cur.score - facePenalty * cur.faces) / (float)Math.sqrt(cur.faces);
                        if(v > bestV){ bestV = v; bestP = p; bestE = cur; }
                    }
                }
            }
            if(bestP == null) break;
            int own = label[(int)Math.min(w - 1, Math.max(0, Math.floor(bestP.cx))) + w * (int)Math.min(h - 1, Math.max(0, Math.floor(bestP.cy)))];
            for(float[] poly : bestE.orbit){
                Shape s = new Shape();
                s.d8 = bestP.d8;
                s.poly = poly;
                s.rgba = ownColor(poly, bestP);
                res.shapes.add(s);
                markCovered(poly, bestP, own);
            }
            res.faces += bestE.faces;
        }
        int cov = 0;
        for(int i = 0; i < w * h; i++) if(label[i] >= 0 && covered[i]) cov++;
        res.coverage = cov / (float)total;
        return res;
    }

    private int ownColor(float[] poly, P p){
        // most common colour of the opaque pixels under the shape's footprint
        Map<Integer, Integer> counts = new HashMap<>();
        float y0 = 1e9f, y1 = -1e9f;
        for(int i = 0; i < 4; i++){ y0 = Math.min(y0, poly[i * 2 + 1]); y1 = Math.max(y1, poly[i * 2 + 1]); }
        for(int r = Math.max(0, (int)Math.floor(y0)); r < Math.min(h, (int)Math.ceil(y1)); r++){
            float[] s = span(poly, r + 0.5f);
            if(s == null) continue;
            for(int x = Math.max(0, (int)Math.ceil(s[0] - 0.5f)); x <= Math.min(w - 1, (int)Math.floor(s[1] - 0.5f)); x++){
                int i = r * w + x;
                if(solid[i]) counts.merge(px[i] | 0xff, 1, Integer::sum);
            }
        }
        int best = 0xff808080, bc = -1;
        for(Map.Entry<Integer, Integer> e : counts.entrySet()) if(e.getValue() > bc){ bc = e.getValue(); best = e.getKey(); }
        return best;
    }

    private void markCovered(float[] poly, P p, int own){
        float y0 = 1e9f, y1 = -1e9f;
        for(int i = 0; i < 4; i++){ y0 = Math.min(y0, poly[i * 2 + 1]); y1 = Math.max(y1, poly[i * 2 + 1]); }
        for(int r = Math.max(0, (int)Math.floor(y0)); r < Math.min(h, (int)Math.ceil(y1)); r++){
            float[] s = span(poly, r + 0.5f);
            if(s == null) continue;
            for(int x = Math.max(0, (int)Math.ceil(s[0] - 0.5f)); x <= Math.min(w - 1, (int)Math.floor(s[1] - 0.5f)); x++){
                int i = r * w + x;
                if(label[i] == own) covered[i] = true;
            }
        }
    }
}
