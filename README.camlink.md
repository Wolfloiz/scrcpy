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

*(Preenchido pelo Spike A — T015 da feature 001-phone-webcam-bridge)*

- `CamLinkControlServer`: thread adicional no servidor escutando
  `localabstract:camlink`, recebendo comandos JSON (protocolo em
  `specs/001-phone-webcam-bridge/contracts/control-protocol.md` do CamLink) e
  aplicando controles via `setRepeatingRequest` na `CameraCaptureSession`
  existente. Acesso do desktop via `adb forward`.

## Rebase contra o upstream

```bash
git fetch upstream --tags
git rebase <nova-tag-estavel> camlink
# resolver conflitos (restritos a server/), rebuildar e revalidar o protocolo
```
