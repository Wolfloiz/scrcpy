# scrcpy-server — fork CamLink (branch `camlink`)

Fork do [scrcpy](https://github.com/Genymobile/scrcpy) usado pelo
[CamLink](https://github.com/Wolfloiz/CamLink) para controles de câmera em
runtime (zoom, foco, exposição, WB, torch, EIS) sem instalar nada no celular.

- **Base**: tag estável `v4.0` do upstream (`genymobile/scrcpy`)
- **Branch**: `camlink` — rebase controlado sobre cada release estável do upstream
- **Escopo do fork**: somente o componente `server/` (Java, roda no Android com
  UID shell). O cliente scrcpy permanece stock (dependência de runtime do CamLink).
- **Licença**: Apache-2.0 (herdada do upstream; o CamLink em si é GPL-3.0)

## Pré-requisitos de build

| Requisito | Versão |
|---|---|
| JDK | 17 |
| Android SDK platform | 36 (`ANDROID_PLATFORM`) |
| Android build-tools | 36.0.0 (`ANDROID_BUILD_TOOLS`) |

Defina `ANDROID_HOME` (ou `ANDROID_SDK_ROOT`) apontando para o SDK.

## Build do jar (scrcpy-server)

### Opção A — Gradle (recomendada)

```bash
./gradlew :server:assembleRelease
```

Saída: `server/build/outputs/apk/release/server-release-unsigned.apk`.
Esse artefato **é** o `scrcpy-server` (um jar/dex empacotado como APK não
assinado; o scrcpy o executa via `app_process`, não o instala).

### Opção B — sem Gradle

```bash
BUILD_DIR=build_manual ./server/build_without_gradle.sh
```

Saída: `build_manual/server/scrcpy-server`.

## Uso pelo CamLink

O CamLink aponta o cliente scrcpy (≥ 4.0, stock) para o jar deste fork via
variável de ambiente:

```bash
SCRCPY_SERVER_PATH=/caminho/para/scrcpy-server scrcpy --video-source=camera --no-playback ...
```

A versão do jar deve casar com a versão do cliente scrcpy instalado (4.0).

## Modificações CamLink

*(Spike A — T015 da feature 001-phone-webcam-bridge)*

Todas as mudanças ficam isoladas no pacote `camlink/` + dois hooks mínimos,
para manter o rebase contra o upstream barato:

| Arquivo | Mudança |
|---|---|
| `camlink/CamLinkControlServer.java` | **novo** — thread daemon com `LocalServerSocket("camlink")` (namespace localabstract), NDJSON UTF-8, envelope `ok/error` do contrato. Escopo do spike: `hello` (versionamento) e `set_zoom` (validado contra `CONTROL_ZOOM_RATIO_RANGE` → `OUT_OF_RANGE`; JSON/cmd inválido → `BAD_REQUEST`). Um cliente por vez; reaceita reconexões. |
| `video/CameraCapture.java` | hook — `setZoomRatio(float)` público: posta no camera thread, clampa e refaz `setRepeatingRequest` com `CONTROL_ZOOM_RATIO` (sem reabrir a câmera); `getZoomRatioRange()` para validação; campo `zoomRange` vira `volatile` (escrito no camera thread, lido pela thread de controle). |
| `Server.java` | hook — inicia `CamLinkControlServer` quando `--video-source=camera` (falha de bind é warning, não derruba o stream) e o encerra no `finally` do shutdown. |

O protocolo completo (foco, exposição, ISO, WB, EIS, torch, modos, RAW) entra
nas fases US2/US3/US5 do CamLink, sempre sobre esta mesma thread.

### Validação do Spike A (roteiro)

Requisitos: JDK 17 + Android SDK (build), celular Android 12+ com depuração
USB autorizada, scrcpy ≥ 4.0 e ffmpeg no PC.

```bash
# 1. Buildar o jar do fork (Opção A ou B acima)
./gradlew :server:assembleRelease

# 2. Stream headless da câmera com o jar do fork, gravando para inspeção
SCRCPY_SERVER_PATH=server/build/outputs/apk/release/server-release-unsigned.apk \
  scrcpy --video-source=camera --no-playback --record=spike-a.mkv &

# 3. Túnel do socket de controle (aguarde o log "CamLink control server listening")
adb forward tcp:27183 localabstract:camlink

# 4. Handshake + zoom em runtime (espere ~5 s entre os comandos p/ efeito visível)
printf '{"cmd":"hello"}\n' | nc -q1 127.0.0.1 27183
# → {"ok":true,"protocol":1,"server":"camlink-v4.0"}
printf '{"cmd":"set_zoom","ratio":2.0}\n' | nc -q1 127.0.0.1 27183
# → {"ok":true,"data":{"ratio":2}}
printf '{"cmd":"set_zoom","ratio":999}\n' | nc -q1 127.0.0.1 27183
# → {"ok":false,"error":{"code":"OUT_OF_RANGE","msg":"..."}}

# 5. Encerrar o scrcpy e comprovar o zoom mudando no vídeo gravado
ffplay spike-a.mkv
```

**Critérios de aceite** (research.md R1): `hello` responde com versão; zoom
visivelmente alterado no stream headless sem reinício de sessão; log do
servidor mostra `CamLink: set camera zoom`. **Critério de abortar**: sessão
inacessível a partir da thread extra → pivotar para pipeline própria (APK) e
replanejar as fases 4/5/7.

### Resultado do Spike A — ✅ VALIDADO (2026-07-09)

Dispositivo: Samsung Galaxy S20 FE (SM-G781B), Android 13 (One UI 5). Jar
buildado via `build_without_gradle.sh` (JDK 17 Temurin + platform-36 +
build-tools 36.0.0); servidor executado headless via `app_process` com
`tunnel_forward=true raw_stream=true`, vídeo lido pelo ffmpeg do host.

- `hello` → `{"ok":true,"protocol":1,"server":"camlink-v4.0"}` ✓
- `set_zoom 3.0` → `{"ok":true,"data":{"ratio":3}}`; log do servidor
  `CamLink: set camera zoom: 3.0`; zoom 3× visível nos frames da gravação,
  **sem reinício da sessão** ✓
- `set_zoom 999` → `OUT_OF_RANGE "zoom 999.0 out of [1.0, 8.0]"` ✓
- comando desconhecido → `BAD_REQUEST` ✓

### Quirks Samsung descobertos (afetam o CamLink desktop)

1. **Overflow por cmdline longa**: a libstagefright da One UI tem hooks
   (`ACodec::reconfigEncoder4OtherApps` / `CCodec::customConfigVideoEncoder`)
   que copiam a **cmdline do processo** para buffer fixo na pilha ao
   configurar um encoder de vídeo. argv longo do `app_process` (~230+ chars
   após `Server 4.0`) → `stack corruption detected (-fstack-protector)` e
   SIGABRT. **Mitigação**: manter a linha de argumentos do servidor curta
   (validado com ~130 chars). O `stream_manager` do CamLink deve minimizar as
   opções passadas ao scrcpy em devices Samsung.
2. **Evicção por brilho adaptativo**: `com.samsung.adaptivebrightnessgo`
   (prioridade 999) abre uma câmera a cada ~5 s; o cliente UID shell é
   evictado ~2 s após abrir a câmera (`Camera disconnected`). **Mitigação**:
   desativar brilho adaptativo durante o stream
   (`settings put system screen_brightness_mode 0`) ou orientar o usuário —
   candidato a diagnóstico acionável (FR-010) no CamLink.
3. `/data/local/tmp` pode ser limpo pelo sistema entre sessões — sempre
   re-push do jar antes de iniciar.

## Rebase contra o upstream

```bash
git fetch upstream --tags
git rebase <nova-tag-estavel> camlink
# resolver conflitos (restritos a server/), rebuildar e revalidar o protocolo
```
