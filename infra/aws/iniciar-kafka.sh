#!/bin/bash
set -euo pipefail
exec > >(tee /var/log/eft-kafka-inicio.log) 2>&1
dnf install -y docker
systemctl enable --now docker
install -d /opt/eft-kafka
cat > /opt/eft-kafka/run.sh <<'SCRIPT'
#!/bin/bash
set -euo pipefail
token=$(curl -fsS -X PUT -H 'X-aws-ec2-metadata-token-ttl-seconds: 300' http://169.254.169.254/latest/api/token)
public_ip=$(curl -fsS -H "X-aws-ec2-metadata-token: $token" http://169.254.169.254/latest/meta-data/public-ipv4)
docker volume create eft-kafka-data >/dev/null
# La IP publica puede cambiar al iniciar la instancia; el volumen conserva los datos.
if docker container inspect eft-kafka >/dev/null 2>&1; then docker rm -f eft-kafka; fi
docker run -d --name eft-kafka --restart unless-stopped --memory 1400m \
  -p 9092:9092 -v eft-kafka-data:/var/lib/kafka/data \
  -e CLUSTER_ID=MkU3OEVBNTcwNTJENDM2Qk -e KAFKA_NODE_ID=1 \
  -e KAFKA_PROCESS_ROLES=broker,controller \
  -e KAFKA_LISTENERS=INTERNAL://0.0.0.0:19092,EXTERNAL://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093 \
  -e "KAFKA_ADVERTISED_LISTENERS=INTERNAL://localhost:19092,EXTERNAL://${public_ip}:9092" \
  -e KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=CONTROLLER:PLAINTEXT,INTERNAL:PLAINTEXT,EXTERNAL:PLAINTEXT \
  -e KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER -e KAFKA_INTER_BROKER_LISTENER_NAME=INTERNAL \
  -e KAFKA_CONTROLLER_QUORUM_VOTERS=1@localhost:9093 \
  -e KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1 -e KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1 \
  -e KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1 -e KAFKA_AUTO_CREATE_TOPICS_ENABLE=false \
  -e KAFKA_LOG_DIRS=/var/lib/kafka/data -e KAFKA_LOG_RETENTION_HOURS=24 \
  -e 'KAFKA_HEAP_OPTS=-Xms256m -Xmx512m' apache/kafka:3.9.1
for attempt in $(seq 1 90); do
  if docker exec -e 'KAFKA_HEAP_OPTS=-Xms32m -Xmx96m' eft-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:19092 --list; then break; fi
  sleep 2
done
for topic in eft.pagos.v1 eft.pagos.v1.DLT; do
  docker exec -e 'KAFKA_HEAP_OPTS=-Xms32m -Xmx96m' eft-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:19092 \
    --create --if-not-exists --topic "$topic" --partitions 3 --replication-factor 1
done
SCRIPT
chmod 750 /opt/eft-kafka/run.sh
cat > /etc/systemd/system/eft-kafka.service <<'UNIT'
[Unit]
Description=Kafka EFT en AWS Academy Learner Lab
After=docker.service network-online.target
Requires=docker.service
Wants=network-online.target
[Service]
Type=oneshot
RemainAfterExit=yes
ExecStart=/opt/eft-kafka/run.sh
ExecStartPost=/usr/sbin/shutdown -h +240
ExecStop=/usr/bin/docker stop eft-kafka
TimeoutStartSec=600
[Install]
WantedBy=multi-user.target
UNIT
systemctl daemon-reload
systemctl enable --now eft-kafka
