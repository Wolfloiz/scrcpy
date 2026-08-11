package com.genymobile.scrcpy.video;

import com.genymobile.scrcpy.AndroidVersions;
import com.genymobile.scrcpy.Options;
import com.genymobile.scrcpy.model.ConfigurationException;
import com.genymobile.scrcpy.model.Orientation;
import com.genymobile.scrcpy.model.Size;
import com.genymobile.scrcpy.opengl.AffineOpenGLFilter;
import com.genymobile.scrcpy.opengl.OpenGLFilter;
import com.genymobile.scrcpy.opengl.OpenGLRunner;
import com.genymobile.scrcpy.util.AffineMatrix;
import com.genymobile.scrcpy.util.HandlerExecutor;
import com.genymobile.scrcpy.util.Ln;
import com.genymobile.scrcpy.util.LogUtils;
import com.genymobile.scrcpy.wrappers.ServiceManager;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.graphics.Rect;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.graphics.ImageFormat;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Range;
import android.view.Surface;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

public class CameraCapture extends SurfaceCapture {

    public static final float[] VFLIP_MATRIX = {
            1, 0, 0, 0, // column 1
            0, -1, 0, 0, // column 2
            0, 0, 1, 0, // column 3
            0, 1, 0, 1, // column 4
    };

    private static final float ZOOM_FACTOR = 1 + 1 / 16f;

    private final String explicitCameraId;
    private final CameraFacing cameraFacing;
    private final Size explicitSize;
    private final CameraAspectRatio aspectRatio;
    private final int fps;
    private final boolean highSpeed;
    private final Rect crop;
    private final Orientation captureOrientation;
    private final float angle;
    private final boolean initialTorch;
    private float zoom;

    private VideoConstraints videoConstraints;

    private String cameraId;
    private Size captureSize;
    private Size videoSize; // after OpenGL transforms
    // volatile: written on the camera thread, read by the CamLink control thread
    private volatile Range<Float> zoomRange;

    private AffineMatrix transform;
    private OpenGLRunner glRunner;

    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraDevice cameraDevice;
    private Executor cameraExecutor;

    private final AtomicBoolean disconnected = new AtomicBoolean();

    // The following fields must be accessed only from the camera thread
    private boolean started;
    private CaptureRequest.Builder requestBuilder;
    private CameraCaptureSession currentSession;

    /**
     * CamLink (US5): {@code ImageReader} extra da sessão pra Captura RAW, criado em {@link
     * #start} SOMENTE quando o aparelho declara {@code REQUEST_AVAILABLE_CAPABILITIES_RAW} (ver
     * {@link #camlinkBestRawSize}) — {@code null} em qualquer aparelho sem RAW, sem nenhuma
     * mudança de comportamento pra eles. Volatile: escrito por {@code start()} (thread do
     * encoder), lido pela thread de controle via {@link #camlinkGetRawReader}.
     */
    private volatile ImageReader camlinkRawReader;

    /**
     * CamLink (US3): listener opcional de {@code onCaptureCompleted} da repeating request
     * corrente (face-AF). {@code volatile} porque é setado por
     * {@link #camlinkSetCaptureListener} (thread de controle) e lido no callback interno de
     * {@link #setRepeatingRequest} (thread da câmera) — sem precisar reaplicar a request: o
     * callback interno é sempre o mesmo objeto e sempre delega, então trocar o listener pega
     * efeito no próximo frame, mesmo que outro comando (zoom, foco) reaplique a request no meio.
     */
    private volatile CameraCaptureSession.CaptureCallback camlinkCompletedListener;

    public CameraCapture(Options options) {
        this.explicitCameraId = options.getCameraId();
        this.cameraFacing = options.getCameraFacing();
        this.explicitSize = options.getCameraSize();
        this.aspectRatio = options.getCameraAspectRatio();
        this.fps = options.getCameraFps();
        this.highSpeed = options.getCameraHighSpeed();
        this.crop = options.getCrop();
        this.captureOrientation = options.getCaptureOrientation();
        assert captureOrientation != null;
        this.angle = options.getAngle();
        this.initialTorch = options.getCameraTorch();
        this.zoom = options.getCameraZoom();
    }

