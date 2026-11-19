package aeger.vitals;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.LongAdder;

/** Compteurs de débit, affichés sur une ligne toutes les 30 s. */
@Component
public class Stats {

    private static final Logger log = LoggerFactory.getLogger(Stats.class);

    final LongAdder read = new LongAdder();
    final LongAdder inserted = new LongAdder();
    final LongAdder duplicates = new LongAdder();
    final LongAdder invalid = new LongAdder();
    final LongAdder dbErrors = new LongAdder();
    final LongAdder apiRequests = new LongAdder();

    @Scheduled(fixedRate = 30_000, initialDelay = 30_000)
    void logThroughput() {
        log.info("Débit 30 s : mesures lues={} | insérées={} | doublons ignorés={} | rejetées (invalides)={} | erreurs base={} | requêtes API={}",
                read.sumThenReset(), inserted.sumThenReset(), duplicates.sumThenReset(), invalid.sumThenReset(),
                dbErrors.sumThenReset(), apiRequests.sumThenReset());
    }
}
