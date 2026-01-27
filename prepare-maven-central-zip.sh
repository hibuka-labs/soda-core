#!/bin/bash

# =============================
# 配置部分
# =============================
GROUP_ID="com.hibuka.soda"
# 定义要处理的模块列表（格式：目录路径:ArtifactId）
# 注意：根目录用 "." 表示
MODULES=(
  ".:soda-core-parent"
  "soda-core-base:soda-core-base"
  "soda-event-redis:soda-event-redis"
)

# =============================
# 初始化
# =============================
PROJECT_ROOT=$(pwd)
WORK_DIR="${PROJECT_ROOT}/.zip-workspace"

echo "=================================================="
echo "准备打包 Maven Central 发布包"
echo "项目根目录: ${PROJECT_ROOT}"
echo "=================================================="

# 1. 获取项目信息
# ----------------
echo "尝试使用 Maven 获取版本号和 GroupId..."
VERSION=$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout)
GROUP_ID=$(mvn help:evaluate -Dexpression=project.groupId -q -DforceStdout)

if [ -z "$VERSION" ]; then
    echo "Error: 无法获取版本号"
    exit 1
fi
if [ -z "$GROUP_ID" ]; then
    echo "Error: 无法获取 GroupId"
    exit 1
fi

# 移除 -SNAPSHOT (如果存在) - Maven Central 发布通常需要 release 版本
# 这里我们假设用户已经改好了 version，或者我们手动去除 SNAPSHOT
# 但通常最好是让用户自己确保 pom 版本是 release 版。
# 这里的逻辑保留你原本的去 SNAPSHOT 逻辑（如果原意如此），
# 或者直接使用 pom 里的版本。
# 假设我们只发布 release 包：
RAW_VERSION=${VERSION}
RELEASE_VERSION=${VERSION/-SNAPSHOT/}

echo "GroupId: ${GROUP_ID}"
echo "原始版本号: ${RAW_VERSION}"
echo "发布版本号: ${RELEASE_VERSION}"
VERSION=${RELEASE_VERSION}

# =============================
# 清理工作区
# =============================
rm -rf "${WORK_DIR}"
mkdir -p "${WORK_DIR}"

# =============================
# 检查 GPG
# =============================
if ! command -v gpg >/dev/null 2>&1; then
  echo "错误: 未找到 gpg 命令，请先安装 GnuPG"
  exit 1
fi

# =============================
# 检查 Target 目录
# =============================
if [ ! -d "soda-core-base/target" ] && [ ! -d "target" ]; then
    echo "警告: 未检测到 target 目录，可能未运行构建。"
    echo "建议先运行: mvn clean package"
    # 这里不强制退出，允许用户确认
fi

