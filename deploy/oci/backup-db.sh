#!/usr/bin/env bash
# TeenyMoney DB 일일 백업. root로 실행 (systemd timer teenymoney-backup).
# - 서버: /opt/teenymoney-data/backups 에 7일 보관
# - OCI Object Storage 버킷 teenymoney-backup 에 30일 보관
# 무료 한도(Object Storage 20GB, API 요청 월 5만 건)를 넘지 않도록, 버킷 전체 사용량이
# LIMIT_BYTES(15GB)를 넘으면 업로드하지 않고 실패로 끝낸다(PAYG 계정이라 넘으면 과금).
set -Eeuo pipefail

LOCAL_DIR=/opt/teenymoney-data/backups
BUCKET=teenymoney-backup
LOCAL_DAYS=7
REMOTE_DAYS=30
LIMIT_BYTES=$((15 * 1024 * 1024 * 1024))

# 앱이 쓰는 OCI Customer Secret Key와 S3 호환 주소를 그대로 쓴다.
set -a; . /etc/teenymoney/teenymoney.env; . /opt/teenymoney-data/.env; set +a
export RCLONE_CONFIG_OCI_TYPE=s3 RCLONE_CONFIG_OCI_PROVIDER=Other \
       RCLONE_CONFIG_OCI_ACCESS_KEY_ID="$AWS_ACCESS_KEY_ID" \
       RCLONE_CONFIG_OCI_SECRET_ACCESS_KEY="$AWS_SECRET_ACCESS_KEY" \
       RCLONE_CONFIG_OCI_ENDPOINT="$AWS_S3_ENDPOINT" RCLONE_CONFIG_OCI_REGION="$AWS_REGION" \
       RCLONE_CONFIG_OCI_FORCE_PATH_STYLE=true RCLONE_CONFIG_OCI_NO_CHECK_BUCKET=true

install -d -m 700 "$LOCAL_DIR"
f="$LOCAL_DIR/teenymoney-$(date +%Y%m%d-%H%M).sql.gz"
docker exec -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" teenymoney-data-mysql-1 \
  mysqldump -uroot --single-transaction --routines --triggers --events --set-gtid-purged=OFF \
  --databases teenymoney teenymoney_test | gzip > "$f.part"
# 덤프가 끝까지 쓰였는지 확인한 뒤에만 이름을 바꾼다.
zcat "$f.part" | tail -1 | grep -q "Dump completed" || { rm -f "$f.part"; echo "덤프 불완전" >&2; exit 1; }
mv "$f.part" "$f"; chmod 600 "$f"
find "$LOCAL_DIR" -name 'teenymoney-*.sql.gz' -mtime +$((LOCAL_DAYS - 1)) -delete

used=0
for b in $(rclone lsf oci: --dirs-only | tr -d /); do
  used=$((used + $(rclone size "oci:$b" --json | sed -E 's/.*"bytes":([0-9]+).*/\1/')))
done
if (( used > LIMIT_BYTES )); then
  echo "Object Storage 사용량 ${used}B 가 한도 ${LIMIT_BYTES}B 를 넘어 업로드하지 않음" >&2; exit 1
fi

rclone copyto "$f" "oci:$BUCKET/$(basename "$f")"
rclone delete "oci:$BUCKET" --min-age "${REMOTE_DAYS}d"
echo "OK $(basename "$f") $(stat -c %s "$f")B, Object Storage 사용량(업로드 전) ${used}B"
