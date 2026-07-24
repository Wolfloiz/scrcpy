package com.genymobile.scrcpy.camlink;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/**
 * T031 — Valida o processador de comandos CamLink contra os MESMOS golden files usados pelo teste
 * de contrato Rust ({@code src-tauri/tests/protocol_contract_test.rs} no repositório CamLink):
 * parsing NDJSON, validação contra capabilities e envelope ok/error, byte-a-byte com o contrato
 * ({@code specs/001-phone-webcam-bridge/contracts/control-protocol.md}).
 *
 * <p>Localização dos golden files: variável de ambiente {@code CAMLINK_GOLDEN_DIR}, com fallback
 * para o caminho relativo do submodule dentro do repositório CamLink. Se o diretório não existir
 * (ex.: build standalone do fork), o teste é ignorado via {@link Assume} — nunca falha o build
 * upstream.
 */
public final class ProtocolTest {

    private static final String SERVER_NAME = "camlink-v4.0";

    private File goldenDir;

    @Before
    public void locateGoldenDir() {
        String env = System.getenv("CAMLINK_GOLDEN_DIR");
        if (env != null && !env.isEmpty()) {
            goldenDir = new File(env);
        } else {
            // Working dir dos testes gradle: scrcpy/server → raiz do CamLink dois níveis acima
            goldenDir = new File("../../specs/001-phone-webcam-bridge/contracts/golden");
        }
        Assume.assumeTrue(
                "golden files não encontrados em " + goldenDir.getAbsolutePath(), goldenDir.isDirectory());
    }

    private static JSONObject readJson(File file) throws IOException, JSONException {
        String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        return new JSONObject(text);
    }

    private JSONObject fixture(String name) throws IOException, JSONException {
        return readJson(new File(goldenDir, "fixtures/capabilities_" + name + ".json"));
    }

    /** Controller fake: grava as aplicações; capabilities vêm da fixture do golden file. */
    private static final class FakeController implements CamLinkCommandProcessor.Controller {
        private final JSONObject capabilities;
        final List<String> applied = new ArrayList<>();

        FakeController(JSONObject capabilities) {
            this.capabilities = capabilities;
        }

        @Override
        public JSONObject getCapabilities() {
            return capabilities;
        }

        @Override
        public void applyZoom(double ratio) {
            applied.add("zoom=" + ratio);
        }

        @Override
        public void applyFocusContinuous() {
            applied.add("focus=continuous");
        }

        @Override
        public void applyFocusTap(double x, double y) {
            applied.add("focus=tap:" + x + "," + y);
        }

        @Override
        public void applyFocusManual(double distance) {
            applied.add("focus=manual:" + distance);
        }

        @Override
        public void applyExposureCompensation(int steps) {
            applied.add("exposure=" + steps);
        }

        @Override
        public void applyIso(int value) {
            applied.add("iso=" + value);
        }

        @Override
        public void applyWb(String mode) {
            applied.add("wb=" + mode);
        }

        @Override
        public void applyEis(boolean enabled) {
            applied.add("eis=" + enabled);
        }

        @Override
        public void applyTorch(boolean enabled) {
            applied.add("torch=" + enabled);
        }
    }