# =============================
# 循环处理模块
# =============================
for module_info in "${MODULES[@]}"; do
  MODULE_DIR_REL=${module_info%%:*}
  ARTIFACT_ID=${module_info##*:}
  
  MODULE_DIR="${PROJECT_ROOT}/${MODULE_DIR_REL}"
  # 修正：对于根目录，target 就在根下；对于子模块，在子模块目录下
  if [ "${MODULE_DIR_REL}" == "." ]; then
      TARGET_DIR="${PROJECT_ROOT}/target"
  else
      TARGET_DIR="${PROJECT_ROOT}/${MODULE_DIR_REL}/target"
  fi
  
  echo ""
  echo "--------------------------------------------------"
  echo "正在处理模块: ${ARTIFACT_ID}"
  echo "目录: ${MODULE_DIR_REL}"
  echo "--------------------------------------------------"

  # 准备临时目录
  BUNDLE_DIR="${WORK_DIR}/${ARTIFACT_ID}"
  mkdir -p "${BUNDLE_DIR}"

  # 1. 处理 POM 文件
  # ----------------
  SRC_POM="${MODULE_DIR}/pom.xml"
  DEST_POM="${BUNDLE_DIR}/${ARTIFACT_ID}-${VERSION}.pom"
  
  if [ ! -f "${SRC_POM}" ]; then
    echo "错误: 未找到 pom.xml: ${SRC_POM}"
    exit 1
  fi
  
  # 替换 POM 中的版本号 (包括 parent version 和依赖 version)
  # 将 -SNAPSHOT 版本号替换为 release 版本号
  # 简单替换：将 ${RAW_VERSION} 替换为 ${VERSION}
  # 如果 RAW_VERSION 包含 SNAPSHOT，则会被替换掉
  if [ "${RAW_VERSION}" != "${VERSION}" ]; then
      sed "s/${RAW_VERSION}/${VERSION}/g" "${SRC_POM}" > "${DEST_POM}"
  else
      cp "${SRC_POM}" "${DEST_POM}"
  fi
  
  echo "已生成 POM: $(basename ${DEST_POM})"

  # 2. 判断是否需要处理 Jar
  # ----------------
  IS_POM_PACKAGING=false
  # 如果是 parent 模块，通常是 pom packaging
  if [ "${ARTIFACT_ID}" == "soda-core-parent" ]; then
    IS_POM_PACKAGING=true
  fi
  # 或者 grep packaging 标签
  if grep -q "<packaging>pom</packaging>" "${SRC_POM}"; then
      IS_POM_PACKAGING=true
  fi

  if [ "$IS_POM_PACKAGING" = false ]; then
    # 检查 Jar
    JAR_FILE="${TARGET_DIR}/${ARTIFACT_ID}-${VERSION}.jar"
    SNAPSHOT_JAR_FILE="${TARGET_DIR}/${ARTIFACT_ID}-${RAW_VERSION}.jar"
    
    # 有时候 SNAPSHOT 产物文件名可能不带 SNAPSHOT (虽然少见)，或者带时间戳
    # 这里主要匹配最常见的两种情况
    
    if [ -f "${JAR_FILE}" ]; then
      cp "${JAR_FILE}" "${BUNDLE_DIR}/"
      echo "已复制 JAR: $(basename ${JAR_FILE})"
    elif [ -f "${SNAPSHOT_JAR_FILE}" ]; then
      cp "${SNAPSHOT_JAR_FILE}" "${BUNDLE_DIR}/${ARTIFACT_ID}-${VERSION}.jar"
      echo "已复制 JAR: $(basename ${SNAPSHOT_JAR_FILE}) -> ${ARTIFACT_ID}-${VERSION}.jar"
    else
      echo "警告: 未找到 JAR 文件 (${ARTIFACT_ID}-${VERSION}.jar 或 ${ARTIFACT_ID}-${RAW_VERSION}.jar)"
    fi
    
    # 检查 Sources
    SRC_JAR="${TARGET_DIR}/${ARTIFACT_ID}-${VERSION}-sources.jar"
    SNAPSHOT_SRC_JAR="${TARGET_DIR}/${ARTIFACT_ID}-${RAW_VERSION}-sources.jar"
    
    if [ -f "${SRC_JAR}" ]; then
      cp "${SRC_JAR}" "${BUNDLE_DIR}/"
    elif [ -f "${SNAPSHOT_SRC_JAR}" ]; then
      cp "${SNAPSHOT_SRC_JAR}" "${BUNDLE_DIR}/${ARTIFACT_ID}-${VERSION}-sources.jar"
    else
      echo "警告: 未找到 Sources JAR"
    fi
    
    # 检查 Javadoc
    DOC_JAR="${TARGET_DIR}/${ARTIFACT_ID}-${VERSION}-javadoc.jar"
    SNAPSHOT_DOC_JAR="${TARGET_DIR}/${ARTIFACT_ID}-${RAW_VERSION}-javadoc.jar"
    
    if [ -f "${DOC_JAR}" ]; then
      cp "${DOC_JAR}" "${BUNDLE_DIR}/"
    elif [ -f "${SNAPSHOT_DOC_JAR}" ]; then
      cp "${SNAPSHOT_DOC_JAR}" "${BUNDLE_DIR}/${ARTIFACT_ID}-${VERSION}-javadoc.jar"
    else
      echo "警告: 未找到 Javadoc JAR"
    fi
  fi

    # ----------------
    # 2. 生成 Flattened POM (关键步骤：解决依赖版本缺失和元数据继承问题)
    # ----------------
    echo "正在生成 Flattened POM..."
    
    # 使用 flatten-maven-plugin 生成解析后的 POM
    # mode=oss: 官方推荐用于 Maven Central 发布 (Open Source Software)
    # 它会保留 name, description, url, scm, developers, licenses 等关键信息
    # 并且会处理依赖和继承
    
    # 注意：我们需要先确保 install parent 到本地仓库，否则 flatten 子模块时可能报错
    # 但我们已经在最外层，所以可以直接对当前模块执行 flatten
    
    # 使用完整坐标调用，防止环境未配置 pluginGroups
    # 使用 -f 指定当前模块的 pom.xml，确保为每个模块生成对应的 flattened pom
    mvn org.codehaus.mojo:flatten-maven-plugin:1.6.0:flatten -f "${MODULE_DIR}/pom.xml" -Dflatten.mode=oss -Dflatten.clean=true -q
    
    FLATTENED_POM="${MODULE_DIR}/.flattened-pom.xml"
    if [ ! -f "${FLATTENED_POM}" ]; then
        echo "Error: Flattened POM generation failed for ${MODULE_NAME}"
        exit 1
    fi
 
     # ----------------
     # 3. 准备 Staging 目录 (关键步骤：隔离文件，确保打包纯净)
     # ----------------
     STAGING_DIR="${PROJECT_ROOT}/target/central-staging/${ARTIFACT_ID}"
     rm -rf "${STAGING_DIR}"
     mkdir -p "${STAGING_DIR}"
     
     echo ">>> 准备打包目录: ${STAGING_DIR}"
 
     # 复制 POM (使用生成的 flattened pom)
     TARGET_POM="${STAGING_DIR}/${ARTIFACT_ID}-${VERSION}.pom"
     cp "${FLATTENED_POM}" "${TARGET_POM}"
    
    # 再次确保 version 是 release 版 (flatten 插件可能保留了 snapshot)
    sed "s/<version>${RAW_VERSION}<\/version>/<version>${VERSION}<\/version>/g" "${TARGET_POM}" > "${TARGET_POM}.tmp" && mv "${TARGET_POM}.tmp" "${TARGET_POM}"

    # 复制 JAR / Sources / Javadoc (如果存在)
    if [ -f "${BUNDLE_DIR}/${ARTIFACT_ID}-${VERSION}.jar" ]; then
        cp "${BUNDLE_DIR}/${ARTIFACT_ID}-${VERSION}.jar" "${STAGING_DIR}/"
    fi
    if [ -f "${BUNDLE_DIR}/${ARTIFACT_ID}-${VERSION}-sources.jar" ]; then
        cp "${BUNDLE_DIR}/${ARTIFACT_ID}-${VERSION}-sources.jar" "${STAGING_DIR}/"
    fi
    if [ -f "${BUNDLE_DIR}/${ARTIFACT_ID}-${VERSION}-javadoc.jar" ]; then
        cp "${BUNDLE_DIR}/${ARTIFACT_ID}-${VERSION}-javadoc.jar" "${STAGING_DIR}/"
    fi

    # ----------------
    # 4. 生成签名 (在 Staging 目录中进行)
    # ----------------
    echo ">>> 生成签名和校验和..."
    
    # 对 Staging 目录下的所有文件进行签名
    for file in "${STAGING_DIR}"/*; do
        if [ -f "$file" ]; then
            # GPG 签名
            gpg -ab "$file"
            if [ $? -ne 0 ]; then
                echo "Error: GPG signature failed for $file"
                exit 1
            fi
            
            # 生成 MD5
            md5 -q "$file" > "${file}.md5"
            
            # 生成 SHA1
            shasum -a 1 "$file" | cut -d ' ' -f 1 > "${file}.sha1"
        fi
    done

    # ----------------
    # 5. 打包 ZIP (包含目录结构的 Bundle - Repository Structure)
    # Maven Central 推荐结构 (例如: com/hibuka/soda/soda-core-parent/2.1.0-boot32/...)
    # ----------------
    REPO_PATH="${GROUP_ID//.//}/${ARTIFACT_ID}/${VERSION}"
    REPO_STAGING_DIR="${PROJECT_ROOT}/target/repo-staging/${REPO_PATH}"
    
    # 清理并创建深层目录
    rm -rf "${PROJECT_ROOT}/target/repo-staging"
    mkdir -p "${REPO_STAGING_DIR}"
    
    # 复制文件到深层目录
    cp "${STAGING_DIR}"/* "${REPO_STAGING_DIR}/"
    
    ZIP_NAME_REPO="${ARTIFACT_ID}-${VERSION}-repo-bundle.zip"
    ZIP_PATH_REPO="${PROJECT_ROOT}/${ZIP_NAME_REPO}"
    rm -f "${ZIP_PATH_REPO}"
    
    echo ">>> 生成发布包 (Repo Structure Bundle): ${ZIP_NAME_REPO}"
    # 在 repo-staging 根目录下打包，这样包内就会包含 com/hibuka/... 结构
    (cd "${PROJECT_ROOT}/target/repo-staging" && jar -cMf "${ZIP_PATH_REPO}" *)

done

echo ""
echo "=================================================="
echo "所有模块处理完成！"
echo "生成的 ZIP 文件列表："
ls -1 "${PROJECT_ROOT}"/*-repo-bundle.zip 2>/dev/null
echo ""
echo "请将这些 ZIP 文件上传至 Maven Central (Sonatype OSSRH)。"
echo "=================================================="
