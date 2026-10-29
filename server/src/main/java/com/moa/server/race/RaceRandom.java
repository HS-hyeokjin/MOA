package com.moa.server.race;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

@Component
public class RaceRandom {
    private final SecureRandom random = new SecureRandom();
    public int dice() { return random.nextInt(6) + 1; }
    public String event() {
        return switch (random.nextInt(10)) {
            case 0 -> "DOUBLE";
            case 1 -> "BACK";
            default -> "NORMAL";
        };
    }
}
