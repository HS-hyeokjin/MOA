package com.moa.server.auth;

import com.moa.server.api.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class AuthController {
    private final AuthService auth;
    private final Clock clock;
    private final Map<String, Attempts> attempts = new HashMap<>();
    private record Attempts(long minute, int count) { }
    public record LoginRequest(@NotBlank @Size(max = 64) String code) { }
    public AuthController(AuthService auth, Clock clock) { this.auth = auth; this.clock = clock; }

    @PostMapping("/auth/login")
    AuthService.LoginResult login(@Valid @RequestBody LoginRequest request, HttpServletRequest servlet) {
        limit(servlet.getRemoteAddr());
        return auth.login(request.code());
    }
    private synchronized void limit(String address) {
        long minute = clock.millis() / 60_000;
        if (attempts.size() >= 1_000) attempts.entrySet().removeIf(entry -> entry.getValue().minute() != minute);
        Attempts prior = attempts.get(address);
        int count = prior != null && prior.minute() == minute ? prior.count() : 0;
        if (count >= 60 || (prior == null && attempts.size() >= 10_000)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "잠시 후 다시 로그인해 주세요.");
        }
        attempts.put(address, new Attempts(minute, count + 1));
    }
    @GetMapping("/me")
    FamilyMember me(@AuthenticationPrincipal FamilyMember member) { return member; }
    @GetMapping("/members")
    List<FamilyMember> members() { return auth.members(); }
    @PostMapping("/auth/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(@RequestHeader("Authorization") String authorization) { auth.logout(authorization.substring(7)); }
}
