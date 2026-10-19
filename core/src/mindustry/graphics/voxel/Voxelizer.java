package mindustry.graphics.voxel;

import arc.struct.*;

/**
 * Turns a flat sprite into a voxel model.
 * <p>
 * The sprite is split into pieces (4-connected patches of one exact color). Each piece gets one elevation: pieces whose pixels
 * sit nearer the center of the sprite are taller, and the tallest piece equals the average of the footprint's width and depth.
 * Coplanar faces of the same color are then welded into single quads (greedy meshing).
 */
public class Voxelizer{
    /** Shortest a piece can be, as a fraction of the maximum height. */
    public static final float minHeightFraction = 0.15f;
    /** Pixels with alpha at or below this are empty. */
    public static final int alphaCutoff = 127;

    /** Returns null if the model would not fit in 16-bit indices. */
    public static VoxelModel build(int[] rgba, int w, int h){
        int[] height = heightMap(rgba, w, h);
        return mesh(rgba, height, w, h);
    }

    /** Per-pixel column height in pixels. 0 for empty pixels. */
    public static int[] heightMap(int[] rgba, int w, int h){
        boolean[] solid = new boolean[w * h];
        int x0 = w, y0 = h, x1 = -1, y1 = -1;
        for(int y = 0; y < h; y++){
            for(int x = 0; x < w; x++){
                if((rgba[y * w + x] & 0xff) > alphaCutoff){
                    solid[y * w + x] = true;
                    x0 = Math.min(x0, x); x1 = Math.max(x1, x);
                    y0 = Math.min(y0, y); y1 = Math.max(y1, y);
                }
            }
        }
        int[] height = new int[w * h];
        if(x1 < 0) return height;

        float cx = (x0 + x1 + 1) / 2f, cy = (y0 + y1 + 1) / 2f;
        float maxH = ((x1 - x0 + 1) + (y1 - y0 + 1)) / 2f;

        int[] piece = new int[w * h];
        java.util.Arrays.fill(piece, -1);
        Seq<float[]> stats = new Seq<>(); //per piece: mean distance from center
        IntSeq stack = new IntSeq();
        int[] dx = {1, -1, 0, 0}, dy = {0, 0, 1, -1};

        for(int s = 0; s < w * h; s++){
            if(!solid[s] || piece[s] >= 0) continue;
            int id = stats.size, color = rgba[s];
            float sum = 0f;
            int n = 0;
            piece[s] = id;
            stack.clear();
            stack.add(s);
            while(stack.size > 0){
                int i = stack.pop(), x = i % w, y = i / w;
                n++;
                sum += (float)Math.hypot(x + 0.5f - cx, y + 0.5f - cy);
                for(int d = 0; d < 4; d++){
                    int xx = x + dx[d], yy = y + dy[d];
                    if(xx < 0 || yy < 0 || xx >= w || yy >= h) continue;
                    int j = yy * w + xx;
                    if(solid[j] && piece[j] < 0 && rgba[j] == color){
                        piece[j] = id;
                        stack.add(j);
                    }
                }
            }
            stats.add(new float[]{sum / n});
        }

        float dmin = Float.MAX_VALUE, dmax = 0f;
        for(float[] st : stats){
            dmin = Math.min(dmin, st[0]);
            dmax = Math.max(dmax, st[0]);
        }
        int[] pieceHeight = new int[stats.size];
        for(int i = 0; i < pieceHeight.length; i++){
            float t = dmax > dmin ? (dmax - stats.get(i)[0]) / (dmax - dmin) : 1f;
            pieceHeight[i] = Math.max(1, Math.round(maxH * (minHeightFraction + (1f - minHeightFraction) * t)));
        }
        for(int i = 0; i < w * h; i++){
            if(solid[i]) height[i] = pieceHeight[piece[i]];
        }
        return height;
    }

    /** Greedy-meshes the columns. Faces on the ground plane are skipped, since they can never be seen. */
    public static VoxelModel mesh(int[] rgba, int[] height, int w, int h){
        int maxHeight = 0;
        for(int v : height) maxHeight = Math.max(maxHeight, v);
        if(maxHeight == 0) return new VoxelModel(new float[0], new int[0], new short[0], 0);

        int[] dim = {w, h, maxHeight};
        FloatSeq pos = new FloatSeq();
        IntSeq col = new IntSeq();
        ShortSeq idx = new ShortSeq();

        for(int d = 0; d < 3; d++){
            int u = (d + 1) % 3, v = (d + 2) % 3;
            for(int dir = 1; dir >= -1; dir -= 2){
                int[] n = new int[3];
                n[d] = dir;
                for(int i = 0; i < dim[d]; i++){
                    if(dir < 0 && d == 2 && i == 0) continue; //ground contact
                    int[] mask = new int[dim[u] * dim[v]];
                    for(int a = 0; a < dim[u]; a++){
                        for(int b = 0; b < dim[v]; b++){
                            int[] p = new int[3];
                            p[d] = i; p[u] = a; p[v] = b;
                            if(solid(p[0], p[1], p[2], w, h, height) && !solid(p[0] + n[0], p[1] + n[1], p[2] + n[2], w, h, height)){
                                mask[a * dim[v] + b] = rgba[(h - 1 - p[1]) * w + p[0]];
                            }
                        }
                    }
                    for(int a = 0; a < dim[u]; a++){
                        for(int b = 0; b < dim[v]; b++){
                            int m = mask[a * dim[v] + b];
                            if(m == 0) continue;
                            int wd = 1;
                            while(a + wd < dim[u] && mask[(a + wd) * dim[v] + b] == m) wd++;
                            int ht = 1;
                            grow:
                            while(b + ht < dim[v]){
                                for(int k = 0; k < wd; k++){
                                    if(mask[(a + k) * dim[v] + b + ht] != m) break grow;
                                }
                                ht++;
                            }
                            for(int k = 0; k < wd; k++){
                                for(int l = 0; l < ht; l++) mask[(a + k) * dim[v] + b + l] = 0;
                            }

                            int plane = i + (dir > 0 ? 1 : 0);
                            int base = col.size;
                            if(base + 4 > 65535) return null;
                            int[][] corners = {{a, b}, {a + wd, b}, {a + wd, b + ht}, {a, b + ht}};
                            if(dir < 0){
                                int[] t = corners[1]; corners[1] = corners[3]; corners[3] = t;
                            }
                            for(int[] c : corners){
                                float[] p = new float[3];
                                p[d] = plane; p[u] = c[0]; p[v] = c[1];
                                pos.add(p[0] - w / 2f, p[1] - h / 2f, p[2]);
                                pos.add(n[0], n[1], n[2]);
                                col.add(m);
                            }
                            idx.add((short)base, (short)(base + 1), (short)(base + 2));
                            idx.add((short)base, (short)(base + 2), (short)(base + 3));
                        }
                    }
                }
            }
        }
        return new VoxelModel(pos.toArray(), col.toArray(), idx.toArray(), maxHeight);
    }

    /** x,y are grid coordinates (y up); z is height. */
    static boolean solid(int x, int y, int z, int w, int h, int[] height){
        if(x < 0 || y < 0 || z < 0 || x >= w || y >= h) return false;
        return z < height[(h - 1 - y) * w + x];
    }
}
