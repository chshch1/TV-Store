#!/usr/bin/env bash
# Codespaces 启动后自动安装 Android SDK，并写入 local.properties。
set -e

SDK_ROOT="${HOME}/android-sdk"
CMDLINE_VERSION="commandlinetools-linux-11076708_latest"
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [ ! -x "${SDK_ROOT}/cmdline-tools/latest/bin/sdkmanager" ]; then
  echo "==> 下载 Android 命令行工具"
  mkdir -p "${SDK_ROOT}/cmdline-tools"
  curl -sSL -o /tmp/cmdline-tools.zip \
    "https://dl.google.com/android/repository/${CMDLINE_VERSION}.zip"
  unzip -q -o /tmp/cmdline-tools.zip -d "${SDK_ROOT}/cmdline-tools"
  mv "${SDK_ROOT}/cmdline-tools/cmdline-tools" "${SDK_ROOT}/cmdline-tools/latest"
fi

export ANDROID_SDK_ROOT="${SDK_ROOT}"
export PATH="${SDK_ROOT}/cmdline-tools/latest/bin:${SDK_ROOT}/platform-tools:${PATH}"

echo "==> 接受许可协议"
yes | sdkmanager --licenses >/dev/null 2>&1 || true

echo "==> 安装 platform-tools / platforms;android-35 / build-tools;34.0.0"
sdkmanager "platform-tools" "platforms;android-35" "build-tools;34.0.0"

echo "sdk.dir=${SDK_ROOT}" > "${PROJECT_DIR}/local.properties"

# 让后续新开的终端也能直接使用 sdkmanager / adb
if ! grep -q "android-sdk/cmdline-tools" "${HOME}/.bashrc" 2>/dev/null; then
  {
    echo ''
    echo '# Android SDK (TVWebDavBox)'
    echo "export ANDROID_HOME=\"${SDK_ROOT}\""
    echo "export ANDROID_SDK_ROOT=\"${SDK_ROOT}\""
    echo "export PATH=\"${SDK_ROOT}/cmdline-tools/latest/bin:${SDK_ROOT}/platform-tools:\$PATH\""
  } >> "${HOME}/.bashrc"
fi

echo "==> 完成，可直接执行 ./gradlew assembleRelease"
