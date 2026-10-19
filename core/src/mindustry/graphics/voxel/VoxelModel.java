package mindustry.graphics.voxel;

/** Greedy-meshed voxel geometry in sprite-pixel units. x/y are centered on the sprite, z is up, ground is z = 0. */
public class VoxelModel{
    /** Floats per vertex: position(3), normal(3). Colors are stored separately as packed rgba8888 ints. */
    public static final int stride = 6;

    public final float[] vertices;
    public final int[] colors;
    public final short[] indices;
    /** Tallest column, in pixels. */
    public final int maxHeight;

    public VoxelModel(float[] vertices, int[] colors, short[] indices, int maxHeight){
        this.vertices = vertices;
        this.colors = colors;
        this.indices = indices;
        this.maxHeight = maxHeight;
    }

    public int vertexCount(){
        return colors.length;
    }

    public int triangleCount(){
        return indices.length / 3;
    }
}
