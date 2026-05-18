# MyPlans API Gateway

Punto de entrada único para todos los microservicios de MyPlans. Recibe
las peticiones del frontend, valida el JWT como primera línea de
defensa, propaga información del usuario a los servicios downstream y
ofrece una documentación Swagger consolidada.

## Cómo correr

```bash
cd api_gateway
mvn clean package -DskipTests
java -jar target/api_gateway-0.0.1-SNAPSHOT.jar
```

El gateway queda escuchando en `http://localhost:8095`.

Por defecto enruta hacia:
- **Auth** en `http://localhost:8090` (configurable con `AUTH_SERVICE_URI`)
- **Core** en `http://localhost:8081` (configurable con `CORE_SERVICE_URI`)

## Cambios aplicados sobre el código original

### 1. Ruta del Core añadida

El gateway original solo conocía el `auth-service`. Ahora también enruta
todos los endpoints del Core (`/api/v1/**`) y expone su documentación
en `/api-docs-core`.

### 2. Validación de JWT en el gateway

`JwtAuthenticationFilter` es un `GlobalFilter` reactivo que valida el
JWT antes de reenviar la petición al downstream. Esto:

1. **Rechaza temprano** lo inválido sin cargar tráfico inútil sobre
   auth/core.
2. **Devuelve mensajes específicos** según la causa del fallo (igual
   formato que auth y core):
   - Sin token → `"Debes iniciar sesión para acceder a este recurso"`
   - Token expirado → `"Tu sesión ha expirado. Por favor inicia sesión nuevamente"`
   - Token mal firmado → `"Token inválido. Por favor inicia sesión nuevamente"`
3. **Propaga información del usuario** en headers al downstream:
   - `X-User-Id` (id numérico)
   - `X-User-Email`
   - `X-User-Roles` (lista separada por comas)

   Útil para logging y auditoría sin que cada servicio tenga que
   re-parsear el JWT. Eso sí: auth y core **siguen validando el JWT
   por su cuenta** (defensa en profundidad: protege ante accesos
   directos que se salten el gateway o ante vulnerabilidades futuras).

**Endpoints públicos** (sin JWT):

```
/api/auth/login
/api/auth/register
/api/auth/reset-password
/api/auth/new-password
/api/auth/logout            (siempre 200 — evita loops de logout en el frontend)
/api-docs*                  (OpenAPI proxies)
/swagger-ui*                (UI consolidada)
/webjars/**
/v3/api-docs*
/actuator/health
```

### 3. Compatibilidad de firma JWT

`JwtAuthenticationFilter` usa `Decoders.BASE64.decode(secret)` → HS384,
exactamente igual que el Auth y el Core. Si los tres servicios no
coincidieran en el algoritmo, el gateway rechazaría todos los tokens
emitidos por el Auth.

### 4. Manejo centralizado de errores

`GatewayExceptionHandler` (un `ErrorWebExceptionHandler` reactivo)
captura todos los errores que no son del JwtAuthenticationFilter y
devuelve un body JSON consistente con auth y core:

```json
{
  "timestamp": "2026-05-17T21:30:00.123",
  "status": 503,
  "error": "Service Unavailable",
  "message": "El servicio no está disponible en este momento. Intenta más tarde"
}
```

Casos cubiertos:
- **404** — ruta que no coincide con ningún route configurado.
- **503** — microservicio downstream caído (ConnectException, IOException).
- **4xx/5xx genéricos** — ResponseStatusException reenviado.
- **Fallback 500** — oculta stack traces al frontend.

### 5. CORS programático

El `globalcors` del yml original tenía un bug:

```yaml
allowedOrigins: "http://localhost:3000, http://localhost:5173"
```

Esto se interpretaba como **un único origen literal con coma adentro**,
no como una lista — por lo tanto CORS no funcionaba para ningún origen
real. Se reemplazó por `CorsConfig.java` que lee los orígenes desde
una propiedad y los parte correctamente. El yml ahora vive en
`gateway.cors.allowed-origins` y acepta lista separada por comas:

