package mindustry.fp;

import mindustry.fp.fit.*;

import javax.imageio.*;
import java.awt.image.*;
import java.io.*;

/** Development aid: prints how well the in-game fit configuration covers each sprite. */
public class FitStats{
    public static void main(String[] args) throws IOException{
        for(String a : args){
            BufferedImage img = ImageIO.read(new File(a));
            int w = img.getWidth(), h = img.getHeight();
            int[] px = new int[w * h];
            int opaque = 0;
            for(int y = 0; y < h; y++) for(int x = 0; x < w; x++){
                int argb = img.getRGB(x, y);
                px[y * w + x] = (argb << 8) | (argb >>> 24);
                if((px[y * w + x] & 0xff) > 127) opaque++;
            }
            Fitter.Sym sym = ModelCache.detect(px, w, h);
            int budget = Math.max(12, Math.min(300, Math.round(opaque * 0.15f)));
            Fitter f = new Fitter(px, w, h, sym);
            f.allowOctahedra = false;
            Fitter.Result r = f.fit(budget, 6);
            System.out.printf("%.0f %s %d %s%n", r.coverage * 100, sym, r.shapes.size(), a);
        }
    }
}
