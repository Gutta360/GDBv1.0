#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

# Gate 1 only. Run this file on rwp-server from the owner's already
# authenticated interactive SSH terminal. It never asks for, reads, or stores
# the sudo password. Each mutating batch is invoked separately.

ROOT=/opt/gdb-staging
CONFIG=/etc/gdb-staging
STATE=/var/lib/gdb-staging
LOG=/var/log/gdb-staging
CONTAINER=gdb-staging-postgres
NETWORK=gdb-staging-private
VOLUME=gdb-staging-postgres-data
IMAGE=postgres:18.6-alpine
PROXY_CONTAINER=gdb-staging-postgres-proxy
PROXY_NETWORK=gdb-staging-proxy-edge
PROXY_IMAGE=haproxy:3.2.26-alpine
PROXY_CONFIG=/etc/gdb-staging/haproxy-postgres.cfg
DB=gdb_staging
MIGRATOR=gdb_staging_migrator
APP=gdb_staging_app
JAR="$ROOT/backend/current/gdb-backend-1.0.0-SNAPSHOT.jar"

die() { printf 'GATE1 STOP: %s\n' "$*" >&2; exit 1; }
note() { printf 'GATE1: %s\n' "$*"; }
need_cmd() { command -v "$1" >/dev/null 2>&1 || die "required command is unavailable: $1"; }

for command_name in sudo docker ss systemctl curl openssl awk sed grep getent seq shred tee stat date timeout ip mktemp; do
  need_cmd "$command_name"
done

sudo -v || die 'interactive sudo validation failed; no mutation performed'

port_is_bound() {
  local port=$1
  sudo ss -ltnH | awk '{print $4}' | grep -Eq "(^|:)${port}$"
}

assert_loopback_listener() {
  local port=$1
  local listeners
  local found=0
  listeners=$(sudo ss -ltnH | awk -v wanted=":$port" '$4 ~ wanted "$" {print $4}')
  while IFS= read -r listener; do
    [[ -z "$listener" ]] && continue
    case "$listener" in
      "127.0.0.1:$port"|"[::ffff:127.0.0.1]:$port"|"[::1]:$port"|"::1:$port") found=1 ;;
      *) die "approved port $port has a non-loopback listener: $listener" ;;
    esac
  done <<< "$listeners"
  (( found == 1 )) || die "approved port $port is not bound on a loopback address"
}

assert_port_free() {
  local port=$1
  if port_is_bound "$port"; then
    die "approved staging port ${port} is already bound"
  fi
}

assert_existing_services() {
  local unit
  for unit in gdc-backend.service gdclab-backend.service gt2-backend.service \
              nginx.service docker.service containerd.service tailscaled.service; do
    sudo systemctl is-active --quiet "$unit" || die "existing service is not active: $unit"
  done
}

assert_staging_primitives() {
  local network_state volume_driver container_state

  network_state=$(sudo docker network inspect "$NETWORK" --format '{{.Driver}} {{.Internal}}' 2>/dev/null) \
    || die "approved Docker network is missing: $NETWORK"
  [[ "$network_state" == 'bridge true' ]] || die "network $NETWORK is not the approved internal bridge"

  volume_driver=$(sudo docker volume inspect "$VOLUME" --format '{{.Driver}}' 2>/dev/null) \
    || die "approved Docker volume is missing: $VOLUME"
  [[ "$volume_driver" == 'local' ]] || die "volume $VOLUME is not a local volume"

  if sudo docker container inspect "$CONTAINER" >/dev/null 2>&1; then
    container_state=$(sudo docker inspect "$CONTAINER" --format '{{.State.Status}}')
    die "staging container already exists ($CONTAINER, state=$container_state); inspect manually before continuing"
  fi

  sudo getent passwd gdb-staging >/dev/null || die 'approved staging account is missing'
  [[ -d "$ROOT" && -d /var/lib/gdb-staging && -d /var/log/gdb-staging && -d "$CONFIG" ]] \
    || die 'one or more approved staging directories are missing'
}

assert_gapps_container_metadata() {
  local state health
  state=$(sudo docker inspect gapps-postgres --format '{{.State.Status}}' 2>/dev/null) \
    || die 'existing gapps-postgres container metadata is unavailable'
  [[ "$state" == running ]] || die "existing gapps-postgres is not running (state=$state)"
  health=$(sudo docker inspect gapps-postgres --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}not-configured{{end}}')
  [[ "$health" == healthy || "$health" == not-configured ]] \
    || die "existing gapps-postgres health metadata is not healthy: $health"
}

assert_postgres_internal_only() {
  local pg_networks pg_ports network_state
  network_state=$(sudo docker network inspect "$NETWORK" --format '{{.Driver}} {{.Internal}}' 2>/dev/null) \
    || die "approved PostgreSQL network is unavailable: $NETWORK"
  [[ "$network_state" == 'bridge true' ]] || die "PostgreSQL network is not the approved internal bridge"
  pg_networks=$(sudo docker inspect "$CONTAINER" --format '{{range $name, $network := .NetworkSettings.Networks}}{{$name}} {{end}}')
  [[ "$pg_networks" == "$NETWORK " ]] \
    || die "PostgreSQL is attached to an unexpected network: $pg_networks"
  pg_ports=$(sudo docker inspect "$CONTAINER" --format '{{json .NetworkSettings.Ports}}')
  [[ "$pg_ports" == '{"5432/tcp":null}' ]] \
    || die "PostgreSQL has an unexpected host port mapping: $pg_ports"
}

assess_direct_internal_path() {
  local container_ip
  container_ip=$(sudo docker inspect "$CONTAINER" --format '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}')
  [[ -n "$container_ip" ]] || die 'PostgreSQL container has no internal IP'
  note "read-only direct bridge assessment: PostgreSQL container IP is $container_ip"
  sudo ip route get "$container_ip"
  if timeout 3 bash -c "</dev/tcp/$container_ip/5432"; then
    note 'read-only direct bridge TCP probe: reachable'
  else
    note 'read-only direct bridge TCP probe: not reachable'
  fi
  note 'direct container-IP use is not selected for the backend because the IP is not a stable service endpoint'
}

