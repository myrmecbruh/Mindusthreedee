package mindustry.fp;

import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.struct.*;

/**
 * A batch that draws nothing: it keeps every sprite the game asks for, in world coordinates with its colour and draw layer. Pointing the
 * game's own drawing code at it is how buildings get their layers and animations in 3D, whatever the block.
 */
public class RecordingBatch extends Batch{
    public static class Sprite{
        public Texture texture;
        /** Corners as the game's batch lays them out: bottom left, top left, top right, bottom right. */
        public final float[] x = new float[4], y = new float[4], u = new float[4], v = new float[4];
        public float color, mix, z;
        public boolean additive;
        public int order;
    }

    public final Seq<Sprite> sprites = new Seq<>();
    private final Seq<Sprite> pool = new Seq<>();
    private int used;
    private int order;

    public void begin(){
        used = 0;
        order = 0;
        sprites.clear();
        z = 0f;
        colorPacked = Color.whiteFloatBits;
        mixColorPacked = Color.clearFloatBits;
        blending = Blending.normal;
    }

    private Sprite next(){
        if(used >= pool.size) pool.add(new Sprite());
        Sprite s = pool.get(used++);
        s.z = z;
        s.order = order++;
        s.additive = blending == Blending.additive;
        sprites.add(s);
        return s;
    }

    @Override
    protected void draw(Texture texture, float[] v, int offset, int count){
        //six floats per vertex (x, y, colour, u, v, mix colour), four vertices per sprite
        for(int i = offset; i + 24 <= offset + count; i += 24){
            Sprite s = next();
            s.texture = texture;
            s.color = v[i + 2];
            s.mix = v[i + 5];
            for(int k = 0; k < 4; k++){
                s.x[k] = v[i + k * 6];
                s.y[k] = v[i + k * 6 + 1];
                s.u[k] = v[i + k * 6 + 3];
                s.v[k] = v[i + k * 6 + 4];
            }
        }
    }

    @Override
    protected void draw(TextureRegion region, float x, float y, float originX, float originY, float width, float height, float rotation){
        Sprite s = next();
        s.texture = region.texture;
        s.color = colorPacked;
        s.mix = mixColorPacked;

        float wx = x + originX, wy = y + originY;
        float fx = -originX, fy = -originY, fx2 = width - originX, fy2 = height - originY;
        float cos = 1f, sin = 0f;
        if(!Mathf.zero(rotation)){
            cos = Mathf.cosDeg(rotation);
            sin = Mathf.sinDeg(rotation);
        }
        float x1 = cos * fx - sin * fy + wx, y1 = sin * fx + cos * fy + wy;
        float x2 = cos * fx - sin * fy2 + wx, y2 = sin * fx + cos * fy2 + wy;
        float x3 = cos * fx2 - sin * fy2 + wx, y3 = sin * fx2 + cos * fy2 + wy;
        float x4 = x1 + (x3 - x2), y4 = y3 - (y2 - y1);
        s.x[0] = x1; s.y[0] = y1; s.u[0] = region.u; s.v[0] = region.v2;
        s.x[1] = x2; s.y[1] = y2; s.u[1] = region.u; s.v[1] = region.v;
        s.x[2] = x3; s.y[2] = y3; s.u[2] = region.u2; s.v[2] = region.v;
        s.x[3] = x4; s.y[3] = y4; s.u[3] = region.u2; s.v[3] = region.v2;
    }

    @Override
    protected void draw(Runnable request){
        request.run();
    }

    @Override
    protected void flush(){
    }

    @Override
    protected void setBlending(Blending blending){
        this.blending = blending;
    }

    @Override
    protected void setShader(arc.graphics.gl.Shader shader, boolean apply){
    }

    @Override
    protected void setProjection(arc.math.Mat projection){
    }

    @Override
    protected void setTransform(arc.math.Mat transform){
    }
}
