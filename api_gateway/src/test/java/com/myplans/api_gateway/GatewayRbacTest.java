package com.myplans.api_gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PS-001 — Sin token: 401 con JSON descriptivo
 * PS-002 — Token inventado: 401
 * PS-003 — Token expirado: 401 con mensaje en español
 * PS-004 — CORS: el header Access-Control-Allow-Origin está presente
 * PI-004 — Sin token en /api/v1/planos: bloqueado en Gateway
 * PI-006 — Sin token en /api/v1/historial: bloqueado en Gateway
 * PI-002 — Sin token en /api/auth/me: bloqueado en Gateway
 *
 * Complementa GatewayIntegrationTest.java (ya existente).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "AUTH_SERVICE_URI=http://localhost:1",
        "CORE_SERVICE_URI=http://localhost:1",
        "AUDIT_SERVICE_URI=http://localhost:1"
})
class GatewayRbacTest {

    @Autowired
    private WebTestClient webTestClient;

    // PI-002, PI-004, PI-006: distintas rutas protegidas bloqueadas sin token

    @Test
    void givenNoToken_whenAccessCore_thenReturn401WithDescriptiveJson() {
        webTestClient.get()
                .uri("/api/v1/planos")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentType("application/json")
                .expectBody()
                .jsonPath("$.status").isEqualTo(401)
                .jsonPath("$.message").isNotEmpty();
    }

    @Test
    void givenNoToken_whenAccessTags_thenReturn401() {
        webTestClient.get()
                .uri("/api/v1/tags/1")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.status").isEqualTo(401);
    }

    @Test
    void givenNoToken_whenAccessHistorial_thenReturn401() {
        webTestClient.get()
                .uri("/api/v1/historial")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.status").isEqualTo(401)
                .jsonPath("$.message").isNotEmpty();
    }

    @Test
    void givenNoToken_whenAccessAuthMe_thenReturn401() {
        webTestClient.get()
                .uri("/api/auth/me")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.status").isEqualTo(401);
    }

    @Test
    void givenNoToken_whenAccessReportes_thenReturn401() {
        webTestClient.get()
                .uri("/api/v1/reportes/plano/1/excel?statusExport=APROBADO")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // PS-002: Token inventado → 401

    @Test
    void givenFakeToken_whenAccess_thenReturn401() {
        webTestClient.get()
                .uri("/api/v1/planos")
                .header("Authorization", "Bearer token.inventado.falso")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.message")
                .value(msg -> assertThat(((String) msg).toLowerCase())
                        .containsAnyOf("token", "sesión", "inicia"));
    }

    @Test
    void givenRandomString_whenAccess_thenReturn401() {
        webTestClient.get()
                .uri("/api/v1/planos")
                .header("Authorization", "Bearer abc123xyz")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // PS-003: Token expirado → 401 con mensaje en castellano

    @Test
    void givenExpiredAdminToken_whenAccessCore_thenReturn401WithSpanishMessage() {
        String expired = TestJwtHelper.expiredToken("admin@test.com", 1, List.of("ROLE_ADMIN"));

        webTestClient.get()
                .uri("/api/v1/planos")
                .header("Authorization", "Bearer " + expired)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.message")
                .value(msg -> assertThat(((String) msg).toLowerCase())
                        .containsAnyOf("expirado", "sesión", "inicia"));
    }

    @Test
    void givenExpiredAuditorToken_whenAccessHistorial_thenReturn401() {
        String expired = TestJwtHelper.expiredToken("aud@test.com", 2, List.of("ROLE_AUDITOR"));

        webTestClient.get()
                .uri("/api/v1/historial")
                .header("Authorization", "Bearer " + expired)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.message")
                .value(msg -> assertThat(((String) msg).toLowerCase())
                        .containsAnyOf("expirado", "sesión", "inicia"));
    }

    // PS-004: CORS — preflight OPTIONS debe retornar headers correctos

    @Test
    void givenCorsPreflightFromFrontend_whenOptions_thenReturn200WithCorsHeaders() {
        webTestClient.options()
                .uri("/api/auth/login")
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "Content-Type,Authorization")
                .exchange()
                .expectStatus().value(status ->
                        assertThat(status).isNotEqualTo(401))
                .expectHeader()
                .value("Access-Control-Allow-Origin", origin ->
                        assertThat(origin).containsAnyOf("http://localhost:5173", "*"));
    }

    // Rutas públicas no deben requerir token

    @Test
    void givenNoToken_whenAccessLogin_thenNotBlocked() {
        webTestClient.post()
                .uri("/api/auth/login")
                .bodyValue("{\"email\":\"x@x.com\",\"password\":\"y\"}")
                .header("Content-Type", "application/json")
                .exchange()
                .expectStatus().value(status ->
                        assertThat(status).isNotEqualTo(401));
    }

    @Test
    void givenNoToken_whenAccessRegister_thenNotBlocked() {
        webTestClient.post()
                .uri("/api/auth/register")
                .bodyValue("{\"email\":\"test@test.com\",\"password\":\"TestPass123!\"}")
                .header("Content-Type", "application/json")
                .exchange()
                .expectStatus().value(status ->
                        assertThat(status).isNotEqualTo(401));
    }

    @Test
    void givenNoToken_whenAccessResetPassword_thenNotBlocked() {
        webTestClient.post()
                .uri("/api/auth/reset-password")
                .bodyValue("{\"email\":\"test@test.com\"}")
                .header("Content-Type", "application/json")
                .exchange()
                .expectStatus().value(status ->
                        assertThat(status).isNotEqualTo(401));
    }

    @Test
    void givenNoToken_whenLogout_thenNotBlocked() {
        webTestClient.post()
                .uri("/api/auth/logout")
                .exchange()
                .expectStatus().value(status ->
                        assertThat(status).isNotEqualTo(401));
    }

    // Token válido: Gateway propaga (downstream puede dar 5xx por estar apagado)

    @Test
    void givenValidAdminToken_whenAccessCore_thenGatewayPropagates() {
        String token = TestJwtHelper.validToken("admin@test.com", 1, List.of("ROLE_ADMIN"));

        webTestClient.get()
                .uri("/api/v1/planos")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().value(status ->
                        assertThat(status).isNotEqualTo(401));
    }

    @Test
    void givenValidAuditorToken_whenAccessHistorial_thenGatewayPropagates() {
        String token = TestJwtHelper.validToken("aud@test.com", 2, List.of("ROLE_AUDITOR"));

        webTestClient.get()
                .uri("/api/v1/historial")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().value(status ->
                        assertThat(status).isNotEqualTo(401));
    }

    @Test
    void givenValidUserToken_whenAccessPlanos_thenGatewayPropagates() {
        String token = TestJwtHelper.validToken("op@test.com", 3, List.of("ROLE_USER"));

        webTestClient.get()
                .uri("/api/v1/planos")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().value(status ->
                        assertThat(status).isNotEqualTo(401));
    }

    // Swagger / docs públicos

    @Test
    void givenNoToken_whenSwagger_thenAccessible() {
        webTestClient.get()
                .uri("/swagger-ui.html")
                .exchange()
                .expectStatus().value(status ->
                        assertThat(status).isNotEqualTo(401));
    }

    @Test
    void givenNoToken_whenApiDocs_thenAccessible() {
        webTestClient.get()
                .uri("/api-docs-auth")
                .exchange()
                .expectStatus().value(status ->
                        assertThat(status).isNotEqualTo(401));
    }
}