assert_proxy_resources_absent() {
  if sudo docker container inspect "$PROXY_CONTAINER" >/dev/null 2>&1; then
    die "proxy container already exists: $PROXY_CONTAINER; inspect manually before continuing"
  fi
  if sudo docker network inspect "$PROXY_NETWORK" >/dev/null 2>&1; then
    die "proxy network already exists: $PROXY_NETWORK; inspect manually before continuing"
  fi
  if sudo test -e "$PROXY_CONFIG"; then
    die "proxy configuration already exists: $PROXY_CONFIG; inspect manually before continuing"
  fi
}

assert_staging_proxy_ready() {
  local proxy_health proxy_networks proxy_uid edge_containers
  sudo docker container inspect "$PROXY_CONTAINER" >/dev/null 2>&1 \
    || die "staging proxy is absent; run batch4-network-fix before continuing"
  proxy_health=$(sudo docker inspect "$PROXY_CONTAINER" --format '{{.State.Health.Status}}')
  [[ "$proxy_health" == healthy ]] || die "staging proxy is not healthy: $proxy_health"
  proxy_networks=$(sudo docker inspect "$PROXY_CONTAINER" --format '{{len .NetworkSettings.Networks}}')
  [[ "$proxy_networks" == 2 ]] || die "staging proxy is not attached to exactly two networks: $proxy_networks"
  sudo docker network inspect "$NETWORK" --format '{{range .Containers}}{{.Name}} {{end}}' \
    | grep -q "$PROXY_CONTAINER" || die 'staging proxy is not attached to the internal PostgreSQL network'
  sudo docker network inspect "$PROXY_NETWORK" --format '{{.Driver}} {{.Internal}}' \
    | grep -q '^bridge false$' || die 'staging proxy edge network is not a standard bridge'
  edge_containers=$(sudo docker network inspect "$PROXY_NETWORK" --format '{{len .Containers}} {{range .Containers}}{{.Name}} {{end}}')
  [[ "$edge_containers" == "1 $PROXY_CONTAINER " ]] \
    || die "staging proxy edge network has unexpected containers: $edge_containers"
  proxy_uid=$(sudo docker exec "$PROXY_CONTAINER" id -u)
  [[ "$proxy_uid" != 0 ]] || die 'staging proxy is running as root'
  proxy_limits=$(sudo docker inspect "$PROXY_CONTAINER" --format '{{.HostConfig.NanoCpus}} {{.HostConfig.Memory}} {{.HostConfig.MemorySwap}} {{.HostConfig.PidsLimit}}')
  [[ "$proxy_limits" == '250000000 134217728 134217728 64' ]] \
    || die "unexpected TCP proxy resource limits: $proxy_limits"
  assert_postgres_internal_only
  assert_loopback_listener 55433
}

read_only_preflight() {
  note 'read-only preflight'
  hostname
  date -Is
  uptime
  nproc
  free -h
  df -h /
  assert_existing_services
  assert_gapps_container_metadata
  assert_staging_primitives
  for port in 8192 8193 8194 55433; do assert_port_free "$port"; done
  note 'approved staging ports are unbound; existing services are active; approved Docker primitives are intact'
  sudo docker ps --format 'table {{.Names}}\t{{.Image}}\t{{.Ports}}' | sed -n '1,20p'
}

ensure_secret_files() {
  local postgres_password_key='POSTGRES_'
  if sudo test -f "$CONFIG/postgres.env" && sudo test -f "$CONFIG/roles.env"; then
    [[ "$(sudo stat -c '%U:%G:%a' "$CONFIG/postgres.env")" == root:root:600 ]] \
      || die 'postgres.env ownership or mode is not root:root:0600'
    [[ "$(sudo stat -c '%U:%G:%a' "$CONFIG/roles.env")" == root:root:600 ]] \
      || die 'roles.env ownership or mode is not root:root:0600'
    sudo awk -F= -v key="${postgres_password_key}PASSWORD" \
      '$1 == "POSTGRES_DB" && $2 == "gdb_staging" {db=1} $1 == key && length($2)>0 {password=1} END {exit !(db && password)}' \
      "$CONFIG/postgres.env" \
      || die 'existing postgres.env is incomplete or does not target gdb_staging'
    sudo awk -F= '($1 == "GDB_MIGRATOR_PASSWORD" || $1 == "GDB_RUNTIME_PASSWORD" || $1 == "GDB_SNAPSHOT_MASTER_KEY" || $1 == "GDB_ADMIN_BOOTSTRAP_TOKEN") && length($2)>0 {count++} END {exit !(count == 4)}' \
      "$CONFIG/roles.env" \
      || die 'existing roles.env is incomplete'
    return
  fi

  if sudo test -e "$CONFIG/postgres.env" || sudo test -e "$CONFIG/roles.env"; then
    die 'only part of the staging secret set exists; do not overwrite it manually'
  fi

  sudo /bin/sh <<'ROOT_SECRETS'
set -eu
umask 077
config=/etc/gdb-staging
pg_password=$(openssl rand -hex 32)
migrator_password=$(openssl rand -hex 32)
runtime_password=$(openssl rand -hex 32)
snapshot_key=$(openssl rand -base64 32 | tr -d '\n')
bootstrap_token=$(openssl rand -hex 32)
postgres_password_key='POSTGRES_'
printf '%s\n' \
  'POSTGRES_DB=gdb_staging' \
  "${postgres_password_key}PASSWORD=$pg_password" > "$config/postgres.env"
printf '%s\n' \
  "GDB_MIGRATOR_PASSWORD=$migrator_password" \
  "GDB_RUNTIME_PASSWORD=$runtime_password" \
  "GDB_SNAPSHOT_MASTER_KEY=$snapshot_key" \
  "GDB_ADMIN_BOOTSTRAP_TOKEN=$bootstrap_token" > "$config/roles.env"
chown root:root "$config/postgres.env" "$config/roles.env"
chmod 0600 "$config/postgres.env" "$config/roles.env"
ROOT_SECRETS
}

