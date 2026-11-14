# AWS EC2 → OCI(오사카) 이전 절차

대상: OCI Always Free A1 (Ubuntu 24.04 aarch64) 1대. 서버 구조는 EC2와 같다
(nginx → Tomcat 9 `/opt/tomcat` 서비스 `tomcat` → MySQL·Redis Docker).
그래서 `deploy-dev.yml`의 원격 배포 스크립트를 그대로 쓴다.

바뀌는 것은 세 가지뿐이다.

| 항목 | EC2 | OCI |
| --- | --- | --- |
| 이미지·FCM 키 저장소 | S3 + IAM 역할 | OCI Object Storage(S3 호환) + Customer Secret Key |
| 배포 SSH 접근 | 러너 IP를 보안그룹에 임시 허용 | 22번을 열어 두고 키 인증만 허용 (`EC2_SECURITY_GROUP_ID` 비움) |
| 리전 | ap-northeast-2 | ap-osaka-1 |

**JWT_SECRET, CHARGE_BILLING_KEY_ENCRYPTION_KEY는 EC2 값을 그대로 옮긴다.**
바뀌면 모든 사용자가 로그아웃되고, 저장된 토스 빌링키를 복호화할 수 없다.

---

## 1. OCI 콘솔

1. 인스턴스: `VM.Standard.A1.Flex`, Ubuntu 24.04 aarch64, SSH 키 등록. 접속 계정 `ubuntu`.
2. Networking → Reserved Public IP를 만들어 인스턴스 VNIC에 연결 (재생성해도 IP 유지).
3. VCN Security List Ingress: TCP 22, 80, 443. **3306·6379는 열지 않는다.**
4. Storage → Object Storage → 버킷 생성 (Private). 이름 예: `teenymoney-media`.
5. 프로필 → Customer Secret Keys → 키 생성. Access Key / Secret 을 받아 둔다 (Secret은 한 번만 보인다).
6. Tenancy 상세에서 Object Storage Namespace 확인.
   S3 호환 주소: `https://<namespace>.compat.objectstorage.ap-osaka-1.oraclecloud.com`

## 2. 서버 첫 세팅

```bash
# 개발 PC에서
scp -r deploy/oci ubuntu@<OCI_IP>:~/oci

# 서버에서
sudo install -d -m 700 /opt/teenymoney-data
sudo tee /opt/teenymoney-data/.env >/dev/null <<'EOF'
MYSQL_ROOT_PASSWORD=<새 값>
DB_USERNAME=<EC2와 같은 계정명>
DB_PASSWORD=<새 값>
REDIS_PASSWORD=<새 값>
EOF
sudo bash ~/oci/setup-server.sh
```

## 3. Tomcat 환경변수 (`/opt/tomcat/bin/setenv.sh`)

EC2의 `setenv.sh`를 복사해 와서 아래만 바꾼다.

```bash
# EC2에서 가져오기 (개발 PC 경유)
scp ec2:/opt/tomcat/bin/setenv.sh ./setenv.sh   # 경로가 다르면 DEPLOY.md §3 참고
```

```bash
export DB_PASSWORD='<.env의 DB_PASSWORD>'
export REDIS_PASSWORD='<.env의 REDIS_PASSWORD>'

export AWS_REGION='ap-osaka-1'
export AWS_S3_ENDPOINT='https://<namespace>.compat.objectstorage.ap-osaka-1.oraclecloud.com'
export AWS_S3_BUCKET='teenymoney-media'
export AWS_ACCESS_KEY_ID='<OCI Customer Secret Key의 Access Key>'
export AWS_SECRET_ACCESS_KEY='<OCI Customer Secret Key의 Secret>'
# JWT_SECRET, CHARGE_BILLING_KEY_ENCRYPTION_KEY, TOSS_*, FCM_KEY, FINLIFE·DIFY·SOLAPI 값은 EC2 그대로
```

```bash
sudo install -o tomcat -g tomcat -m 600 setenv.sh /opt/tomcat/bin/setenv.sh
```

## 4. 리허설: 데이터 복사 → 동작 확인

### Object Storage (이미지 + FCM 키 파일)

```bash
# 개발 PC (AWS CLI). OCI용 프로필은 Customer Secret Key로 만든다.
aws s3 sync s3://teenymoney-media-kb22 ./media-backup
aws configure --profile oci   # Access Key / Secret, region ap-osaka-1
aws s3 sync ./media-backup s3://teenymoney-media --profile oci \
  --endpoint-url https://<namespace>.compat.objectstorage.ap-osaka-1.oraclecloud.com
```

