# Instrucciones de ejecución y pruebas

## Requisitos

- Docker Desktop con contenedores Linux y Docker Compose 2.24.4 o posterior, necesario para `!override`.
- Windows PowerShell 5.1 o PowerShell 7 para los scripts de inicio y verificación HTTP.
- Java 17 o posterior y Maven 3.9+ para ejecutar la suite y Batch fuera de Docker.
- PowerShell 7 (`pwsh`) para `verificar-semana3.ps1`, que utiliza `Path.GetRelativePath`.
- Acceso a Internet para descargar imágenes y dependencias la primera vez.

Clonar el repositorio y abrir su carpeta:

```powershell
git clone https://github.com/AgustinG1/fin-legacy-eft-s9.git
cd fin-legacy-eft-s9
```

## 1. Iniciar todos los componentes locales

Abrir Docker Desktop y ejecutar:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/iniciar-reportes.ps1
```

Este script inicia, en orden, PostgreSQL, autenticación, servicios de negocio, Config Server/Eureka, BFF, Kafka, auditoría, Batch y reportes. Crea tópicos y bases/usuarios separados; reportes recibe un usuario con permisos de lectura. Genera `.env`, claves RSA de firma y tres certificados HTTPS persistentes localmente. No publicar esos archivos ni la salida completa de `docker compose config`.

Las ejecuciones posteriores conservan datos y credenciales. `-SinConstruir` reutiliza imágenes existentes: usarlo solo cuando no se modificó el código. El inicio local restaura Kafka local; no ejecutarlo durante una comprobación del broker remoto.

| Componente | Dirección local |
|---|---|
| OAuth2 | http://localhost:9100 |
| Clientes / cuentas / pagos | http://localhost:8101 / 8102 / 8103 |
| Auditoría / reportes | http://localhost:8104 / 8105 |
| BFF web / móvil / cajero | https://localhost:8201 / https://localhost:8202 / https://localhost:8203 |
| Config Server / Eureka | http://localhost:8888 / 8761 |
| PostgreSQL | localhost:5433 |

Los BFF aceptan TLS 1.2/1.3 y rechazan HTTP. `scripts/preparar-https.ps1`, llamado automáticamente al iniciar canales, utiliza `keytool` en Docker para crear un PKCS12 por BFF con RSA de 3072 bits, SAN `localhost`/`127.0.0.1`/nombre del servicio y vigencia de 365 días. La contraseña se genera en `.env`; los almacenes se montan en solo lectura desde `runtime/tls`. Las ejecuciones posteriores reutilizan las claves.

Las comprobaciones confían exclusivamente en el certificado público local correspondiente al puerto y verifican el nombre del servidor y las fechas. No desactivan globalmente la validación TLS ni agregan certificados al almacén de Windows. Un navegador puede advertir que el certificado es autofirmado: para la demostración utiliza los scripts, que tienen confianza explícita. La autenticación OAuth2 y las conexiones internas al backend permanecen por HTTP en el entorno local; no se afirma cifrado de extremo a extremo.

Son APIs, no interfaces gráficas. Las rutas de negocio requieren token; abrirlas directamente en el navegador puede devolver 401. Kafka local solo escucha dentro de la red Docker.

## 2. Compilar y ejecutar pruebas automatizadas

```powershell
mvn -B verify
```

Resultado de referencia: 62 pruebas aprobadas. Incluyen reglas Batch, rollback, reinicio desde checkpoint, rangos sin filas perdidas/repetidas, transacciones contables, idempotencia, permisos, resiliencia, outbox, auditoría y reportes.

## 3. Comprobar la integración local

Ejecutar secuencialmente, con los contenedores activos:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verificar-servicios.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verificar-https.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verificar-canales.ps1 -ProbarCaida -ProbarBalanceo
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verificar-eventos.ps1 -ProbarCaida
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verificar-reportes.ps1
```

Se crean clientes, cuentas y operaciones sintéticos. Las pruebas de fallos interrumpen y restauran temporalmente servicios locales. No ejecutar simultáneamente ni durante la demostración AWS. Los CSV originales permanecen intactos. Los resultados se generan en `evidencias/` y los logs en `logs/`, ambos excluidos de Git.

HTTPS comprueba 12 condiciones: certificado esperado, autenticación obligatoria, rechazo de HTTP y rechazo de certificado distinto en cada canal. Guarda `evidencias/https/verificacion.csv` y las huellas públicas en `certificados.csv`. Las comprobaciones de canales guardan sus resultados en `evidencias/canales-https/`, y las de reportes en `evidencias/semana3/reportes-https/`.

Reportes comprueba 35 condiciones: conteos, paginación, acceso a los tres procesos mediante BFF web, autorización y prohibición de escritura en la base. Las pruebas de Kafka comprueban publicación, reentrega, DLT y recuperación de un evento pendiente sin un segundo movimiento contable.

## 4. Ejecutar Batch y comprobar semana 3

```powershell
java -jar batch/target/batch-0.1.0-SNAPSHOT.jar --spring.profiles.active=demo
pwsh -NoProfile -File scripts/verificar-semana3.ps1
```

El primer comando usa H2 persistente en `runtime/`. El segundo requiere Java, el JAR compilado, `.env` y PostgreSQL local iniciado; compara H2 con uno/tres trabajadores y PostgreSQL. Los 14 CSV deben coincidir, normalizando saltos de línea.

La entrada predeterminada es `data/semana_3`: tres archivos de 1.000 filas. Cada job tiene cuatro rangos de 250 filas, lectores independientes, bloques transaccionales y hasta tres trabajadores. Los límites no cambian al variar la cantidad de trabajadores, para mantener los checkpoints. Los reportes se escriben por cursor.