batch4_save_diagnostics() {
  local stamp
  stamp=$(date -u +%Y%m%dT%H%M%SZ)
  if sudo docker container inspect "$CONTAINER" >/dev/null 2>&1; then
    sudo docker inspect "$CONTAINER" \
      --format 'name={{.Name}} image={{.Config.Image}} state={{.State.Status}} health={{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}} memory={{.HostConfig.Memory}} memory_swap={{.HostConfig.MemorySwap}} nano_cpus={{.HostConfig.NanoCpus}} pids={{.HostConfig.PidsLimit}} ports={{json .NetworkSettings.Ports}} mounts={{range .Mounts}}{{.Name}}:{{.Destination}} {{end}}' \
      | sudo tee "$LOG/batch4-${stamp}-inspect.txt" >/dev/null || true
    sudo docker logs "$CONTAINER" 2>&1 \
      | sudo tee "$LOG/batch4-${stamp}-postgres.log" >/dev/null || true
    note "Batch 4 diagnostics preserved under $LOG with timestamp $stamp"
  fi
}

batch4_exit_capture() {
  local rc=$?
  if (( rc != 0 )); then
    batch4_save_diagnostics
  fi
  trap - EXIT
  exit "$rc"
}

batch4_postgres() {
  read_only_preflight
  assert_port_free 55433
  ensure_secret_files

  if ! sudo docker image inspect "$IMAGE" >/dev/null 2>&1; then
    note "pulling only the approved PostgreSQL image: $IMAGE"
    sudo docker pull "$IMAGE" >/dev/null || die "could not obtain $IMAGE"
  fi

  note 'creating the dedicated PostgreSQL container'
  trap batch4_exit_capture EXIT
  sudo docker run -d --name "$CONTAINER" \
    --network "$NETWORK" \
    --restart unless-stopped \
    --cpus=1 --memory=768m --memory-swap=768m --pids-limit=128 \
    --read-only --tmpfs /run --tmpfs /tmp \
    --health-cmd='pg_isready -U postgres -d gdb_staging' \
    --health-interval=2s --health-timeout=3s --health-retries=30 \
    -p 127.0.0.1:55433:5432 \
    --env-file "$CONFIG/postgres.env" \
    -v "$VOLUME:/var/lib/postgresql" \
    "$IMAGE" >/dev/null

  for attempt in $(seq 1 60); do
    health=$(sudo docker inspect "$CONTAINER" --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}unknown{{end}}')
    [[ "$health" == healthy ]] && break
    [[ "$health" == unhealthy || "$health" == exited || "$health" == dead ]] \
      && die "PostgreSQL container entered an unusable state: $health"
    sleep 2
  done
  health=$(sudo docker inspect "$CONTAINER" --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}unknown{{end}}')
  [[ "$health" == healthy ]] || die "PostgreSQL did not become healthy: $health"

  image_id=$(sudo docker image inspect "$IMAGE" --format '{{.Id}}')
  note "PostgreSQL version: $(sudo docker exec --user postgres "$CONTAINER" psql -U postgres -d "$DB" -Atqc 'SHOW server_version;')"
  note "PostgreSQL image id: $image_id"
  mount_target=$(sudo docker inspect "$CONTAINER" --format '{{range .Mounts}}{{if eq .Name "gdb-staging-postgres-data"}}{{.Destination}}{{end}}{{end}}')
  [[ "$mount_target" == /var/lib/postgresql ]] || die "unexpected PostgreSQL 18 mount target: $mount_target"
  attached_networks=$(sudo docker inspect "$CONTAINER" --format '{{range $name, $network := .NetworkSettings.Networks}}{{$name}} {{end}}')
  [[ "$attached_networks" == "$NETWORK " ]] || die "container is attached to an unexpected Docker network: $attached_networks"
  resource_limits=$(sudo docker inspect "$CONTAINER" --format '{{.HostConfig.NanoCpus}} {{.HostConfig.Memory}} {{.HostConfig.MemorySwap}} {{.HostConfig.PidsLimit}}')
  [[ "$resource_limits" == '1000000000 805306368 805306368 128' ]] \
    || die "unexpected PostgreSQL resource limits: $resource_limits"
  assert_loopback_listener 55433
  sudo docker inspect "$CONTAINER" --format 'container={{.Name}} status={{.State.Status}} health={{.State.Health.Status}}'
  trap - EXIT
  note 'Batch 4 complete; no existing GApps container, volume, or database was changed'
}

proxy_save_diagnostics() {
  local stamp
  stamp=$(date -u +%Y%m%dT%H%M%SZ)
  if sudo docker container inspect "$PROXY_CONTAINER" >/dev/null 2>&1; then
    sudo docker inspect "$PROXY_CONTAINER" \
      --format 'name={{.Name}} image={{.Config.Image}} user={{.Config.User}} state={{.State.Status}} health={{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}} memory={{.HostConfig.Memory}} memory_swap={{.HostConfig.MemorySwap}} nano_cpus={{.HostConfig.NanoCpus}} pids={{.HostConfig.PidsLimit}} ports={{json .NetworkSettings.Ports}} networks={{range $name, $network := .NetworkSettings.Networks}}{{$name}} {{end}}' \
      | sudo tee "$LOG/proxy-${stamp}-inspect.txt" >/dev/null || true
    sudo docker logs "$PROXY_CONTAINER" 2>&1 \
      | sudo tee "$LOG/proxy-${stamp}-haproxy.log" >/dev/null || true
    note "proxy diagnostics preserved under $LOG with timestamp $stamp"
  fi
}

proxy_exit_capture() {
  local rc=$?
  if (( rc != 0 )); then
    proxy_save_diagnostics
  fi
  trap - EXIT
  exit "$rc"
}

