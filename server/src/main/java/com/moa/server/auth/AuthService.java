package com.moa.server.auth;

import com.moa.server.api.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private final JdbcTemplate db;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    public AuthService(JdbcTemplate db, Clock clock) { this.db = db; this.clock = clock; }

    public record LoginResult(String token, Instant expiresAt, FamilyMember member) { }
    public record Invitation(String memberId, String code, Instant expiresAt) { }

    public List<FamilyMember> members() {
        return db.query("SELECT id, display_name FROM members ORDER BY rowid",
                (row, index) -> new FamilyMember(row.getString(1), row.getString(2)));
    }

    @Transactional
    public Invitation invite(String memberId) {
        if (members().stream().noneMatch(member -> member.id().equals(memberId))) {
            throw new IllegalArgumentException("Unknown member. Use me, sister, brother-in-law, mom, or dad.");
        }
        String code = randomToken(18).toUpperCase(Locale.ROOT);
        Instant expires = clock.instant().plus(Duration.ofDays(7));
        db.update("DELETE FROM invites WHERE member_id = ?", memberId);
        db.update("INSERT INTO invites VALUES (?, ?, ?)", hash(code), memberId, expires.toEpochMilli());
        return new Invitation(memberId, code, expires);
    }

    @Transactional
    public LoginResult login(String suppliedCode) {
        String code = suppliedCode.replaceAll("\\s", "").toUpperCase(Locale.ROOT);
        var candidates = db.query("""
                SELECT m.id, m.display_name FROM invites i JOIN members m ON m.id = i.member_id
                WHERE i.code_hash = ? AND i.expires_at > ?
                """, (row, index) -> new FamilyMember(row.getString(1), row.getString(2)),
                hash(code), clock.millis());
        if (candidates.isEmpty()) throw new ApiException(HttpStatus.UNAUTHORIZED, "초대코드가 올바르지 않거나 만료됐어요.");
        if (db.update("DELETE FROM invites WHERE code_hash = ?", hash(code)) != 1) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "이미 사용한 초대코드예요.");
        }
        String token = randomToken(32);
        Instant expires = clock.instant().plus(Duration.ofDays(90));
        FamilyMember member = candidates.getFirst();
        db.update("DELETE FROM sessions WHERE expires_at <= ?", clock.millis());
        db.update("INSERT INTO sessions VALUES (?, ?, ?)", hash(token), member.id(), expires.toEpochMilli());
        return new LoginResult(token, expires, member);
    }

    public Optional<FamilyMember> authenticate(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) return Optional.empty();
        return db.query("""
                SELECT m.id, m.display_name FROM sessions s JOIN members m ON m.id = s.member_id
                WHERE s.token_hash = ? AND s.expires_at > ?
                """, (row, index) -> new FamilyMember(row.getString(1), row.getString(2)),
                hash(token), clock.millis()).stream().findFirst();
    }

    public void logout(String token) { db.update("DELETE FROM sessions WHERE token_hash = ?", hash(token)); }

    @Transactional
    public void rename(String memberId, String displayName) {
        String name = displayName.strip();
        if (name.isEmpty() || name.length() > 20 || name.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Display name must be 1 to 20 characters.");
        if (db.update("UPDATE members SET display_name = ? WHERE id = ?", name, memberId) != 1) throw new IllegalArgumentException("Unknown member");
    }

    @Transactional
    public void revoke(String memberId) {
        if (members().stream().noneMatch(member -> member.id().equals(memberId))) throw new IllegalArgumentException("Unknown member");
        db.update("DELETE FROM sessions WHERE member_id = ?", memberId);
        db.update("DELETE FROM invites WHERE member_id = ?", memberId);
    }

    private String randomToken(int bytes) {
        byte[] value = new byte[bytes];
        random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    public static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
