package com.genymobile.scrcpy.camlink;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Núcleo puro do protocolo CamLink (T033): uma linha NDJSON de request entra, uma linha de
 * response sai — sem sockets, sem Android — para ser validado na JVM contra os golden files
 * ({@code specs/001-phone-webcam-bridge/contracts/golden/} no repositório CamLink) pelos DOIS
 * lados (JUnit aqui, {@code cargo test} no cliente Rust).
 *
 * <p>Toda validação contra capabilities acontece AQUI, antes de tocar a câmera (contrato §3):
 * um comando rejeitado nunca chega ao {@link Controller}.
 */
public final class CamLinkCommandProcessor {

    public static final int PROTOCOL_VERSION = 1;

    /** Aplica os comandos já validados na câmera real (ou grava, nos testes). */
    public interface Controller {
        /** Capabilities correntes (data-model.md), usadas na validação e no get_capabilities. */
        JSONObject getCapabilities() throws Exception;

        void applyZoom(double ratio) throws Exception;

        void applyFocusContinuous() throws Exception;

        void applyFocusTap(double x, double y) throws Exception;

        void applyFocusManual(double distance) throws Exception;

        void applyExposureCompensation(int steps) throws Exception;

        void applyIso(int value) throws Exception;

        void applyWb(String mode) throws Exception;

        void applyEis(boolean enabled) throws Exception;

        void applyTorch(boolean enabled) throws Exception;
    }

    private final Controller controller;
    private final String serverName;
    // Modo corrente (US3 set_mode; aqui só gateia set_iso — exige "pro")
    private volatile String mode = "auto";

