package com.moa.server;

import com.jayway.jsonpath.JsonPath;
import com.moa.server.auth.AuthService;
import com.moa.server.race.RaceRandom;
import com.moa.server.race.RaceService;
import java.nio.file.Path;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(RaceIntegrationTests.Controlled.class)
class RaceIntegrationTests {
    static final Path ROOT = Path.of("build/test-data", UUID.randomUUID().toString()).toAbsolutePath();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) { registry.add("moa.data-directory", ROOT::toString); }
    static class TestClock extends Clock {
        Instant now = Instant.parse("2026-10-28T03:00:00Z");
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
    }
    static class TestRandom extends RaceRandom {
        int value = 6;
        String event = "NORMAL";
        @Override public int dice() { return value; }
        @Override public String event() { return event; }
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class Controlled {
        @Bean @Primary TestClock testClock() { return new TestClock(); }
        @Bean @Primary TestRandom testRandom() { return new TestRandom(); }
    }
    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired JdbcTemplate db;
    @Autowired RaceService race;
    @Autowired TestClock clock;
    @Autowired TestRandom random;
    @Autowired org.springframework.transaction.support.TransactionTemplate transactions;

    @BeforeEach void reset() {
        db.update("DELETE FROM race_rewards");
        db.update("DELETE FROM race_rolls");
        db.update("DELETE FROM sessions");
        db.update("DELETE FROM invites");
        clock.now = Instant.parse("2026-10-28T03:00:00Z"); random.value = 6; random.event = "NORMAL";
    }
    String key() { return UUID.randomUUID().toString(); }
    String login(String member) throws Exception {
        var response = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + auth.invite(member).code() + "\"}")).andExpect(status().isOk()).andReturn();
        return JsonPath.read(response.getResponse().getContentAsString(), "$.token");
    }
    String roll(String token, String key) throws Exception {
        return mvc.perform(post("/api/v1/games/race/rolls").header("Authorization", "Bearer " + token).header("Idempotency-Key", key))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test void privateGameStartsWithFivePlayersAndServerControlsRolls() throws Exception {
        mvc.perform(get("/api/v1/games/race")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/games/race/rolls").header("Idempotency-Key", key())).andExpect(status().isUnauthorized());
        String token = login("me");
        mvc.perform(get("/api/v1/games/race").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.players.length()").value(5)).andExpect(jsonPath("$.remainingRolls").value(3))
                .andExpect(jsonPath("$.weekStart").value("2026-10-26")).andExpect(jsonPath("$.weekEnd").value("2026-11-01T15:00:00Z"));
        var response = roll(token, key());
        assertEquals(6, (int) JsonPath.read(response, "$.roll.dice"));
        assertEquals(6, (int) JsonPath.read(response, "$.roll.position"));
        assertEquals(2, (int) JsonPath.read(response, "$.game.remainingRolls"));
        mvc.perform(post("/api/v1/games/race/rolls").header("Authorization", "Bearer " + token).header("Idempotency-Key", "bad"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/games/race/rolls").header("Authorization", "Bearer " + token)).andExpect(status().isBadRequest());
    }

    @Test void retryReturnsOriginalRollAndCannotBeReusedByOtherMember() throws Exception {
        String token = login("me"), key = key();
        String first = roll(token, key);
        random.event = "DOUBLE";
        String repeated = roll(token, key);
        assertEquals((Number) JsonPath.read(first, "$.roll.id"), (Number) JsonPath.read(repeated, "$.roll.id"));
        assertEquals("NORMAL", JsonPath.read(repeated, "$.roll.event"));
        assertEquals(2, (int) JsonPath.read(repeated, "$.game.remainingRolls"));
        mvc.perform(post("/api/v1/games/race/rolls").header("Authorization", "Bearer " + login("mom")).header("Idempotency-Key", key))
                .andExpect(status().isConflict());
        assertEquals(1, db.queryForObject("SELECT count(*) FROM race_rolls", Integer.class));
    }

    @Test void dailyQuotaUsesKoreanMidnightAndDoesNotCarryOver() {
        clock.now = Instant.parse("2026-10-28T14:59:59Z");
        for (int i = 0; i < 3; i++) race.roll("dad", key());
        assertEquals(0, race.game("dad").remainingRolls());
        assertThrows(com.moa.server.api.ApiException.class, () -> race.roll("dad", key()));
        clock.now = Instant.parse("2026-10-28T15:00:00Z");
        assertEquals("2026-10-29", race.game("dad").today());
        assertEquals(3, race.game("dad").remainingRolls());
        assertEquals(24, race.roll("dad", key()).roll().position());
        clock.now = clock.now.plus(Duration.ofDays(2));
        assertEquals(3, race.game("dad").remainingRolls());
    }

    @Test void concurrentDistinctRequestsCannotExceedDailyQuota() throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = java.util.stream.IntStream.range(0, 12).mapToObj(i -> executor.submit(() -> {
                start.await();
                try { race.roll("mom", key()); return true; }
                catch (com.moa.server.api.ApiException error) { assertEquals(409, error.status().value()); return false; }
            })).toList();
            start.countDown();
            int successes = 0;
            for (var future : futures) if (future.get(20, TimeUnit.SECONDS)) successes++;
            assertEquals(3, successes);
        }
        assertEquals(3, db.queryForObject("SELECT count(*) FROM race_rolls", Integer.class));
    }

    @Test void concurrentSameRequestOnlyDrawsOnce() throws Exception {
        var start = new CountDownLatch(1);
        String request = key();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = executor.submit(() -> { start.await(); return race.roll("me", request); });
            var b = executor.submit(() -> { start.await(); return race.roll("me", request); });
            start.countDown();
            assertEquals(a.get(15, TimeUnit.SECONDS).roll(), b.get(15, TimeUnit.SECONDS).roll());
        }
        assertEquals(2, race.game("me").remainingRolls());
    }

    @Test void doubleAndBackwardEventsHaveCorrectMovementAndNeverGoBelowZero() {
        random.event = "BACK";
        var back = race.roll("sister", key()).roll();
        assertEquals(0, back.position()); assertEquals(0, back.movement());
        random.event = "DOUBLE";
        var doubled = race.roll("sister", key()).roll();
        assertEquals(12, doubled.movement());
        random.event = "BACK";
        assertEquals(9, race.roll("sister", key()).roll().position());
    }

    @Test void mondaySettlesTiesOnceAndRetryAcrossWeeksDoesNotUseNewChance() {
        clock.now = Instant.parse("2026-11-01T14:59:59Z");
        String previousKey = key();
        var lastRoll = race.roll("me", previousKey).roll();
        race.roll("mom", key());
        random.value = 4; race.roll("dad", key());
        random.value = 2; race.roll("sister", key());
        clock.now = Instant.parse("2026-11-01T15:00:00Z");
        var game = race.game("me");
        assertEquals("2026-11-02", game.weekStart());
        assertTrue(game.players().stream().allMatch(player -> player.position() == 0 && player.rank() == 0));
        assertEquals(3, game.remainingRolls());
        assertEquals(4, game.lastWeek().results().size());
        assertEquals(java.util.List.of(1, 1, 3, 4), game.lastWeek().results().stream().map(RaceService.Winner::rank).toList());
        assertEquals(230, db.queryForObject("SELECT sum(points) FROM race_rewards", Integer.class));
        assertEquals(0, game.players().stream().filter(player -> player.member().id().equals("brother-in-law")).findFirst().orElseThrow().totalPoints());
        var retried = race.roll("me", previousKey);
        assertEquals(lastRoll, retried.roll()); assertEquals(3, retried.game().remainingRolls());
        race.game("dad");
        assertEquals(4, db.queryForObject("SELECT count(*) FROM race_rewards", Integer.class));
        assertEquals(2, race.roll("mom", key()).roll().position());
    }

    @Test void delayedSettlementIncludesAllPlayedWeeksAndSurvivesNewServiceInstance() {
        race.roll("me", key());
        clock.now = clock.now.plus(Duration.ofDays(7)); race.roll("me", key());
        clock.now = clock.now.plus(Duration.ofDays(21));
        assertEquals(200, race.game("me").players().stream().filter(p -> p.member().id().equals("me")).findFirst().orElseThrow().totalPoints());
        var restarted = new RaceService(db, transactions, clock, random);
        assertEquals(200, restarted.game("me").players().stream().filter(p -> p.member().id().equals("me")).findFirst().orElseThrow().totalPoints());
        assertEquals(2, db.queryForObject("SELECT count(*) FROM race_rewards", Integer.class));
        assertNull(race.game("me").lastWeek());
    }
}
