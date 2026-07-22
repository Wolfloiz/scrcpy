package com.genymobile.scrcpy.camlink;

import com.genymobile.scrcpy.util.Ln;
import com.genymobile.scrcpy.video.CameraCapture;
import com.genymobile.scrcpy.wrappers.ServiceManager;

import android.annotation.TargetApi;
import android.graphics.Rect;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaCodec;
import android.util.Range;
import android.util.Rational;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Controller real (T034–T036): monta as {@code DeviceCapabilities} a partir das
 * CameraCharacteristics e aplica cada comando já validado reconstruindo a repeating request da
 * sessão do scrcpy — nunca reabrindo a câmera (contrato §3). O servidor é câmera-only no CamLink
 * (Android 12+), então os hooks anotados com API 31 são seguros aqui.
 */
@TargetApi(31)
public final class CamLinkCameraController implements CamLinkCommandProcessor.Controller {

    /** Destino dos eventos assíncronos (af_state) — implementado pelo CamLinkControlServer. */
    public interface EventSink {
        void sendEvent(String line);
    }

    private static final long DEFAULT_EXPOSURE_NS = 33_333_333L; // 1/30 s

    private final CameraCapture capture;
    private final EventSink eventSink;
    private JSONObject cachedCapabilities;

    public CamLinkCameraController(CameraCapture capture, EventSink eventSink) {
        this.capture = capture;
        this.eventSink = eventSink;
    }

    // -----------------------------------------------------------------------
    // Capabilities (T034)
    // -----------------------------------------------------------------------

    @Override
    public synchronized JSONObject getCapabilities() throws Exception {
        if (cachedCapabilities == null) {
            cachedCapabilities = buildCapabilities();
        }
        return cachedCapabilities;
    }

    private JSONObject buildCapabilities() throws Exception {
        CameraManager manager = ServiceManager.getCameraManager();
        String currentId = capture.camlinkGetCameraId();
        CameraCharacteristics current = manager.getCameraCharacteristics(currentId);

        JSONObject caps = new JSONObject();
        JSONArray cameras = new JSONArray();
        for (String id : manager.getCameraIdList()) {
            CameraCharacteristics ch = manager.getCameraCharacteristics(id);
            JSONObject cam = new JSONObject();
            cam.put("id", id);
            Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
            cam.put("facing", facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT ? "front" : "back");
            cam.put("max_resolution", maxResolution(ch));
            JSONArray fpsRanges = new JSONArray();
            Range<Integer>[] ranges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            if (ranges != null) {
                for (Range<Integer> r : ranges) {
                    JSONArray pair = new JSONArray();
                    pair.put(r.getLower());
                    pair.put(r.getUpper());
                    fpsRanges.put(pair);
                }
            }
            cam.put("fps_ranges", fpsRanges);
            cameras.put(cam);
        }
        caps.put("cameras", cameras);

        Range<Float> zoomRange = current.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
        JSONArray zoom = new JSONArray();
        if (zoomRange != null) {
            zoom.put((double) zoomRange.getLower());
            zoom.put((double) zoomRange.getUpper());
        } else {
            Float maxDigital = current.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
            zoom.put(1.0);
            zoom.put(maxDigital != null ? (double) maxDigital : 1.0);
        }
        caps.put("zoom_range", zoom);

        if (hasCapability(current, CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)) {
            Range<Integer> iso = current.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
            if (iso != null) {
                JSONArray isoRange = new JSONArray();
                isoRange.put(iso.getLower());
                isoRange.put(iso.getUpper());
                caps.put("iso_range", isoRange);
            } else {
                caps.put("iso_range", JSONObject.NULL);
            }
        } else {
            caps.put("iso_range", JSONObject.NULL);
        }

        // Range de compensação em passos de EV inteiros (contrato/data-model):
        // converte o range nativo (em steps do step-size da câmera) para EV.
        Range<Integer> compRange = current.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
        Rational compStep = current.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
        JSONArray comp = new JSONArray();
        if (compRange != null && compStep != null && compStep.floatValue() > 0) {
            comp.put(Math.round(compRange.getLower() * compStep.floatValue()));
            comp.put(Math.round(compRange.getUpper() * compStep.floatValue()));
        } else {
            comp.put(0);
            comp.put(0);
        }
        caps.put("exposure_comp_range", comp);

        JSONArray wbModes = new JSONArray();
        int[] awbModes = current.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES);
        if (awbModes != null) {
            for (int m : awbModes) {
                String name = wbModeName(m);
                if (name != null) {
                    wbModes.put(name);
                }
            }
        }
        caps.put("wb_modes", wbModes);