    @Override
    protected void init(VideoConstraints videoConstraints) throws ConfigurationException, IOException {
        this.videoConstraints = videoConstraints;

        cameraThread = new HandlerThread("camera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        cameraExecutor = new HandlerExecutor(cameraHandler);

        try {
            cameraId = selectCamera(explicitCameraId, cameraFacing);
            if (cameraId == null) {
                throw new ConfigurationException("No matching camera found");
            }

            Ln.i("Using camera '" + cameraId + "'");
            cameraDevice = openCamera(cameraId);
        } catch (CameraAccessException | InterruptedException e) {
            throw new IOException(e);
        }
    }

    @Override
    public void prepare() throws IOException {
        try {
            int maxSize = videoConstraints.getMaxSize();
            captureSize = selectSize(cameraId, explicitSize, maxSize, aspectRatio, highSpeed);
            if (captureSize == null) {
                throw new IOException("Could not select camera size");
            }
        } catch (CameraAccessException e) {
            throw new IOException(e);
        }

        VideoFilter filter = new VideoFilter(captureSize);

        if (crop != null) {
            filter.addCrop(crop, false);
        }

        if (captureOrientation != Orientation.Orient0) {
            filter.addOrientation(captureOrientation);
        }

        filter.addAngle(angle);

        transform = filter.getInverseTransform();
        videoSize = filter.getOutputSize().constrain(videoConstraints);
    }

    private static String selectCamera(String explicitCameraId, CameraFacing cameraFacing) throws CameraAccessException, ConfigurationException {
        CameraManager cameraManager = ServiceManager.getCameraManager();

        String[] cameraIds = cameraManager.getCameraIdList();
        if (explicitCameraId != null) {
            if (!Arrays.asList(cameraIds).contains(explicitCameraId)) {
                Ln.e("Camera with id " + explicitCameraId + " not found\n" + LogUtils.buildCameraListMessage(false));
                throw new ConfigurationException("Camera id not found");
            }
            return explicitCameraId;
        }

        if (cameraFacing == null) {
            // Use the first one
            return cameraIds.length > 0 ? cameraIds[0] : null;
        }

        for (String cameraId : cameraIds) {
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);

            int facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            if (cameraFacing.value() == facing) {
                return cameraId;
            }
        }

        // Not found
        return null;
    }

    @TargetApi(AndroidVersions.API_24_ANDROID_7_0)
    private static Size selectSize(String cameraId, Size explicitSize, int maxSize, CameraAspectRatio aspectRatio, boolean highSpeed)
            throws CameraAccessException {
        if (explicitSize != null) {
            return explicitSize;
        }

        CameraManager cameraManager = ServiceManager.getCameraManager();
        CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);

        StreamConfigurationMap configs = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        android.util.Size[] sizes = highSpeed ? configs.getHighSpeedVideoSizes() : configs.getOutputSizes(MediaCodec.class);
        if (sizes == null) {
            return null;
        }

        Stream<android.util.Size> stream = Arrays.stream(sizes);
        if (maxSize > 0) {
            stream = stream.filter(it -> it.getWidth() <= maxSize && it.getHeight() <= maxSize);
        }

        Float targetAspectRatio = resolveAspectRatio(aspectRatio, characteristics);
        if (targetAspectRatio != null) {
            stream = stream.filter(it -> {
                float ar = ((float) it.getWidth() / it.getHeight());
                float arRatio = ar / targetAspectRatio;
                // Accept if the aspect ratio is the target aspect ratio + or - 10%
                return arRatio >= 0.9f && arRatio <= 1.1f;
            });
        }

        Optional<android.util.Size> selected = stream.max((s1, s2) -> {
            // Greater width is better
            int cmp = Integer.compare(s1.getWidth(), s2.getWidth());
            if (cmp != 0) {
                return cmp;
            }

            if (targetAspectRatio != null) {
                // Closer to the target aspect ratio is better
                float ar1 = ((float) s1.getWidth() / s1.getHeight());
                float arRatio1 = ar1 / targetAspectRatio;
                float distance1 = Math.abs(1 - arRatio1);

                float ar2 = ((float) s2.getWidth() / s2.getHeight());
                float arRatio2 = ar2 / targetAspectRatio;
                float distance2 = Math.abs(1 - arRatio2);

                // Reverse the order because lower distance is better
                cmp = Float.compare(distance2, distance1);
                if (cmp != 0) {
                    return cmp;
                }
            }

            // Greater height is better
            return Integer.compare(s1.getHeight(), s2.getHeight());
        });

