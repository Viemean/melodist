#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$SCRIPT_DIR"

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
if [ -d "$ANDROID_HOME/platform-tools" ]; then
    export PATH="$ANDROID_HOME/platform-tools:$PATH"
fi

echo "==> 检查 ADB 环境..."
if ! command -v adb >/dev/null 2>&1; then
    echo "错误: 系统中未找到 adb 命令，请确保已安装 Android SDK 平台工具并加入 PATH。" >&2
    exit 1
fi

IS_RELEASE=false
TARGET_DEVICE=""

while [ "$#" -gt 0 ]; do
    case "$1" in
        --release)
            IS_RELEASE=true
            shift
            ;;
        --debug)
            IS_RELEASE=false
            shift
            ;;
        -s|--device)
            if [ -n "${2:-}" ]; then
                TARGET_DEVICE="$2"
                shift 2
            else
                echo "错误: $1 参数后缺少设备标识。" >&2
                exit 1
            fi
            ;;
        emulator-*|*:*|[0-9a-zA-Z]*)
            TARGET_DEVICE="$1"
            shift
            ;;
        *)
            shift
            ;;
    esac
done

# 1. 确定目标手机设备
if [ -z "$TARGET_DEVICE" ]; then
    mapfile -t ONLINE_DEVICES < <(adb devices | awk 'NR>1 && $NF=="device" {sub(/[[:space:]]+device$/, ""); print}' || true)
    DEVICE_COUNT="${#ONLINE_DEVICES[@]}"

    if [ "$DEVICE_COUNT" -eq 0 ]; then
        echo "错误: 未检测到任何在线 ADB 设备。请连接手机开启 USB 调试或执行无线配对。" >&2
        exit 1
    elif [ "$DEVICE_COUNT" -eq 1 ]; then
        TARGET_DEVICE="${ONLINE_DEVICES[0]}"
    else
        PHYSICAL_DEV=""
        for dev in "${ONLINE_DEVICES[@]}"; do
            if [[ "$dev" != emulator-* ]]; then
                PHYSICAL_DEV="$dev"
                if [[ "$dev" == *" (2)."* ]]; then
                    break
                fi
            fi
        done
        TARGET_DEVICE="${PHYSICAL_DEV:-${ONLINE_DEVICES[0]}}"
        echo "提示: 检测到多台在线设备 ($DEVICE_COUNT 台)，已自动优选目标: $TARGET_DEVICE"
    fi
fi

echo "==> 目标部署设备: $TARGET_DEVICE"
ADB_CMD=(adb -s "$TARGET_DEVICE")

cd "$PROJECT_ROOT"

# 2. 编译独立 OriginOS 原子组件 APK (包名: com.tme.vivowidget)
PACKAGE_NAME="com.tme.vivowidget"
if [ "$IS_RELEASE" = true ]; then
    BUILD_TYPE="release"
    echo "==> 开始编译独立原子组件发行包 (:widget-originos:assembleRelease)..."
    ./gradlew :widget-originos:assembleRelease
else
    BUILD_TYPE="debug"
    echo "==> 开始编译独立原子组件调试包 (:widget-originos:assembleDebug)..."
    ./gradlew :widget-originos:assembleDebug
fi

# 3. 查找 APK 产物
CANDIDATES=(
    "$PROJECT_ROOT/widget-originos/build/outputs/apk/$BUILD_TYPE/widget-originos-$BUILD_TYPE.apk"
    "$PROJECT_ROOT/widget-originos/build/intermediates/apk/$BUILD_TYPE/widget-originos-$BUILD_TYPE.apk"
)

APK_PATH=""
for c in "${CANDIDATES[@]}"; do
    if [ -f "$c" ]; then
        if [ -z "$APK_PATH" ] || [ "$c" -nt "$APK_PATH" ]; then
            APK_PATH="$c"
        fi
    fi
done

if [ -z "$APK_PATH" ] || [ ! -f "$APK_PATH" ]; then
    echo "错误: 未找到 widget-originos APK 产物" >&2
    exit 1
fi

echo "==> 准备安装 APK: $APK_PATH"
set +e
INSTALL_OUTPUT=$("${ADB_CMD[@]}" install -r -t "$APK_PATH" 2>&1)
INSTALL_EXIT_CODE=$?
set -e

if [ $INSTALL_EXIT_CODE -ne 0 ]; then
    if echo "$INSTALL_OUTPUT" | grep -q "INSTALL_FAILED_UPDATE_INCOMPATIBLE"; then
        echo "提示: 检测到已有签名不一致的包 ($PACKAGE_NAME)，正在卸载旧版本并重试..."
        "${ADB_CMD[@]}" uninstall "$PACKAGE_NAME" || true
        "${ADB_CMD[@]}" install -r -t "$APK_PATH"
    else
        echo "错误: APK 安装失败:" >&2
        echo "$INSTALL_OUTPUT" >&2
        exit $INSTALL_EXIT_CODE
    fi
fi

echo "==> 尝试触发主应用状态同步..."
"${ADB_CMD[@]}" shell am broadcast -a org.melodist.mobile.widget.ACTION_REQUEST_SYNC -n com.tencent.qqmusic/org.melodist.mobile.widget.MelodistWidgetProvider >/dev/null 2>&1 || true
"${ADB_CMD[@]}" shell am broadcast -a org.melodist.mobile.widget.ACTION_REQUEST_SYNC -n org.melodist.mobile/org.melodist.mobile.widget.MelodistWidgetProvider >/dev/null 2>&1 || true

echo "==> 部署完成！请在桌面「原子组件」中查找 Melodist 并按需拖入负一屏。"
