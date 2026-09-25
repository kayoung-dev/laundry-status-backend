package dev.washon.machine.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Pure-function checks of the ported ocr-reading.ts parse/decide logic. */
class StateDecisionTest {

    @Test
    void parsesEveryRecognisedDisplayShape() {
        assertThat(OcrReading.parse(null)).isEqualTo(new OcrReading.Finished());
        assertThat(OcrReading.parse("  ")).isEqualTo(new OcrReading.Finished());
        assertThat(OcrReading.parse("00")).isEqualTo(new OcrReading.Finished());
        assertThat(OcrReading.parse("0:00")).isEqualTo(new OcrReading.Finished());
        assertThat(OcrReading.parse("E01")).isEqualTo(new OcrReading.ErrorDisplay("E01"));
        assertThat(OcrReading.parse("F-21")).isEqualTo(new OcrReading.ErrorDisplay("F21"));
        assertThat(OcrReading.parse("f 3")).isEqualTo(new OcrReading.ErrorDisplay("F3"));
        assertThat(OcrReading.parse("12:01")).isEqualTo(new OcrReading.Countdown(13)); // ceil
        assertThat(OcrReading.parse("23")).isEqualTo(new OcrReading.Countdown(23));
        assertThat(OcrReading.parse("--")).isEqualTo(new OcrReading.Noise());
        assertThat(OcrReading.parse("AB")).isEqualTo(new OcrReading.Noise());
    }

    /**
     * P2-002 convention: a single-letter stage code + colon + remaining
     * minutes ("W:22"/"R:8"/"S:3"), distinct from the plain countdown format
     * ("22") used before this story and asserted above.
     */
    @Test
    void parsesTheStagePrefixedCountdownConventionIntoAKnownStage() {
        assertThat(OcrReading.parse("W:22")).isEqualTo(new OcrReading.Countdown(22, WashStage.wash));
        assertThat(OcrReading.parse("R:8")).isEqualTo(new OcrReading.Countdown(8, WashStage.rinse));
        assertThat(OcrReading.parse("S:3")).isEqualTo(new OcrReading.Countdown(3, WashStage.spin));
        assertThat(OcrReading.parse("w:5")).isEqualTo(new OcrReading.Countdown(5, WashStage.wash)); // case-insensitive
        assertThat(OcrReading.parse("W:0")).isEqualTo(new OcrReading.Finished()); // zero still means done, stage aside

        // Plain countdown (no stage marker) still parses with stage == null:
        // no regression to the pre-P2-002 shape.
        assertThat(OcrReading.parse("22").equals(new OcrReading.Countdown(22))).isTrue();
        assertThat(((OcrReading.Countdown) OcrReading.parse("22")).stage()).isNull();
    }

    @Test
    void requiresAStrictlyDroppingNeverRisingThreeFrameCountdown() {
        OcrReading c30 = new OcrReading.Countdown(30);
        OcrReading c28 = new OcrReading.Countdown(28);
        OcrReading c31 = new OcrReading.Countdown(31);

        assertThat(StateDecision.decide(List.of(c30, c28), MachineState.unknown)).isNull();
        assertThat(StateDecision.decide(List.of(c30, c30, c30), MachineState.unknown)).isNull(); // flat
        assertThat(StateDecision.decide(List.of(c30, c31, c28), MachineState.unknown)).isNull(); // rise
        assertThat(StateDecision.decide(List.of(c31, c30, c28), MachineState.unknown))
                .isEqualTo(new StateDecision(MachineState.in_use, 28, null));
    }

    @Test
    void carriesTheLatestFrameSStageIntoTheDecisionOrNullWhenItIsUnknown() {
        OcrReading w22 = new OcrReading.Countdown(22, WashStage.wash);
        OcrReading w19 = new OcrReading.Countdown(19, WashStage.wash);
        OcrReading r16 = new OcrReading.Countdown(16, WashStage.rinse);
        OcrReading plain13 = new OcrReading.Countdown(13); // no stage marker

        assertThat(StateDecision.decide(List.of(w22, w19, w19), MachineState.unknown).stage())
                .isEqualTo(WashStage.wash);

        // Stage tracks the latest frame, same as remainingMin: a transition into
        // rinse is reflected as soon as it appears, with no consensus of its own.
        StateDecision decision = StateDecision.decide(List.of(w22, w19, r16), MachineState.in_use);
        assertThat(decision.state()).isEqualTo(MachineState.in_use);
        assertThat(decision.remainingMin()).isEqualTo(16);
        assertThat(decision.stage()).isEqualTo(WashStage.rinse);

        // Plain (non-stage) OCR text must still decide in_use with a null stage:
        // no wrong default, no crash, no regression for text that doesn't use
        // the P2-002 convention.
        assertThat(StateDecision.decide(List.of(plain13, plain13, new OcrReading.Countdown(10)), MachineState.unknown)
                .stage()).isNull();
    }

    @Test
    void readsADarkDisplayInContextAndLetsTheGracePeriodOwnDone() {
        OcrReading dark = new OcrReading.Finished();
        OcrReading c2 = new OcrReading.Countdown(2);

        assertThat(StateDecision.decide(List.of(c2, dark), MachineState.in_use))
                .isEqualTo(new StateDecision(MachineState.done, 0, null));
        assertThat(StateDecision.decide(List.of(dark, dark), MachineState.unknown))
                .isEqualTo(new StateDecision(MachineState.available, null, null));
        assertThat(StateDecision.decide(List.of(c2, dark), MachineState.done)).isNull();
    }
}
