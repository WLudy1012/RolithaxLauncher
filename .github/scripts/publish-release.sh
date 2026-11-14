#!/usr/bin/env bash
set -euo pipefail

[[ "$RELEASE_TAG" =~ ^v[0-9]+(\.[0-9]+){1,3}(-Beta|-Force)?$ ]]
[[ "$RELEASE_SHA" =~ ^[0-9a-f]{40}$ ]]
mapfile -t runtimes < <(awk 'NF == 2 { sub(/^\*/, "", $2); print $2 }' runtimes/android/SHA256SUMS.txt)
(("${#runtimes[@]}" > 0))
runtime_checksums="$(<runtimes/android/SHA256SUMS.txt)"
cd "${1:?缺少三端产物目录}"

check_tag() {
  [[ "$(gh api "repos/$GH_REPO/commits/$RELEASE_TAG" --jq .sha)" == "$RELEASE_SHA" ]] || {
    echo "::error::发布 tag 已移动"
    exit 1
  }
}

check_tag
windows=RolithaxLauncher-Windows-x64-Setup.exe
linux=RolithaxLauncher-Linux.tar.gz
android_assets=(RolithaxLauncher.apk "${runtimes[@]}")
for asset in "${android_assets[@]}"; do
  test -s "android/$asset"
done
test -s android/release-notes.md
test -s "windows/$windows"
test -s "linux/$linux"
(
  cd android
  sha256sum --binary -- "${android_assets[@]}" | diff - SHA256SUMS.txt
  printf '%s\n' "$runtime_checksums" | sha256sum --strict --check -
)

required=("${android_assets[@]}" "$windows" "$linux" SHA256SUMS.txt)
check_assets() {
  local names asset
  names=$(gh release view "$RELEASE_TAG" --json assets --jq '.assets[].name')
  for asset in "$@"; do
    grep -Fxq -- "$asset" <<< "$names" || {
      echo "::error::Release 缺少附件 $asset"
      return 1
    }
  done
}

prerelease=false
[[ "$RELEASE_TAG" != *-Beta ]] || prerelease=true
if state=$(gh release view "$RELEASE_TAG" --json isDraft --jq .isDraft); then
  if [[ "$state" == false ]]; then
    check_assets "${required[@]}"
    echo "Release 已发布，保留现有附件。"
    exit 0
  fi
  [[ "$state" == true ]]
fi

mkdir -p publish
for asset in "${android_assets[@]}"; do
  cp -- "android/$asset" publish/
done
cp -- "windows/$windows" "linux/$linux" android/release-notes.md publish/
cd publish
assets=("${android_assets[@]}" "$windows" "$linux")
sha256sum --binary -- "${assets[@]}" > SHA256SUMS.txt
{
  printf '\n## 下载文件和校验值\n\n| 文件 | SHA-256 |\n| --- | --- |\n'
  while read -r digest name; do
    printf '| `%s` | `%s` |\n' "${name#\*}" "$digest"
  done < SHA256SUMS.txt
  printf '\n## 桌面安装包\n\n'
  printf -- '- `%s` 包含 Windows x64 应用和运行时，可离线安装。\n' "$windows"
  printf -- '- `%s` 包含 Linux x64 应用和 Java 运行时，解压后运行 `RolithaxLauncher/bin/RolithaxLauncher`。\n' "$linux"
} >> release-notes.md

check_tag
if [[ "${state:-}" != true ]]; then
  gh release create "$RELEASE_TAG" --verify-tag --draft \
    --title "Rolithax Launcher $RELEASE_TAG" --notes-file release-notes.md --prerelease="$prerelease"
else
  names=$(gh release view "$RELEASE_TAG" --json assets --jq '.assets[].name')
  for asset in app-release.apk app-release-lite.apk; do
    if grep -Fxq -- "$asset" <<< "$names"; then
      gh release delete-asset "$RELEASE_TAG" "$asset" --yes
    fi
  done
fi
gh release upload "$RELEASE_TAG" "${assets[@]}" SHA256SUMS.txt --clobber
check_assets "${assets[@]}" SHA256SUMS.txt
check_tag
gh release edit "$RELEASE_TAG" --draft=false --prerelease="$prerelease" \
  --title "Rolithax Launcher $RELEASE_TAG" --notes-file release-notes.md