batch4_network_fix() {
  note 'read-only PostgreSQL networking assessment'
  assert_existing_services
  assert_gapps_container_metadata
  sudo docker inspect "$CONTAINER" >/dev/null 2>&1 \
    || die 'healthy PostgreSQL staging container is absent'
  [[ "$(sudo docker inspect "$CONTAINER" --format '{{.State.Health.Status}}')" == healthy ]] \
    || die 'PostgreSQL staging container is not healthy'
  assert_postgres_internal_only
  assess_direct_internal_path
  assert_port_free 55433
  assert_proxy_resources_absent

  note "pulling only the approved TCP proxy image: $PROXY_IMAGE"
  if ! sudo docker image inspect "$PROXY_IMAGE" >/dev/null 2>&1; then
    sudo docker pull "$PROXY_IMAGE" >/dev/null || die "could not obtain $PROXY_IMAGE"
  fi
  proxy_image_ref=$(sudo docker image inspect "$PROXY_IMAGE" --format '{{index .RepoDigests 0}}' 2>/dev/null || true)
  [[ "$proxy_image_ref" == *'@sha256:'* ]] \
    || die 'proxy image has no immutable repository digest; stop before creating the proxy'
  note "proxy image digest: $proxy_image_ref"

  sudo /bin/sh <<'PROXY_CONFIG_TEXT'
set -eu
umask 022
cat > /etc/gdb-staging/haproxy-postgres.cfg <<'CFG'
global
    log stdout format raw local0
    maxconn 64
    stats socket /tmp/haproxy.sock mode 600 level admin

defaults
    mode tcp
    log global
    option tcplog
    timeout connect 5s
    timeout client 10m
    timeout server 10m
    timeout check 3s

resolvers docker
    nameserver dns 127.0.0.11:53
    resolve_retries 3
    timeout resolve 1s
    timeout retry 1s
    hold valid 10s

frontend postgres_frontend
    bind :5432
    default_backend postgres_backend

backend postgres_backend
    option tcp-check
    server postgres gdb-staging-postgres:5432 check resolvers docker resolve-prefer ipv4 init-addr last,libc,none
CFG
chown root:root /etc/gdb-staging/haproxy-postgres.cfg
chmod 0644 /etc/gdb-staging/haproxy-postgres.cfg
PROXY_CONFIG_TEXT

  trap proxy_exit_capture EXIT
  sudo docker network create "$PROXY_NETWORK" >/dev/null
  sudo docker create --pull=never --name "$PROXY_CONTAINER" \
    --network "$NETWORK" \
    --restart unless-stopped \
    --cpus=0.25 --memory=128m --memory-swap=128m --pids-limit=64 \
    --read-only --tmpfs /run --tmpfs /tmp \
    --cap-drop=ALL --security-opt=no-new-privileges:true \
    --health-cmd='haproxy -c -f /usr/local/etc/haproxy/haproxy.cfg' \
    --health-interval=5s --health-timeout=3s --health-retries=12 \
    -p 127.0.0.1:55433:5432 \
    -v "$PROXY_CONFIG:/usr/local/etc/haproxy/haproxy.cfg:ro" \
    "$proxy_image_ref" haproxy -f /usr/local/etc/haproxy/haproxy.cfg >/dev/null
  sudo docker network connect "$PROXY_NETWORK" "$PROXY_CONTAINER"
  sudo docker start "$PROXY_CONTAINER" >/dev/null

  for attempt in $(seq 1 30); do
    proxy_health=$(sudo docker inspect "$PROXY_CONTAINER" --format '{{.State.Health.Status}}')
    [[ "$proxy_health" == healthy ]] && break
    [[ "$proxy_health" == unhealthy || "$proxy_health" == exited || "$proxy_health" == dead ]] \
      && die "TCP proxy entered an unusable state: $proxy_health"
    sleep 2
  done
  proxy_health=$(sudo docker inspect "$PROXY_CONTAINER" --format '{{.State.Health.Status}}')
  [[ "$proxy_health" == healthy ]] || die "TCP proxy did not become healthy: $proxy_health"

  proxy_uid=$(sudo docker exec "$PROXY_CONTAINER" id -u)
  [[ "$proxy_uid" != 0 ]] || die 'TCP proxy is running as root'
  proxy_limits=$(sudo docker inspect "$PROXY_CONTAINER" --format '{{.HostConfig.NanoCpus}} {{.HostConfig.Memory}} {{.HostConfig.MemorySwap}} {{.HostConfig.PidsLimit}}')
  [[ "$proxy_limits" == '250000000 134217728 134217728 64' ]] \
    || die "unexpected TCP proxy resource limits: $proxy_limits"
  assert_staging_proxy_ready

  if ! timeout 3 bash -c '</dev/tcp/127.0.0.1/55433'; then
    die 'localhost TCP connection to the PostgreSQL proxy failed'
  fi
  proxy_stats=$(sudo docker exec "$PROXY_CONTAINER" sh -c \
    "printf 'show stat\\n' | socat - UNIX-CONNECT:/tmp/haproxy.sock")
  printf '%s\n' "$proxy_stats" | grep -q '^postgres_backend,postgres,' \
    || die 'HAProxy backend status was not found'
  printf '%s\n' "$proxy_stats" | grep -q ',UP,' \
    || die 'HAProxy does not report the internal PostgreSQL backend UP'
  note 'localhost TCP proxy path is reachable and HAProxy reports PostgreSQL UP'
  sudo ss -ltnp | grep -E ':(55433)([[:space:]]|$)'
  sudo docker inspect "$PROXY_CONTAINER" --format 'proxy={{.Name}} status={{.State.Status}} health={{.State.Health.Status}} user={{.Config.User}}'
  trap - EXIT
  note 'Batch 4 networking remediation prepared and verified; PostgreSQL remains internal-only'
}

batch4_network_verify() {
  note 'read-only Batch 4 networking verification'
  assert_existing_services
  assert_gapps_container_metadata
  sudo docker inspect "$CONTAINER" >/dev/null 2>&1 \
    || die 'PostgreSQL staging container is absent'
  [[ "$(sudo docker inspect "$CONTAINER" --format '{{.State.Health.Status}}')" == healthy ]] \
    || die 'PostgreSQL staging container is not healthy'
  assert_postgres_internal_only
  assert_staging_proxy_ready
  proxy_image_ref=$(sudo docker inspect "$PROXY_CONTAINER" --format '{{.Config.Image}}')
  [[ "$proxy_image_ref" == *'@sha256:'* ]] \
    || die "proxy is not running from an immutable image reference: $proxy_image_ref"
  if ! timeout 3 bash -c '</dev/tcp/127.0.0.1/55433'; then
    die 'localhost TCP connection to the PostgreSQL proxy failed'
  fi
  proxy_stats=$(sudo docker exec "$PROXY_CONTAINER" sh -c \
    "printf 'show stat\\n' | socat - UNIX-CONNECT:/tmp/haproxy.sock")
  printf '%s\n' "$proxy_stats" | grep -q '^postgres_backend,postgres,' \
    || die 'HAProxy backend status was not found'
  printf '%s\n' "$proxy_stats" | grep -q ',UP,' \
    || die 'HAProxy does not report the internal PostgreSQL backend UP'
  sudo ss -ltnp | grep -E ':(55433)([[:space:]]|$)'
  sudo docker inspect "$PROXY_CONTAINER" --format 'proxy={{.Name}} status={{.State.Status}} health={{.State.Health.Status}} image={{.Config.Image}} user={{.Config.User}}'
  note 'Batch 4 networking verification PASS; PostgreSQL remains internal-only and localhost proxy is healthy'
}

