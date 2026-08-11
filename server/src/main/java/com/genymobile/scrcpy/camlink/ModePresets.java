package com.genymobile.scrcpy.camlink;

import android.hardware.camera2.CaptureRequest;

/**
 * Tabela de parâmetros Camera2 por modo inteligente (US3 — contrato §3 "Tabela de modos" /
 * research.md R2). Pura, sem I/O — só dados, testável em JVM sem Camera2 real.
 *
 * <p>Campos {@code null} (fpsMin/fpsMax, evCompEv, awbMode) significam "livre": o modo não força
 * esse parâmetro (só o modo {@code pro} tem campos livres — os outros três fixam tudo).
 *
 * <p>fps é exposto como par de {@code Integer} (não {@code android.util.Range}) de propósito:
 * {@code Range} é uma classe framework real cujos métodos ({@code getLower()}/{@code getUpper()})
 * lançam em testes JVM puros contra o android.jar de stub do AGP — quem consome monta o
 * {@code Range<Integer>} só no ponto de aplicação real (Camera2), onde roda em device de verdade.
 */
public final class ModePresets {

    private ModePresets() {
    }

    /** Um preset completo — os 7 parâmetros Camera2 da tabela do contrato + face-AF. */
    public static final class Preset {
        public final int afMode;
        public final int aeMode;
        /** {@code null} = livre (não seta AE_TARGET_FPS_RANGE); sempre não-null junto com fpsMax. */
        public final Integer fpsMin;
        public final Integer fpsMax;
        /** Compensação em EV inteiro (não em steps nativos — quem aplica converte). {@code null} = livre. */
        public final Integer evCompEv;
        /** {@code null} = livre (não seta CONTROL_AWB_MODE). */
        public final Integer awbMode;
        public final int stabilizationMode;
        public final int noiseReductionMode;
        public final boolean faceAf;

        private Preset(int afMode, int aeMode, Integer fpsMin, Integer fpsMax, Integer evCompEv, Integer awbMode,
                int stabilizationMode, int noiseReductionMode, boolean faceAf) {
            this.afMode = afMode;
            this.aeMode = aeMode;
            this.fpsMin = fpsMin;
            this.fpsMax = fpsMax;
            this.evCompEv = evCompEv;
            this.awbMode = awbMode;
            this.stabilizationMode = stabilizationMode;
            this.noiseReductionMode = noiseReductionMode;
            this.faceAf = faceAf;
        }
    }

    public static final Preset AUTO = new Preset(
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
            CaptureRequest.CONTROL_AE_MODE_ON,
            30, 30,
            0,
            CaptureRequest.CONTROL_AWB_MODE_AUTO,
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON,
            CaptureRequest.NOISE_REDUCTION_MODE_FAST,
            true);

    public static final Preset NIGHT = new Preset(
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
            CaptureRequest.CONTROL_AE_MODE_ON,
            15, 30,
            1,
            CaptureRequest.CONTROL_AWB_MODE_AUTO,
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON,
            CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY,
            true);

    public static final Preset SPORT = new Preset(
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
            CaptureRequest.CONTROL_AE_MODE_ON,
            60, 60,
            0,
            CaptureRequest.CONTROL_AWB_MODE_AUTO,
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
            CaptureRequest.NOISE_REDUCTION_MODE_FAST,
            false);

    public static final Preset PRO = new Preset(
            CaptureRequest.CONTROL_AF_MODE_OFF,
            CaptureRequest.CONTROL_AE_MODE_OFF,
            null, null,
            null,
            null,
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
            CaptureRequest.NOISE_REDUCTION_MODE_OFF,
            false);

    /** {@code null} se {@code mode} não for um dos 4 valores válidos do contrato. */
    public static Preset forMode(String mode) {
        if (mode == null) {
            return null;
        }
        switch (mode) {
            case "auto":
                return AUTO;
            case "night":
                return NIGHT;
            case "sport":
                return SPORT;
            case "pro":
                return PRO;
            default:
                return null;
        }
    }
}
