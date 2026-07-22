package com.genymobile.scrcpy.camlink;

import com.genymobile.scrcpy.util.Ln;
import com.genymobile.scrcpy.video.CameraCapture;

import android.net.LocalServerSocket;
import android.net.LocalSocket;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Thread de controle CamLink (T033): escuta em {@code localabstract:camlink} e delega cada linha
 * NDJSON ao {@link CamLinkCommandProcessor} (que valida contra capabilities e aplica na
 * CameraCaptureSession do scrcpy via {@link CamLinkCameraController}). O desktop alcança este
 * socket com {@code adb forward tcp:<PORT> localabstract:camlink}.
 *
 * <p>Eventos assíncronos ({@code af_state}) são intercalados com as respostas no mesmo socket —
 * linhas com chave {@code event}, nunca {@code ok} (contrato §5); o cliente demultiplexa.
 *
 * <p>Contrato: {@code specs/001-phone-webcam-bridge/contracts/control-protocol.md} no repositório
 * CamLink, validado pelos golden files nos dois lados (ProtocolTest.java aqui, cargo test lá).
 */
public final class CamLinkControlServer implements Runnable, CamLinkCameraController.EventSink {

    public static final String SOCKET_NAME = "camlink";
    public static final String SERVER_NAME = "camlink-v4.0";

    private final CamLinkCommandProcessor processor;
    private final Thread thread;
    private LocalServerSocket serverSocket;
    private volatile boolean stopped;
    // Cliente corrente (um por vez); eventos assíncronos escrevem aqui
    private volatile OutputStream clientOut;

    private CamLinkControlServer(CameraCapture cameraCapture) {
        CamLinkCameraController controller = new CamLinkCameraController(cameraCapture, this);
        this.processor = new CamLinkCommandProcessor(controller, SERVER_NAME);
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
            } finally {
                clientOut = null;
            }
        }
    }

    private void serve(LocalSocket client) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
        OutputStream out = client.getOutputStream();
        clientOut = out;
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.trim().isEmpty()) {
                continue;
            }
            String response = processor.process(line);
            synchronized (this) {
                out.write((response + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        }
    }

    /** Emite um evento assíncrono para o cliente corrente (no-op sem cliente). */
    @Override
    public void sendEvent(String eventLine) {
        OutputStream out = clientOut;
        if (out == null) {
            return;
        }
        try {
            synchronized (this) {
                out.write((eventLine + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (IOException e) {
            Ln.w("CamLink event dropped: " + e.getMessage());
        }
    }
}