secret_value() {
  local key=$1
  sudo awk -F= -v wanted="$key" '$1 == wanted {sub(/^[^=]*=/, ""); print; exit}' "$CONFIG/roles.env"
}

db_role_query() {
  local role=$1
  local password=$2
  local database=$3
  local sql=$4
  local rc=0
  printf '*:*:%s:%s:%s\n' "$database" "$role" "$password" \
    | sudo docker exec -i "$CONTAINER" sh -c 'umask 077; cat > /tmp/gdb-gate1.pgpass'
  sudo docker exec -e PGPASSFILE=/tmp/gdb-gate1.pgpass "$CONTAINER" \
    psql --no-psqlrc --quiet --tuples-only --no-align \
    -h 127.0.0.1 -U "$role" -d "$database" -c "$sql" || rc=$?
  sudo docker exec "$CONTAINER" rm -f /tmp/gdb-gate1.pgpass >/dev/null 2>&1 || true
  return "$rc"
}

batch5_database_and_migration() {
  sudo docker inspect "$CONTAINER" >/dev/null 2>&1 || die 'Batch 4 container is not present'
  health=$(sudo docker inspect "$CONTAINER" --format '{{.State.Health.Status}}')
  [[ "$health" == healthy ]] || die "PostgreSQL is not healthy: $health"
  assert_staging_proxy_ready
  sudo test -f "$JAR" || die "verified backend artifact is missing: $JAR; install the reviewed jar before Batch 5"

  note 'creating the staging roles and applying only to gdb_staging'
  sudo /bin/sh <<'ROOT_DB'
set -eu
umask 077
config=/etc/gdb-staging
. "$config/roles.env"
cat > "$config/db-bootstrap.sql" <<SQL
CREATE ROLE gdb_staging_migrator LOGIN PASSWORD '$GDB_MIGRATOR_PASSWORD';
CREATE ROLE gdb_staging_app LOGIN PASSWORD '$GDB_RUNTIME_PASSWORD';
ALTER DATABASE gdb_staging OWNER TO gdb_staging_migrator;
REVOKE ALL ON DATABASE gdb_staging FROM PUBLIC;
GRANT CONNECT ON DATABASE gdb_staging TO gdb_staging_migrator, gdb_staging_app;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
ALTER ROLE gdb_staging_migrator NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
ALTER ROLE gdb_staging_app NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
SQL
docker exec --user postgres -i gdb-staging-postgres psql -U postgres -d gdb_staging -v ON_ERROR_STOP=1 < "$config/db-bootstrap.sql"
shred -u "$config/db-bootstrap.sql"
ROOT_DB

  sudo /bin/sh <<'ROOT_ENVS'
set -eu
umask 027
config=/etc/gdb-staging
root=/opt/gdb-staging
state=/var/lib/gdb-staging
. "$config/roles.env"
install -d -o gdb-staging -g gdb-staging -m 0750 "$state/snapshots"
cat > "$config/migrator.env" <<ENV
GDB_DB_URL=jdbc:postgresql://127.0.0.1:55433/gdb_staging
GDB_DB_USERNAME=gdb_staging_migrator
GDB_DB_PASSWORD=$GDB_MIGRATOR_PASSWORD
GDB_SNAPSHOT_MASTER_KEY=$GDB_SNAPSHOT_MASTER_KEY
GDB_SNAPSHOT_STORAGE=$state/snapshots
GDB_SERVER_ADDRESS=127.0.0.1
GDB_SERVER_PORT=0
GDB_ALLOWED_ORIGINS=https://gdb-staging-admin-internal
SPRING_FLYWAY_ENABLED=true
ENV
cat > "$config/backend.env" <<ENV
GDB_DB_URL=jdbc:postgresql://127.0.0.1:55433/gdb_staging
GDB_DB_USERNAME=gdb_staging_app
GDB_DB_PASSWORD=$GDB_RUNTIME_PASSWORD
GDB_DB_POOL_MAX=5
GDB_DB_POOL_MIN=1
GDB_DB_CONNECTION_TIMEOUT_MS=5000
GDB_ADMIN_BOOTSTRAP_TOKEN=$GDB_ADMIN_BOOTSTRAP_TOKEN
GDB_ADMIN_SESSION_TTL=PT2H
GDB_ADMIN_SESSION_COOKIE_SECURE=true
GDB_SNAPSHOT_MASTER_KEY=$GDB_SNAPSHOT_MASTER_KEY
GDB_SNAPSHOT_STORAGE=$state/snapshots
GDB_SERVER_ADDRESS=127.0.0.1
GDB_SERVER_PORT=8192
GDB_ALLOWED_ORIGINS=https://gdb-staging-admin-internal
GDB_ENROLLMENT_TTL=PT15M
GDB_MAX_BATCH_SIZE=100
GDB_MAX_PULL_SIZE=100
GDB_MAX_SNAPSHOT_BYTES=52428800
GDB_SNAPSHOT_RETENTION_COUNT=3
SPRING_FLYWAY_ENABLED=false
ENV
chown root:gdb-staging "$config/migrator.env" "$config/backend.env"
chmod 0640 "$config/migrator.env" "$config/backend.env"
ROOT_ENVS

  note 'running the one-shot Flyway migration as gdb-staging_migrator'
  sudo -u gdb-staging /bin/sh -c \
    'set -a; . /etc/gdb-staging/migrator.env; set +a; \
     /usr/bin/timeout --foreground 120s /usr/bin/java -Xms64m -Xmx256m \
       -XX:MaxMetaspaceSize=128m -jar \
       /opt/gdb-staging/backend/current/gdb-backend-1.0.0-SNAPSHOT.jar \
       --spring.main.web-application-type=none --spring.flyway.enabled=true' \
    || die 'Flyway one-shot failed or timed out; preserve evidence and stop'

  migrator_password=$(secret_value GDB_MIGRATOR_PASSWORD)
  runtime_password=$(secret_value GDB_RUNTIME_PASSWORD)
  migration_version=$(db_role_query "$MIGRATOR" "$migrator_password" "$DB" \
    "SELECT version FROM gdb_core.flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1;") \
    || die 'could not read Flyway history with the migration role'
  [[ "$migration_version" == 3 ]] || die "expected Flyway version 3, observed: $migration_version"
  note "Flyway current version: $migration_version"

  db_role_query "$APP" "$runtime_password" "$DB" \
    'SELECT count(*) FROM gdb_core.applications;' >/dev/null \
    || die 'runtime role cannot perform its required core-schema read'

  if db_role_query "$APP" "$runtime_password" "$DB" \
      'CREATE TABLE gdb_core.gdb_gate1_unauthorized_ddl (id integer);' >/dev/null 2>&1; then
    die 'runtime role unexpectedly performed unauthorized DDL'
  fi
  note 'runtime role core-schema DDL rejection: PASS'
  note 'Batch 5 complete; Flyway reached version 3 and runtime DDL was rejected'
}

