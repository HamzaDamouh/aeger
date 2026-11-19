package aeger.batch;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * Command line of the job.
 *
 * <pre>
 *   --day YYYY-MM-DD      (required) day to process, Europe/Paris calendar day
 *   --input-json PATH     (test/demo) read measures from a JSON-lines file instead of Kafka
 *   --dry-run             (test/demo) compute and print everything, write nothing (no lake, no PostgreSQL)
 * </pre>
 */
public record JobArgs(LocalDate day, String inputJson, boolean dryRun) {

    public static final String USAGE =
            "usage: aeger.batch.DailyJob --day YYYY-MM-DD [--input-json PATH] [--dry-run]";

    /** Bad command line (exit code 2). */
    public static final class UsageException extends IllegalArgumentException {
        public UsageException(String message) {
            super(message);
        }
    }

    public static JobArgs parse(String[] args) {
        String day = null;
        String inputJson = null;
        boolean dryRun = false;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("--day=")) {
                day = a.substring("--day=".length());
            } else if (a.equals("--day")) {
                day = valueOf(args, ++i, a);
            } else if (a.startsWith("--input-json=")) {
                inputJson = a.substring("--input-json=".length());
            } else if (a.equals("--input-json")) {
                inputJson = valueOf(args, ++i, a);
            } else if (a.equals("--dry-run")) {
                dryRun = true;
            } else {
                throw new UsageException("unknown argument: " + a);
            }
        }
        if (day == null || day.isBlank()) {
            throw new UsageException("--day is required");
        }
        LocalDate parsed;
        try {
            parsed = LocalDate.parse(day.trim());
        } catch (DateTimeParseException e) {
            throw new UsageException("--day must be YYYY-MM-DD, got: " + day);
        }
        return new JobArgs(parsed, inputJson, dryRun);
    }

    private static String valueOf(String[] args, int i, String name) {
        if (i >= args.length) {
            throw new UsageException("missing value for " + name);
        }
        return args[i];
    }
}
