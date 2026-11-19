package aeger.alerting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.LongAdder;

/** Compteurs de débit, affichés sur une ligne toutes les 30 s. */
@Component
public class Stats {

    private static final Logger log = LoggerFactory.getLogger(Stats.class);

    final LongAdder measures = new LongAdder();
    final LongAdder invalid = new LongAdder();
    final LongAdder alerts = new LongAdder();
    final LongAdder sent = new LongAdder();
    final LongAdder failed = new LongAdder();
    final LongAdder dropped = new LongAdder();

    @Scheduled(fixedRate = 30_000, initialDelay = 30_000)
    void logThroughput() {
        log.info("Débit 30 s : mesures lues={} | alertes={} | envoyées={} | échecs envoi={} | perdues (file pleine)={} | messages invalides={}",
                measures.sumThenReset(), alerts.sumThenReset(), sent.sumThenReset(), failed.sumThenReset(),
                dropped.sumThenReset(), invalid.sumThenReset());
    }
}