batch5_recover_existing() {
  sudo docker inspect "$CONTAINER" >/dev/null 2>&1 || die 'Batch 4 PostgreSQL container is not present'
  [[ "$(sudo docker inspect "$CONTAINER" --format '{{.State.Health.Status}}')" == healthy ]] \
    || die 'PostgreSQL staging container is not healthy'
  assert_staging_proxy_ready
  sudo test -f "$JAR" || die "verified backend artifact is missing: $JAR"

  existing_migration=$(sudo docker exec --user postgres "$CONTAINER" psql -U postgres -d "$DB" -Atqc \
    "SELECT COALESCE(MAX(version), 'NONE') FROM gdb_core.flyway_schema_history WHERE success;")
  [[ "$existing_migration" == 3 ]] \
    || die "existing staging database is not at Flyway version 3: $existing_migration"
  existing_privileges=$(sudo docker exec --user postgres "$CONTAINER" psql -U postgres -d "$DB" -Atqc \
    "SELECT has_schema_privilege('gdb_staging_app','gdb_core','USAGE') || '|' || has_table_privilege('gdb_staging_app','gdb_core.applications','SELECT') || '|' || has_function_privilege('gdb_staging_app','gdb_core.provision_application_schema(text)','EXECUTE');")
  [[ "$existing_privileges" == 't|t|t' || "$existing_privileges" == 'true|true|true' ]] \
    || die "existing runtime privileges are not the approved set: $existing_privileges"

  if sudo test -e "$CONFIG/roles.env" || sudo test -e "$CONFIG/migrator.env" || sudo test -e "$CONFIG/backend.env"; then
    die 'one or more recovery environment files already exist; inspect manually before continuing'
  fi

  note 'rotating only the existing staging role credentials and rebuilding missing environment files'
  sudo /bin/sh <<'ROOT_RECOVERY'
set -eu
umask 077
config=/etc/gdb-staging
runtime_root=/var/lib/gdb-staging
cleanup() { shred -u "$config/role-recovery.sql" 2>/dev/null || true; }
trap cleanup EXIT
migrator_password=$(openssl rand -hex 32)
runtime_password=$(openssl rand -hex 32)
snapshot_key=$(openssl rand -base64 32 | tr -d '\n')
bootstrap_token=$(openssl rand -hex 32)
printf '%s\n' \
  "GDB_MIGRATOR_PASSWORD=$migrator_password" \
  "GDB_RUNTIME_PASSWORD=$runtime_password" \
  "GDB_SNAPSHOT_MASTER_KEY=$snapshot_key" \
  "GDB_ADMIN_BOOTSTRAP_TOKEN=$bootstrap_token" > "$config/roles.env"
chown root:root "$config/roles.env"
chmod 0600 "$config/roles.env"
cat > "$config/role-recovery.sql" <<SQL
ALTER ROLE gdb_staging_migrator PASSWORD '$migrator_password';
ALTER ROLE gdb_staging_app PASSWORD '$runtime_password';
SQL
chown root:root "$config/role-recovery.sql"
chmod 0600 "$config/role-recovery.sql"
docker exec --user postgres -i gdb-staging-postgres psql -U postgres -d gdb_staging -v ON_ERROR_STOP=1 < "$config/role-recovery.sql"
shred -u "$config/role-recovery.sql"
install -d -o gdb-staging -g gdb-staging -m 0750 "$runtime_root/snapshots"
cat > "$config/migrator.env" <<ENV
GDB_DB_URL=jdbc:postgresql://127.0.0.1:55433/gdb_staging
GDB_DB_USERNAME=gdb_staging_migrator
GDB_DB_PASSWORD=$migrator_password
GDB_SNAPSHOT_MASTER_KEY=$snapshot_key
GDB_SNAPSHOT_STORAGE=$runtime_root/snapshots
GDB_SERVER_ADDRESS=127.0.0.1
GDB_SERVER_PORT=0
GDB_ALLOWED_ORIGINS=https://gdb-staging-admin-internal
SPRING_FLYWAY_ENABLED=false
ENV
cat > "$config/backend.env" <<ENV
GDB_DB_URL=jdbc:postgresql://127.0.0.1:55433/gdb_staging
GDB_DB_USERNAME=gdb_staging_app
GDB_DB_PASSWORD=$runtime_password
GDB_DB_POOL_MAX=5
GDB_DB_POOL_MIN=1
GDB_DB_CONNECTION_TIMEOUT_MS=5000
GDB_ADMIN_BOOTSTRAP_TOKEN=$bootstrap_token
GDB_ADMIN_SESSION_TTL=PT2H
GDB_ADMIN_SESSION_COOKIE_SECURE=true
GDB_SNAPSHOT_MASTER_KEY=$snapshot_key
GDB_SNAPSHOT_STORAGE=$runtime_root/snapshots
GDB_SERVER_ADDRESS=127.0.0.1
GDB_SERVER_PORT=8192
GDB_ALLOWED_ORIGINS=https://gdb-staging-admin-internal
GDB_ENROLLMENT_TTL=PT15M
GDB_MAX_BATCH_SIZE=100
GDB_MAX_PULL_SIZE=100
GDB_MAX_SNAPSHOT_BYTES=52428800
GDB_SNAPSHOT_RETENTION_COUNT=3
SPRING_FLYWAY_ENABLED=false
ENV
chown root:gdb-staging "$config/migrator.env" "$config/backend.env"
chmod 0640 "$config/migrator.env" "$config/backend.env"
ROOT_RECOVERY

  migrator_password=$(secret_value GDB_MIGRATOR_PASSWORD)
  runtime_password=$(secret_value GDB_RUNTIME_PASSWORD)
  migration_version=$(db_role_query "$MIGRATOR" "$migrator_password" "$DB" \
    "SELECT version FROM gdb_core.flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1;") \
    || die 'could not verify Flyway history with the recovered migration role'
  [[ "$migration_version" == 3 ]] || die "recovered migration role observed unexpected Flyway version: $migration_version"
  db_role_query "$APP" "$runtime_password" "$DB" \
    'SELECT count(*) FROM gdb_core.applications;' >/dev/null \
    || die 'recovered runtime role cannot perform its required core-schema read'
  if db_role_query "$APP" "$runtime_password" "$DB" \
      'CREATE TABLE gdb_core.gdb_gate1_recovery_unauthorized_ddl (id integer);' >/dev/null 2>&1; then
    die 'recovered runtime role unexpectedly performed unauthorized DDL'
  fi
  note 'Existing Flyway version 3 preserved; staging credentials and environment files recovered; runtime DDL rejection: PASS'
}

