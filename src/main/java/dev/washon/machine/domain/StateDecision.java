package dev.washon.machine.domain;

import java.util.List;

/**
 * The state a window of OCR readings implies. Ported from
 * apps/api/src/state-machine/ocr-reading.ts#decideState.
 */
public record StateDecision(MachineState state, Integer remainingMin, String errorCode, WashStage stage) {

    /** How many consecutive frames must agree before we declare {@code in_use}. */
    public static final int COUNTDOWN_CONSENSUS_FRAMES = 3;

    /** Convenience constructor for decisions that never carry a wash stage (every state but in_use). */
    public StateDecision(MachineState state, Integer remainingMin, String errorCode) {
        this(state, remainingMin, errorCode, null);
    }

    /**
     * Decides the machine state from a window of consecutive readings, oldest
     * first, with the just-received frame last, plus the state we currently
     * believe the machine to be in. Returns {@code null} for "not enough
     * evidence".
     *
     * <p>A countdown needs COUNTDOWN_CONSENSUS_FRAMES consecutive countdown
     * frames that never increase and drop overall before we believe the
     * machine is running -- that is the noise filter, a single misread digit
     * cannot start a cycle. {@code finished} and {@code error} act on the
     * latest frame alone: those are the states where reacting late is worse
     * than reacting to one bad frame, and a following frame corrects any misread.
     *
     * <p>{@code finished} is read in context: right after a countdown it means
     * "the cycle just ended" (-> done, then the grace period releases it to
     * available), while a display that has simply been dark is an idle machine
     * (-> available). Without that distinction an untouched machine would flap
     * done -> available -> done forever.
     */
    public static StateDecision decide(List<OcrReading> window, MachineState currentState) {
        if (window.isEmpty()) {
            return null;
        }
        OcrReading latest = window.get(window.size() - 1);

        if (latest instanceof OcrReading.ErrorDisplay error) {
            return new StateDecision(MachineState.error, null, error.code());
        }

        if (latest instanceof OcrReading.Finished) {
            if (currentState == MachineState.done) {
                // The done -> available grace period owns this transition; more
                // dark frames must not cut it short.
                return null;
            }
            boolean cycleJustEnded = window.subList(0, window.size() - 1).stream()
                    .anyMatch(reading -> reading instanceof OcrReading.Countdown);
            return cycleJustEnded
                    ? new StateDecision(MachineState.done, 0, null)
                    : new StateDecision(MachineState.available, null, null);
        }

        if (latest instanceof OcrReading.Countdown countdown) {
            if (window.size() < COUNTDOWN_CONSENSUS_FRAMES) {
                return null;
            }
            List<OcrReading> recent = window.subList(window.size() - COUNTDOWN_CONSENSUS_FRAMES, window.size());
            int[] values = new int[recent.size()];
            for (int i = 0; i < recent.size(); i++) {
                if (!(recent.get(i) instanceof OcrReading.Countdown c)) {
                    return null;
                }
                values[i] = c.remainingMin();
            }
            for (int i = 1; i < values.length; i++) {
                if (values[i] > values[i - 1]) {
                    return null; // a rising number is not a countdown
                }
            }
            if (values[values.length - 1] >= values[0]) {
                return null; // flat window: no evidence the machine is actually running
            }
            // Stage is read from the latest frame only: it needs no consensus of
            // its own, the same way remainingMin always tracks the latest frame
            // once the countdown consensus itself is established.
            return new StateDecision(MachineState.in_use, countdown.remainingMin(), null, countdown.stage());
        }

        return null; // noise
    }
}
