package aeger.ingestion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeduplicatorTest {

    private final Deduplicator dedup = new Deduplicator();

    @Test
    void premiereMesureAccepteeEtRepetitionRefusee() {
        assertTrue(dedup.markIfNew("hr-00001", 10));
        assertFalse(dedup.markIfNew("hr-00001", 10));
    }

    @Test
    void braceletsIndependants() {
        assertTrue(dedup.markIfNew("hr-00001", 1));
        assertTrue(dedup.markIfNew("hr-00002", 1));
    }

    @Test
    void desordreToleredansLaFenetre() {
        assertTrue(dedup.markIfNew("hr-00001", 100));
        assertTrue(dedup.markIfNew("hr-00001", 98));   // arrive en retard
        assertTrue(dedup.markIfNew("hr-00001", 99));
        assertFalse(dedup.markIfNew("hr-00001", 98));
        assertTrue(dedup.markIfNew("hr-00001", 101));
        assertFalse(dedup.markIfNew("hr-00001", 100));
    }

    @Test
    void lesCasesRecycleesSontEffacees() {
        assertTrue(dedup.markIfNew("std-00001", 5));
        // 5 + 4096 occupe la même case que 5 : doit être acceptée
        assertTrue(dedup.markIfNew("std-00001", 5 + Deduplicator.WINDOW));
        // 6 + 4096 n'a jamais été vue (la case de 6 a été effacée en avançant)
        assertTrue(dedup.markIfNew("std-00001", 6 + Deduplicator.WINDOW));
        // 5 est désormais hors fenêtre : considérée comme doublon
        assertFalse(dedup.markIfNew("std-00001", 5));
    }

    @Test
    void grandSautVideLaFenetre() {
        assertTrue(dedup.markIfNew("hr-00003", 1));
        assertTrue(dedup.markIfNew("hr-00003", 1_000_000));
        assertTrue(dedup.markIfNew("hr-00003", 999_999));
        assertFalse(dedup.markIfNew("hr-00003", 1_000_000));
    }

    @Test
    void oublierPermetLeRenvoi() {
        assertTrue(dedup.markIfNew("hr-00004", 7));
        dedup.forget("hr-00004", 7);
        assertTrue(dedup.markIfNew("hr-00004", 7));
    }
}