```yaml
gateway:
  cors:
    allowed-origins: ${ALLOWED_ORIGINS:http://localhost:3000,http://localhost:5173}
```

### 6. Swagger consolidado

Un solo Swagger UI en `http://localhost:8095/swagger-ui.html` muestra
las APIs de **ambos** servicios, con selector arriba a la derecha
(Auth / Core). Los OpenAPI específicos se proxyean en:
- `/api-docs-auth` → auth `/api-docs`
- `/api-docs-core` → core `/api-docs`

### 7. Variables de entorno

Todos los hosts y secrets se pueden sobrescribir via env vars para
facilitar despliegue en Docker/K8s:

| Variable | Default | Descripción |
| -------- | ------- | ----------- |
| `SERVER_PORT` | `8095` | Puerto del gateway |
| `AUTH_SERVICE_URI` | `http://localhost:8090` | URL del microservicio Auth |
| `CORE_SERVICE_URI` | `http://localhost:8081` | URL del microservicio Core |
| `JWT_SECRET` | (hex de dev) | Secret compartido con auth y core |
| `ALLOWED_ORIGINS` | `localhost:3000,localhost:5173` | Orígenes permitidos por CORS |

### 8. Otros arreglos

- **pom.xml**: agregado `<parameters>true</parameters>` (mismo motivo
  que en auth y core), jjwt para validar, lombok, actuator.
- **Actuator health endpoint** público en `/actuator/health` (útil
  para Docker liveness probes y para que el frontend sepa si el
  gateway está vivo).

## Estructura de las rutas

| Path | Destino | Auth requerida |
| ---- | ------- | -------------- |
| `/api/auth/login` | Auth | ✗ |
| `/api/auth/register` | Auth | ✗ |
| `/api/auth/reset-password` | Auth | ✗ |
| `/api/auth/new-password` | Auth | ✗ |
| `/api/auth/logout` | Auth | ✗ |
| `/api/auth/me*` | Auth | ✓ |
| `/api/admin/**` | Auth | ✓ (admin) |
| `/api/v1/**` | Core | ✓ |
| `/api-docs-auth` | Auth `/api-docs` | ✗ |
| `/api-docs-core` | Core `/api-docs` | ✗ |
| `/swagger-ui.html` | Gateway (UI consolidada) | ✗ |
| `/actuator/health` | Gateway | ✗ |

## Flujo end-to-end

```
Frontend                Gateway              Auth/Core
   │                       │                     │
   ├──POST /login──────────►                     │
   │                       ├──forward───────────►│
   │                       │                     │
   │◄─────token + claims───┤◄────token───────────┤
   │  (id_usuario, roles)  │                     │
   │                       │                     │
   ├──GET /api/v1/planos───►                     │
   │   Authorization: …    │ JwtAuthFilter:      │
   │                       │  - valida firma     │
   │                       │  - lee id_usuario   │
   │                       │  - lee roles        │
   │                       │                     │
   │                       ├─forward + headers──►│
   │                       │  X-User-Id          │ Core valida JWT
   │                       │  X-User-Email       │ propio (defensa
   │                       │  X-User-Roles       │ en profundidad).
   │                       │                     │ Extrae id_usuario
   │                       │                     │ para idUsuarioIngreso.
   │                       │                     │
   │◄──200 + body──────────┤◄─────────────────────┤
```

## Defensa en profundidad

El JWT se valida **dos veces** en el flujo normal: una en el gateway y
otra en cada microservicio downstream. Esto se hace a propósito:

- **Gateway**: rechaza rápido lo malo (latencia baja, menos carga
  downstream) y propaga headers para logging.
- **Servicios**: siguen validando porque:
  1. Pueden ser accedidos directamente saltándose el gateway (en redes
     internas o con configuración mal hecha).
  2. Si una vulnerabilidad futura del gateway permite saltar la
     validación, los servicios siguen seguros.
  3. Los servicios necesitan el JWT entero para extraer claims como
     `id_usuario` con tipos correctos.

