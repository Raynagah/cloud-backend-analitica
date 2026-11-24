# Pedidos360 - Microservicio de Analítica y Recomendaciones (`ms-analytics`)

Microservicio de **analítica en tiempo real y recomendaciones** de la plataforma **Pedidos360**. Consume los eventos de comportamiento que publican los demás microservicios en **Apache Kafka**, los persiste en **Amazon DynamoDB**, calcula **recomendaciones de productos** y transmite **métricas en vivo por WebSocket** al dashboard de administración.

## 🛠️ Tecnologías Utilizadas

* **Lenguaje / Framework:** Java 17, Spring Boot 4.1.1 (`webmvc`, `websocket`)
* **Seguridad:** Spring Security OAuth2 Resource Server (JWT de Azure AD) + autorización por rol
* **Mensajería:** Apache Kafka (`spring-kafka`, consumidor)
* **Base de datos NoSQL:** Amazon DynamoDB (AWS SDK v2); **DynamoDB Local** en desarrollo
* **Tiempo real:** WebSocket nativo de Spring
* **Pruebas:** JUnit 5
* **Contenedorización:** Docker

## 🧩 Arquitectura

```
ms-producto ─┐
ms-carrito  ─┼─► Kafka (user-events) ─► ms-analytics ─┬─► DynamoDB (eventos, productos, co-vistas)
ms-pagos ────┘   (fraud-alerts)                       ├─► Métricas en memoria ──► WebSocket /ws/metrics
                                                       └─► Recomendaciones ──► REST (vía BFF)
```

### Eventos consumidos (tópico `user-events`)

`PRODUCTO_VISTO`, `CARRITO_ITEM_AGREGADO`, `CARRITO_ITEM_ELIMINADO`, `CARRITO_VACIADO`, `COMPRA_REALIZADA`. También escucha `fraud-alerts` (alertas del antifraude de ms-pagos).

### Recomendaciones

Combina tres señales, en este orden de prioridad:

1. **Co-visualización:** productos vistos por los mismos usuarios que vieron el producto actual.
2. **Similitud de precio:** productos de rango de precio cercano.
3. **Populares:** respaldo cuando no hay suficiente historial.

Nunca recomienda el producto que el usuario está mirando.

### Tablas de DynamoDB

Se crean automáticamente al iniciar (`<prefijo>` = `pedidos360_`): `pedidos360_analytics_eventos`, `pedidos360_analytics_productos` y `pedidos360_analytics_coview`.

## 📡 Endpoints

| Método | Ruta | Acceso | Descripción |
| :--- | :--- | :--- | :--- |
| `GET` | `/api/v1/analytics/recomendaciones?productoId=&max=` | Usuario autenticado | Recomendaciones para el usuario (vía BFF: `/api/v1/bff/analitica/recomendaciones`). |
| `GET` | `/api/v1/analytics/eventos/recientes?limite=` | Usuario autenticado | Historial reciente de navegación del propio usuario. |
| `GET` | `/api/v1/analytics/metricas` | **Solo `admin`** | Foto actual de las métricas (la misma que viaja por WebSocket). |
| `WS` | `/ws/metrics?token=<JWT>` | **Solo `admin`** | Métricas en vivo, emitidas cada 2 s. |

### Métricas en tiempo real (WebSocket)

Clics totales, usuarios activos, eventos por tipo, productos más vistos, clics por minuto y alertas de fraude recientes. El navegador no puede enviar cabeceras en un WebSocket, por lo que el JWT viaja como parámetro `?token=`.

> **Nota de arquitectura:** el WebSocket se conecta **directo** a este microservicio (el BFF no hace proxy de WebSockets). En AWS, API Gateway REST no soporta WebSockets: se debe exponer el puerto `8089` o usar CloudFront con `wss://`.

## 🔐 Seguridad y Roles

* Todos los endpoints REST exigen JWT válido de Azure AD. El handshake del WebSocket valida el token en `JwtHandshakeInterceptor`.
* **Las métricas son exclusivas para administradores.** El rol (`admin` / `cliente`) **no** se toma del token ni del navegador: este microservicio lo consulta a **ms-usuarios** (`GET /api/usuarios/me`) reenviando el mismo token. Ante cualquier error la respuesta es **fail closed** (acceso denegado: `403`).
* El veredicto se cachea `ANALYTICS_ADMIN_CACHE_SECONDS` segundos (60 por defecto) para no consultar en cada petición.

