package com.myplans.api_gateway;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.List;
import java.util.Map;

public final class TestJwtHelper {

    public static final String SECRET = "404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970";

    private TestJwtHelper() {
    }

    public static String validToken(String email, Integer idUsuario, List<String> roles) {
        SecretKey key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET));
        return Jwts.builder()
                .subject(email)
                .claims(Map.of("id_usuario", idUsuario, "roles", roles))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 3_600_000L))
                .signWith(key)
                .compact();
    }

    public static String expiredToken(String email, Integer idUsuario, List<String> roles) {
        SecretKey key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET));
        return Jwts.builder()
                .subject(email)
                .claims(Map.of("id_usuario", idUsuario, "roles", roles))
                .issuedAt(new Date(System.currentTimeMillis() - 7_200_000L))
                .expiration(new Date(System.currentTimeMillis() - 3_600_000L))
                .signWith(key)
                .compact();
    }
}