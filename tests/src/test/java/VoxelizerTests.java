import mindustry.graphics.voxel.*;
import org.junit.jupiter.api.*;

import javax.imageio.*;
import java.awt.image.*;
import java.io.*;
import java.nio.file.*;

import static org.junit.jupiter.api.Assertions.*;

public class VoxelizerTests{
    static final Path sprites = Paths.get("../assets-raw/sprites");

    /** Loads a PNG as rgba8888 ints, the same layout arc's Pixmap uses. */
    static int[] load(String path, int[] size) throws IOException{
        BufferedImage img = ImageIO.read(sprites.resolve(path).toFile());
        int w = img.getWidth(), h = img.getHeight();
        size[0] = w; size[1] = h;
        int[] out = new int[w * h];
        for(int y = 0; y < h; y++){
            for(int x = 0; x < w; x++){
                int argb = img.getRGB(x, y);
                out[y * w + x] = (argb << 8) | (argb >>> 24);
            }
        }
        return out;
    }

    @Test
    void maxHeightIsAverageOfWidthAndDepth() throws IOException{
        int[] size = new int[2];
        int[] dagger = load("units/dagger.png", size);
        int[] heights = Voxelizer.heightMap(dagger, size[0], size[1]);
        int max = 0;
        for(int v : heights) max = Math.max(max, v);
        //opaque footprint is 40x24
        assertEquals(32, max);

        int[] press = load("blocks/production/graphite-press.png", size);
        max = 0;
        for(int v : Voxelizer.heightMap(press, size[0], size[1])) max = Math.max(max, v);
        assertEquals(64, max);
    }

    @Test
    void emptyPixelsHaveNoHeightAndEveryOpaquePixelHasSome() throws IOException{
        int[] size = new int[2];
        int[] dagger = load("units/dagger.png", size);
        int[] heights = Voxelizer.heightMap(dagger, size[0], size[1]);
        for(int i = 0; i < dagger.length; i++){
            boolean opaque = (dagger[i] & 0xff) > Voxelizer.alphaCutoff;
            assertEquals(opaque, heights[i] > 0, "pixel " + i);
        }
    }

    @Test
    void weldingReducesFacesAndKeepsGeometryValid() throws IOException{
        int[] size = new int[2];
        int[] press = load("blocks/production/graphite-press.png", size);
        int w = size[0], h = size[1];
        int[] heights = Voxelizer.heightMap(press, w, h);
        VoxelModel model = Voxelizer.mesh(press, heights, w, h);
        assertNotNull(model);

        //one quad per exposed unit face, with no welding
        int naive = 0;
        for(int y = 0; y < h; y++){
            for(int x = 0; x < w; x++){
                int hc = heights[y * w + x];
                if(hc == 0) continue;
                naive++;
                int[][] n = {{1,0},{-1,0},{0,1},{0,-1}};
                for(int[] d : n){
                    int xx = x + d[0], yy = y + d[1];
                    int hn = xx < 0 || yy < 0 || xx >= w || yy >= h ? 0 : heights[yy * w + xx];
                    if(hn < hc) naive += hc - hn;
                }
            }
        }
        assertTrue(model.triangleCount() / 2 < naive / 2, "welded " + model.triangleCount() / 2 + " quads vs " + naive + " unwelded");

        float maxZ = 0;
        for(int i = 0; i < model.vertexCount(); i++){
            float z = model.vertices[i * VoxelModel.stride + 2];
            assertTrue(z >= 0, "vertex below ground");
            maxZ = Math.max(maxZ, z);
        }
        assertEquals(model.maxHeight, maxZ);
        for(short idx : model.indices) assertTrue(idx >= 0 && idx < model.vertexCount());
    }

    @Test
    void facesPointOutOfTheirVoxels() throws IOException{
        int[] size = new int[2];
        int[] dagger = load("units/dagger.png", size);
        VoxelModel model = Voxelizer.build(dagger, size[0], size[1]);
        //for every triangle, the winding normal must match the stored normal
        for(int t = 0; t < model.indices.length; t += 3){
            float[][] p = new float[3][];
            for(int k = 0; k < 3; k++){
                int s = model.indices[t + k] * VoxelModel.stride;
                p[k] = new float[]{model.vertices[s], model.vertices[s + 1], model.vertices[s + 2], model.vertices[s + 3], model.vertices[s + 4], model.vertices[s + 5]};
            }
            float ax = p[1][0] - p[0][0], ay = p[1][1] - p[0][1], az = p[1][2] - p[0][2];
            float bx = p[2][0] - p[0][0], by = p[2][1] - p[0][1], bz = p[2][2] - p[0][2];
            float cx = ay * bz - az * by, cy = az * bx - ax * bz, cz = ax * by - ay * bx;
            float dot = cx * p[0][3] + cy * p[0][4] + cz * p[0][5];
            assertTrue(dot > 0, "triangle " + t / 3 + " is wound against its normal");
        }
    }
}
