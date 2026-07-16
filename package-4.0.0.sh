#!/bin/bash

# =============================
# 打包 Maven Central 发布包 (4.0.0)
# Spring Boot 4 + Jackson 3 + Java 21
# =============================

VERSION="4.0.0"
GROUP_ID="com.hibuka.soda"
PROJECT_ROOT=$(pwd)
STAGING_BASE="${PROJECT_ROOT}/target/maven-central-staging"

echo "=================================================="
echo "打包 Maven Central 发布包"
echo "版本：${VERSION}"
echo "Spring Boot 4.1.0 / Jackson 3 / Java 21"
echo "=================================================="

# 清理并创建目录
rm -rf "${STAGING_BASE}"
mkdir -p "${STAGING_BASE}"

# =============================
# 处理 soda-core-base 模块
# =============================
echo ""
echo "处理模块：soda-core-base"
echo "--------------------------------------------------"

MODULE_DIR="${PROJECT_ROOT}/soda-core-base"
TARGET_DIR="${MODULE_DIR}/target"
STAGING_DIR="${STAGING_BASE}/soda-core-base"

mkdir -p "${STAGING_DIR}"

# 复制 JAR 文件
cp "${TARGET_DIR}/soda-core-base-${VERSION}.jar" "${STAGING_DIR}/"
cp "${TARGET_DIR}/soda-core-base-${VERSION}-sources.jar" "${STAGING_DIR}/"
cp "${TARGET_DIR}/soda-core-base-${VERSION}-javadoc.jar" "${STAGING_DIR}/"

# 生成 Flattened POM
echo "生成 Flattened POM..."
mvn org.codehaus.mojo:flatten-maven-plugin:1.6.0:flatten -f "${MODULE_DIR}/pom.xml" -Dflatten.mode=oss -q
cp "${MODULE_DIR}/.flattened-pom.xml" "${STAGING_DIR}/soda-core-base-${VERSION}.pom"

# 生成签名和校验和
echo "生成签名和校验和..."
cd "${STAGING_DIR}"
for file in *.jar *.pom; do
    if [ -f "$file" ]; then
        echo "  处理：$file"
        gpg -ab "$file" 2>/dev/null
        md5 -q "$file" > "${file}.md5"
        shasum -a 1 "$file" | cut -d ' ' -f 1 > "${file}.sha1"
    fi
done

# =============================
# 处理 soda-event-redis 模块
# =============================
echo ""
echo "处理模块：soda-event-redis"
echo "--------------------------------------------------"

MODULE_DIR="${PROJECT_ROOT}/soda-event-redis"
TARGET_DIR="${MODULE_DIR}/target"
STAGING_DIR="${STAGING_BASE}/soda-event-redis"

mkdir -p "${STAGING_DIR}"

# 复制 JAR 文件
cp "${TARGET_DIR}/soda-event-redis-${VERSION}.jar" "${STAGING_DIR}/"
cp "${TARGET_DIR}/soda-event-redis-${VERSION}-sources.jar" "${STAGING_DIR}/"
cp "${TARGET_DIR}/soda-event-redis-${VERSION}-javadoc.jar" "${STAGING_DIR}/"

# 生成 Flattened POM
echo "生成 Flattened POM..."
mvn org.codehaus.mojo:flatten-maven-plugin:1.6.0:flatten -f "${MODULE_DIR}/pom.xml" -Dflatten.mode=oss -q
cp "${MODULE_DIR}/.flattened-pom.xml" "${STAGING_DIR}/soda-event-redis-${VERSION}.pom"

# 生成签名和校验和
echo "生成签名和校验和..."
cd "${STAGING_DIR}"
for file in *.jar *.pom; do
    if [ -f "$file" ]; then
        echo "  处理：$file"
        gpg -ab "$file" 2>/dev/null
        md5 -q "$file" > "${file}.md5"
        shasum -a 1 "$file" | cut -d ' ' -f 1 > "${file}.sha1"
    fi
done

# =============================
# 打包成 ZIP (Maven 仓库结构)
# =============================
echo ""
echo "=================================================="
echo "生成 ZIP 发布包"
echo "=================================================="

REPO_STAGING="${PROJECT_ROOT}/target/repo-staging"
rm -rf "${REPO_STAGING}"

# 创建 Maven 仓库目录结构
BASE_PATH="${REPO_STAGING}/${GROUP_ID//.//}/soda-core-base/${VERSION}"
mkdir -p "${BASE_PATH}"
cp "${STAGING_BASE}/soda-core-base/"* "${BASE_PATH}/"

BASE_PATH="${REPO_STAGING}/${GROUP_ID//.//}/soda-event-redis/${VERSION}"
mkdir -p "${BASE_PATH}"
cp "${STAGING_BASE}/soda-event-redis/"* "${BASE_PATH}/"

# 打包
cd "${REPO_STAGING}"
ZIP_NAME_BASE="${PROJECT_ROOT}/soda-core-base-${VERSION}-repo-bundle.zip"
ZIP_NAME_EVENT="${PROJECT_ROOT}/soda-event-redis-${VERSION}-repo-bundle.zip"

echo "生成：soda-core-base-${VERSION}-repo-bundle.zip"
(cd "${REPO_STAGING}/${GROUP_ID//.//}/soda-core-base" && jar -cMf "${ZIP_NAME_BASE}" "${VERSION}")

echo "生成：soda-event-redis-${VERSION}-repo-bundle.zip"
(cd "${REPO_STAGING}/${GROUP_ID//.//}/soda-event-redis" && jar -cMf "${ZIP_NAME_EVENT}" "${VERSION}")

echo ""
echo "=================================================="
echo "打包完成！"
echo "=================================================="
echo "生成的文件："
ls -lh "${PROJECT_ROOT}"/*-${VERSION}-repo-bundle.zip
echo ""
echo "请将这两个 ZIP 文件分别上传到 Maven Central (Sonatype OSSRH)"
echo "=================================================="
