package com.hms.shared.web.idempotency;

import com.hms.shared.domain.BusinessRuleException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code Idempotency-Key} handling shared by invoice issue, payment and refund creation (plan ruling R5, api-design.md
 * section 1). One append-only row per (organization, endpoint, key) in {@code hms.idempotency_record} holds the hash
 * of the request and the successful response.
 *
 * <p>{@link #execute} runs inside the caller's business transaction (the application service's), so the record and
 * the business change commit or roll back together: a failed request stores nothing and may be retried with the same
 * key. A transaction-scoped advisory lock on (organization, endpoint, key) serialises concurrent requests with the same
 * key; the second one waits, then replays the first's committed response. Take it first, before the business locks.
 *
 * <p>{@code endpoint} is the route template (e.g. {@code "POST /api/v1/invoices/{id}/issue"}); the request hash
 * ({@link #hash}) covers the path ids and body, so the same key sent for another resource or body is a 422
 * {@code IDEMPOTENCY_KEY_REUSED}.
 */
@Component
public class IdempotencyService {

    public static final String HEADER = "Idempotency-Key";
    static final int MAX_KEY_LENGTH = 200;

    /** The response to send; {@code replayed} is true when it came from the stored record. */
    public record Result<T>(int status, T body, boolean replayed) {
    }

    private record Stored(String requestHash, int status, String body) {
    }

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Clock clock;

    IdempotencyService(JdbcClient jdbc, JsonMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    /** 422 VALIDATION_ERROR when the header is missing, blank, too long or not printable ASCII. */
    public static String requireKey(String key) {
        if (key == null || key.isBlank() || key.length() > MAX_KEY_LENGTH || !key.chars().allMatch(c -> c > 32
                && c < 127)) {
            throw new BusinessRuleException("VALIDATION_ERROR", "The Idempotency-Key header is required.",
                    Map.of("errors", List.of(Map.of("field", HEADER, "code", "REQUIRED",
                            "message", "Idempotency-Key must be 1 to " + MAX_KEY_LENGTH
                                    + " printable characters without spaces."))));
        }
        return key;
    }

    /** SHA-256 (hex) over the parts (path ids, canonical body), separated so that boundaries cannot shift. */
    public static String hash(Object... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Object part : parts) {
                byte[] bytes = String.valueOf(part).getBytes(StandardCharsets.UTF_8);
                digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
                digest.update((byte) ':');
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Replays the stored response of ({@code organizationId}, {@code endpoint}, {@code key}) when the hash matches,
     * refuses it (422 IDEMPOTENCY_KEY_REUSED) when it does not, otherwise runs {@code action} and stores its result
     * with {@code status}. Must run inside the caller's transaction, whose organization context is
     * {@code organizationId}.
     */
    public <T> Result<T> execute(UUID organizationId, String endpoint, String key, String requestHash,
            int status, Class<T> type, Supplier<T> action) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("idempotency must run inside the business transaction");
        }
        requireKey(key);
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")
                .param(organizationId + "|" + endpoint + "|" + key).query(Object.class).list();
        Optional<Stored> stored = jdbc.sql("""
                SELECT request_hash, response_status, response_body::text FROM hms.idempotency_record
                WHERE organization_id = ? AND endpoint = ? AND key = ?""").params(organizationId, endpoint, key)
                .query((rs, n) -> new Stored(rs.getString(1), rs.getInt(2), rs.getString(3))).optional();
        if (stored.isPresent()) {
            if (!stored.get().requestHash().equals(requestHash)) {
                throw new BusinessRuleException("IDEMPOTENCY_KEY_REUSED",
                        "This Idempotency-Key was already used for a different request.");
            }
            return new Result<>(stored.get().status(), json.readValue(stored.get().body(), type), true);
        }
        T body = action.get();
        jdbc.sql("""
                INSERT INTO hms.idempotency_record (organization_id, key, endpoint, request_hash, response_status,
                    response_body, created_at) VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?)""")
                .params(organizationId, key, endpoint, requestHash, status, json.writeValueAsString(body),
                        Timestamp.from(clock.instant())).update();
        return new Result<>(status, body, false);
    }
}