        if (selected.isPresent()) {
            android.util.Size size = selected.get();
            return new Size(size.getWidth(), size.getHeight());
        }

        // Not found
        return null;
    }

    private static Float resolveAspectRatio(CameraAspectRatio ratio, CameraCharacteristics characteristics) {
        if (ratio == null) {
            return null;
        }

        if (ratio.isSensor()) {
            Rect activeSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            return (float) activeSize.width() / activeSize.height();
        }

        return ratio.getAspectRatio();
    }

    @TargetApi(AndroidVersions.API_30_ANDROID_11)
    @Override
    public void start(Surface surface) throws IOException {
        if (transform != null) {
            assert glRunner == null;
            OpenGLFilter glFilter = new AffineOpenGLFilter(transform);
            // The transform matrix returned by SurfaceTexture is incorrect for camera capture (it often contains an additional unexpected 90°
            // rotation). Use a vertical flip transform matrix instead.
            glRunner = new OpenGLRunner(glFilter, VFLIP_MATRIX);
            surface = glRunner.start(captureSize, videoSize, surface);
        }

        cameraHandler.post(() -> {
            assertCameraThread();
            started = true;
        });

        Surface captureSurface = surface;
        OutputConfiguration outputConfig = new OutputConfiguration(captureSurface);
        List<OutputConfiguration> outputs = new ArrayList<>();
        outputs.add(outputConfig);

        // CamLink (US5): superfície extra pra Captura RAW (contracts/control-protocol.md §4) —
        // aditivo e condicional: só existe quando o aparelho declara RAW_SENSOR e a sessão não é
        // high-speed (sessões high-speed do Camera2 só aceitam as superfícies de vídeo, não uma
        // superfície RAW arbitrária). Sem RAW, `outputs` fica idêntico ao scrcpy puro.
        if (!highSpeed) {
            try {
                CameraCharacteristics ch = ServiceManager.getCameraManager().getCameraCharacteristics(cameraId);
                android.util.Size rawSize = camlinkBestRawSize(ch);
                if (rawSize != null) {
                    ImageReader reader = ImageReader.newInstance(rawSize.getWidth(), rawSize.getHeight(), ImageFormat.RAW_SENSOR, 2);
                    camlinkRawReader = reader;
                    outputs.add(new OutputConfiguration(reader.getSurface()));
                }
            } catch (CameraAccessException e) {
                Ln.w("CamLink: não consegui checar suporte a RAW: " + e.getMessage());
            }
        }

        int sessionType = highSpeed ? SessionConfiguration.SESSION_HIGH_SPEED : SessionConfiguration.SESSION_REGULAR;
        SessionConfiguration sessionConfig = new SessionConfiguration(sessionType, outputs, cameraExecutor, new CameraCaptureSession.StateCallback() {
            @Override
            public void onConfigured(CameraCaptureSession session) {
                assertCameraThread();
                if (!started) {
                    // Stopped on the encoder thread between the call to start() and this callback
                    return;
                }

                CameraManager cameraManager = ServiceManager.getCameraManager();
                try {
                    CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
                    zoomRange = characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
                } catch (CameraAccessException e) {
                    Ln.w("Could not get camera characteristics");
                }

                try {
                    requestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                    requestBuilder.addTarget(captureSurface);

                    if (fps > 0) {
                        requestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(fps, fps));
                    }
                    if (initialTorch) {
                        Ln.i("Turn camera torch on");
                        requestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH);
                    }
                    if (zoom != 1) {
                        zoom = clampZoom(zoom);
                        Ln.i("Set camera zoom: " + zoom);
                        requestBuilder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom);
                    }

                    CaptureRequest request = requestBuilder.build();
                    setRepeatingRequest(session, request);
                    currentSession = session;
                } catch (CameraAccessException e) {
                    Ln.e("Camera error", e);
                    disconnected.set(true);
                    getCaptureControl().reset(CaptureControl.RESET_REASON_TERMINATED);
                }
            }

            @Override
            public void onConfigureFailed(CameraCaptureSession session) {
                Ln.e("Camera configuration error");
                disconnected.set(true);
                getCaptureControl().reset(CaptureControl.RESET_REASON_TERMINATED);
            }
        });

        try {
            cameraDevice.createCaptureSession(sessionConfig);
        } catch (CameraAccessException e) {
            stop();
            throw new IOException(e);
        }
    }

    @Override
    public void stop() {
        cameraHandler.post(() -> {
            assertCameraThread();
            currentSession = null;
            requestBuilder = null;
            started = false;
        });

        if (glRunner != null) {
            glRunner.stopAndRelease();
            glRunner = null;
        }
    }

    @Override
    public void release() {
        if (cameraDevice != null) {
            cameraDevice.close();
        }
        if (cameraThread != null) {
            cameraThread.quitSafely();
        }
    }

    @Override
    public Size getSize() {
        return videoSize;
    }

    @Override
    protected boolean applyNewVideoConstraints(VideoConstraints videoConstraints) {
        if (explicitSize != null) {
            return false;
        }

        this.videoConstraints = videoConstraints;
        return true;
    }

    @SuppressLint("MissingPermission")
    @TargetApi(AndroidVersions.API_31_ANDROID_12)
    private CameraDevice openCamera(String id) throws CameraAccessException, InterruptedException {
        CompletableFuture<CameraDevice> future = new CompletableFuture<>();
        ServiceManager.getCameraManager().openCamera(id, new CameraDevice.StateCallback() {
            @Override
            public void onOpened(CameraDevice camera) {
                Ln.d("Camera opened successfully");
                future.complete(camera);
            }

            @Override
            public void onDisconnected(CameraDevice camera) {
                Ln.w("Camera disconnected");
                disconnected.set(true);
                getCaptureControl().reset(CaptureControl.RESET_REASON_TERMINATED);
            }

            @Override
            public void onError(CameraDevice camera, int error) {
                int cameraAccessExceptionErrorCode;
                switch (error) {
                    case CameraDevice.StateCallback.ERROR_CAMERA_IN_USE:
                        cameraAccessExceptionErrorCode = CameraAccessException.CAMERA_IN_USE;
                        break;
                    case CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE:
                        cameraAccessExceptionErrorCode = CameraAccessException.MAX_CAMERAS_IN_USE;
                        break;
                    case CameraDevice.StateCallback.ERROR_CAMERA_DISABLED:
                        cameraAccessExceptionErrorCode = CameraAccessException.CAMERA_DISABLED;
                        break;
                    case CameraDevice.StateCallback.ERROR_CAMERA_DEVICE:
                    case CameraDevice.StateCallback.ERROR_CAMERA_SERVICE:
                    default:
                        cameraAccessExceptionErrorCode = CameraAccessException.CAMERA_ERROR;
                        break;
                }
                future.completeExceptionally(new CameraAccessException(cameraAccessExceptionErrorCode));
            }
        }, cameraHandler);

        try {
            return future.get();
        } catch (ExecutionException e) {
            throw (CameraAccessException) e.getCause();
        }
    }

    @TargetApi(AndroidVersions.API_31_ANDROID_12)
    private void setRepeatingRequest(CameraCaptureSession session, CaptureRequest request) throws CameraAccessException {
        CameraCaptureSession.CaptureCallback callback = new CameraCaptureSession.CaptureCallback() {
            @Override
            public void onCaptureStarted(CameraCaptureSession session, CaptureRequest request, long timestamp, long frameNumber) {
                // Called for each frame captured, do nothing
            }

            @Override
            public void onCaptureFailed(CameraCaptureSession session, CaptureRequest request, CaptureFailure failure) {
                Ln.w("Camera capture failed: frame " + failure.getFrameNumber());
            }

            @Override
            public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
                // CamLink (US3): delega pro listener corrente (face-AF), se algum estiver
                // registrado — ver camlinkSetCaptureListener.
                CameraCaptureSession.CaptureCallback listener = camlinkCompletedListener;
                if (listener != null) {
                    listener.onCaptureCompleted(session, request, result);
                }
            }
        };

        if (highSpeed) {
            CameraConstrainedHighSpeedCaptureSession highSpeedSession = (CameraConstrainedHighSpeedCaptureSession) session;
            List<CaptureRequest> requests = highSpeedSession.createHighSpeedRequestList(request);
            highSpeedSession.setRepeatingBurst(requests, callback, cameraHandler);
        } else {
            session.setRepeatingRequest(request, callback, cameraHandler);
        }
    }

    @Override
    public boolean isClosed() {
        return disconnected.get();
    }

    public void setTorchEnabled(boolean enabled) {
        cameraHandler.post(() -> {
            assertCameraThread();
            if (currentSession != null && requestBuilder != null) {
                try {
                    Ln.i("Turn camera torch " + (enabled ? "on" : "off"));
                    requestBuilder.set(CaptureRequest.FLASH_MODE, enabled ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
                    CaptureRequest request = requestBuilder.build();
                    setRepeatingRequest(currentSession, request);
                } catch (CameraAccessException e) {
                    Ln.e("Camera error", e);
                }
            }
        });
    }

    @TargetApi(AndroidVersions.API_30_ANDROID_11)
    private void zoom(boolean in) {
        cameraHandler.post(() -> {
            assertCameraThread();
            if (currentSession != null && requestBuilder != null) {
                // Always align to log values
                double z = Math.round(Math.log(zoom) / Math.log(ZOOM_FACTOR));
                double dir = in ? 1 : -1;
                zoom = (float) Math.pow(ZOOM_FACTOR, z + dir);

                try {
                    zoom = clampZoom(zoom);
                    Ln.i("Set camera zoom: " + zoom);
                    requestBuilder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom);
                    CaptureRequest request = requestBuilder.build();
                    setRepeatingRequest(currentSession, request);
                } catch (CameraAccessException e) {
                    Ln.e("Camera error", e);
                }
            }
        });
    }

    public void zoomIn() {
        zoom(true);
    }

    public void zoomOut() {
        zoom(false);
    }

    // --- CamLink (fork) hooks ---

    /**
     * CamLink: zoom ratio range reported by the camera, for validating set_zoom against
     * capabilities. Null until the capture session is configured.
     */
    public Range<Float> getZoomRatioRange() {
        return zoomRange;
    }

    /**
     * CamLink: apply an absolute zoom ratio at runtime by rebuilding the repeating request,
     * without reopening the camera. Safe to call from any thread.
     */
    @TargetApi(AndroidVersions.API_30_ANDROID_11)
    public void setZoomRatio(float ratio) {
        cameraHandler.post(() -> {
            assertCameraThread();
            if (currentSession != null && requestBuilder != null) {
                try {
                    zoom = clampZoom(ratio);
                    Ln.i("CamLink: set camera zoom: " + zoom);
                    requestBuilder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom);
                    CaptureRequest request = requestBuilder.build();
                    setRepeatingRequest(currentSession, request);
                } catch (CameraAccessException e) {
                    Ln.e("Camera error", e);
                }
            }
        });
    }

    private float clampZoom(float value) {
        assertCameraThread();
        if (zoomRange == null) {
            return value;
        }

        return zoomRange.clamp(value);
    }

    /**
     * CamLink: id da câmera efetivamente aberta (para montar capabilities a partir das
     * CameraCharacteristics corretas). Null antes de init().
     */
    public String camlinkGetCameraId() {
        return cameraId;
    }

    /**
     * CamLink (US3): registra (ou remove, com {@code null}) um listener de
     * {@code onCaptureCompleted} da repeating request corrente — usado pra face-AF
     * (STATISTICS_FACES a cada frame). Não precisa reaplicar a request nem tocar a thread da
     * câmera: o callback interno de {@link #setRepeatingRequest} já é sempre instalado e sempre
     * delega pro listener corrente (campo volatile), então trocar aqui pega efeito no próximo
     * frame, mesmo com outro comando (zoom, foco) reaplicando a request no meio.
     */
    public void camlinkSetCaptureListener(CameraCaptureSession.CaptureCallback listener) {
        camlinkCompletedListener = listener;
    }

    /**
     * CamLink (T035): hook genérico de controle em runtime — muta a repeating request corrente e a
     * reaplica via setRepeatingRequest, sem reabrir a câmera (contrato §3, efeito < 1 s). Safe de
     * qualquer thread; no-op silencioso se a sessão ainda não subiu.
     */
    @TargetApi(AndroidVersions.API_31_ANDROID_12)
    public void camlinkUpdateRequest(java.util.function.Consumer<CaptureRequest.Builder> mutator) {
        cameraHandler.post(() -> {
            assertCameraThread();
            if (currentSession != null && requestBuilder != null) {
                try {
                    mutator.accept(requestBuilder);
                    setRepeatingRequest(currentSession, requestBuilder.build());
                } catch (CameraAccessException e) {
                    Ln.e("CamLink: camera error", e);
                }
            }
        });
    }

    /**
     * CamLink (T036): dispara UMA captura com a request corrente mutada (ex.: AF_TRIGGER_START do
     * tap-to-focus) e em seguida devolve o trigger a IDLE na repeating request para não re-disparar
     * a cada frame. O callback observa o resultado (CONTROL_AF_STATE → evento af_state). Sessões
     * high-speed não suportam capture única — o comando vira no-op logado.
     */
    @TargetApi(AndroidVersions.API_31_ANDROID_12)
    public void camlinkCaptureOnce(
            java.util.function.Consumer<CaptureRequest.Builder> mutator, CameraCaptureSession.CaptureCallback callback) {
        cameraHandler.post(() -> {
            assertCameraThread();
            if (currentSession == null || requestBuilder == null) {
                return;
            }
            if (highSpeed) {
                Ln.w("CamLink: capture única indisponível em sessão high-speed");
                return;
            }
            try {
                mutator.accept(requestBuilder);
                currentSession.capture(requestBuilder.build(), callback, cameraHandler);
                requestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
                setRepeatingRequest(currentSession, requestBuilder.build());
            } catch (CameraAccessException e) {
                Ln.e("CamLink: camera error", e);
            }
        });
    }

    /**
     * CamLink (US5): maior tamanho RAW_SENSOR suportado pela câmera, ou {@code null} se o
     * aparelho não declarar {@code REQUEST_AVAILABLE_CAPABILITIES_RAW} nem expuser nenhum
     * tamanho RAW_SENSOR. Única fonte de verdade dessa checagem — usada tanto aqui (criação da
     * superfície) quanto em {@code CamLinkCameraController#buildCapabilities} (o que a UI vê),
     * pra elas nunca poderem divergir.
     */
    public static android.util.Size camlinkBestRawSize(CameraCharacteristics ch) {
        int[] capabilities = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        boolean hasRaw = false;
        if (capabilities != null) {
            for (int c : capabilities) {
                if (c == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) {
                    hasRaw = true;
                    break;
                }
            }
        }
        if (!hasRaw) {
            return null;
        }
        StreamConfigurationMap configs = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        android.util.Size[] sizes = configs != null ? configs.getOutputSizes(ImageFormat.RAW_SENSOR) : null;
        if (sizes == null || sizes.length == 0) {
            return null;
        }
        android.util.Size best = sizes[0];
        for (android.util.Size s : sizes) {
            if ((long) s.getWidth() * s.getHeight() > (long) best.getWidth() * best.getHeight()) {
                best = s;
            }
        }
        return best;
    }

    /**
     * CamLink (US5): {@code ImageReader} da superfície RAW extra desta sessão, ou {@code null}
     * se o aparelho não suporta RAW (ver {@link #camlinkBestRawSize}) ou a sessão ainda não
     * subiu. {@link RawCapture} usa isso pra registrar o listener de imagem disponível e ler os
     * frames capturados.
     */
    public ImageReader camlinkGetRawReader() {
        return camlinkRawReader;
    }

    /**
     * CamLink (US5): dispara UMA captura independente da repeating request de vídeo, mirando
     * SÓ a superfície informada (ex.: o {@link ImageReader} RAW) — usado pra Snapshot e cada
     * frame da Sequência RAW. Sessões high-speed não suportam capture única (mesma limitação de
     * {@link #camlinkCaptureOnce}).
     */
    @TargetApi(AndroidVersions.API_31_ANDROID_12)
    public void camlinkCaptureToSurface(Surface targetSurface, CameraCaptureSession.CaptureCallback callback) {
        cameraHandler.post(() -> {
            assertCameraThread();
            if (currentSession == null || cameraDevice == null) {
                return;
            }
            if (highSpeed) {
                Ln.w("CamLink: captura RAW indisponível em sessão high-speed");
                return;
            }
            try {
                CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                builder.addTarget(targetSurface);
                currentSession.capture(builder.build(), callback, cameraHandler);
            } catch (CameraAccessException e) {
                Ln.e("CamLink: camera error (raw capture)", e);
            }
        });
    }

    /**
     * CamLink (US5): handler da thread da câmera, pra {@link RawCapture} registrar o listener do
     * {@code ImageReader} no mesmo thread que já processa todo o resto (evita mais uma thread e
     * qualquer necessidade de sincronização extra entre elas).
     */
    public Handler camlinkGetCameraHandler() {
        return cameraHandler;
    }

    private void assertCameraThread() {
        assert Thread.currentThread() == cameraThread;
    }
}
