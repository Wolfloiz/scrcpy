package com.genymobile.scrcpy.camlink;

import com.genymobile.scrcpy.util.Ln;
import com.genymobile.scrcpy.video.CameraCapture;

import android.annotation.TargetApi;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.DngCreator;
import android.hardware.camera2.TotalCaptureResult;
import android.media.Image;
import android.media.ImageReader;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Captura RAW/DNG (US5, contracts/control-protocol.md §4): dispara capturas na superfície
 * RAW_SENSOR extra da sessão ({@link CameraCapture#camlinkGetRawReader}), monta o DNG com {@link
 * DngCreator} e transmite pelo framing binário (tag {@code 0xD1}) — SEMPRE no mesmo socket de
 * controle, nunca base64 (research.md R6: desperdiçaria ~33% do gargalo do túnel ADB). No máximo
 * 1 job por vez (Snapshot OU Sequência — data-model.md), gateado pelo {@code busy} antes de
 * chegar aqui pelo {@link CamLinkCommandProcessor}.
 *
 * <p>Cadência da Sequência (FR-019/020): recalculada a cada frame a partir do tempo de escrita do
 * frame ANTERIOR no socket (proxy do throughput real do túnel ADB) — mesma fórmula de
 * {@code raw_manager::effective_raw_fps} no cliente Rust (throughput ÷ tamanho do frame, clamp
 * [1,3]), duplicada aqui porque cada lado mede seu próprio throughput (mesmo espírito de
 * {@code ModePresets} espelhando a tabela de modos nos dois lados).
 */
@TargetApi(31)
public final class RawCapture {

    /** Espelha {@code raw_manager::RAW_FRAME_TAG} (Rust). */
    private static final byte FRAME_TAG = (byte) 0xD1;

    /** Espelha {@code raw_manager::RAW_SEQUENCE_MIN_FPS}/{@code MAX_FPS} (Rust). */
    private static final double MIN_FPS = 1.0;
    private static final double MAX_FPS = 3.0;

    /** Destino dos frames binários — implementado por {@link CamLinkControlServer}. */
    public interface FrameSink {
        void sendRawFrame(byte[] framed);
    }

    private final CameraCapture capture;
    private final CameraCharacteristics characteristics;
    private final FrameSink sink;
    private final CamLinkCameraController.EventSink eventSink;
    /** Tamanho estimado de 1 frame RAW (`frame_bytes` das capabilities) — usado no cálculo de fps. */
    private final long frameBytes;

    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final AtomicLong seq = new AtomicLong(0);
    /** Invalida capturas agendadas de um job que já foi parado/substituído (ver {@link #captureOne}). */
    private final AtomicLong generation = new AtomicLong(0);
    private volatile boolean sequenceRunning;
    private volatile double lastThroughputBytesPerSec = 0;

    public RawCapture(
            CameraCapture capture,
            CameraCharacteristics characteristics,
            FrameSink sink,
            CamLinkCameraController.EventSink eventSink,
            long frameBytes) {
        this.capture = capture;
        this.characteristics = characteristics;
        this.sink = sink;
        this.eventSink = eventSink;
        this.frameBytes = frameBytes;
        ImageReader reader = capture.camlinkGetRawReader();
        if (reader != null) {
            reader.setOnImageAvailableListener(this::onImageAvailable, capture.camlinkGetCameraHandler());
        }
    }

    public boolean isBusy() {
        return busy.get();
    }

    /** Snapshot único (contrato §4: {@code raw_snapshot}). */
    public void snapshot() {
        if (!busy.compareAndSet(false, true)) {
            return; // dispatch já bloqueia isso com BUSY antes de chegar aqui — defesa extra
        }
        sequenceRunning = false;
        captureOne(generation.incrementAndGet());
    }

    /** Sequência (contrato §4: {@code raw_sequence_start}). @return fps concedida inicialmente. */
    public double sequenceStart(double requestedFps) {
        if (!busy.compareAndSet(false, true)) {
            return 0;
        }
        sequenceRunning = true;
        long myGeneration = generation.incrementAndGet();
        double granted = clamp(requestedFps);
        captureOne(myGeneration);
        return granted;
    }

    /** Contrato §4: {@code raw_sequence_stop} — idempotente. */
    public void sequenceStop() {
        generation.incrementAndGet();
        sequenceRunning = false;
        busy.set(false);
    }

    private void captureOne(long myGeneration) {
        if (myGeneration != generation.get()) {
            return; // job foi parado (ou substituído) entre o agendamento e a execução
        }
        ImageReader reader = capture.camlinkGetRawReader();
        if (reader == null) {
            // Não deveria acontecer — dispatch já barra via UNSUPPORTED — mas nunca travar o job.
            afterAttempt(myGeneration);
            return;
        }
        Attempt attempt = new Attempt(myGeneration);
        currentAttempt = attempt;
        capture.camlinkCaptureToSurface(reader.getSurface(), new CameraCaptureSession.CaptureCallback() {
            @Override
            public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
                attempt.setResult(result);
            }

            @Override
            public void onCaptureFailed(CameraCaptureSession session, CaptureRequest request, CaptureFailure failure) {
                Ln.w("CamLink: captura RAW falhou (reason=" + failure.getReason() + ")");
                attempt.fail();
            }
        });
    }

    /** Tentativa em voo — no máximo 1 por vez (busy). Correlaciona Image + TotalCaptureResult da
     *  MESMA captura, que chegam por callbacks independentes em qualquer ordem. */
    private volatile Attempt currentAttempt;

    private void onImageAvailable(ImageReader reader) {
        Image image = reader.acquireLatestImage();
        if (image == null) {
            return;
        }
        Attempt attempt = currentAttempt;
        if (attempt == null || !attempt.setImage(image)) {
            image.close(); // tentativa já resolvida (falhou/trocou) — não vazar o Image
        }
    }

    private final class Attempt {
        private final long generationAtStart;
        private Image image;
        private TotalCaptureResult result;
        private boolean done;

        Attempt(long generationAtStart) {
            this.generationAtStart = generationAtStart;
        }

        synchronized boolean setImage(Image img) {
            if (done) {
                return false;
            }
            this.image = img;
            maybeFinish();
            return true;
        }

        synchronized void setResult(TotalCaptureResult r) {
            if (done) {
                return;
            }
            this.result = r;
            maybeFinish();
        }

        synchronized void fail() {
            if (done) {
                return;
            }
            done = true;
            if (image != null) {
                image.close();
            }
            currentAttempt = null;
            if (eventSink != null) {
                eventSink.sendEvent(eventRawFrameDropped("captura falhou"));
            }
            afterAttempt(generationAtStart);
        }

        private void maybeFinish() {
            if (done || image == null || result == null) {
                return;
            }
            done = true;
            currentAttempt = null;
            writeAndSend(generationAtStart, image, result);
        }
    }

    private void writeAndSend(long myGeneration, Image image, TotalCaptureResult result) {
        try {
            long seqNum = seq.incrementAndGet();
            long timestampMs = System.currentTimeMillis();
            int width = image.getWidth();
            int height = image.getHeight();

            ByteArrayOutputStream dngStream = new ByteArrayOutputStream();
            new DngCreator(characteristics, result).writeImage(dngStream, image);
            byte[] dng = dngStream.toByteArray();
            byte[] framed = frame(seqNum, timestampMs, width, height, dng);

            long writeStartNanos = System.nanoTime();
            sink.sendRawFrame(framed);
            long writeElapsedNanos = System.nanoTime() - writeStartNanos;
            if (writeElapsedNanos > 0) {
                lastThroughputBytesPerSec = framed.length / (writeElapsedNanos / 1_000_000_000.0);
            }
        } catch (IOException | JSONException e) {
            Ln.w("CamLink: falha ao gerar/enviar frame RAW: " + e.getMessage());
            if (eventSink != null) {
                eventSink.sendEvent(eventRawFrameDropped(e.getMessage()));
            }
        } finally {
            image.close();
        }
        afterAttempt(myGeneration);
    }

    private void afterAttempt(long myGeneration) {
        if (myGeneration != generation.get()) {
            return; // job mudou (stop, ou outro job) enquanto este frame processava
        }
        if (sequenceRunning) {
            scheduleNext(myGeneration);
        } else {
            busy.set(false);
        }
    }

    private void scheduleNext(long myGeneration) {
        double granted = grantedFpsFromMeasuredThroughput();
        long delayMs = Math.round(1000.0 / granted);
        capture.camlinkGetCameraHandler().postDelayed(() -> captureOne(myGeneration), delayMs);
    }

    /** Espelha {@code raw_manager::effective_raw_fps} (Rust) — ver comentário de classe. */
    private double grantedFpsFromMeasuredThroughput() {
        if (lastThroughputBytesPerSec <= 0 || frameBytes <= 0) {
            return MAX_FPS; // sem medição ainda; o próprio próximo frame recalibra
        }
        double sustainable = lastThroughputBytesPerSec / (double) frameBytes;
        return clamp(sustainable);
    }

    private static double clamp(double fps) {
        return Math.max(MIN_FPS, Math.min(MAX_FPS, fps));
    }

    private static String eventRawFrameDropped(String reason) {
        try {
            JSONObject event = new JSONObject();
            event.put("event", "raw_frame_dropped");
            event.put("reason", reason != null ? reason : "erro desconhecido");
            return event.toString();
        } catch (JSONException e) {
            return "{\"event\":\"raw_frame_dropped\",\"reason\":\"erro desconhecido\"}";
        }
    }

    /** Framing binário do contrato §4: {@code [u8 tag][u32be metadata_len][json][u64be dng_len][dng]}. */
    private static byte[] frame(long seqNum, long timestampMs, int width, int height, byte[] dng)
            throws JSONException, IOException {
        JSONObject metadata = new JSONObject();
        metadata.put("seq", seqNum);
        metadata.put("timestamp_ms", timestampMs);
        metadata.put("width", width);
        metadata.put("height", height);
        byte[] metadataJson = metadata.toString().getBytes(StandardCharsets.UTF_8);

        ByteArrayOutputStream out = new ByteArrayOutputStream(1 + 4 + metadataJson.length + 8 + dng.length);
        out.write(FRAME_TAG);
        writeU32BE(out, metadataJson.length);
        out.write(metadataJson);
        writeU64BE(out, dng.length);
        out.write(dng);
        return out.toByteArray();
    }

    private static void writeU32BE(ByteArrayOutputStream out, int value) {
        out.write((value >>> 24) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static void writeU64BE(ByteArrayOutputStream out, long value) {
        for (int shift = 56; shift >= 0; shift -= 8) {
            out.write((int) ((value >>> shift) & 0xFF));
        }
    }
}