`FCM_KEY`가 가리키는 객체(Firebase 서비스 계정 JSON)가 새 버킷에 같은 키로 있어야 기동된다.

### MySQL

```bash
# EC2
docker exec <mysql컨테이너> mysqldump -u root -p --single-transaction --routines --triggers teenymoney > dump.sql
# OCI (dump.sql을 scp로 옮긴 뒤)
docker exec -i teenymoney-data-mysql-1 mysql -u root -p"$MYSQL_ROOT_PASSWORD" teenymoney < dump.sql
```

Redis는 리프레시 토큰·SMS 인증 같은 단기 데이터라 옮기지 않는다 (전환 후 한 번 다시 로그인).
옮겨야 하면 EC2의 `dump.rdb`를 redis 볼륨에 복사한다.

### nginx와 프론트엔드

EC2의 nginx 사이트 설정과 Vue 빌드 결과물 폴더를 같은 경로로 복사한다.

```bash
scp ec2:/etc/nginx/sites-available/<사이트> .   # include로 흩어져 있으면 nginx -T 로 확인
```

인증서는 DNS 전환 후 `sudo certbot --nginx -d www.teenymoney.kro.kr` 로 새로 받는다.
전환 전에는 `listen 443` 블록을 잠시 주석 처리하고 80으로 확인한다.

### 첫 배포와 확인

GitHub 저장소 설정 (development environment):

| 이름 | 값 |
| --- | --- |
| vars `EC2_HOST` | OCI Reserved IP |
| vars `EC2_PORT` | `22` |
| vars `EC2_USER` | `ubuntu` |
| vars `EC2_SECURITY_GROUP_ID` | **삭제** (AWS 단계를 건너뛴다) |
| secrets `DEPLOY_SSH_PRIVATE_KEY` | 배포용 키. 공개키를 서버 `~ubuntu/.ssh/authorized_keys`에 추가 |
| secrets `EC2_KNOWN_HOSTS` | `ssh-keyscan -p 22 <OCI_IP>` 결과 |

Actions에서 `Deploy development`를 수동 실행한 뒤, 개발 PC hosts 파일에
`<OCI_IP> www.teenymoney.kro.kr`를 넣고 확인한다.

- `curl http://www.teenymoney.kro.kr/api/v1/health` / `/api/v1/health/db`
- 로그인, 이미지 업로드·조회 (presigned URL이 열리는지), 푸시 알림, SSE(`/api/v1/sse/subscribe`에 `:ping`)
- 확인 후 hosts 줄 삭제

## 5. 실제 전환

1. 하루 전: 내도메인.한국에서 TTL을 낮춘다 (가능하면).
2. EC2 Tomcat 정지 (`sudo systemctl stop tomcat`) → 더 이상 쓰기가 생기지 않는다.
3. 최종 mysqldump → OCI 복원 → 주요 테이블 행 수 비교.
4. `aws s3 sync`를 한 번 더 (새로 올라온 이미지).
5. DNS A 레코드를 OCI IP로 변경 → certbot → 4의 확인 항목 반복.
6. 팀원에게 공지 (SSH 터널 주소, 배포 대상 변경).

롤백: EC2는 정지 상태로 며칠 둔다. 되돌릴 때는 OCI에서 덤프를 떠 EC2에 복원한 뒤 DNS를 되돌린다.

## 6. 백업 (무료 계정은 유휴 인스턴스 회수 위험이 있다)

```bash
# /opt/teenymoney-data/backup.sh, root crontab: 0 4 * * * /opt/teenymoney-data/backup.sh
#!/bin/bash
set -Eeuo pipefail
source /opt/teenymoney-data/.env
f=/tmp/teenymoney-$(date +%F).sql.gz
docker exec teenymoney-data-mysql-1 mysqldump -u root -p"$MYSQL_ROOT_PASSWORD" --single-transaction teenymoney | gzip > "$f"
aws s3 cp "$f" s3://teenymoney-backup/ --profile oci \
  --endpoint-url https://<namespace>.compat.objectstorage.ap-osaka-1.oraclecloud.com
rm -f "$f"
```

추가로 Block Storage → 부트 볼륨에 백업 정책을 건다 (무료 백업 5개).

## 7. AWS 정리 (전환 후 이상 없을 때)

EC2 → EBS·스냅샷 → Elastic IP → S3 버킷(백업 확인 후) → IAM 역할·OIDC 배포 역할.
모든 리전을 확인하고 다음 달 청구서가 0인지 본다.