| Job | Archivo | Entradas | Únicos | Rechazos | Duplicados |
|---|---|---:|---:|---:|---:|
| movimientosJob | movimientos_financieros_diarios.csv | 1000 | 392 | 608 | 0 |
| interesesJob | intereses_trimestrales.csv | 1000 | 421 | 578 | 1 |
| estadosJob | estados_financieros_anuales.csv | 1000 | 304 | 696 | 0 |

Debe cumplirse `entradas = únicos + rechazos + duplicados`. `COMPLETED` significa que todas las filas fueron tratadas, no que todas fueran válidas.

En H2 los reportes quedan en `output/<proceso>/<dataset>/`: `control.csv`, `procesados.csv`, `rechazos.csv`, `duplicados.csv` y, para diarios/anuales, `resumen.csv`. En Docker se guardan en el volumen `eft-reports` y se consultan mediante reportes/BFF.

El perfil `demo` usa tasa efectiva del período `0.05`, etiqueta `TRIMESTRAL-DEMO` y redondeo HALF_UP a dos decimales. Son supuestos de prueba. Para otra regla, establecer tasa/período en `.env` para Docker o utilizar `--eft.interest-rate=VALOR --eft.interest-period=PERIODO` en Java. No se convierte una tasa anual ni se modifican saldos operativos.

### Reglas y recuperación

Identificadores positivos y montos de hasta dos decimales. Fechas reales en `aaaa-MM-dd`, `aaaa/MM/dd`, `dd-MM-aaaa` o `dd/MM/aaaa`. Diarios admite débito/crédito con monto positivo. Intereses exige saldo no negativo, titular, edad entre 18 y 100 y tipo válido. Anuales conserva depósitos positivos y retiros/compras/pagos negativos, con descripción. Los duplicados se comparan mediante campos de negocio normalizados; cada rechazo conserva su origen y motivo.

Las excepciones temporales de base de datos tienen reintentos con espera creciente. Si se agotan, el coordinador admite hasta dos reinicios adicionales del job recuperable con los mismos parámetros. Errores permanentes, como una cabecera incorrecta, finalizan sin reintentos inútiles. Una interrupción abrupta puede dejar metadatos `STARTED`/`UNKNOWN`: se debe comprobar que no exista otro proceso activo antes de recuperar ese estado.

Repetir el comando normal reconoce jobs completados. Para reprocesar sin duplicar filas de origen:

```powershell
java -jar batch/target/batch-0.1.0-SNAPSHOT.jar --spring.profiles.active=demo --eft.replay=revision-2
```

No modificar archivos ni ejecutar dos reprocesamientos del mismo conjunto simultáneamente. `scripts/medir-batch.ps1` compara tiempos; incluye arranque y reportes, por lo que no constituye una prueba de capacidad en producción.

## 5. Contratos y seguridad

Los scripts solicitan tokens a `POST /oauth2/token` mediante `client_credentials`, sin guardarlos en las evidencias. Las credenciales de operador, consulta y canales se generan en `.env`. Los servicios validan firma, emisor, audiencia y scopes; los BFF comprueban la propiedad de las cuentas de la identidad sintética firmada.

| API | Rutas principales | Permisos |
|---|---|---|
| Clientes | `/api/clientes`, `/api/clientes/{id}` | `clientes.read`, `clientes.write` |
| Cuentas | `/api/cuentas`, `/api/cuentas/{id}` | `cuentas.read`, `cuentas.write` |
| Pagos | `/api/pagos`, `/api/pagos/{id}` | `pagos.read`, `pagos.write` |
| Web | `/api/web/perfil`, `/api/web/pagos` | `web.read`, `web.write` |
| Móvil | `/api/movil/resumen`, `/api/movil/pagos` | `movil.read`, `movil.write` |
| Cajero | `/api/cajero/cuentas/{id}/saldo`, `/api/cajero/cuentas/{id}/retiros` | `cajero.read`, `cajero.write` |
| Reportes | `/api/reportes/resumen`, `/api/reportes/{dataset}/{kind}` | `reportes.read` |
| Migración web | `/api/web/migracion/resumen`, `/api/web/migracion/{dataset}/{kind}` | `web.migracion` |
| Auditoría | `/api/auditoria/{id}` | `auditoria.read` |

Reportes acepta `kind=DAILY`, `INTEREST` o `ANNUAL` y paginación `?pagina=0&tamano=20` (máximo 100). Solo presenta la última ejecución completada de cada conjunto; una ejecución posterior fallida/incompleta impide presentar el resultado anterior como vigente. Los clientes ordinarios de canal no tienen el permiso administrativo de migración.

Config Server y Eureka requieren la credencial de infraestructura. Las consultas de canal aplican circuit breaker y hasta tres intentos ante fallos temporales; los errores de negocio y las mutaciones no se reintentan indiscriminadamente. Pagos conserva su recuperación por ID y outbox. Auditoría deduplica mensajes por ID y contenido; los inválidos se envían a DLT.

## 6. Kafka remoto y apagado

Seguir [despliegue.md](despliegue.md). El cambio remoto afecta a pagos y auditoría. Las APIs, OAuth2 y todas las bases permanecen locales.

Para detener los componentes locales conservando volúmenes:

```powershell
docker compose -f compose.yaml -f compose.servicios.yaml -f compose.canales.yaml -f compose.kafka.yaml -f compose.reportes.yaml stop
```

La integración con Kafka en AWS se verificó el 9 de octubre de 2026, con 12 comprobaciones normales y 13 de recuperación. La caída real de PostgreSQL no se ensayó; su recuperación se verifica mediante fallos controlados en las pruebas Batch. La demostración no implementa inicio de sesión interactivo, TLS en las conexiones internas ni alta disponibilidad del broker único.
