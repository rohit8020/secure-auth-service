package com.rohit8020.authservice.security;

import com.rohit8020.authservice.entity.User;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class JwtUtil {

    private final PrivateKey privateKey;
    private final PublicKey publicKey;
    private final String keyId;
    private final String issuer;

    private final long accessExpiration;
    private final long refreshExpiration;
    private final long clientAccessExpiration;

    public JwtUtil(
            @Value("${jwt.private-key}") String privateKey,
            @Value("${jwt.public-key}") String publicKey,
            @Value("${jwt.key-id}") String keyId,
            @Value("${jwt.issuer}") String issuer,
            @Value("${jwt.expiration}") long accessExpiration,
            @Value("${jwt.refresh-expiration}") long refreshExpiration,
            @Value("${jwt.client-expiration}") long clientAccessExpiration) {

        this.privateKey = parsePrivateKey(privateKey);
        this.publicKey = parsePublicKey(publicKey);
        this.keyId = keyId;
        this.issuer = issuer;
        this.accessExpiration = accessExpiration;
        this.refreshExpiration = refreshExpiration;
        this.clientAccessExpiration = clientAccessExpiration;
    }

    public String generateToken(User user) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", user.getId());
        claims.put("role", user.getRole().name());
        claims.put("token_use", "user");
        return Jwts.builder()
                .setClaims(claims)
                .setSubject(user.getUsername())
                .setIssuer(issuer)
                .setHeaderParam("kid", keyId)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + accessExpiration))
                .signWith(privateKey, SignatureAlgorithm.RS256)
                .compact();
    }

    public String generateClientToken(String clientId, String scope) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("scope", scope);
        claims.put("client_id", clientId);
        claims.put("token_use", "client");
        return Jwts.builder()
                .setClaims(claims)
                .setSubject(clientId)
                .setIssuer(issuer)
                .setHeaderParam("kid", keyId)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + clientAccessExpiration))
                .signWith(privateKey, SignatureAlgorithm.RS256)
                .compact();
    }

    public Date refreshTokenExpiry() {
        return new Date(System.currentTimeMillis() + refreshExpiration);
    }

    public long getClientAccessExpiration() {
        return clientAccessExpiration;
    }

    public Claims extractClaims(String token) {
        return Jwts.parser()
                .verifyWith(publicKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public String extractUsername(String token) {
        return extractClaims(token).getSubject();
    }

    public long getAccessExpiration() {
        return accessExpiration;
    }

    public JWKSet jwkSet() {
        RSAKey key = new RSAKey.Builder((RSAPublicKey) publicKey)
                .keyID(keyId)
                .algorithm(JWSAlgorithm.RS256)
                .build();
        return new JWKSet(key);
    }

    private PrivateKey parsePrivateKey(String value) {
        try {
            byte[] decoded = Base64.getDecoder().decode(normalize(value));
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(decoded));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to parse RSA private key", ex);
        }
    }

    private PublicKey parsePublicKey(String value) {
        try {
            byte[] decoded = Base64.getDecoder().decode(normalize(value));
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(decoded));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to parse RSA public key", ex);
        }
    }

    private String normalize(String value) {
        return value.replaceAll("\\s+", "");
    }
}