batch5_verify_existing() {
  sudo docker inspect "$CONTAINER" >/dev/null 2>&1 || die 'Batch 4 PostgreSQL container is not present'
  [[ "$(sudo docker inspect "$CONTAINER" --format '{{.State.Health.Status}}')" == healthy ]] \
    || die 'PostgreSQL staging container is not healthy'
  assert_staging_proxy_ready
  for path in "$CONFIG/roles.env" "$CONFIG/migrator.env" "$CONFIG/backend.env"; do
    sudo test -f "$path" || die "required recovery environment file is missing: $path"
  done
  [[ "$(sudo stat -c '%U:%G:%a' "$CONFIG/roles.env")" == root:root:600 ]] \
    || die 'roles.env ownership or mode is not root:root:0600'
  [[ "$(sudo stat -c '%U:%G:%a' "$CONFIG/migrator.env")" == root:gdb-staging:640 ]] \
    || die 'migrator.env ownership or mode is not root:gdb-staging:0640'
  [[ "$(sudo stat -c '%U:%G:%a' "$CONFIG/backend.env")" == root:gdb-staging:640 ]] \
    || die 'backend.env ownership or mode is not root:gdb-staging:0640'
  sudo awk -F= '($1 == "GDB_MIGRATOR_PASSWORD" || $1 == "GDB_RUNTIME_PASSWORD" || $1 == "GDB_SNAPSHOT_MASTER_KEY" || $1 == "GDB_ADMIN_BOOTSTRAP_TOKEN") && length($2)>0 {count++} END {exit !(count == 4)}' \
    "$CONFIG/roles.env" || die 'roles.env is incomplete'
  sudo grep -q '^GDB_DB_USERNAME=gdb_staging_migrator$' "$CONFIG/migrator.env" \
    || die 'migrator.env has an unexpected database role'
  sudo grep -q '^SPRING_FLYWAY_ENABLED=false$' "$CONFIG/migrator.env" \
    || die 'migrator.env does not disable Flyway for recovery'
  sudo grep -q '^GDB_DB_USERNAME=gdb_staging_app$' "$CONFIG/backend.env" \
    || die 'backend.env has an unexpected database role'
  sudo grep -q '^GDB_SERVER_PORT=8192$' "$CONFIG/backend.env" \
    || die 'backend.env has an unexpected server port'
  sudo grep -q '^SPRING_FLYWAY_ENABLED=false$' "$CONFIG/backend.env" \
    || die 'backend.env does not disable Flyway at runtime'

  migrator_password=$(secret_value GDB_MIGRATOR_PASSWORD)
  runtime_password=$(secret_value GDB_RUNTIME_PASSWORD)
  migration_version=$(db_role_query "$MIGRATOR" "$migrator_password" "$DB" \
    "SELECT version FROM gdb_core.flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1;") \
    || die 'migration-role credential verification failed'
  [[ "$migration_version" == 3 ]] || die "unexpected Flyway version: $migration_version"
  db_role_query "$APP" "$runtime_password" "$DB" \
    'SELECT count(*) FROM gdb_core.applications;' >/dev/null \
    || die 'runtime-role credential verification failed'
  if db_role_query "$APP" "$runtime_password" "$DB" \
      'CREATE TABLE gdb_core.gdb_gate1_verify_unauthorized_ddl (id integer);' >/dev/null 2>&1; then
    die 'runtime role unexpectedly performed unauthorized DDL'
  fi
  note 'Batch 5 recovery verification PASS; credentials, environment files, Flyway version 3, and runtime DDL isolation verified'
}

batch5_env_repair() {
  for path in "$CONFIG/migrator.env" "$CONFIG/backend.env"; do
    sudo test -f "$path" || die "required environment file is missing: $path"
  done
  note 'normalizing only the Flyway-disabled guard in staging environment files'
  sudo /bin/sh <<'ROOT_ENV_REPAIR'
set -eu
umask 077
config=/etc/gdb-staging
migrator_tmp=$(mktemp "$config/.migrator.env.repair.XXXXXX")
backend_tmp=$(mktemp "$config/.backend.env.repair.XXXXXX")
cleanup() { rm -f "$migrator_tmp" "$backend_tmp"; }
trap cleanup EXIT
awk -F= '$1 != "SPRING_FLYWAY_ENABLED" {print}' "$config/migrator.env" > "$migrator_tmp"
printf '%s\n' 'SPRING_FLYWAY_ENABLED=false' >> "$migrator_tmp"
awk -F= '$1 != "SPRING_FLYWAY_ENABLED" {print}' "$config/backend.env" > "$backend_tmp"
printf '%s\n' 'SPRING_FLYWAY_ENABLED=false' >> "$backend_tmp"
chown root:gdb-staging "$migrator_tmp" "$backend_tmp"
chmod 0640 "$migrator_tmp" "$backend_tmp"
mv "$migrator_tmp" "$config/migrator.env"
mv "$backend_tmp" "$config/backend.env"
ROOT_ENV_REPAIR
  note 'Environment-file repair complete; no database, container, network, or service was changed'
}