## ⚙️ Instalación y Ejecución

### Requisitos Previos

* Java 17 y Maven 3.9+
* Kafka accesible y ms-usuarios en ejecución
* DynamoDB Local (desarrollo) o credenciales de AWS (producción)
* Docker (opcional)

### Variables de Entorno

| Variable | Descripción | Valor por defecto |
| :--- | :--- | :--- |
| `AZURE_TENANT_ID` | ID del inquilino de Azure AD | `78b145ef-56b9-4397-b87c-27b242a9fce5` |
| `KAFKA_HOST` | Servidor Kafka (`host:puerto`) | `localhost:9092` |
| `USUARIOS_URL` | URL de ms-usuarios (fuente de verdad del rol) | `http://localhost:8081` |
| `DYNAMODB_ENDPOINT` | Endpoint de DynamoDB Local. **Vacío = AWS real** | *(vacío)* |
| `AWS_REGION` | Región de DynamoDB | `us-east-1` |
| `DYNAMODB_TABLE_PREFIX` | Prefijo de las tablas | `pedidos360_` |
| `ANALYTICS_ALLOWED_ORIGINS` | Orígenes del frontend permitidos (REST y WebSocket), separados por coma | `http://localhost,http://localhost:5173,...` |
| `ANALYTICS_WS_REQUIRE_AUTH` | `true` exige JWT y rol admin. `false` solo para demos locales | `true` |
| `ANALYTICS_ADMIN_CACHE_SECONDS` | Segundos de caché del veredicto de rol | `60` |

> **En AWS real:** dejar `DYNAMODB_ENDPOINT` vacío y dar acceso a DynamoDB mediante un **rol IAM** en la EC2 (o `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY`). `KAFKA_HOST` y `USUARIOS_URL` deben usar las **IP privadas** de sus máquinas, con los security groups abiertos entre ellas.

### Ejecución Local

1. **Levantar DynamoDB Local:**

```bash
docker run -d --name dynamodb-local -p 8000:8000 amazon/dynamodb-local
```

2. **Iniciar el servicio:**

```bash
DYNAMODB_ENDPOINT=http://localhost:8000 mvn spring-boot:run
```

Disponible en `http://localhost:8089` (health: `/actuator/health`).

### Pruebas Unitarias

```bash
mvn test
```

### Despliegue con Docker

```bash
docker build -t pedidos360/ms-analytics:v1 .

docker run -d \
  --name ms-analytics \
  -p 8089:8089 \
  -e KAFKA_HOST="<IP_PRIVADA_KAFKA>:9092" \
  -e USUARIOS_URL="http://<IP_PRIVADA_USUARIOS>:8081" \
  -e AWS_REGION="us-east-1" \
  -e ANALYTICS_ALLOWED_ORIGINS="https://<URL_PUBLICA_FRONTEND>" \
  pedidos360/ms-analytics:v1
```

---

## 🔗 Ecosistema de Repositorios

### Frontend

* [Frontend React](https://github.com/Raynagah/cloud-frontend.git)

### Backend

* [BFF Orchestrator](https://github.com/Raynagah/cloud-backend-bff)
* [Microservicio Producto](https://github.com/Raynagah/cloud-backend-producto)
* [Microservicio Carrito](https://github.com/Raynagah/cloud-backend-carrito)
* [Microservicio Usuarios](https://github.com/NBello26/ms-usuarios-cloud.git)
* [Microservicio Base de Datos](https://github.com/NBello26/ms-bd-cloud)
* [Microservicio Ordenes](https://github.com/Raynagah/cloud-backend-ordenes)
* [Microservicio Notificaciones](https://github.com/Raynagah/cloud-backend-notificaciones)
* [Microservicio Despacho](https://github.com/Raynagah/cloud-backend-despacho)
* [Microservicio Pago](https://github.com/Raynagah/cloud-backend-pago)
* [Microservicio Analítica (Este repositorio)](https://github.com/Raynagah/cloud-backend-analitica)