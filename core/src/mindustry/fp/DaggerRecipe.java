package mindustry.fp;

import mindustry.fp.fit.*;
import mindustry.graphics.voxel.*;

import java.util.*;

/**
 * The hand-tuned dagger: shapes placed by eye over its sprites (see the design demos), baked as plan polygons with heights, in sprite pixels.
 * Every part is built with its final height, so all parts are drawn at the same base height.
 */
public class DaggerRecipe{
    /** poly, zBot, zTop, octahedron, rgba */
    private static final Object[][] LEG = {
        {new float[]{16.677f, 20.435f, 19.419f, 35.203f, 10.103f, 33.345f, 7.361f, 18.577f}, 0f, 9f, false, 0x6e7080ff},
        {new float[]{17.063f, 18.218f, 8.341f, 23.584f, 8.437f, 14.602f, 17.159f, 9.236f}, 0f, 7f, false, 0x565666ff}
    };
    private static final Object[][] BASE = {
        {new float[]{15.28f, 17.43f, 32.72f, 17.43f, 32.72f, 34.53f, 15.28f, 34.53f}, 3f, 9f, false, 0x565666ff}
    };
    private static final Object[][] BODY = {
        {new float[]{5.521f, 17.125f, 17.706f, 19.193f, 24.179f, 36.135f, 11.994f, 34.067f}, 7f, 21.865f, false, 0x989aa4ff},
        {new float[]{36.006f, 34.067f, 23.821f, 36.135f, 30.294f, 19.193f, 42.479f, 17.125f}, 7f, 21.865f, false, 0x989aa4ff},
        {new float[]{8.352f, 23.596f, 15.858f, 25.64f, 15.428f, 32.944f, 7.922f, 30.9f}, 7f, 15.052f, false, 0xb0bac0ff},
        {new float[]{40.078f, 30.9f, 32.572f, 32.944f, 32.142f, 25.64f, 39.648f, 23.596f}, 7f, 15.052f, false, 0xb0bac0ff},
        {new float[]{19.44f, 24.15f, 28.56f, 24.15f, 28.56f, 31.77f, 19.44f, 31.77f}, 7f, 20.078f, false, 0xffa665ff},
        {new float[]{23.499f, 10.982f, 23.53f, 15.322f, 15.921f, 19.578f, 15.89f, 15.238f}, 7f, 14.088f, false, 0x989aa4ff},
        {new float[]{32.11f, 15.238f, 32.079f, 19.578f, 24.47f, 15.322f, 24.501f, 10.982f}, 7f, 14.088f, false, 0x989aa4ff},
        {new float[]{20.45f, 14.62f, 27.55f, 14.62f, 27.55f, 20.7f, 20.45f, 20.7f}, 7f, 15.29f, false, 0xb0bac0ff},
        {new float[]{19.774f, 35.822f, 14.95f, 36.564f, 11.386f, 32.458f, 16.21f, 31.716f}, 7f, 11f, false, 0x6e7080ff},
        {new float[]{31.79f, 31.716f, 36.614f, 32.458f, 33.05f, 36.564f, 28.226f, 35.822f}, 7f, 11f, false, 0x6e7080ff},
        {new float[]{19.33f, 19.95f, 28.67f, 19.95f, 28.67f, 25.53f, 19.33f, 25.53f}, 7f, 17.654f, false, 0xd06b53ff},
        {new float[]{4.913f, 18.693f, 8.434f, 20.462f, 7.247f, 25.327f, 3.726f, 23.558f}, 7f, 11f, false, 0x989aa4ff},
        {new float[]{44.274f, 23.558f, 40.753f, 25.327f, 39.566f, 20.462f, 43.087f, 18.693f}, 7f, 11f, false, 0x989aa4ff},
        {new float[]{8.119f, 24.879f, 7.583f, 28.863f, 3.501f, 25.601f, 4.037f, 21.617f}, 7f, 11f, false, 0x6e7080ff},
        {new float[]{43.963f, 21.617f, 44.499f, 25.601f, 40.417f, 28.863f, 39.881f, 24.879f}, 7f, 11f, false, 0x6e7080ff},
        {new float[]{18.4f, 27.9f, 24f, 23.1f, 29.6f, 27.9f, 24f, 32.7f}, 18.57762784296746f, 27.078f, true, 0xffa665ff},
        {new float[]{18.8f, 22.2f, 24f, 18.9f, 29.2f, 22.2f, 24f, 25.5f}, 16.15375933855557f, 23.654f, true, 0xd06b53ff}
    };
    private static final Object[][] GUN = {
        {new float[]{-4.292f, 11.069f, -4.047f, 15.073f, -12.445f, 21.933f, -12.69f, 17.929f}, 14f, 19f, false, 0x6e7080ff},
        {new float[]{-4.047f, 15.073f, -3.803f, 19.078f, -12.201f, 25.938f, -12.445f, 21.933f}, 14f, 21.5f, false, 0xb0bac0ff},
        {new float[]{-3.803f, 19.078f, -3.559f, 23.082f, -11.957f, 29.942f, -12.201f, 25.938f}, 14f, 24f, false, 0xffa665ff},
        {new float[]{-3.559f, 23.082f, -3.315f, 27.087f, -11.713f, 33.947f, -11.957f, 29.942f}, 14f, 20f, false, 0x989aa4ff},
        {new float[]{-3.315f, 27.087f, -3.07f, 31.091f, -11.468f, 37.951f, -11.713f, 33.947f}, 14f, 22.5f, false, 0xb0bac0ff}
    };

    private static final int size = 48, weaponW = 48, weaponH = 48;
    private final Map<String, VoxelModel> cache = new HashMap<>();

    public VoxelModel leg(boolean flip){ return get("leg", LEG, flip, size, size); }
    public VoxelModel base(){ return get("base", BASE, false, size, size); }
    public VoxelModel body(){ return get("body", BODY, false, size, size); }
    public VoxelModel gun(boolean flip){ return get("gun", GUN, flip, weaponW, weaponH); }

    private VoxelModel get(String name, Object[][] data, boolean flip, int w, int h){
        String key = name + flip;
        VoxelModel m = cache.get(key);
        if(m != null) return m;
        List<Fitter.Shape> shapes = new ArrayList<>();
        float[] zb = new float[data.length], zt = new float[data.length];
        for(int i = 0; i < data.length; i++){
            float[] p = (float[])data[i][0];
            Fitter.Shape s = new Fitter.Shape();
            for(int k = 0; k < 4; k++){
                s.poly[k * 2] = flip ? w - p[k * 2] : p[k * 2];
                s.poly[k * 2 + 1] = p[k * 2 + 1];
            }
            s.d8 = (Boolean)data[i][3];
            s.rgba = (Integer)data[i][4];
            shapes.add(s);
            zb[i] = (Float)data[i][1];
            zt[i] = (Float)data[i][2];
        }
        m = ModelBuilder.mesh(shapes, zb, zt, w / 2f, h / 2f, false);
        cache.put(key, m);
        return m;
    }
}
