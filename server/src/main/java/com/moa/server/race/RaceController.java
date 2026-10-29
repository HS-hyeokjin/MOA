package com.moa.server.race;

import com.moa.server.auth.FamilyMember;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/games/race")
public class RaceController {
    private final RaceService race;
    public RaceController(RaceService race) { this.race = race; }

    @GetMapping
    ResponseEntity<RaceService.Game> game(@AuthenticationPrincipal FamilyMember member) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(race.game(member.id()));
    }
    @PostMapping("/rolls")
    ResponseEntity<RaceService.Outcome> roll(@AuthenticationPrincipal FamilyMember member, @RequestHeader("Idempotency-Key") String requestId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(race.roll(member.id(), requestId));
    }
}