        boolean eis = false;
        int[] stabilization = current.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES);
        if (stabilization != null) {
            for (int m : stabilization) {
                if (m == CameraCharacteristics.CONTROL_VIDEO_STABILIZATION_MODE_ON) {
                    eis = true;
                    break;
                }
            }
        }
        caps.put("supports_eis", eis);

        Boolean flash = current.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
        caps.put("supports_torch", flash != null && flash);

        if (hasCapability(current, CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)) {
            StreamConfigurationMap configs = current.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            android.util.Size[] rawSizes = configs != null ? configs.getOutputSizes(android.graphics.ImageFormat.RAW_SENSOR) : null;
            if (rawSizes != null && rawSizes.length > 0) {
                android.util.Size best = rawSizes[0];
                for (android.util.Size s : rawSizes) {
                    if ((long) s.getWidth() * s.getHeight() > (long) best.getWidth() * best.getHeight()) {
                        best = s;
                    }
                }
                JSONObject raw = new JSONObject();
                JSONArray sensorSize = new JSONArray();
                sensorSize.put(best.getWidth());
                sensorSize.put(best.getHeight());
                raw.put("sensor_size", sensorSize);
                // RAW16: 2 bytes por pixel (DngCreator consome RAW_SENSOR)
                raw.put("frame_bytes", (long) best.getWidth() * best.getHeight() * 2);
                caps.put("raw", raw);
            } else {
                caps.put("raw", JSONObject.NULL);
            }
        } else {
            caps.put("raw", JSONObject.NULL);
        }

        return caps;
    }

    private static JSONArray maxResolution(CameraCharacteristics ch) throws JSONException {
        JSONArray out = new JSONArray();
        StreamConfigurationMap configs = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        android.util.Size best = null;
        if (configs != null) {
            android.util.Size[] sizes = configs.getOutputSizes(MediaCodec.class);
            if (sizes != null) {
                for (android.util.Size s : sizes) {
                    if (best == null || (long) s.getWidth() * s.getHeight() > (long) best.getWidth() * best.getHeight()) {
                        best = s;
                    }
                }
            }
        }
        out.put(best != null ? best.getWidth() : 0);
        out.put(best != null ? best.getHeight() : 0);
        return out;
    }

    private static boolean hasCapability(CameraCharacteristics ch, int capability) {
        int[] caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        if (caps == null) {
            return false;
        }
        for (int c : caps) {
            if (c == capability) {
                return true;
            }
        }
        return false;
    }

    private static String wbModeName(int mode) {
        switch (mode) {
            case CameraCharacteristics.CONTROL_AWB_MODE_AUTO:
                return "auto";
            case CameraCharacteristics.CONTROL_AWB_MODE_DAYLIGHT:
                return "daylight";
            case CameraCharacteristics.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT:
                return "cloudy";
            case CameraCharacteristics.CONTROL_AWB_MODE_FLUORESCENT:
                return "fluorescent";
            case CameraCharacteristics.CONTROL_AWB_MODE_INCANDESCENT:
                return "incandescent";
            default:
                return null; // modos fora do contrato ficam de fora
        }
    }

    private static Integer wbModeValue(String name) {
        switch (name) {
            case "auto":
                return CameraCharacteristics.CONTROL_AWB_MODE_AUTO;
            case "daylight":
                return CameraCharacteristics.CONTROL_AWB_MODE_DAYLIGHT;
            case "cloudy":
                return CameraCharacteristics.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT;
            case "fluorescent":
                return CameraCharacteristics.CONTROL_AWB_MODE_FLUORESCENT;
            case "incandescent":
                return CameraCharacteristics.CONTROL_AWB_MODE_INCANDESCENT;
            default:
                return null;
        }
    }

    // -----------------------------------------------------------------------
    // Aplicação de controles (T035/T036) — rebuild da repeating request
    // -----------------------------------------------------------------------

    @Override
    public void applyZoom(double ratio) {
        capture.setZoomRatio((float) ratio);
    }

    @Override
    public void applyFocusContinuous() {
        capture.camlinkUpdateRequest(builder -> {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, null);
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, null);
        });
    }

    @Override
    public void applyFocusTap(double x, double y) throws Exception {
        // Mapeia as coordenadas normalizadas do preview para o retângulo ativo
        // do sensor (contrato §3: x/y em [0,1]).
        CameraManager manager = ServiceManager.getCameraManager();
        CameraCharacteristics ch = manager.getCameraCharacteristics(capture.camlinkGetCameraId());
        Rect active = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        if (active == null) {
            throw new IllegalStateException("sensor sem SENSOR_INFO_ACTIVE_ARRAY_SIZE");
        }
        int half = Math.max(active.width(), active.height()) / 20; // região ~10%
        int cx = active.left + (int) (x * active.width());
        int cy = active.top + (int) (y * active.height());
        Rect region = new Rect(
                Math.max(active.left, cx - half),
                Math.max(active.top, cy - half),
                Math.min(active.right, cx + half),
                Math.min(active.bottom, cy + half));
        MeteringRectangle[] regions = {new MeteringRectangle(region, MeteringRectangle.METERING_WEIGHT_MAX)};

        capture.camlinkCaptureOnce(builder -> {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, regions);
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, regions);
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
        }, new CameraCaptureSession.CaptureCallback() {
            @Override
            public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
                Integer afState = result.get(CaptureResult.CONTROL_AF_STATE);
                String state;
                if (afState == null) {
                    state = "searching";
                } else if (afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
                        || afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED) {
                    state = "focused";
                } else if (afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED) {
                    state = "failed";
                } else {
                    state = "searching";
                }
                eventSink.sendEvent(CamLinkCommandProcessor.eventAfState(state));
            }
        });
    }

    @Override
    public void applyFocusManual(double distance) throws Exception {
        // `distance` normalizada [0,1] → dioptrias reais (0 = infinito,
        // 1 = distância mínima de foco da lente).
        CameraManager manager = ServiceManager.getCameraManager();
        CameraCharacteristics ch = manager.getCameraCharacteristics(capture.camlinkGetCameraId());
        Float minFocus = ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
        float diopters = (float) distance * (minFocus != null ? minFocus : 10.0f);
        capture.camlinkUpdateRequest(builder -> {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, diopters);
        });
    }

    @Override
    public void applyExposureCompensation(int ev) throws Exception {
        // EV inteiro do contrato → steps nativos do step-size da câmera.
        CameraManager manager = ServiceManager.getCameraManager();
        CameraCharacteristics ch = manager.getCameraCharacteristics(capture.camlinkGetCameraId());
        Rational step = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
        int steps = step != null && step.floatValue() > 0 ? Math.round(ev / step.floatValue()) : ev;
        capture.camlinkUpdateRequest(builder -> {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, steps);
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, null);
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, null);
        });
    }

    @Override
    public void applyIso(int value) {
        capture.camlinkUpdateRequest(builder -> {
            Long currentExposure = builder.get(CaptureRequest.SENSOR_EXPOSURE_TIME);
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, value);
            builder.set(
                    CaptureRequest.SENSOR_EXPOSURE_TIME,
                    currentExposure != null ? currentExposure : DEFAULT_EXPOSURE_NS);
            Ln.i("CamLink: iso manual " + value);
        });
    }

    @Override
    public void applyWb(String mode) {
        Integer awb = wbModeValue(mode);
        if (awb == null) {
            return; // processor já validou contra capabilities
        }
        capture.camlinkUpdateRequest(builder -> builder.set(CaptureRequest.CONTROL_AWB_MODE, awb));
    }

    @Override
    public void applyEis(boolean enabled) {
        capture.camlinkUpdateRequest(builder -> builder.set(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                enabled
                        ? CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
                        : CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF));
    }

    @Override
    public void applyTorch(boolean enabled) {
        capture.setTorchEnabled(enabled);
    }
}
