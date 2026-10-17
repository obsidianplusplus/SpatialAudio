#!/bin/sh
# 构建 SpatialAudioHeadTracker.app（arm64 + x86_64 通用二进制），并生成模组解压时使用的清单文件。
#
# 用法：sh build.sh <输出目录>
# 依赖：macOS 14+ SDK 与 Xcode 命令行工具（xcode-select --install）。
set -eu

if [ "$#" -ne 1 ]; then
    echo "用法：sh build.sh <输出目录>" >&2
    exit 2
fi

OUT_DIR="$1"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC_DIR="$SCRIPT_DIR/SpatialAudioHeadTracker"
APP_NAME="SpatialAudioHeadTracker.app"
APP="$OUT_DIR/$APP_NAME"
EXECUTABLE="SpatialAudioHeadTracker"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

rm -rf "$OUT_DIR"
mkdir -p "$APP/Contents/MacOS"

# 分别编译两种架构；把 Info.plist 同时嵌入可执行文件的 __TEXT,__info_plist 段，
# 即使脱离 .app 单独运行，系统也能读到 NSMotionUsageDescription。
for ARCH in arm64 x86_64; do
    echo "正在编译 $ARCH ……"
    xcrun --sdk macosx swiftc -O \
        -target "$ARCH-apple-macos14.0" \
        -o "$WORK_DIR/$EXECUTABLE-$ARCH" \
        "$SRC_DIR/main.swift" \
        -Xlinker -sectcreate -Xlinker __TEXT -Xlinker __info_plist -Xlinker "$SRC_DIR/Info.plist"
done

lipo -create -output "$APP/Contents/MacOS/$EXECUTABLE" "$WORK_DIR/$EXECUTABLE-arm64" "$WORK_DIR/$EXECUTABLE-x86_64"
cp "$SRC_DIR/Info.plist" "$APP/Contents/Info.plist"
printf 'APPL????' > "$APP/Contents/PkgInfo"

# ad-hoc 签名（Apple Silicon 要求所有可执行代码都有签名）。
codesign --force --sign - "$APP"
codesign --verify --verbose "$APP"

# 自检：校验 SAHT/SAAB 数据格式、输出耳机运动可用性与授权情况，并以离线手动渲染检查
# Apple 空间音频声床的方向（不会触发授权弹窗，也不需要音频设备）；失败时构建中止。
SELF_TEST_LOG="$WORK_DIR/self-test.txt"
if "$APP/Contents/MacOS/$EXECUTABLE" --self-test > "$SELF_TEST_LOG"; then
    cat "$SELF_TEST_LOG"
else
    cat "$SELF_TEST_LOG"
    echo "助手自检失败（selfTest=FAIL），构建中止。" >&2
    exit 1
fi

# 自检只要有一个声床源位置候选通过就算通过；这里额外要求运行时实际使用的 bedSourcePosition 也通过，
# 否则发布出去的渲染器会把声床的正前方摆错方向。失败时请按上面的 bedSourcePositionPassing 修改 main.swift 中的常量。
if ! grep -q '^bedSourcePositionInUse=.* inUsePasses=true$' "$SELF_TEST_LOG"; then
    echo "运行时使用的 bedSourcePosition 未通过离线渲染检查，请改用 bedSourcePositionPassing 中列出的位置，构建中止。" >&2
    exit 1
fi

# 生成清单：第一行为内容摘要（用于判断是否需要重新解压），其后为全部文件的相对路径。
cd "$OUT_DIR"
VERSION="$(find "$APP_NAME" -type f | LC_ALL=C sort | while IFS= read -r FILE; do shasum -a 256 "$FILE"; done | shasum -a 256 | cut -c1-16)"
{
    echo "version=$VERSION"
    find "$APP_NAME" -type f | LC_ALL=C sort
} > manifest.txt

echo "助手构建完成：$APP（版本 $VERSION）"
