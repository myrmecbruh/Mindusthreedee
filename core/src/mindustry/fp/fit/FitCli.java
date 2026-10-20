package mindustry.fp.fit;

import javax.imageio.*;
import java.awt.image.*;
import java.io.*;
import java.util.*;

/** Command line check of the fitter: FitCli lr|d4|none budget minGain file.png... */
public class FitCli{
    public static void main(String[] args) throws IOException{
        Fitter.Sym sym = Fitter.Sym.valueOf(args[0]);
        int budget = Integer.parseInt(args[1]), minGain = Integer.parseInt(args[2]);
        for(int a = 3; a < args.length; a++){
            BufferedImage img = ImageIO.read(new File(args[a]));
            int w = img.getWidth(), h = img.getHeight();
            int[] px = new int[w * h];
            for(int y = 0; y < h; y++) for(int x = 0; x < w; x++){
                int argb = img.getRGB(x, y);
                px[y * w + x] = (argb << 8) | (argb >>> 24);       // rgba8888
            }
            long t = System.nanoTime();
            Fitter.Result r = new Fitter(px, w, h, sym).fit(budget, minGain);
            System.out.printf("%s: %d shapes, %d faces, coverage %.0f%%, %d ms%n", args[a], r.shapes.size(), r.faces, r.coverage * 100, (System.nanoTime() - t) / 1000000);
        }
    }
}
