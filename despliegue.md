# Desplegar Kafka en AWS Academy Learner Lab

Solo Kafka se instala en AWS. Batch, bases de datos, microservicios y BFF permanecen en Docker local. El despliegue y la prueba remota están pendientes de ejecución manual.

## 1. Entrar al laboratorio de Duoc

1. Abrir AWS Academy de Duoc → Learner Lab → **Start Lab**.
2. Esperar el indicador verde y abrir **AWS** desde el propio laboratorio.
3. Elegir **N. Virginia / us-east-1**. Comprobar que es la sesión del laboratorio, no una cuenta personal.

Se utiliza el crédito educativo del laboratorio; no se afirma que EC2 sea gratis fuera de él. Si el laboratorio no admite una opción o pide un método de pago, detenerse y revisar sus restricciones, sin cambiar a una cuenta personal.

## 2. Crear una instancia

Entrar a **EC2 → Instancias → Lanzar instancias** y establecer:

| Campo | Valor |
|---|---|
| Nombre | `eft-kafka` |
| Imagen | Amazon Linux 2023 AMI de Amazon, x86_64, estándar |
| Tipo | `t3.small` (2 GiB), si está permitido por el laboratorio |
| Par de claves | Continuar sin par de claves; se usará Session Manager |
| VPC / subred | VPC predeterminada y una subred pública predeterminada |
| IP pública automática | Habilitada |
| Almacenamiento | 12 GiB gp3; resto de valores predeterminados |
| Detalles avanzados → Perfil de instancia IAM | `LabInstanceProfile`, existente en el laboratorio |
| Detalles avanzados → Especificación de créditos | Standard / Estándar, desactivar Unlimited si aparece |
| Número de instancias | 1 |

Si `LabInstanceProfile` no aparece, no crear roles nuevos: revisar la guía del laboratorio antes de continuar. El perfil debe permitir Systems Manager para conectar sin SSH.

### Grupo de seguridad

Crear `eft-kafka-sg` con una sola regla de entrada:

- Tipo: **TCP personalizado**.
- Puerto: **9092**.
- Origen: **Mi IP**, una dirección `/32`.

Quitar cualquier regla SSH creada automáticamente. Mantener la salida predeterminada, necesaria para descargar Docker/Kafka y conectar a Systems Manager. No abrir 9092 a `0.0.0.0/0`. No abrir 9093 ni 19092. La demostración usa datos sintéticos y Kafka PLAINTEXT restringido a esa IP; OAuth2 protege las APIs locales, no el protocolo Kafka.

### Datos de usuario

En **Detalles avanzados → Datos de usuario**, pegar el contenido completo de [infra/aws/iniciar-kafka.sh](infra/aws/iniciar-kafka.sh), desde `#!/bin/bash` hasta el final. Pegar como texto, sin marcar que ya está codificado en base64.

El script instala Docker, inicia Kafka 3.9.1 con volumen persistente, configura la IP anunciada y crea `eft.pagos.v1` y `eft.pagos.v1.DLT` con tres particiones y réplica 1. Programa una detención de la instancia cuatro horas después de cada arranque. No crea otros recursos AWS.

Revisar los valores y pulsar **Lanzar instancia**. Esperar que esté **En ejecución**, pasen las comprobaciones de estado y termine la instalación (varios minutos).

## 3. Comprobar Kafka dentro de EC2

Seleccionar `eft-kafka` → **Conectar → Session Manager → Conectar**. Si aún no está disponible, esperar unos minutos y comprobar `LabInstanceProfile`, la IP pública y la salida de red; no abrir SSH a Internet como solución automática.

En la terminal de la instancia ejecutar:

```bash
sudo cloud-init status --wait
sudo systemctl status eft-kafka --no-pager
sudo docker ps --filter name=eft-kafka
sudo docker exec -e 'KAFKA_HEAP_OPTS=-Xms32m -Xmx96m' eft-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:19092 --describe
```

Esperar `active (exited)` en el servicio de instalación, contenedor `Up` y ambos tópicos con `PartitionCount: 3` y `ReplicationFactor: 1`. `active (exited)` es normal: el servicio inicia Docker y finaliza, mientras Kafka permanece ejecutándose.

Si falla:

```bash
sudo tail -n 80 /var/log/eft-kafka-inicio.log
sudo docker logs --tail 80 eft-kafka
```

Copiar la **IPv4 pública** desde los detalles de EC2.

## 4. Conectar el proyecto local

En Windows, abrir Docker Desktop y PowerShell dentro del proyecto. Si es una instalación nueva, ejecutar primero el inicio de [instrucciones.md](instrucciones.md). Sustituir `IP_PUBLICA` en los siguientes comandos:

```powershell
Test-NetConnection IP_PUBLICA -Port 9092
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/conectar-kafka-lab.ps1 -DireccionBroker IP_PUBLICA:9092
docker compose -f compose.yaml -f compose.servicios.yaml -f compose.canales.yaml -f compose.kafka.yaml stop kafka
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verificar-kafka-lab.ps1 -DireccionBroker IP_PUBLICA:9092
```

Debe aparecer `TcpTestSucceeded: True`. El script de conexión configura pagos y auditoría para usar el broker remoto. Se detiene Kafka local antes de comprobar el evento: así no se confunden ambas conexiones. La prueba exige esa configuración, crea una operación sintética y verifica publicación, consumo, outbox confirmado y repetición sin doble depósito. Sus resultados quedan en `evidencias/aws/`.

La salud HTTP de los servicios no basta para dar el despliegue por aprobado; la prueba de evento debe terminar sin errores. No ejecutar los scripts de inicio ni `verificar-eventos.ps1` durante esta comprobación, porque están destinados al broker local.

Auditoría distingue las posiciones de Kafka con `KAFKA_SOURCE`: `local` para Docker y `aws-lab` para este laboratorio. Mantener ese identificador al reiniciar el mismo broker; usar uno distinto si se reemplaza por un clúster nuevo sin su historial. Las entregas anteriores se conservan como locales al migrar a `audit_delivery_v2`. Si el evento no aparece, revisar también el outbox y `eft.pagos.v1.DLT`; un rechazo de auditoría no implica un fallo de red.

Si no conecta, comprobar que la IP de EC2 siga siendo la misma, que **Mi IP** corresponda a la conexión actual de Windows, que la regla sea TCP 9092 y que Kafka esté `Up`. Reiniciar EC2 puede cambiar su IP pública: el script actualiza la dirección anunciada al arrancar; repetir el comando de conexión local con la nueva IP.

## 5. Demostrar recuperación de un evento

Después de aprobar la prueba anterior:

1. En Session Manager: `sudo docker stop eft-kafka`.
2. En Windows, iniciar la comprobación con espera ampliada:

   ```powershell
   powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verificar-kafka-lab.ps1 -DireccionBroker IP_PUBLICA:9092 -EsperarRecuperacion
   ```

3. Esperar el mensaje que confirma pago completado y evento pendiente.
4. En Session Manager: `sudo docker start eft-kafka`.
5. La comprobación espera hasta tres minutos y debe detectar el evento, confirmar el outbox y verificar que el saldo no se duplicó.

Guardar las dos ejecuciones y capturas del broker/tópicos para el informe y el video. No capturar credenciales, tokens ni detalles privados de la cuenta. No afirmar esta prueba como completada hasta ejecutarla.

## 6. Terminar la demostración

En EC2 seleccionar la instancia → **Estado de la instancia → Detener instancia**; confirmar que llegue a **Detenida**. Después finalizar la sesión en Learner Lab según sus instrucciones. El volumen conserva los datos; detener una instancia no elimina su almacenamiento. No dejar el broker encendido al terminar. El apagado automático a las cuatro horas es un respaldo.

Para volver a Kafka local:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/iniciar-reportes.ps1 -SinConstruir
```

## Referencias

- [AWS: Session Manager, conexión sin puertos de entrada ni claves SSH](https://docs.aws.amazon.com/systems-manager/latest/userguide/session-manager.html).
- [AWS: permisos del perfil de instancia para Session Manager](https://docs.aws.amazon.com/systems-manager/latest/userguide/session-manager-getting-started-instance-profile.html).
- [AWS: modo Unlimited y créditos de CPU](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/burstable-performance-instances-unlimited-mode.html).
- [Apache Kafka 3.9: imagen Docker y comandos de tópicos](https://kafka.apache.org/39/getting-started/quickstart/).
