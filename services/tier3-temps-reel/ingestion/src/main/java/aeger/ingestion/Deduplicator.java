package aeger.ingestion;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Retrait des doublons (identifiant d'une mesure = bracelet_id + seq).
 * <p>
 * Économe en mémoire : pour chaque bracelet, le seq maximal vu et une fenêtre
 * des 4 096 derniers seq sous forme de bits (512 octets), ce qui tolère le désordre.
 * Un seq plus ancien que la fenêtre est considéré comme un doublon.
 * Pour 10 000 bracelets : environ 6 Mo.
 */
public class Deduplicator {

    static final int WINDOW = 4096;

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    /** Renvoie true si la mesure est nouvelle (et la marque comme vue), false si c'est un doublon. */
    public boolean markIfNew(String braceletId, long seq) {
        Window w = windows.computeIfAbsent(braceletId, k -> new Window());
        synchronized (w) {
            return w.markIfNew(seq);
        }
    }

    /** Oublie une mesure (écriture Kafka échouée) pour qu'un nouvel envoi soit accepté. */
    public void forget(String braceletId, long seq) {
        Window w = windows.get(braceletId);
        if (w != null) {
            synchronized (w) {
                w.forget(seq);
            }
        }
    }

    public int braceletCount() {
        return windows.size();
    }

    /** Fenêtre circulaire de bits : le bit (seq mod 4096) indique si seq a été vu. */
    private static final class Window {
        private final long[] bits = new long[WINDOW / 64];
        private long maxSeq = -1;

        boolean markIfNew(long seq) {
            if (maxSeq < 0 || seq > maxSeq) {
                // la fenêtre avance : on efface les cases des seq qui en sortent
                if (maxSeq < 0 || seq - maxSeq >= WINDOW) {
                    Arrays.fill(bits, 0L);
                } else {
                    for (long s = maxSeq + 1; s < seq; s++) {
                        clear(s);
                    }
                }
                maxSeq = seq;
                set(seq);
                return true;
            }
            if (seq <= maxSeq - WINDOW) {
                return false; // trop ancien pour la fenêtre : traité comme doublon
            }
            if (get(seq)) {
                return false;
            }
            set(seq);
            return true;
        }

        void forget(long seq) {
            if (seq <= maxSeq && seq > maxSeq - WINDOW) {
                clear(seq);
            }
        }

        private int slot(long seq) {
            return (int) Math.floorMod(seq, (long) WINDOW);
        }

        private boolean get(long seq) {
            int i = slot(seq);
            return (bits[i >>> 6] & (1L << (i & 63))) != 0;
        }

        private void set(long seq) {
            int i = slot(seq);
            bits[i >>> 6] |= 1L << (i & 63);
        }

        private void clear(long seq) {
            int i = slot(seq);
            bits[i >>> 6] &= ~(1L << (i & 63));
        }
    }
}
