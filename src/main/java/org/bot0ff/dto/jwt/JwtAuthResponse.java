package org.bot0ff.dto.jwt;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.bot0ff.entity.Player;

@Data
@AllArgsConstructor
public class JwtAuthResponse {
    private Player player;
}