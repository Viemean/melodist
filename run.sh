#!/usr/bin/env bash
set -euo pipefail

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"

AVD_NAME="MelodistTV"

# 检查当前是否有在线设备
DEVICE_COUNT=$(adb devices | grep -v "List" | grep "device$" | wc -l || true)

if [ "$DEVICE_COUNT" -eq 0 ]; then
    echo "==> 未检测到在线设备，正在唤起 Android TV 模拟器 ($AVD_NAME)..."
    nohup "$ANDROID_HOME/emulator/emulator" -avd "$AVD_NAME" -gpu host -accel on > /tmp/tv_emulator.log 2>&1 &
    disown

    echo "==> 等待设备 ADB 在线..."
    adb wait-for-device

    echo "==> 等待 Android TV 系统启动就绪..."
    for i in {1..60}; do
        boot_completed=$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)
        if [ "$boot_completed" = "1" ]; then
            echo "==> 系统已完全启动就绪 (耗时约 ${i} 秒)"
            break
        fi
        sleep 2
    done
fi

echo "==> 1. 构建并增量安装 APK..."
./gradlew :app-tv:installDebug

echo "==> 2. 启动 Melodist TV 主界面..."
adb shell am start -n org.melodist.tv/.MainActivity

echo "==> 应用已启动！"
