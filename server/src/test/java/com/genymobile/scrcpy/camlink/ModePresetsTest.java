package com.genymobile.scrcpy.camlink;

import android.hardware.camera2.CaptureRequest;

import org.junit.Assert;
import org.junit.Test;

/**
 * T042 — Valida a tabela {@link ModePresets} literalmente contra
 * {@code contracts/control-protocol.md} §3 "Tabela de modos" / {@code research.md} R2. Pura, JVM
 * simples (sem Camera2 real) — os valores de {@code CaptureRequest}/{@code CameraCharacteristics}
 * usados aqui são só as constantes inteiras públicas, disponíveis no {@code android.jar} de stub
 * usado pra compilar os testes unitários (mesma base de {@code ProtocolTest}).
 */
public final class ModePresetsTest {

    @Test
    public void autoMatchesContractTable() {
        ModePresets.Preset p = ModePresets.AUTO;
        Assert.assertEquals(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO, p.afMode);
        Assert.assertEquals(CaptureRequest.CONTROL_AE_MODE_ON, p.aeMode);
        Assert.assertEquals(Integer.valueOf(30), p.fpsMin);
        Assert.assertEquals(Integer.valueOf(30), p.fpsMax);
        Assert.assertEquals(Integer.valueOf(0), p.evCompEv);
        Assert.assertEquals(CaptureRequest.CONTROL_AWB_MODE_AUTO, (int) p.awbMode);
        Assert.assertEquals(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON, p.stabilizationMode);
        Assert.assertEquals(CaptureRequest.NOISE_REDUCTION_MODE_FAST, p.noiseReductionMode);
        Assert.assertTrue(p.faceAf);
    }

    @Test
    public void nightMatchesContractTable() {
        ModePresets.Preset p = ModePresets.NIGHT;
        Assert.assertEquals(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO, p.afMode);
        Assert.assertEquals(CaptureRequest.CONTROL_AE_MODE_ON, p.aeMode);
        Assert.assertEquals(Integer.valueOf(15), p.fpsMin);
        Assert.assertEquals(Integer.valueOf(30), p.fpsMax);
        Assert.assertEquals(Integer.valueOf(1), p.evCompEv);
        Assert.assertEquals(CaptureRequest.CONTROL_AWB_MODE_AUTO, (int) p.awbMode);
        Assert.assertEquals(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON, p.stabilizationMode);
        Assert.assertEquals(CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY, p.noiseReductionMode);
        Assert.assertTrue(p.faceAf);
    }

    @Test
    public void sportMatchesContractTable() {
        ModePresets.Preset p = ModePresets.SPORT;
        Assert.assertEquals(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO, p.afMode);
        Assert.assertEquals(CaptureRequest.CONTROL_AE_MODE_ON, p.aeMode);
        Assert.assertEquals(Integer.valueOf(60), p.fpsMin);
        Assert.assertEquals(Integer.valueOf(60), p.fpsMax);
        Assert.assertEquals(Integer.valueOf(0), p.evCompEv);
        Assert.assertEquals(CaptureRequest.CONTROL_AWB_MODE_AUTO, (int) p.awbMode);
        Assert.assertEquals(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF, p.stabilizationMode);
        Assert.assertEquals(CaptureRequest.NOISE_REDUCTION_MODE_FAST, p.noiseReductionMode);
        Assert.assertFalse(p.faceAf);
    }

    @Test
    public void proLeavesAeFpsAwbFree() {
        ModePresets.Preset p = ModePresets.PRO;
        Assert.assertEquals(CaptureRequest.CONTROL_AF_MODE_OFF, p.afMode);
        Assert.assertEquals(CaptureRequest.CONTROL_AE_MODE_OFF, p.aeMode);
        Assert.assertNull("pro é livre: não deve fixar fps_range", p.fpsMin);
        Assert.assertNull("pro é livre: não deve fixar fps_range", p.fpsMax);
        Assert.assertNull("pro é livre: não deve fixar EV", p.evCompEv);
        Assert.assertNull("pro é livre: não deve fixar AWB", p.awbMode);
        Assert.assertEquals(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF, p.stabilizationMode);
        Assert.assertEquals(CaptureRequest.NOISE_REDUCTION_MODE_OFF, p.noiseReductionMode);
        Assert.assertFalse(p.faceAf);
    }

    @Test
    public void forModeReturnsNullForUnknownValue() {
        Assert.assertNull(ModePresets.forMode("cinematic"));
        Assert.assertNull(ModePresets.forMode(null));
    }

    @Test
    public void forModeCoversAllFourContractValues() {
        Assert.assertSame(ModePresets.AUTO, ModePresets.forMode("auto"));
        Assert.assertSame(ModePresets.NIGHT, ModePresets.forMode("night"));
        Assert.assertSame(ModePresets.SPORT, ModePresets.forMode("sport"));
        Assert.assertSame(ModePresets.PRO, ModePresets.forMode("pro"));
    }
}
