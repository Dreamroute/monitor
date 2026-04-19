#!/usr/bin/env bash

set -Eeuo pipefail

APP_NAME="${APP_NAME:-monitor}"
REPO_URL="${REPO_URL:-git@github.com:Dreamroute/monitor.git}"
BRANCH="${BRANCH:-main}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEFAULT_WORK_DIR="/usr/local/src/${APP_NAME}"
if [[ -d "${SCRIPT_DIR}/.git" ]]; then
  DEFAULT_WORK_DIR="${SCRIPT_DIR}"
fi

WORK_DIR="${WORK_DIR:-${DEFAULT_WORK_DIR}}"
APP_DIR="${APP_DIR:-/usr/local/${APP_NAME}}"
LOG_DIR="${LOG_DIR:-/var/log/${APP_NAME}}"
APP_JAR="${APP_DIR}/${APP_NAME}.jar"
PID_FILE="${PID_FILE:-${APP_DIR}/${APP_NAME}.pid}"
LOG_FILE="${LOG_FILE:-${LOG_DIR}/${APP_NAME}.log}"

MAVEN_CMD="${MAVEN_CMD:-mvn}"
MVN_ARGS="${MVN_ARGS:--DskipTests clean package}"
JAVA_CMD="${JAVA_CMD:-java}"
JAVA_OPTS="${JAVA_OPTS:--Xms500m -Xmx500m}"
SPRING_PROFILE="${SPRING_PROFILE:-prd}"
STOP_TIMEOUT="${STOP_TIMEOUT:-20}"
TAIL_LOG="${TAIL_LOG:-0}"

info() {
  echo "[INFO] $*"
}

die() {
  echo "[ERROR] $*" >&2
  exit 1
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || die "Command not found: $1"
}

ensure_clean_or_empty_dir() {
  local dir="$1"

  if [[ -d "${dir}/.git" ]]; then
    return
  fi

  if [[ -e "${dir}" ]] && [[ -n "$(find "${dir}" -mindepth 1 -maxdepth 1 -print -quit 2>/dev/null)" ]]; then
    die "${dir} exists but is not a git repository. Set WORK_DIR to another path or clean it first."
  fi
}

sync_source() {
  ensure_clean_or_empty_dir "${WORK_DIR}"

  if [[ -d "${WORK_DIR}/.git" ]]; then
    info "Updating source in ${WORK_DIR}"
    git -C "${WORK_DIR}" remote set-url origin "${REPO_URL}"
    git -C "${WORK_DIR}" fetch origin "${BRANCH}"
    if git -C "${WORK_DIR}" show-ref --verify --quiet "refs/heads/${BRANCH}"; then
      git -C "${WORK_DIR}" checkout "${BRANCH}"
    else
      git -C "${WORK_DIR}" checkout -b "${BRANCH}" "origin/${BRANCH}"
    fi
    git -C "${WORK_DIR}" pull --ff-only origin "${BRANCH}"
  else
    info "Cloning ${REPO_URL} to ${WORK_DIR}"
    mkdir -p "$(dirname "${WORK_DIR}")"
    git clone --branch "${BRANCH}" "${REPO_URL}" "${WORK_DIR}"
  fi
}

build_app() {
  info "Building ${APP_NAME}"
  (
    cd "${WORK_DIR}"
    # shellcheck disable=SC2086
    "${MAVEN_CMD}" -B ${MVN_ARGS}
  )
}

find_built_jar() {
  local jar

  jar="$(find "${WORK_DIR}/target" -maxdepth 1 -type f -name "${APP_NAME}-*.jar" ! -name "*.original" | sort | tail -n 1)"
  [[ -n "${jar}" ]] || die "No jar found in ${WORK_DIR}/target"

  echo "${jar}"
}

install_jar() {
  local built_jar="$1"

  info "Installing $(basename "${built_jar}") to ${APP_JAR}"
  mkdir -p "${APP_DIR}" "${LOG_DIR}"
  cp "${built_jar}" "${APP_JAR}.new"
  mv "${APP_JAR}.new" "${APP_JAR}"
}

is_app_pid() {
  local pid="$1"
  local args

  args="$(ps -p "${pid}" -o args= 2>/dev/null || true)"
  [[ "${args}" == *" -jar ${APP_JAR}"* ]]
}

running_pids() {
  local pids=""

  if [[ -f "${PID_FILE}" ]]; then
    local pid
    pid="$(tr -cd '0-9' < "${PID_FILE}" || true)"
    if [[ -n "${pid}" ]] && kill -0 "${pid}" >/dev/null 2>&1 && is_app_pid "${pid}"; then
      pids="${pid}"
    fi
  fi

  local found
  found="$(pgrep -f "java .* -jar ${APP_JAR}" || true)"
  if [[ -n "${found}" ]]; then
    pids="${pids}"$'\n'"${found}"
  fi

  if [[ -n "${pids}" ]]; then
    printf '%s\n' "${pids}" | awk 'NF && !seen[$0]++'
  fi
}

stop_old_app() {
  local pids
  pids="$(running_pids || true)"

  if [[ -z "${pids}" ]]; then
    info "No existing ${APP_NAME} process found"
    rm -f "${PID_FILE}"
    return
  fi

  info "Stopping old ${APP_NAME} process: ${pids//$'\n'/ }"
  kill -15 ${pids} >/dev/null 2>&1 || true

  local elapsed=0
  while [[ "${elapsed}" -lt "${STOP_TIMEOUT}" ]]; do
    sleep 1
    elapsed=$((elapsed + 1))

    if [[ -z "$(running_pids || true)" ]]; then
      rm -f "${PID_FILE}"
      info "Old process stopped"
      return
    fi
  done

  pids="$(running_pids || true)"
  if [[ -n "${pids}" ]]; then
    info "Old process did not stop in ${STOP_TIMEOUT}s, killing: ${pids//$'\n'/ }"
    kill -9 ${pids} >/dev/null 2>&1 || true
  fi

  rm -f "${PID_FILE}"
}

start_new_app() {
  info "Starting ${APP_NAME}"
  touch "${LOG_FILE}"

  # shellcheck disable=SC2086
  nohup "${JAVA_CMD}" ${JAVA_OPTS} -jar "${APP_JAR}" --spring.profiles.active="${SPRING_PROFILE}" >> "${LOG_FILE}" 2>&1 &
  local pid=$!
  echo "${pid}" > "${PID_FILE}"

  sleep 2
  if ! kill -0 "${pid}" >/dev/null 2>&1; then
    tail -n 80 "${LOG_FILE}" >&2 || true
    die "${APP_NAME} failed to start. Check ${LOG_FILE}"
  fi

  info "${APP_NAME} started. pid=${pid}, log=${LOG_FILE}"
}

main() {
  require_cmd git
  require_cmd "${MAVEN_CMD}"
  require_cmd "${JAVA_CMD}"
  require_cmd pgrep

  info "Deploy config: WORK_DIR=${WORK_DIR}, APP_DIR=${APP_DIR}, BRANCH=${BRANCH}, PROFILE=${SPRING_PROFILE}"

  sync_source
  build_app

  local built_jar
  built_jar="$(find_built_jar)"

  install_jar "${built_jar}"
  stop_old_app
  start_new_app

  if [[ "${TAIL_LOG}" == "1" || "${TAIL_LOG}" == "true" ]]; then
    tail -f "${LOG_FILE}"
  fi
}

main "$@"
