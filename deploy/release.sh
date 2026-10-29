#!/usr/bin/env bash
# =============================================================
# soda-core 发布脚本（本文件不含任何密钥，可安全入库）
# 密钥配置：复制 release.local.sh.example 为 release.local.sh（已 gitignore）
# 详见同目录 RELEASE_SKILL.md
#
# 用法:
#   ./deploy/release.sh <version> [--target all|aliyun|central] [--skip-tests] [--dry-run]
#   ./deploy/release.sh verify <version> [--target all|aliyun|central]
#   ./deploy/release.sh bundle <version>
#
# 示例:
#   ./deploy/release.sh 17.0.0                  # 全量发布（阿里云 + Maven Central）
#   ./deploy/release.sh verify 17.0.0           # 只验证远端是否发布成功（配合手动发布）
#   ./deploy/release.sh bundle 17.0.0           # 只生成 Central 签名 bundle
# =============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "${SCRIPT_DIR}")"

# ---------- 加载本地密钥（gitignore，不入库） ----------
LOCAL_CONF="${SCRIPT_DIR}/release.local.sh"
if [ -f "${LOCAL_CONF}" ]; then
    # shellcheck source=/dev/null
    source "${LOCAL_CONF}"
fi

# ---------- 参数解析 ----------
SUBCMD="publish"
case "${1:-}" in
    verify|bundle)
        SUBCMD="$1"; shift ;;
esac
VERSION="${1:-}"
[ -z "${VERSION}" ] && { echo "用法: $0 [publish|verify|bundle] <version> [...]"; exit 1; }
shift || true

TARGET="all"
SKIP_TESTS=""
DRY_RUN=""
while [ $# -gt 0 ]; do
    case "$1" in
        --target)    TARGET="$2"; shift 2 ;;
        --skip-tests) SKIP_TESTS="1"; shift ;;
        --dry-run)   DRY_RUN="1"; shift ;;
        *) echo "未知参数: $1"; exit 1 ;;
    esac
done

# ---------- 通用工具 ----------
say()  { echo ""; echo ">>> $*"; }
fail() { echo ""; echo "!!! $*"; exit 1; }

MODULES=(soda-core-parent soda-core-base soda-event-redis)
JAR_MODULES=(soda-core-base soda-event-redis)   # 有 jar 产物的模块（parent 是 pom 打包，不查 jar）
GROUP_PATH="com/hibuka/soda"

artifact_url() { # $1=repo-url $2=artifact $3=扩展名（默认 pom）
    echo "$1/${GROUP_PATH}/$2/${VERSION}/$2-${VERSION}.${3:-pom}"
}

# ---------- 守卫检查 ----------
guard_common() {
    say "守卫检查"
    case "${VERSION}" in
        *SNAPSHOT*) fail "版本号不能是 SNAPSHOT: ${VERSION}" ;;
    esac
    echo "${VERSION}" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$' \
        || fail "版本号必须是 x.y.z 格式（主版本=Java 基线，见 RELEASE_SKILL.md）: ${VERSION}"

    local branch major
    branch="$(git -C "${PROJECT_ROOT}" rev-parse --abbrev-ref HEAD)"
    major="${VERSION%%.*}"
    [ "${major}" -ge 17 ] \
        || fail "大版本 ${major} 属历史版本线（2.x/3.x/4.x），已 EOL 不再发版（见版本矩阵）"
    [ "${branch}" = "main" ] \
        || fail "版本 ${VERSION} 只在 main 上发布，当前分支: ${branch}"

    if [ -z "${DRY_RUN}" ]; then
        git -C "${PROJECT_ROOT}" diff --quiet \
            && git -C "${PROJECT_ROOT}" diff --cached --quiet \
            || fail "工作区不干净，请先提交或 stash"
    fi
    echo "    分支 ${branch} ✓  版本 ${VERSION} ✓"
}

require_tools() {
    for t in "$@"; do
        command -v "${t}" >/dev/null 2>&1 || fail "缺少工具: ${t}"
    done
}

require_conf() { # 检查密钥变量已配置（只检查名字，不打印值）
    local missing=""
    for v in "$@"; do
        [ -n "${!v:-}" ] || missing="${missing} ${v}"
    done
    [ -z "${missing}" ] || fail "release.local.sh 缺少配置:${missing}（参考 release.local.sh.example）"
}

# ---------- 构建 ----------
set_version() {
    say "设置版本号 ${VERSION}"
    if [ -n "${DRY_RUN}" ]; then echo "    (dry-run) mvn versions:set -DnewVersion=${VERSION}"; return; fi
    mvn -q versions:set -DnewVersion="${VERSION}" -DprocessAllModules \
        -DgenerateBackupPoms=false
}

