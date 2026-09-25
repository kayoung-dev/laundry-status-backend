package dev.washon.machine.domain;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a single OCR frame's text means, independent of any machine history.
 * Ported from apps/api/src/state-machine/ocr-reading.ts#parseOcrText.
 *
 * <p>{@link Noise} covers anything we refuse to interpret: unparseable text,
 * and (decided by the caller) frames whose OCR confidence is too low to trust.
 */
public sealed interface OcrReading {

    /**
     * @param stage null for the plain countdown format ("23"); non-null when
     *        {@code rawText} used the P2-002 stage-prefix convention (see
     *        {@link #STAGE_PATTERN}) and the machine is mid-cycle.
     */
    record Countdown(int remainingMin, WashStage stage) implements OcrReading {

        /** Plain countdown, no stage information (the pre-P2-002 shape: "23"). */
        public Countdown(int remainingMin) {
            this(remainingMin, null);
        }
    }

    record Finished() implements OcrReading {
    }

    record ErrorDisplay(String code) implements OcrReading {
    }

    record Noise() implements OcrReading {
    }

    Pattern ERROR_CODE_PATTERN = Pattern.compile("^[EF]\\d{1,3}$");
    Pattern CLOCK_PATTERN = Pattern.compile("^(\\d{1,3}):([0-5]\\d)$");
    Pattern MINUTES_PATTERN = Pattern.compile("^\\d{1,3}$");

    /**
     * P2-002 wash-stage convention: a single-letter stage code, a colon, then
     * the remaining minutes -- e.g. {@code W:22} (wash, 22 min left),
     * {@code R:8} (rinse), {@code S:3} (spin). This is a placeholder
     * heuristic for what a real OCR/camera model would eventually emit
     * (there is no real camera in this system yet, see class docs); it is
     * deliberately distinct from the plain countdown ("22") and clock
     * ("MM:SS") shapes below, both of which start with a digit, and from the
     * E/F-prefixed error codes above.
     */
    Pattern STAGE_PATTERN = Pattern.compile("^([WRS]):(\\d{1,3})$");

    /** Separators are OCR artifacts on 7-segment displays, not meaning. */
    Pattern SEPARATORS = Pattern.compile("[\\s._-]");

    /**
     * Parses one frame of OCR text into a reading.
     *
     * <p>Recognised shapes: blank/zero display ("", "0", "00", "0:00") ->
     * finished; E/F-prefixed codes ("E01", "F-21", "f 3") -> error;
     * stage-prefixed countdown ("W:22", "R:8", "S:3", see
     * {@link #STAGE_PATTERN}) -> countdown with a known stage; "MM:SS" or
     * plain minutes ("23") -> countdown with stage == null. Anything else is
     * noise.
     */
    static OcrReading parse(String rawText) {
        String text = (rawText == null ? "" : rawText).strip().toUpperCase(Locale.ROOT);
        if (text.isEmpty()) {
            return new Finished();
        }

        String compact = SEPARATORS.matcher(text).replaceAll("");

        if (ERROR_CODE_PATTERN.matcher(compact).matches()) {
            return new ErrorDisplay(compact);
        }

        Matcher stage = STAGE_PATTERN.matcher(text);
        if (stage.matches()) {
            int minutes = Integer.parseInt(stage.group(2));
            if (minutes == 0) {
                return new Finished();
            }
            return new Countdown(minutes, WashStage.fromCode(stage.group(1)));
        }

        Matcher clock = CLOCK_PATTERN.matcher(text);
        if (clock.matches()) {
            int totalSeconds = Integer.parseInt(clock.group(1)) * 60 + Integer.parseInt(clock.group(2));
            if (totalSeconds == 0) {
                return new Finished();
            }
            return new Countdown((totalSeconds + 59) / 60); // ceil(seconds / 60)
        }

        if (MINUTES_PATTERN.matcher(compact).matches()) {
            int minutes = Integer.parseInt(compact);
            return minutes == 0 ? new Finished() : new Countdown(minutes);
        }

        return new Noise();
    }
}