    public CamLinkCommandProcessor(Controller controller, String serverName) {
        this.controller = controller;
        this.serverName = serverName;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    /** Processa uma linha NDJSON; sempre devolve uma linha de resposta (nunca lança). */
    public String process(String line) {
        JSONObject request;
        try {
            request = new JSONObject(line);
        } catch (JSONException e) {
            return error("BAD_REQUEST", "JSON inválido");
        }
        try {
            return dispatch(request);
        } catch (JSONException e) {
            return error("BAD_REQUEST", "JSON inválido");
        } catch (Exception e) {
            return error("CAMERA_ERROR", "falha na câmera: " + e.getMessage());
        }
    }

    private String dispatch(JSONObject request) throws Exception {
        String cmd = request.optString("cmd", "");
        switch (cmd) {
            case "hello":
                return hello();
            case "get_capabilities":
                return ok(controller.getCapabilities());
            case "set_zoom":
                return setZoom(request);
            case "set_focus":
                return setFocus(request);
            case "set_exposure":
                return setExposure(request);
            case "set_iso":
                return setIso(request);
            case "set_wb":
                return setWb(request);
            case "set_eis":
                return setEis(request);
            case "set_torch":
                return setTorch(request);
            default:
                return error("BAD_REQUEST", "comando desconhecido: " + cmd);
        }
    }

    private String hello() throws JSONException {
        JSONObject response = new JSONObject();
        response.put("ok", true);
        response.put("protocol", PROTOCOL_VERSION);
        response.put("server", serverName);
        return response.toString();
    }

    private String setZoom(JSONObject request) throws Exception {
        if (!request.has("ratio")) {
            return error("BAD_REQUEST", "campo obrigatório ausente: ratio");
        }
        double ratio = request.getDouble("ratio");
        JSONArray range = controller.getCapabilities().getJSONArray("zoom_range");
        double lo = range.getDouble(0);
        double hi = range.getDouble(1);
        if (ratio < lo || ratio > hi) {
            return error("OUT_OF_RANGE", "zoom " + ratio + " fora de [" + lo + ", " + hi + "]");
        }
        controller.applyZoom(ratio);
        JSONObject data = new JSONObject();
        data.put("ratio", ratio);
        return ok(data);
    }

    private String setFocus(JSONObject request) throws Exception {
        if (!request.has("mode")) {
            return error("BAD_REQUEST", "campo obrigatório ausente: mode");
        }
        String focusMode = request.getString("mode");
        JSONObject data = new JSONObject();
        data.put("mode", focusMode);
        switch (focusMode) {
            case "continuous":
                controller.applyFocusContinuous();
                return ok(data);
            case "tap": {
                if (!request.has("x") || !request.has("y")) {
                    return error("BAD_REQUEST", "campo obrigatório ausente: x/y");
                }
                double x = request.getDouble("x");
                double y = request.getDouble("y");
                if (x < 0 || x > 1 || y < 0 || y > 1) {
                    return error("OUT_OF_RANGE", "coordenadas de foco fora de [0, 1]");
                }
                controller.applyFocusTap(x, y);
                data.put("x", x);
                data.put("y", y);
                return ok(data);
            }
            case "manual": {
                if (!request.has("distance")) {
                    return error("BAD_REQUEST", "campo obrigatório ausente: distance");
                }
                double distance = request.getDouble("distance");
                controller.applyFocusManual(distance);
                data.put("distance", distance);
                return ok(data);
            }
            default:
                return error("BAD_REQUEST", "modo de foco inválido: " + focusMode);
        }
    }

    private String setExposure(JSONObject request) throws Exception {
        if (!request.has("compensation")) {
            return error("BAD_REQUEST", "campo obrigatório ausente: compensation");
        }
        int compensation = request.getInt("compensation");
        JSONArray range = controller.getCapabilities().getJSONArray("exposure_comp_range");
        int lo = range.getInt(0);
        int hi = range.getInt(1);
        if (compensation < lo || compensation > hi) {
            return error("OUT_OF_RANGE", "compensation " + compensation + " fora de [" + lo + ", " + hi + "]");
        }
        controller.applyExposureCompensation(compensation);
        JSONObject data = new JSONObject();
        data.put("compensation", compensation);
        return ok(data);
    }

    private String setIso(JSONObject request) throws Exception {
        if (!request.has("value")) {
            return error("BAD_REQUEST", "campo obrigatório ausente: value");
        }
        JSONObject caps = controller.getCapabilities();
        if (caps.isNull("iso_range")) {
            return error("UNSUPPORTED", "iso manual não suportado");
        }
        if (!"pro".equals(mode)) {
            return error("BAD_REQUEST", "set_iso exige modo pro");
        }
        int value = request.getInt("value");
        JSONArray range = caps.getJSONArray("iso_range");
        int lo = range.getInt(0);
        int hi = range.getInt(1);
        if (value < lo || value > hi) {
            return error("OUT_OF_RANGE", "iso " + value + " fora de [" + lo + ", " + hi + "]");
        }
        controller.applyIso(value);
        JSONObject data = new JSONObject();
        data.put("value", value);
        return ok(data);
    }

    private String setWb(JSONObject request) throws Exception {
        if (!request.has("mode")) {
            return error("BAD_REQUEST", "campo obrigatório ausente: mode");
        }
        String wbMode = request.getString("mode");
        JSONArray modes = controller.getCapabilities().getJSONArray("wb_modes");
        boolean supported = false;
        for (int i = 0; i < modes.length(); i++) {
            if (wbMode.equals(modes.getString(i))) {
                supported = true;
                break;
            }
        }
        if (!supported) {
            return error("UNSUPPORTED", "wb " + wbMode + " não suportado");
        }
        controller.applyWb(wbMode);
        JSONObject data = new JSONObject();
        data.put("mode", wbMode);
        return ok(data);
    }

    private String setEis(JSONObject request) throws Exception {
        if (!request.has("enabled")) {
            return error("BAD_REQUEST", "campo obrigatório ausente: enabled");
        }
        if (!controller.getCapabilities().optBoolean("supports_eis", false)) {
            return error("UNSUPPORTED", "eis não suportado");
        }
        boolean enabled = request.getBoolean("enabled");
        controller.applyEis(enabled);
        JSONObject data = new JSONObject();
        data.put("enabled", enabled);
        return ok(data);
    }

    private String setTorch(JSONObject request) throws Exception {
        if (!request.has("enabled")) {
            return error("BAD_REQUEST", "campo obrigatório ausente: enabled");
        }
        if (!controller.getCapabilities().optBoolean("supports_torch", false)) {
            return error("UNSUPPORTED", "torch não suportado");
        }
        boolean enabled = request.getBoolean("enabled");
        controller.applyTorch(enabled);
        JSONObject data = new JSONObject();
        data.put("enabled", enabled);
        return ok(data);
    }

    // --- Eventos assíncronos (contrato §5: chave "event", nunca "ok") ---

    public static String eventAfState(String state) {
        try {
            JSONObject event = new JSONObject();
            event.put("event", "af_state");
            event.put("state", state);
            return event.toString();
        } catch (JSONException e) {
            return "{\"event\":\"af_state\",\"state\":\"failed\"}";
        }
    }

    public static String eventFaces(JSONArray rects) {
        try {
            JSONObject event = new JSONObject();
            event.put("event", "faces");
            event.put("rects", rects);
            return event.toString();
        } catch (JSONException e) {
            return "{\"event\":\"faces\",\"rects\":[]}";
        }
    }

    private static String ok(JSONObject data) throws JSONException {
        JSONObject response = new JSONObject();
        response.put("ok", true);
        response.put("data", data);
        return response.toString();
    }

    private static String error(String code, String msg) {
        try {
            JSONObject err = new JSONObject();
            err.put("code", code);
            err.put("msg", msg);
            JSONObject response = new JSONObject();
            response.put("ok", false);
            response.put("error", err);
            return response.toString();
        } catch (JSONException e) {
            return "{\"ok\":false,\"error\":{\"code\":\"CAMERA_ERROR\",\"msg\":\"internal error\"}}";
        }
    }
}
