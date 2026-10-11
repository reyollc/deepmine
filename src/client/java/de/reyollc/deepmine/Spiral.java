package de.reyollc.deepmine;

import java.util.HashSet;
import java.util.Set;

/**
 * Quadratische Auswaertsspirale.
 *
 * Regel pro Schritt: links frei -> links. Sonst geradeaus frei -> geradeaus.
 * Sonst rechts frei -> rechts. Sonst fertig.
 *
 * "Frei" heisst: noch nicht besucht UND innerhalb der Beacon-Reichweite.
 * Bewusst NICHT "da ist noch ein Block": eine natuerliche Hoehle links wuerde
 * sonst wie schon-abgebaut aussehen und die Spirale aus dem Tritt bringen.
 *
 * Alle Koordinaten sind Offsets relativ zum Beacon.
 */
public final class Spiral {

    // Gegen den Uhrzeigersinn, damit links = (f+1)&3 und rechts = (f+3)&3 gilt.
    // 0 = Sued (+Z), 1 = Ost (+X), 2 = Nord (-Z), 3 = West (-X)
    private static final int[] DX = { 0, 1, 0, -1 };
    private static final int[] DZ = { 1, 0, -1, 0 };
    private static final int[] TURNS = { 1, 0, 3 }; // links, geradeaus, rechts

    private final int radius;
    private final int coreRadius;
    private final int startX, startZ, startFacing;
    private final Set<Long> visited = new HashSet<>();

    private int x, z, facing;
    private int steps;   // Anzahl commit-Aufrufe; reicht zusammen mit den Startwerten zum Nachspielen

    /**
     * @param radius     Beacon-Reichweite, Stufe 4 = 50
     * @param coreRadius Pyramide plus Puffer. 9x9-Basis belegt Radius 0..4, also 4.
     * @param startX     Startzelle relativ zum Beacon
     * @param startZ     Startzelle relativ zum Beacon
     * @param facing     0=Sued 1=Ost 2=Nord 3=West
     */
    public Spiral(int radius, int coreRadius, int startX, int startZ, int facing) {
        this.radius = radius;
        this.coreRadius = coreRadius;
        this.startX = startX;
        this.startZ = startZ;
        this.startFacing = facing & 3;
        this.x = startX;
        this.z = startZ;
        this.facing = facing & 3;

        // Pyramide vorab sperren, dann umrundet die Spirale sie von allein.
        for (int cx = -coreRadius; cx <= coreRadius; cx++) {
            for (int cz = -coreRadius; cz <= coreRadius; cz++) {
                visited.add(key(cx, cz));
            }
        }
        visited.add(key(startX, startZ));
    }

    /**
     * Stellt eine Spirale wieder her, indem sie die ersten steps Schritte
     * nachspielt. Die Regel ist deterministisch, also kommt exakt derselbe
     * Zustand heraus wie beim Original - ohne die Besucht-Liste speichern
     * zu muessen.
     */
    public static Spiral restore(int radius, int coreRadius, int startX, int startZ,
                                 int facing, int steps) {
        Spiral s = new Spiral(radius, coreRadius, startX, startZ, facing);
        for (int i = 0; i < steps; i++) {
            int[] step = s.peek();
            if (step == null) break;
            s.commit(step);
        }
        return s;
    }

    public int x() { return x; }
    public int z() { return z; }
    public int radius() { return radius; }
    public int coreRadius() { return coreRadius; }
    public int startX() { return startX; }
    public int startZ() { return startZ; }
    public int startFacing() { return startFacing; }
    public int steps() { return steps; }
    public int cellsDone() { return visited.size(); }
    public int ring() { return Math.max(Math.abs(x), Math.abs(z)); }

    /** Naechster Schritt als {x, z, facing}, oder null wenn der Bereich fertig ist. */
    public int[] peek() {
        for (int turn : TURNS) {
            int f = (facing + turn) & 3;
            int nx = x + DX[f];
            int nz = z + DZ[f];
            if (free(nx, nz)) {
                return new int[] { nx, nz, f };
            }
        }
        return null;
    }

    /** Erst aufrufen, wenn die Zelle wirklich abgebaut und betreten ist. */
    public void commit(int[] step) {
        this.x = step[0];
        this.z = step[1];
        this.facing = step[2];
        visited.add(key(x, z));
        steps++;
    }

    private boolean free(int cx, int cz) {
        return Math.max(Math.abs(cx), Math.abs(cz)) <= radius && !visited.contains(key(cx, cz));
    }

    private static long key(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
    }
}
