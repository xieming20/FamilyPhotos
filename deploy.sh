#!/bin/bash
set -e

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
APK_PATH="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"
SUPABASE_URL="https://plobrqfaqtcihzzakmbk.supabase.co"
SUPABASE_KEY="sb_publishable_xYICMLO85xY97yxuWhAGbA_bddfdQ9P"
JAVA_HOME="/Users/miles/android-dev/jdk"
ANDROID_HOME="/Users/miles/android-dev/sdk"
GRADLE="/Users/miles/android-dev/gradle-8.5/bin/gradle"
BUILD_GRADLE="$PROJECT_DIR/app/build.gradle.kts"

echo "========================================"
echo "  时光相册 一键部署脚本"
echo "========================================"

echo ""
echo "[1/5] 读取当前版本号..."
VERSION_CODE=$(grep 'versionCode' "$BUILD_GRADLE" | head -1 | sed 's/[^0-9]*\([0-9]\+\).*/\1/')
VERSION_NAME=$(grep 'versionName' "$BUILD_GRADLE" | head -1 | sed 's/.*versionName.*"\(.*\)".*/\1/')
echo "  当前版本: v${VERSION_NAME} (versionCode=${VERSION_CODE})"

echo ""
echo "[2/5] 自动递增版本号..."
NEW_VERSION_CODE=$((VERSION_CODE + 1))
echo "  versionCode: ${VERSION_CODE} → ${NEW_VERSION_CODE}"
sed -i '' "s/versionCode = ${VERSION_CODE}/versionCode = ${NEW_VERSION_CODE}/" "$BUILD_GRADLE"
VERSION_CODE=$NEW_VERSION_CODE

echo ""
echo "[3/5] 构建 APK..."
JAVA_HOME=$JAVA_HOME ANDROID_HOME=$ANDROID_HOME $GRADLE -p "$PROJECT_DIR" assembleDebug --no-daemon -q 2>&1
if [ ! -f "$APK_PATH" ]; then
    echo "  ❌ 构建失败，APK 未生成"
    exit 1
fi
APK_SIZE=$(du -h "$APK_PATH" | cut -f1)
VERSION_NAME=$(grep 'versionName' "$BUILD_GRADLE" | head -1 | sed 's/.*versionName.*"\(.*\)".*/\1/')
echo "  ✅ 构建成功 v${VERSION_NAME} (versionCode=${VERSION_CODE}) (${APK_SIZE})"

echo ""
echo "[4/5] 上传 APK 到 Supabase Storage..."
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
echo "[5/5] 更新版本记录..."
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
echo "  versionCode=${VERSION_CODE}（APK内部与数据库一致）"
echo "  用户可在 App 中一键更新"
echo "========================================"
