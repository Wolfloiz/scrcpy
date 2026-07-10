package com.genymobile.scrcpy.camlink;

import com.genymobile.scrcpy.util.Ln;
import com.genymobile.scrcpy.video.CameraCapture;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.util.Range;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * CamLink control thread: listens on localabstract:camlink and applies JSON commands (one per
 * line, NDJSON) to the CameraCaptureSession owned by the scrcpy server, without reopening the
 * camera. The desktop reaches this socket through {@code adb forward tcp:<PORT>
 * localabstract:camlink}.
 *
 * <p>Protocol contract: {@code specs/001-phone-webcam-bridge/contracts/control-protocol.md} in the
 * CamLink repository. Spike A (T015) scope: {@code hello} and {@code set_zoom} only; the remaining
 * commands land with user stories US2/US3/US5.
 */
public final class CamLinkControlServer implements Runnable {

    public static final String SOCKET_NAME = "camlink";
    public static final int PROTOCOL_VERSION = 1;
    public static final String SERVER_NAME = "camlink-v4.0";

    private final CameraCapture cameraCapture;
    private final Thread thread;
    private LocalServerSocket serverSocket;
    private volatile boolean stopped;

    private CamLinkControlServer(CameraCapture cameraCapture) {
        this.cameraCapture = cameraCapture;
        this.thread = new Thread(this, "camlink-control");
        // Daemon: never keep the server process alive on its own
        this.thread.setDaemon(true);
    }

    public static CamLinkControlServer start(CameraCapture cameraCapture) throws IOException {
        CamLinkControlServer server = new CamLinkControlServer(cameraCapture);
        server.serverSocket = new LocalServerSocket(SOCKET_NAME);
        server.thread.start();
        Ln.i("CamLink control server listening on localabstract:" + SOCKET_NAME);
        return server;
    }

    public void stop() {
        stopped = true;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException e) {
            // ignore, shutting down anyway
        }
    }

    @Override
    public void run() {
        while (!stopped) {
            // One client at a time (the CamLink desktop app); loop to accept reconnections
            try (LocalSocket client = serverSocket.accept()) {
                Ln.i("CamLink control client connected");
                serve(client);
                Ln.i("CamLink control client disconnected");
            } catch (IOException e) {
                if (!stopped) {
                    Ln.w("CamLink control connection error: " + e.getMessage());
                }
            }
        }
    }

    private void serve(LocalSocket client) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
        OutputStream out = client.getOutputStream();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.trim().isEmpty()) {
                continue;
            }
            String response = handleLine(line);
            out.write((response + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    private String handleLine(String line) {
        try {
            JSONObject request = new JSONObject(line);
            String cmd = request.optString("cmd", "");
            switch (cmd) {
                case "hello":
                    return hello();
                case "set_zoom":
                    return setZoom(request);
                default:
                    return error("BAD_REQUEST", "unknown command: " + cmd);
            }
        } catch (JSONException e) {
            return error("BAD_REQUEST", "invalid JSON: " + e.getMessage());
        }
    }

    private static String hello() throws JSONException {
        JSONObject response = new JSONObject();
        response.put("ok", true);
        response.put("protocol", PROTOCOL_VERSION);
        response.put("server", SERVER_NAME);
        return response.toString();
    }

    private String setZoom(JSONObject request) throws JSONException {
        if (!request.has("ratio")) {
            return error("BAD_REQUEST", "missing required field: ratio");
        }
        double ratio = request.getDouble("ratio");

        // Validate against capabilities before applying (contract §3); the range is only known
        // once the capture session is configured.
        Range<Float> range = cameraCapture.getZoomRatioRange();
        if (range != null && (ratio < range.getLower() || ratio > range.getUpper())) {
            return error("OUT_OF_RANGE", "zoom " + ratio + " out of [" + range.getLower() + ", " + range.getUpper() + "]");
        }

        cameraCapture.setZoomRatio((float) ratio);

        JSONObject data = new JSONObject();
        data.put("ratio", ratio);
        return ok(data);
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
            // Cannot happen with plain string values; keep a hardcoded fallback anyway
            return "{\"ok\":false,\"error\":{\"code\":\"CAMERA_ERROR\",\"msg\":\"internal error\"}}";
        }
    }
}
