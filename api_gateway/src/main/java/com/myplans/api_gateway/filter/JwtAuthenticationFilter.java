package com.myplans.api_gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Filtro global que valida el JWT en cada request al gateway antes de
 * que se reenvíe al microservicio downstream (auth o core).
 *
 * Comportamiento:
 *
 *  1. Para endpoints públicos (login, register, reset, swagger…),
 *     deja pasar sin validar.
 *  2. Para endpoints protegidos:
 *      - Si NO hay header Authorization Bearer → 401.
 *      - Si el token está expirado → 401 con mensaje específico.
 *      - Si la firma es inválida → 401 con mensaje específico.
 *      - Si el token es válido → propaga al downstream agregando
 *        headers X-User-Id, X-User-Email y X-User-Roles que el resto
 *        del sistema puede usar sin tener que re-parsear el JWT.
 *
 * IMPORTANTE: el secret se debe decodificar con BASE64 (no UTF-8
 * crudo) para emparejar la firma HS384 del microservicio Auth. Si
 * no, el gateway rechaza todos los tokens.
 *
 * Defensa en profundidad: aunque el gateway valide, los microservicios
 * downstream (auth y core) SIGUEN validando el JWT por su cuenta. Esto
 * protege contra:
 *  - Accesos directos a los servicios saltándose el gateway.
 *  - Vulnerabilidades futuras del gateway.
 */
@Component
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    /**
     * Rutas que NO requieren autenticación. Cualquier path que empiece
     * con uno de estos prefijos se deja pasar tal cual.
     *
     * IMPORTANT: aunque el endpoint /api/auth/logout devuelve 200 incluso
     * sin token, lo dejamos público para que el frontend siempre pueda
     * llamarlo sin temor a un 401 que dispare otro logout (loop).
     */
    private static final List<String> PUBLIC_PREFIXES = List.of(
            "/api/auth/login",
            "/api/auth/register",
            "/api/auth/reset-password",
            "/api/auth/new-password",
            "/api/auth/logout",
            "/api-docs",
            "/swagger-ui",
            "/swagger-resources",
            "/webjars",
            "/v3/api-docs",
            "/actuator/health",
            "/api/v1/worker/health"
    );

    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${jwt.secret}")
    private String secret;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().value();

        // Pre-flight CORS — el handler global de CORS de Spring Cloud
        // Gateway responde antes, pero por seguridad lo dejamos pasar
        // explícitamente aquí también.
        if (request.getMethod() != null && "OPTIONS".equalsIgnoreCase(request.getMethod().name())) {
            return chain.filter(exchange);
        }

        if (isPublic(path)) {
            return chain.filter(exchange);
        }

        String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return writeError(exchange, HttpStatus.UNAUTHORIZED,
                    "Debes iniciar sesión para acceder a este recurso");
        }

        String token = authHeader.substring(7).trim();

        try {
            Claims claims = parseClaims(token);

            // Propagamos info del usuario en headers para que los
            // microservicios downstream puedan leerla sin re-parsear
            // el JWT (siguen validándolo, pero esto evita doble trabajo
            // en logs, auditoría, etc.).
            Object idUsuario = claims.get("id_usuario");
            Object roles = claims.get("roles");

            ServerHttpRequest.Builder builder = request.mutate()
                    .header("X-User-Email", claims.getSubject() == null ? "" : claims.getSubject());

            if (idUsuario != null) {
                builder.header("X-User-Id", idUsuario.toString());
            }
            if (roles instanceof List<?> rolesList) {
                builder.header("X-User-Roles", String.join(",", rolesList.stream()
                        .map(Object::toString).toList()));
            }

            return chain.filter(exchange.mutate().request(builder.build()).build());

        } catch (ExpiredJwtException ex) {
            log.warn("JWT expirado en {}: {}", path, ex.getMessage());
            return writeError(exchange, HttpStatus.UNAUTHORIZED,
                    "Tu sesión ha expirado. Por favor inicia sesión nuevamente");
        } catch (SignatureException | MalformedJwtException | UnsupportedJwtException ex) {
            log.warn("JWT inválido en {}: {}", path, ex.getMessage());
            return writeError(exchange, HttpStatus.UNAUTHORIZED,
                    "Token inválido. Por favor inicia sesión nuevamente");
        } catch (JwtException ex) {
            log.warn("Error de JWT en {}: {}", path, ex.getMessage());
            return writeError(exchange, HttpStatus.UNAUTHORIZED,
                    "No se pudo validar tu sesión. Por favor inicia sesión nuevamente");
        } catch (Exception ex) {
            log.error("Error inesperado validando JWT en {}: {}", path, ex.getMessage());
            return writeError(exchange, HttpStatus.UNAUTHORIZED,
                    "No se pudo validar tu sesión. Por favor inicia sesión nuevamente");
        }
    }

    private boolean isPublic(String path) {
        for (String prefix : PUBLIC_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private Claims parseClaims(String token) {
        // BASE64 decode del secret — debe coincidir con cómo lo decodifica el Auth.
        byte[] keyBytes = Decoders.BASE64.decode(secret);
        SecretKey key = Keys.hmacShaKeyFor(keyBytes);
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private Mono<Void> writeError(ServerWebExchange exchange, HttpStatus status, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", LocalDateTime.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);

        byte[] bytes;
        try {
            bytes = mapper.writeValueAsBytes(body);
        } catch (Exception e) {
            bytes = ("{\"message\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8);
        }
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        // Antes del filtro de routing y de los otros filtros de Spring Cloud Gateway.
        // HIGHEST_PRECEDENCE + 1 deja espacio para filtros aún más prioritarios si fuera necesario.
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }
}