    /**
     * Comparação estrutural recursiva entre dois valores JSON. Evita {@code JSONObject#similar},
     * ausente na implementação de {@code org.json} empacotada no {@code android.jar} usado para
     * compilar os testes unitários do módulo (mais antiga que a versão real trazida via
     * {@code testImplementation} — ver comentário em {@code build.gradle}); usa só a API comum às
     * duas (get/has/length/keys/getJSONArray).
     */
    private static boolean jsonEquals(Object expected, Object actual) {
        if (expected instanceof JSONObject && actual instanceof JSONObject) {
            JSONObject e = (JSONObject) expected;
            JSONObject a = (JSONObject) actual;
            if (e.length() != a.length()) {
                return false;
            }
            Iterator<String> keys = e.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (!a.has(key)) {
                    return false;
                }
                try {
                    if (!jsonEquals(e.get(key), a.get(key))) {
                        return false;
                    }
                } catch (JSONException ex) {
                    return false;
                }
            }
            return true;
        }
        if (expected instanceof JSONArray && actual instanceof JSONArray) {
            JSONArray e = (JSONArray) expected;
            JSONArray a = (JSONArray) actual;
            if (e.length() != a.length()) {
                return false;
            }
            for (int i = 0; i < e.length(); i++) {
                try {
                    if (!jsonEquals(e.get(i), a.get(i))) {
                        return false;
                    }
                } catch (JSONException ex) {
                    return false;
                }
            }
            return true;
        }
        if (expected instanceof Number && actual instanceof Number) {
            return ((Number) expected).doubleValue() == ((Number) actual).doubleValue();
        }
        return Objects.equals(expected, actual);
    }

    private static File[] goldenCases(File dir) {
        File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
        Assert.assertNotNull(files);
        Arrays.sort(files);
        return files;
    }

    @Test
    public void allGoldenCasesProduceTheCanonicalResponse() throws IOException, JSONException {
        int checked = 0;
        for (File file : goldenCases(goldenDir)) {
            JSONObject goldenCase = readJson(file);
            JSONObject caps = fixture(goldenCase.optString("capabilities", "full"));
            FakeController controller = new FakeController(caps);
            CamLinkCommandProcessor processor = new CamLinkCommandProcessor(controller, SERVER_NAME);
            processor.setMode(goldenCase.optString("mode", "auto"));

            String requestLine;
            if (goldenCase.has("request_raw")) {
                requestLine = goldenCase.getString("request_raw");
            } else {
                requestLine = goldenCase.getJSONObject("request").toString();
            }

            String responseLine = processor.process(requestLine);
            JSONObject actual = new JSONObject(responseLine);
            JSONObject expected = goldenCase.getJSONObject("response");
            Assert.assertTrue(
                    file.getName() + ": resposta difere do golden\nesperado: " + expected + "\nveio:     " + actual,
                    jsonEquals(expected, actual));
            checked++;
        }
        Assert.assertTrue("esperava ≥ 20 casos golden, veio " + checked, checked >= 20);
    }

    @Test
    public void successfulCommandsReachTheController() throws IOException, JSONException {
        JSONObject caps = fixture("full");
        FakeController controller = new FakeController(caps);
        CamLinkCommandProcessor processor = new CamLinkCommandProcessor(controller, SERVER_NAME);
        processor.setMode("pro");

        processor.process("{\"cmd\":\"set_zoom\",\"ratio\":2.5}");
        processor.process("{\"cmd\":\"set_torch\",\"enabled\":true}");
        processor.process("{\"cmd\":\"set_iso\",\"value\":400}");

        Assert.assertEquals(
                Arrays.asList("zoom=2.5", "torch=true", "iso=400"), controller.applied);
    }

    @Test
    public void rejectedCommandsNeverTouchTheCamera() throws IOException, JSONException {
        JSONObject caps = fixture("minimal");
        FakeController controller = new FakeController(caps);
        CamLinkCommandProcessor processor = new CamLinkCommandProcessor(controller, SERVER_NAME);

        // Validação server-side contra capabilities ANTES de aplicar (contrato §3)
        processor.process("{\"cmd\":\"set_torch\",\"enabled\":true}"); // UNSUPPORTED
        processor.process("{\"cmd\":\"set_zoom\",\"ratio\":5.0}"); // OUT_OF_RANGE
        processor.process("{\"cmd\":\"set_zoom\"}"); // BAD_REQUEST

        Assert.assertTrue(
                "comandos rejeitados não podem chegar à câmera: " + controller.applied,
                controller.applied.isEmpty());
    }

    @Test
    public void eventLinesUseTheEventKeyNeverOk() throws JSONException {
        // Contrato §5: eventos são JSON com chave "event" (nunca "ok") — o
        // cliente demultiplexa por chave.
        String af = CamLinkCommandProcessor.eventAfState("focused");
        JSONObject parsed = new JSONObject(af);
        Assert.assertEquals("af_state", parsed.getString("event"));
        Assert.assertFalse(parsed.has("ok"));

        JSONArray rects = new JSONArray();
        JSONObject rect = new JSONObject();
        rect.put("x", 0.4);
        rect.put("y", 0.2);
        rect.put("w", 0.1);
        rect.put("h", 0.15);
        rects.put(rect);
        String faces = CamLinkCommandProcessor.eventFaces(rects);
        JSONObject parsedFaces = new JSONObject(faces);
        Assert.assertEquals("faces", parsedFaces.getString("event"));
        Assert.assertEquals(1, parsedFaces.getJSONArray("rects").length());
    }
}