build() {
    say "构建 + 测试"
    if [ -n "${DRY_RUN}" ]; then echo "    (dry-run) mvn clean verify"; return; fi
    if [ -n "${SKIP_TESTS}" ]; then
        mvn clean verify -DskipTests
    else
        mvn clean verify
    fi
}

# ---------- 发布：阿里云 ----------
publish_aliyun() {
    say "发布到阿里云私有仓库"
    require_conf ALIYUN_REPO_URL ALIYUN_REPO_USER ALIYUN_REPO_PASS
    if [ -n "${DRY_RUN}" ]; then echo "    (dry-run) mvn deploy → ${ALIYUN_REPO_URL}"; return; fi

    local settings
    settings="$(mktemp -t soda-settings)"
    cat > "${settings}" <<EOF
<settings>
  <servers>
    <server>
      <id>aliyun</id>
      <username>${ALIYUN_REPO_USER}</username>
      <password>${ALIYUN_REPO_PASS}</password>
    </server>
  </servers>
</settings>
EOF
    chmod 600 "${settings}"
    mvn -s "${settings}" deploy -DskipTests \
        -DaltDeploymentRepository="aliyun::${ALIYUN_REPO_URL}"
    rm -f "${settings}"
}

# ---------- 发布：Maven Central ----------
publish_central() {
    say "发布到 Maven Central（Portal Publisher API）"
    require_tools gpg unzip curl
    require_conf CENTRAL_TOKEN_USER CENTRAL_TOKEN_PASS
    if [ -n "${DRY_RUN}" ]; then echo "    (dry-run) 生成 bundle 并上传 Central Portal"; return; fi

    # 1. 复用现有签名打包流程（GPG 签名 + 校验和，生成 per-module repo-bundle.zip）
    #    先清掉历史版本残留的 bundle，防止旧版本文件混入本次上传
    rm -f "${PROJECT_ROOT}"/*-repo-bundle.zip
    (cd "${PROJECT_ROOT}" && bash prepare-maven-central-zip.sh)

    # 2. 合并为单个 deployment bundle（repo 布局可叠加）
    local work merge
    work="$(mktemp -d -t soda-bundle)"
    merge="${work}/merged"
    mkdir -p "${merge}"
    local z
    for z in "${PROJECT_ROOT}"/*-"${VERSION}"-repo-bundle.zip; do
        unzip -oq "${z}" -d "${merge}"
    done
    (cd "${merge}" && jar -cMf "${work}/soda-core-${VERSION}-central-bundle.zip" *)
    say "合并 bundle: ${work}/soda-core-${VERSION}-central-bundle.zip"

    # 3. 上传 Central Portal（AUTOMATIC：校验通过即自动发布）
    local token dep_id
    token="$(printf '%s:%s' "${CENTRAL_TOKEN_USER}" "${CENTRAL_TOKEN_PASS}" | base64 | tr -d '\n')"
    dep_id="$(curl -sS -X POST \
        "https://central.sonatype.com/api/v1/publisher/upload?name=soda-core-${VERSION}&publishingType=AUTOMATIC" \
        -H "Authorization: Bearer ${token}" \
        -F "bundle=@${work}/soda-core-${VERSION}-central-bundle.zip")" \
        || fail "上传 Central Portal 失败"
    case "${dep_id}" in
        *[!A-Za-z0-9._-]*) fail "上传返回异常（可能 token 格式不对，见 RELEASE_SKILL.md 实坑记录）: ${dep_id}" ;;
    esac
    say "deployment id: ${dep_id}，轮询发布状态…"

    # 4. 轮询部署状态直到 PUBLISHED / FAILED
    local state="" waited=0
    while [ ${waited} -lt 1800 ]; do
        state="$(curl -sS -X POST \
            "https://central.sonatype.com/api/v1/publisher/status?id=${dep_id}" \
            -H "Authorization: Bearer ${token}" \
            | grep -o '"deploymentState":"[A-Z_]*"' | head -1 | cut -d'"' -f4 || true)"
        case "${state}" in
            PUBLISHED) say "Central Portal 状态: PUBLISHED ✓"; return 0 ;;
            FAILED|VALIDATION_FAILED) fail "Central Portal 状态: ${state}（登录 central.sonatype.com 看失败原因）" ;;
        esac
        echo "    状态: ${state:-UNKNOWN}（已等 ${waited}s）…"
        sleep 30; waited=$((waited + 30))
    done
    fail "等待 Central Portal 超时，最后状态: ${state}（deployment: ${dep_id}）"
}

# ---------- 验证：远端拉回 ----------
verify_target() { # $1=目标
    say "验证发布结果（从远端拉回）: $1"
    if [ -n "${DRY_RUN}" ]; then echo "    (dry-run) 跳过远端验证"; return; fi
    local art url ok retry max_retry interval
    max_retry="${VERIFY_RETRIES:-10}"
    interval="${VERIFY_INTERVAL:-30}"

    for art in "${MODULES[@]}"; do
        ok=""
        for ((retry=1; retry<=max_retry; retry++)); do
            case "$1" in
                aliyun)
                    url="$(artifact_url "${ALIYUN_REPO_URL}" "${art}")"
                    if curl -sSf -o /dev/null -u "${ALIYUN_REPO_USER}:${ALIYUN_REPO_PASS}" "${url}"; then ok=1; break; fi ;;
                central)
                    url="$(artifact_url "https://repo1.maven.org/maven2" "${art}")"
                    if curl -sSf -o /dev/null "${url}"; then ok=1; break; fi ;;
            esac
            echo "    ${art}: 远端尚未就绪（${retry}/${max_retry}），${interval}s 后重试…"
            sleep "${interval}"
        done
        [ -n "${ok}" ] || fail "${art} 在 ${1} 上拉取失败: ${url}"
        echo "    ${art} ✓  ${url}"
    done

    # jar 产物同样必须拉得到——只验 POM 抓不到"POM-only"事故（见 RELEASE_SKILL.md 实坑 2026-10-28）
    for art in "${JAR_MODULES[@]}"; do
        ok=""
        for ((retry=1; retry<=max_retry; retry++)); do
            case "$1" in
                aliyun)
                    url="$(artifact_url "${ALIYUN_REPO_URL}" "${art}" jar)"
                    if curl -sSf -o /dev/null -u "${ALIYUN_REPO_USER}:${ALIYUN_REPO_PASS}" "${url}"; then ok=1; break; fi ;;
                central)
                    url="$(artifact_url "https://repo1.maven.org/maven2" "${art}" jar)"
                    if curl -sSf -o /dev/null "${url}"; then ok=1; break; fi ;;
            esac
            echo "    ${art}.jar: 远端尚未就绪（${retry}/${max_retry}），${interval}s 后重试…"
            sleep "${interval}"
        done
        [ -n "${ok}" ] || fail "${art}.jar 在 ${1} 上拉取失败: ${url}"
        echo "    ${art}.jar ✓  ${url}"
    done
}

# ---------- 打 tag ----------
do_tag() {
    say "打 tag 并推送"
    if [ -n "${DRY_RUN}" ]; then echo "    (dry-run) git tag v${VERSION} && git push origin v${VERSION}"; return; fi
    # 幂等：分次 --target 发布时 tag 可能已由前一次创建
    if git -C "${PROJECT_ROOT}" rev-parse "v${VERSION}" >/dev/null 2>&1; then
        echo "    tag v${VERSION} 已存在，跳过创建"
    else
        git tag "v${VERSION}"
    fi
    git push origin "v${VERSION}"
    echo "    v${VERSION} ✓"
}

# =============================================================
# 主流程
# =============================================================
echo "=================================================="
echo "soda-core ${SUBCMD}: ${VERSION}  (target=${TARGET})"
echo "=================================================="

case "${SUBCMD}" in
    verify)
        case "${TARGET}" in
            all|aliyun)  require_conf ALIYUN_REPO_URL ALIYUN_REPO_USER ALIYUN_REPO_PASS; verify_target aliyun ;;
        esac
        case "${TARGET}" in
            all|central) verify_target central ;;
        esac
        say "全部验证通过 ✓"
        ;;
    bundle)
        require_tools gpg
        guard_common
        (cd "${PROJECT_ROOT}" && bash prepare-maven-central-zip.sh)
        say "bundle 已生成（项目根目录 *-repo-bundle.zip）"
        ;;
    publish)
        guard_common
        require_tools mvn git
        set_version
        build
        case "${TARGET}" in
            all|aliyun)  publish_aliyun ;;
        esac
        case "${TARGET}" in
            all|central) publish_central ;;
        esac
        case "${TARGET}" in
            all|aliyun)  verify_target aliyun ;;
        esac
        case "${TARGET}" in
            all|central) verify_target central ;;
        esac
        do_tag
        say "发布完成 ✓  记得按 RELEASE_SKILL.md 更新模板 pin 与 README 版本矩阵"
        ;;
esac
