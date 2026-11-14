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

out="${RUNNER_TEMP:?}/release-assets"
runtime_dir=runtimes/android
mkdir -p "$RUNNER_TEMP"
expected_paths=$(find "$runtime_dir" -maxdepth 1 -type f -name '*.tar.xz' -printf '%f\n' | sort)
(
  cd "$runtime_dir"
  sha256sum --strict --check SHA256SUMS.txt
)
mapfile -t runtimes < <(awk 'NF == 2 { sub(/^\*/, "", $2); print $2 }' "$runtime_dir/SHA256SUMS.txt")
(("${#runtimes[@]}" > 0))
[[ "$(printf '%s\n' "${runtimes[@]}" | sort)" == "$expected_paths" ]] || {
  echo "::error::JRE 归档与校验清单不一致"
  exit 1
}

umask 077
mkdir -p "$out"
keystore=$(mktemp "$RUNNER_TEMP/rolithax-release.XXXXXX")
created_properties=false
cleanup() {
  rm -f -- "$keystore"
  if [[ "$created_properties" == true ]]; then rm -f -- keystore.properties; fi
}
trap cleanup EXIT
if [[ -e keystore.properties ]]; then
  echo "::error::请在没有本地签名配置的工作区运行发布构建"
  exit 1
fi
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

created_properties=true
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

./gradlew :app:assembleRelease --no-daemon --no-build-cache --no-configuration-cache --stacktrace
apk=RolithaxLauncher.apk
cp app/build/outputs/apk/release/app-release.apk "$out/$apk"
cleanup
trap - EXIT

apksigner="$ANDROID_HOME/build-tools/$ANDROID_BUILD_TOOLS/apksigner"
test -x "$apksigner"
if ! details=$("$apksigner" verify --verbose "$out/$apk" 2>/dev/null) ||
  ! grep -Fxq 'Verified using v2 scheme (APK Signature Scheme v2): true' <<< "$details" ||
  ! grep -Fxq 'Verified using v3 scheme (APK Signature Scheme v3): true' <<< "$details"; then
  echo "::error::$apk 未通过 APK v2 和 v3 双签名验证"
  exit 1
fi
unzip -Z1 "$out/$apk" > "$RUNNER_TEMP/rolithax-apk-entries"
if grep -q '^assets/jre/' "$RUNNER_TEMP/rolithax-apk-entries"; then
  echo "::error::$apk 包含 JRE"
  exit 1
fi
for runtime in "${runtimes[@]}"; do
  cp -- "$runtime_dir/$runtime" "$out/$runtime"
done

previous=
if [[ "$RELEASE_TAG" == *-Beta ]]; then
  previous=$(git describe --tags --abbrev=0 --match 'v[0-9]*-Beta' HEAD^ 2>/dev/null || true)
fi
if [[ -z "$previous" ]]; then
  previous=$(git describe --tags --abbrev=0 --match 'v[0-9]*' --exclude '*-Beta' HEAD^ 2>/dev/null || true)
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
    git log --no-merges --format='- %s' "$previous..HEAD"
  else
    printf '最近的 15 条变更\n\n'
    git log --no-merges -n 15 --format='- %s' HEAD
  fi
  printf '\n## Android 安装包\n\n'
  printf '%s\n' 'Android 安装包已通过 v2 和 v3 签名验证。首次本机开服时，应用从本仓库按需下载 JRE。'
} > "$out/release-notes.md"

(
  cd "$out"
  sha256sum --binary -- "$apk" "${runtimes[@]}" > SHA256SUMS.txt
)
