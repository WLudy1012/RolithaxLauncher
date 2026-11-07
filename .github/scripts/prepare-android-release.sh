#!/usr/bin/env bash
set -euo pipefail

[[ "$RELEASE_TAG" =~ ^v[0-9]+(\.[0-9]+){1,3}(-Beta|-Force)?$ ]]
version="${RELEASE_TAG#v}"
version="${version%-Beta}"
version="${version%-Force}"
version_in_gradle=$(sed -nE 's/^\s*versionName.*\?: *"([^"]+)".*$/\1/p' app/build.gradle.kts)
[[ "$version_in_gradle" == "$version" ]] || {
  echo "::error::Gradle versionName 与发布 tag 不一致"
  exit 1
}

out="$RUNNER_TEMP/release-assets"
keystore="$RUNNER_TEMP/rolithax-release.jks"
[[ ! -e keystore.properties && ! -e "$keystore" ]] || {
  echo "::error::签名配置已经存在，请在干净的发布工作区运行"
  exit 1
}
umask 077
mkdir -p "$out"
trap 'rm -f -- "$keystore" keystore.properties' EXIT

if [[ -z "${KEYSTORE_BASE64:-}" || -z "${KEYSTORE_PASSWORD:-}" || -z "${KEY_PASSWORD:-}" ]]; then
  echo "::error::发布签名 Secrets 不完整"
  exit 1
fi
printf '%s' "$KEYSTORE_BASE64" | tr -d '[:space:]' | base64 --decode > "$keystore"
unset KEYSTORE_BASE64
keytool -list -keystore "$keystore" -storepass:env KEYSTORE_PASSWORD -alias wludy >/dev/null 2>&1 || {
  echo "::error::签名密钥无效或缺少 wludy 别名"
  exit 1
}

RELEASE_KEYSTORE="$keystore" python3 - <<'PY'
import os
from pathlib import Path

def java_property(value):
    raw = value.encode("utf-16-be")
    return "".join("\\u" + raw[i:i + 2].hex() for i in range(0, len(raw), 2))

settings = {
    "storeFile": os.environ["RELEASE_KEYSTORE"],
    "storePassword": os.environ["KEYSTORE_PASSWORD"],
    "keyAlias": "wludy",
    "keyPassword": os.environ["KEY_PASSWORD"],
}
Path("keystore.properties").write_text(
    "".join(f"{key}={java_property(value)}\n" for key, value in settings.items()),
    encoding="ascii",
)
PY
unset KEYSTORE_PASSWORD KEY_PASSWORD

gradle_flags=(--no-daemon --no-build-cache --no-configuration-cache --stacktrace)
./gradlew :app:assembleRelease "${gradle_flags[@]}"
cp app/build/outputs/apk/release/app-release.apk "$out/app-release.apk"
./gradlew :app:assembleRelease -PwithoutJre=true "${gradle_flags[@]}"
cp app/build/outputs/apk/release/app-release.apk "$out/app-release-lite.apk"
rm -f -- "$keystore" keystore.properties

apksigner="$ANDROID_HOME/build-tools/$ANDROID_BUILD_TOOLS/apksigner"
test -x "$apksigner"
signer_digest() {
  "$apksigner" verify --print-certs "$1" | awk -F': ' '/certificate SHA-256 digest:/ { print $2 }'
}

full_signer=$(signer_digest "$out/app-release.apk")
lite_signer=$(signer_digest "$out/app-release-lite.apk")
[[ -n "$full_signer" && "$full_signer" == "$lite_signer" ]] || {
  echo "::error::完整版和精简版的 APK 签名不一致"
  exit 1
}

jre17=jre17-arm64-20210825-release.tar.xz
unzip -Z1 "$out/app-release.apk" > "$RUNNER_TEMP/full-apk-entries"
unzip -Z1 "$out/app-release-lite.apk" > "$RUNNER_TEMP/lite-apk-entries"
grep -Fxq "assets/jre/$jre17" "$RUNNER_TEMP/full-apk-entries"
if grep -q '^assets/jre/' "$RUNNER_TEMP/lite-apk-entries"; then
  echo "::error::精简版包含了 JRE"
  exit 1
fi
cp "app/src/jreRuntime/assets/jre/$jre17" "$out/$jre17"

fetch_runtime() {
  local asset=$1 digest=$2 url=$3
  if curl -fsSL --retry 2 --retry-max-time 600 --connect-timeout 20 --max-time 300 \
    -o "$out/$asset.part" "$url" &&
    printf '%s  %s\n' "$digest" "$out/$asset.part" | sha256sum --check --status; then
    mv "$out/$asset.part" "$out/$asset"
  else
    rm -f -- "$out/$asset.part"
    echo "::warning::运行时 $asset 未通过下载或校验，本次发布不包含该附件"
  fi
}

jre21=jre21-arm64-20260223-release.tar.xz
jre25=jre25-arm64-20260223-release.tar.xz
fetch_runtime "$jre21" d055fc953771e6cfd2206ef770ca675d69b5e4cfd9f3f1e6820ba5820c3cc1dc \
  "https://pan.huang1111.cn/f/eWkQI1/$jre21" &
runtime21=$!
fetch_runtime "$jre25" 0fdf6d19fe66ea61c12caa24bd655227ddb0d7d9c16c0f13281a7c2878635286 \
  "https://pan.huang1111.cn/f/5NOltm/$jre25" &
runtime25=$!
wait "$runtime21"
wait "$runtime25"

previous=
if [[ "$RELEASE_TAG" == *-Beta ]]; then
  previous=$(git describe --tags --abbrev=0 --match 'v[0-9]*-Beta' "$RELEASE_TAG^" 2>/dev/null || true)
fi
if [[ -z "$previous" ]]; then
  previous=$(git describe --tags --abbrev=0 --match 'v[0-9]*' --exclude '*-Beta' "$RELEASE_TAG^" 2>/dev/null || true)
fi
{
  printf '# Rolithax Launcher %s\n\n' "$RELEASE_TAG"
  if [[ "$RELEASE_TAG" == *-Force ]]; then
    printf '**强制更新**\n\n'
  fi
  if [[ -f ".github/release-notes/$RELEASE_TAG.md" ]]; then
    cat ".github/release-notes/$RELEASE_TAG.md"
  elif [[ -n "$previous" ]]; then
    printf '自 `%s` 以来的变更\n\n' "$previous"
    git log --no-merges --format='- %s' "$previous..$RELEASE_TAG"
  else
    printf '最近的 15 条变更\n\n'
    git log --no-merges -n 15 --format='- %s' "$RELEASE_TAG"
  fi
  printf '\n## Android 安装包\n\n'
  printf '%s\n' '- `app-release.apk` 内嵌 Java 17，可离线准备本机开服。'
  printf '%s\n' '- `app-release-lite.apk` 体积较小，本机开服时按需下载运行时。'
  printf '\n| 安装包 | SHA-256 |\n| --- | --- |\n'
  for apk in app-release.apk app-release-lite.apk; do
    digest=$(sha256sum "$out/$apk")
    printf '| `%s` | `%s` |\n' "$apk" "${digest%% *}"
  done
  printf '\n全部附件的校验值见 `SHA256SUMS.txt`。\n'
} > "$out/release-notes.md"

(
  cd "$out"
  assets=(app-release.apk app-release-lite.apk "$jre17")
  for runtime in "$jre21" "$jre25"; do
    [[ ! -f "$runtime" ]] || assets+=("$runtime")
  done
  sha256sum "${assets[@]}" > SHA256SUMS.txt
)
