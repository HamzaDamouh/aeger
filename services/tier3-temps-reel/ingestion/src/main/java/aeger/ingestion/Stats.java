package aeger.ingestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.LongAdder;

/** Compteurs de débit, affichés sur une ligne toutes les 30 s. */
@Component
public class Stats {

    private static final Logger log = LoggerFactory.getLogger(Stats.class);

    private final LongAdder mqttIn = new LongAdder();
    private final LongAdder httpIn = new LongAdder();
    private final LongAdder published = new LongAdder();
    private final LongAdder duplicates = new LongAdder();
    private final LongAdder rejected = new LongAdder();
    private final LongAdder kafkaFailures = new LongAdder();
    private volatile String lastRejectReason;
    private volatile String lastKafkaError;

    public void mqttMeasure() {
        mqttIn.increment();
    }

    public void httpMeasure() {
        httpIn.increment();
    }

    public void published() {
        published.increment();
    }

    public void duplicate() {
        duplicates.increment();
    }

    public void rejected(String reason) {
        rejected.increment();
        lastRejectReason = reason;
    }

    public void kafkaFailure(Throwable e) {
        kafkaFailures.increment();
        lastKafkaError = e.toString();
    }

    @Scheduled(fixedRate = 30_000, initialDelay = 30_000)
    void logThroughput() {
        long rej = rejected.sumThenReset();
        long fail = kafkaFailures.sumThenReset();
        log.info("Débit 30 s : reçues mqtt={} https={} | publiées Kafka={} | doublons={} | rejetées={}{} | échecs Kafka={}{}",
                mqttIn.sumThenReset(), httpIn.sumThenReset(), published.sumThenReset(), duplicates.sumThenReset(),
                rej, rej > 0 ? " (dernière raison : " + lastRejectReason + ")" : "",
                fail, fail > 0 ? " (" + lastKafkaError + ")" : "");
    }
}
