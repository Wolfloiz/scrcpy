#!/usr/bin/env bash
# T037 — Build reproduzível do jar `scrcpy-server-camlink` (fork CamLink).
#
# Requisitos: JDK 17 + Android SDK (ANDROID_HOME ou ANDROID_SDK_ROOT) —
# ver README.camlink.md. Roda os testes de protocolo (ProtocolTest valida os
# golden files do CamLink) antes de empacotar; falha de teste ABORTA o build.
#
# Uso:
#   ./build-camlink.sh [dir-de-saida]      # default: ./dist
#
# Saída:
#   <dir>/scrcpy-server-camlink            # o jar (APK não assinado renomeado)
#   <dir>/scrcpy-server-camlink.sha256     # hash para build reproduzível
#
# O CamLink aponta para o artefato via:
#   SCRCPY_SERVER_PATH=<dir>/scrcpy-server-camlink

set -euo pipefail

cd "$(dirname "$0")"

OUT_DIR="${1:-dist}"
APK="server/build/outputs/apk/release/server-release-unsigned.apk"

if [[ -z "${ANDROID_HOME:-}" && -z "${ANDROID_SDK_ROOT:-}" ]]; then
    echo "erro: defina ANDROID_HOME (ou ANDROID_SDK_ROOT) apontando para o Android SDK" >&2
    exit 1
fi

# Testes de contrato primeiro (golden files compartilhados com o cliente Rust);
# CAMLINK_GOLDEN_DIR permite rodar fora do layout de submodule do CamLink.
./gradlew :server:testReleaseUnitTest

./gradlew :server:assembleRelease

if [[ ! -f "$APK" ]]; then
    echo "erro: artefato não encontrado em $APK" >&2
    exit 1
fi

mkdir -p "$OUT_DIR"
cp "$APK" "$OUT_DIR/scrcpy-server-camlink"

if command -v sha256sum >/dev/null; then
    (cd "$OUT_DIR" && sha256sum scrcpy-server-camlink > scrcpy-server-camlink.sha256)
    cat "$OUT_DIR/scrcpy-server-camlink.sha256"
fi

echo "ok: $OUT_DIR/scrcpy-server-camlink"
echo "use: SCRCPY_SERVER_PATH=$(cd "$OUT_DIR" && pwd)/scrcpy-server-camlink"
