#!/usr/bin/env bash

set -u

readonly REQUIRED_JAVA_MAJOR=17
readonly REQUIRED_PLATFORM="android-37"
readonly REQUIRED_BUILD_TOOLS="36.0.0"
readonly REQUIRED_NDK="28.2.13676358"
readonly REQUIRED_CMAKE="3.22.1"

failures=0

pass() {
  printf 'PASS  %s\n' "$1"
}

fail() {
  printf 'FAIL  %s\n' "$1" >&2
  failures=$((failures + 1))
}

find_sdk_root() {
  if [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then
    printf '%s' "$ANDROID_SDK_ROOT"
  elif [[ -n "${ANDROID_HOME:-}" ]]; then
    printf '%s' "$ANDROID_HOME"
  else
    printf ''
  fi
}

check_java() {
  if ! command -v java >/dev/null 2>&1; then
    fail "JDK ${REQUIRED_JAVA_MAJOR}：未找到 java"
    return
  fi

  local version major
  version="$(java -version 2>&1 | head -n 1)"
  major="$(printf '%s' "$version" | sed -E 's/.*version "([0-9]+).*/\1/')"
  if [[ "$major" == "$REQUIRED_JAVA_MAJOR" ]]; then
    pass "JDK ${REQUIRED_JAVA_MAJOR} (${version})"
  else
    fail "需要 JDK ${REQUIRED_JAVA_MAJOR}，检测到：${version}"
  fi
}

check_command() {
  local command_name="$1"
  local label="$2"
  if command -v "$command_name" >/dev/null 2>&1; then
    pass "${label} ($(command -v "$command_name"))"
  else
    fail "${label}：未找到 ${command_name}"
  fi
}

check_directory() {
  local path="$1"
  local label="$2"
  if [[ -d "$path" ]]; then
    pass "${label} (${path})"
  else
    fail "${label}：缺少 ${path}"
  fi
}

main() {
  local sdk_root
  sdk_root="$(find_sdk_root)"

  printf '蛇域进化 Android 构建链预检\n'
  printf '%s\n' '--------------------------------'
  check_java

  if [[ -z "$sdk_root" ]]; then
    fail "ANDROID_SDK_ROOT/ANDROID_HOME 未设置"
  elif [[ ! -d "$sdk_root" ]]; then
    fail "Android SDK 路径不存在：${sdk_root}"
  else
    pass "Android SDK (${sdk_root})"
    check_directory "${sdk_root}/platforms/${REQUIRED_PLATFORM}" "SDK Platform ${REQUIRED_PLATFORM}"
    check_directory "${sdk_root}/build-tools/${REQUIRED_BUILD_TOOLS}" "Build Tools ${REQUIRED_BUILD_TOOLS}"
    check_directory "${sdk_root}/ndk/${REQUIRED_NDK}" "NDK ${REQUIRED_NDK}"
    check_directory "${sdk_root}/cmake/${REQUIRED_CMAKE}" "CMake ${REQUIRED_CMAKE}"
  fi

  check_command sdkmanager "Android SDK Manager"
  check_command adb "Android Debug Bridge"

  if [[ -x "./gradlew" ]]; then
    pass "Gradle Wrapper (./gradlew)"
  else
    fail "Gradle Wrapper：尚未生成 ./gradlew"
  fi

  printf '%s\n' '--------------------------------'
  if (( failures > 0 )); then
    printf '预检失败：%d 项未满足。\n' "$failures" >&2
    printf '请先按 toolchain/README.md 配置环境。\n' >&2
    exit 1
  fi

  printf '预检通过，可以开始 Android 原生构建。\n'
}

main "$@"
