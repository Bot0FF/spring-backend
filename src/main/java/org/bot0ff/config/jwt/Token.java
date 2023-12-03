package org.bot0ff.config.jwt;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record Token(UUID id, String username,
                    List<String> authorities,
                    Instant createdAt,
                    Instant expiresAt) {
}
