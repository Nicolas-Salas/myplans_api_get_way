package com.myplans.api_gateway.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.cloud.gateway.support.NotFoundException;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.ConnectException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maneja errores que NO son del JwtAuthenticationFilter, devolviendo
 * un body JSON consistente con auth y core:
 *
 *  {
 *    "timestamp": "...",
 *    "status":    503,
 *    "error":     "Service Unavailable",
 *    "message":   "El servicio downstream no responde"
 *  }
 *
 * Casos cubiertos:
 *   - 404: ruta que no coincide con ningún route configurado.
 *   - 503: el microservicio downstream está caído (ConnectException).
 *   - 4xx / 5xx genéricos: ResponseStatusException reenviado.
 *   - Fallback: 500 sin filtrar stack traces al frontend.
 *
 * Tiene @Order alto (-2) para correr ANTES del DefaultErrorWebExceptionHandler
 * de Spring Boot.
 */
@Component
@Order(-2)
public class GatewayExceptionHandler implements ErrorWebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayExceptionHandler.class);

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        HttpStatus status;
        String message;

        if (ex instanceof NotFoundException) {
            // Servicio downstream marcado como NotFound por Gateway
            // (típicamente porque no hay ruta o el balanceo falló).
            status = HttpStatus.NOT_FOUND;
            message = "La ruta solicitada no existe o el servicio no está disponible";
        } else if (ex instanceof ResponseStatusException rse) {
            HttpStatus resolved = HttpStatus.resolve(rse.getStatusCode().value());
            status = (resolved != null) ? resolved : HttpStatus.INTERNAL_SERVER_ERROR;
            message = (rse.getReason() != null) ? rse.getReason() : status.getReasonPhrase();
        } else if (ex instanceof ConnectException
                || (ex.getCause() != null && ex.getCause() instanceof ConnectException)
                || ex instanceof IOException) {
            status = HttpStatus.SERVICE_UNAVAILABLE;
            message = "El servicio no está disponible en este momento. Intenta más tarde";
            log.warn("Servicio downstream caído: {}", ex.getMessage());
        } else {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
            message = "Ocurrió un error inesperado. Por favor intenta más tarde";
            log.error("Error no controlado en gateway: ", ex);
        }

        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            // El downstream ya empezó a escribir la respuesta; no podemos
            // reescribirla. Reportamos el error y dejamos que termine.
            return Mono.error(ex);
        }
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
            bytes = ("{\"message\":\"" + message + "\"}").getBytes();
        }
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }
}
