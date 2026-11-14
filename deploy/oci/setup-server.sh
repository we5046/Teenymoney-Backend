#!/usr/bin/env bash
# OCI Ubuntu 24.04 (A1, ARM64) 첫 세팅. 한 번만 실행한다: sudo bash setup-server.sh
# 결과: Java 17, Tomcat 9(/opt/tomcat, 서비스 이름 tomcat, 127.0.0.1:8080),
#       nginx, Docker(MySQL·Redis), 80/443 방화벽 개방. deploy-dev.yml이 기대하는 구조와 같다.
set -Eeuo pipefail
cd "$(dirname "$0")"

timedatectl set-timezone Asia/Seoul
apt-get update
apt-get install -y openjdk-17-jre-headless nginx certbot python3-certbot-nginx fail2ban curl
command -v docker >/dev/null || curl -fsSL https://get.docker.com | sh

# Oracle Ubuntu 이미지는 iptables에 REJECT가 기본으로 있다. ufw는 쓰지 않는다.
for p in 80 443; do
  iptables -C INPUT -p tcp --dport "$p" -j ACCEPT 2>/dev/null || iptables -I INPUT 6 -p tcp --dport "$p" -j ACCEPT
done
netfilter-persistent save

# Tomcat 9 최신 패치 버전
if [[ ! -d /opt/tomcat ]]; then
  v="$(curl -fsS https://dlcdn.apache.org/tomcat/tomcat-9/ | grep -oE 'v9\.0\.[0-9]+' | sort -V | tail -1)"
  curl -fsSL "https://dlcdn.apache.org/tomcat/tomcat-9/$v/bin/apache-tomcat-${v#v}.tar.gz" -o /tmp/tomcat.tgz
  id tomcat >/dev/null 2>&1 || useradd -r -m -d /opt/tomcat -s /usr/sbin/nologin tomcat
  mkdir -p /opt/tomcat && tar -xzf /tmp/tomcat.tgz -C /opt/tomcat --strip-components=1
  rm -rf /opt/tomcat/webapps/*
  # 루프백에만 바인딩 (앞단 nginx만 접근)
  sed -i 's|<Connector port="8080" protocol="HTTP/1.1"|<Connector port="8080" address="127.0.0.1" protocol="HTTP/1.1"|' /opt/tomcat/conf/server.xml
  chown -R tomcat:tomcat /opt/tomcat
fi

cat > /etc/systemd/system/tomcat.service <<'UNIT'
[Unit]
Description=Tomcat 9 (TeenyMoney)
After=network.target docker.service

[Service]
Type=forking
User=tomcat
Group=tomcat
Environment=JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
Environment=CATALINA_HOME=/opt/tomcat
Environment=CATALINA_PID=/opt/tomcat/temp/tomcat.pid
Environment="CATALINA_OPTS=-Xms512m -Xmx2g -Duser.timezone=Asia/Seoul"
ExecStart=/opt/tomcat/bin/startup.sh
ExecStop=/opt/tomcat/bin/shutdown.sh
Restart=on-failure

[Install]
WantedBy=multi-user.target
UNIT
systemctl daemon-reload
systemctl enable tomcat

# MySQL·Redis
install -d -m 700 /opt/teenymoney-data
cp compose.yml /opt/teenymoney-data/
if [[ ! -f /opt/teenymoney-data/.env ]]; then
  echo "/opt/teenymoney-data/.env 가 없습니다. 아래 형식으로 만든 뒤 다시 실행하세요:"
  echo "  MYSQL_ROOT_PASSWORD=...  DB_USERNAME=...  DB_PASSWORD=...  REDIS_PASSWORD=..."
  exit 1
fi
chmod 600 /opt/teenymoney-data/.env
docker compose -f /opt/teenymoney-data/compose.yml --env-file /opt/teenymoney-data/.env up -d

echo "완료. 다음: /opt/tomcat/bin/setenv.sh 작성 → DB 복원 → nginx 설정 (docs/OCI_MIGRATION.md)"
