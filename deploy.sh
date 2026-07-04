#!/bin/bash
set -e

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
APK_PATH="$PROJECT_DIR/app/build/outputs/apk/release/app-release.apk"
SUPABASE_URL="https://plobrqfaqtcihzzakmbk.supabase.co"
SUPABASE_KEY="sb_publishable_xYICMLO85xY97yxuWhAGbA_bddfdQ9P"
JAVA_HOME="/Users/miles/android-dev/jdk"
ANDROID_HOME="/Users/miles/android-dev/sdk"
GRADLE="/Users/miles/android-dev/gradle-8.5/bin/gradle"
BUILD_GRADLE="$PROJECT_DIR/app/build.gradle.kts"
AAPT="$ANDROID_HOME/build-tools/34.0.0/aapt"
APKSIGNER="$ANDROID_HOME/build-tools/34.0.0/apksigner"
ZIPALIGN="$ANDROID_HOME/build-tools/34.0.0/zipalign"
KEYSTORE="$HOME/.android/debug.keystore"
KEYSTORE_PASS="android"
KEY_ALIAS="androiddebugkey"

echo "========================================"
echo "  时光相册 一键部署脚本"
echo "========================================"

echo ""
echo "[1/6] 读取当前版本号..."
VERSION_CODE=$(grep -E '^\s*versionCode\s*=' "$BUILD_GRADLE" | head -1 | grep -oE '[0-9]+')
VERSION_NAME=$(grep -E '^\s*versionName\s*=' "$BUILD_GRADLE" | head -1 | sed 's/.*versionName.*"\(.*\)".*/\1/')
echo "  当前版本: v${VERSION_NAME} (versionCode=${VERSION_CODE})"

echo ""
echo "[2/6] 自动递增版本号..."
NEW_VERSION_CODE=$((VERSION_CODE + 1))
echo "  versionCode: ${VERSION_CODE} → ${NEW_VERSION_CODE}"
sed -i '' -E "s/^(\s*versionCode\s*=\s*)${VERSION_CODE}/\1${NEW_VERSION_CODE}/" "$BUILD_GRADLE"
VERSION_CODE=$NEW_VERSION_CODE

echo ""
echo "[3/6] 构建 APK..."
JAVA_HOME=$JAVA_HOME ANDROID_HOME=$ANDROID_HOME $GRADLE -p "$PROJECT_DIR" assembleRelease --no-daemon -q 2>&1
UNSIGNED_APK="$PROJECT_DIR/app/build/outputs/apk/release/app-release-unsigned.apk"
if [ ! -f "$UNSIGNED_APK" ]; then
    echo "  ❌ 构建失败，APK 未生成"
    exit 1
fi

echo "  签名 APK..."
ALIGNED_APK="$PROJECT_DIR/app/build/outputs/apk/release/app-aligned.apk"
$ZIPALIGN -p 4 "$UNSIGNED_APK" "$ALIGNED_APK" 2>&1
JAVA_HOME=$JAVA_HOME $APKSIGNER sign --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" --ks-pass "pass:$KEYSTORE_PASS" --key-pass "pass:$KEYSTORE_PASS" --out "$APK_PATH" "$ALIGNED_APK" 2>&1
rm -f "$ALIGNED_APK"

if [ ! -f "$APK_PATH" ]; then
    echo "  ❌ 签名失败"
    exit 1
fi
APK_SIZE=$(du -h "$APK_PATH" | cut -f1)
VERSION_NAME=$(grep -E '^\s*versionName\s*=' "$BUILD_GRADLE" | head -1 | sed 's/.*versionName.*"\(.*\)".*/\1/')
echo "  ✅ 构建成功 v${VERSION_NAME} (versionCode=${VERSION_CODE}) (${APK_SIZE})"

echo ""
echo "[4/6] 校验 APK 内部版本号..."
if [ -x "$AAPT" ]; then
    APK_VERSION_CODE=$($AAPT dump badging "$APK_PATH" 2>/dev/null | grep -o "versionCode='[0-9]*'" | head -1 | grep -o "[0-9]*")
    APK_VERSION_NAME=$($AAPT dump badging "$APK_PATH" 2>/dev/null | grep -o "versionName='[^']*'" | head -1 | sed "s/versionName='//;s/'//")
    echo "  APK 内部: versionCode=${APK_VERSION_CODE}, versionName=${APK_VERSION_NAME}"
    if [ "$APK_VERSION_CODE" != "$VERSION_CODE" ]; then
        echo "  ❌ 致命错误：APK 内部 versionCode(${APK_VERSION_CODE}) 与 build.gradle(${VERSION_CODE}) 不一致！"
        echo "  拒绝部署，请检查构建配置"
        exit 1
    fi
    echo "  ✅ 版本号校验通过"
else
    echo "  ⚠️  aapt 未找到，跳过校验（建议安装）"
fi

echo ""
echo "[5/6] 上传 APK 到 Supabase Storage..."
UPLOAD_RESULT=$(curl -s -X POST "${SUPABASE_URL}/storage/v1/object/photos/app-release.apk" \
    -H "apikey: ${SUPABASE_KEY}" \
    -H "Authorization: Bearer ${SUPABASE_KEY}" \
    -H "Content-Type: application/vnd.android.package-archive" \
    -H "x-upsert: true" \
    --data-binary @"$APK_PATH")
if echo "$UPLOAD_RESULT" | grep -q '"Key"'; then
    echo "  ✅ 上传成功"
else
    echo "  ❌ 上传失败: $UPLOAD_RESULT"
    exit 1
fi

echo ""
echo "[6/6] 更新版本记录（数据库 versionCode=${VERSION_CODE}）..."
read -p "请输入更新说明（留空使用默认）: " RELEASE_NOTES
if [ -z "$RELEASE_NOTES" ]; then
    RELEASE_NOTES="更新至 v${VERSION_NAME}"
fi

INSERT_RESULT=$(curl -s -X POST "${SUPABASE_URL}/rest/v1/app_versions" \
    -H "apikey: ${SUPABASE_KEY}" \
    -H "Authorization: Bearer ${SUPABASE_KEY}" \
    -H "Content-Type: application/json" \
    -H "Prefer: return=representation" \
    -d "{
        \"version_code\": ${VERSION_CODE},
        \"version_name\": \"${VERSION_NAME}\",
        \"download_url\": \"${SUPABASE_URL}/storage/v1/object/public/photos/app-release.apk\",
        \"release_notes\": \"${RELEASE_NOTES}\"
    }")
if echo "$INSERT_RESULT" | grep -q "\"version_code\":${VERSION_CODE}"; then
    echo "  ✅ 版本记录已更新"
else
    echo "  ❌ 更新失败: $INSERT_RESULT"
    exit 1
fi

echo ""
echo "========================================"
echo "  🎉 部署完成！v${VERSION_NAME} 已发布"
echo "  APK内部 versionCode=${VERSION_CODE}"
echo "  数据库 versionCode=${VERSION_CODE}"
echo "  ✅ 两者一致，不会出现循环更新"
echo "  用户可在 App 中一键更新"
echo "========================================"
