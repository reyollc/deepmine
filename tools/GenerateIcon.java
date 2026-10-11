import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Mod-Icon: quadratische Auswaertsspirale um einen Beacon, von oben.
 *
 * Die echte Spirale des Mods besucht jede Zelle und waere als Bild eine volle
 * Flaeche. Hier wird deshalb dieselbe Form mit Abstand zwischen den Armen
 * gezeichnet - Segmentlaengen 2,2,4,4,6,6,... ergeben genau eine Zelle Luft
 * zwischen den Windungen, so bleibt die Spirale auch klein erkennbar.
 */
public class Gen {

    static final int SIZE = 512;
    static final int N = 13;                 // Zellen je Kante (ungerade)
    static final int R = N / 2;

    static final Color ROCK_TOP = new Color(0x2E2E38);
    static final Color ROCK_BOTTOM = new Color(0x1B1B22);
    static final Color GRID = new Color(0x00000026, true);
    static final Color CORRIDOR = new Color(0xAEB4C6);
    static final Color CORRIDOR_SHADE = new Color(0x646A7A);
    static final Color BEACON = new Color(0x3FD9CC);
    static final Color BEACON_GLOW = new Color(0x3FD9CC55, true);
    static final Color BEACON_CORE = new Color(0xEFFFFD);

    public static void main(String[] args) throws Exception {
        int cell = 36;
        int margin = (SIZE - N * cell) / 2;

        Set<Long> path = spiral();
        System.out.println("Zellen in der Linie: " + path.size());

        BufferedImage img = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();

        // Gestein mit leichtem Verlauf nach unten.
        for (int y = 0; y < SIZE; y++) {
            float t = (float) y / SIZE;
            g.setColor(mix(ROCK_TOP, ROCK_BOTTOM, t));
            g.fillRect(0, y, SIZE, 1);
        }
        g.setColor(GRID);
        for (int i = 0; i <= N; i++) {
            g.fillRect(margin + i * cell - 1, 0, 2, SIZE);
            g.fillRect(0, margin + i * cell - 1, SIZE, 2);
        }

        // Beacon-Schein unter dem Gang.
        int c = margin + R * cell + cell / 2;
        for (int r = cell * 4; r > 0; r -= 6) {
            g.setColor(new Color(0x3F, 0xD9, 0xCC, Math.max(1, 16 - r / (cell / 2))));
            g.fillOval(c - r, c - r, 2 * r, 2 * r);
        }

        // Gang. Der Pyramidenkern bleibt frei, wie im Mod auch.
        for (long k : path) {
            int x = (int) (k >> 32), z = (int) k;
            if (Math.abs(x) <= 1 && Math.abs(z) <= 1) continue;
            int px = margin + (x + R) * cell;
            int py = margin + (z + R) * cell;
            g.setColor(CORRIDOR_SHADE);
            g.fillRect(px, py, cell, cell);
            g.setColor(CORRIDOR);
            g.fillRect(px, py, cell, cell - 6);
        }

        // Beacon in der Mitte.
        // Beacon: eine Zelle, der freie 3x3-Kern drumherum laesst ihn atmen.
        int bp = margin + R * cell;
        g.setColor(BEACON);
        g.fillRect(bp - 4, bp - 4, cell + 8, cell + 8);
        g.setColor(BEACON_CORE);
        g.fillRect(bp + 8, bp + 8, cell - 16, cell - 16);

        g.dispose();
        File out = new File(args.length > 0 ? args[0] : "icon.png");
        ImageIO.write(img, "PNG", out);
        System.out.println("geschrieben: " + out.getAbsolutePath());
    }

    /** Quadratische Spirale mit einer Zelle Luft zwischen den Windungen. */
    private static Set<Long> spiral() {
        Set<Long> cells = new LinkedHashSet<>();
        int x = 0, z = 0, dir = 0, len = 2;
        int[] dx = { 1, 0, -1, 0 }, dz = { 0, 1, 0, -1 };
        cells.add(key(x, z));
        outer:
        for (int seg = 0; seg < 12; seg++) {
            for (int i = 0; i < len; i++) {
                x += dx[dir];
                z += dz[dir];
                if (Math.abs(x) > R || Math.abs(z) > R) break outer;
                cells.add(key(x, z));
            }
            dir = (dir + 1) & 3;
            if (seg % 2 == 1) len += 2;   // nach je zwei Segmenten laenger
        }
        return cells;
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private static Color mix(Color a, Color b, float t) {
        return new Color(
                Math.round(a.getRed() + (b.getRed() - a.getRed()) * t),
                Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t));
    }
}