batch6_backend() {
  sudo test -f "$JAR" || die "verified backend artifact is missing: $JAR"
  sudo test -f "$CONFIG/backend.env" || die 'backend.env is missing; complete Batch 5 first'
  sudo test ! -e /etc/systemd/system/gdb-staging-backend.service \
    || die 'staging systemd unit already exists; inspect it before continuing'
  assert_port_free 8192
  assert_port_free 8193
  assert_port_free 8194

  sudo /bin/sh -c 'cat > /etc/systemd/system/gdb-staging-backend.service' <<'UNIT'
[Unit]
Description=GDB staging backend
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=gdb-staging
Group=gdb-staging
WorkingDirectory=/opt/gdb-staging/backend/current
EnvironmentFile=/etc/gdb-staging/backend.env
ExecStart=/usr/bin/java -Xms128m -Xmx512m -XX:MaxMetaspaceSize=192m -XX:+ExitOnOutOfMemoryError -jar /opt/gdb-staging/backend/current/gdb-backend-1.0.0-SNAPSHOT.jar
Restart=on-failure
RestartSec=5
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
MemoryHigh=640M
MemoryMax=768M
CPUQuota=200%
ReadWritePaths=/var/lib/gdb-staging /var/log/gdb-staging
UMask=0077
SyslogIdentifier=gdb-staging-backend

[Install]
WantedBy=multi-user.target
UNIT

  sudo systemctl daemon-reload
  sudo systemctl enable --now gdb-staging-backend.service
  for attempt in $(seq 1 45); do
    system_state=$(sudo systemctl show gdb-staging-backend.service --value -p ActiveState)
    if [[ "$system_state" == active ]]; then
      health_code=$(curl --max-time 5 --silent --output /dev/null --write-out '%{http_code}' \
        http://127.0.0.1:8192/actuator/health || true)
      [[ "$health_code" == 200 ]] && break
    fi
    sleep 2
  done
  system_state=$(sudo systemctl show gdb-staging-backend.service --value -p ActiveState)
  health_code=$(curl --max-time 5 --silent --output /dev/null --write-out '%{http_code}' \
    http://127.0.0.1:8192/actuator/health || true)
  [[ "$system_state" == active && "$health_code" == 200 ]] \
    || die "backend readiness failed (systemd=$system_state, health_http=$health_code); preserve evidence and stop"
  sudo systemctl show gdb-staging-backend.service -p User -p Group -p MainPID -p ActiveState -p SubState --no-pager
  note 'backend health endpoint returned HTTP 200 on loopback'
  note 'Batch 6 complete; no Nginx, Tailscale, gateway, or existing application service was changed'
}

batch7_verify() {
  note 'final Gate 1 verification'
  sudo systemctl is-active --quiet gdb-staging-backend.service || die 'GDB staging backend is not active'
  sudo docker inspect "$CONTAINER" --format 'postgres={{.State.Status}} health={{.State.Health.Status}}'
  [[ "$(sudo docker inspect "$CONTAINER" --format '{{.State.Health.Status}}')" == healthy ]] \
    || die 'staging PostgreSQL is not healthy'
  assert_staging_proxy_ready
  assert_existing_services
  assert_gapps_container_metadata

  for port in 8193 8194; do assert_port_free "$port"; done
  assert_loopback_listener 8192
  assert_loopback_listener 55433
  sudo ss -ltnp | grep -E ':(8192|55433)([[:space:]]|$)'
  sudo docker stats --no-stream --format 'name={{.Name}} cpu={{.CPUPerc}} mem={{.MemUsage}}' "$CONTAINER"
  free -h
  df -h /
  sudo systemctl show gdb-staging-backend.service -p MemoryCurrent -p CPUUsageNSec -p MainPID --no-pager
  note 'existing application units remain active; ports 8193 and 8194 remain unbound'
  note 'Gate 1 verification complete; tablet, HTTPS publication, backup, Gate 2, Gate 3, and Phase 3C remain out of scope'
}

rollback_staging_only() {
  note 'staging-only rollback; this does not delete the database, volume, network, or secrets'
  sudo systemctl disable --now gdb-staging-backend.service || true
  sudo sh -c "journalctl -u gdb-staging-backend.service --since '2 hours ago' > '$LOG/rollback-backend-journal.txt'"
  if sudo docker container inspect "$PROXY_CONTAINER" >/dev/null 2>&1; then
    sudo docker logs "$PROXY_CONTAINER" 2>&1 | sudo tee "$LOG/rollback-proxy.log" >/dev/null || true
    sudo docker stop "$PROXY_CONTAINER" || true
  fi
  sudo docker logs "$CONTAINER" 2>&1 | sudo tee "$LOG/rollback-postgres.log" >/dev/null || true
  sudo docker stop "$CONTAINER" || true
  note 'rollback stopped only the GDB staging backend and PostgreSQL container; evidence was preserved'
}

case "${1:-}" in
  preflight) read_only_preflight ;;
  batch4) batch4_postgres ;;
  batch4-network-fix) batch4_network_fix ;;
  batch4-network-verify) batch4_network_verify ;;
  batch5) batch5_database_and_migration ;;
  batch5-recover) batch5_recover_existing ;;
  batch5-verify) batch5_verify_existing ;;
  batch5-env-repair) batch5_env_repair ;;
  batch6) batch6_backend ;;
  batch7) batch7_verify ;;
  rollback) rollback_staging_only ;;
  *)
    printf 'Usage: %s {preflight|batch4|batch4-network-fix|batch4-network-verify|batch5|batch5-recover|batch5-verify|batch5-env-repair|batch6|batch7|rollback}\n' "$0" >&2
    exit 2
    ;;
esac
