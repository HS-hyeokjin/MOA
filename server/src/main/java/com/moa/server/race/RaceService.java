package com.moa.server.race;

import com.moa.server.api.ApiException;
import com.moa.server.auth.FamilyMember;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class RaceService {
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    public static final int DAILY_LIMIT = 3;
    public record Player(FamilyMember member, int position, int rank, int rollsToday, int totalPoints) { }
    public record Roll(long id, FamilyMember member, String weekStart, String day, int dice, String event,
                       int movement, int position, Instant createdAt) { }
    public record Winner(FamilyMember member, int position, int rank, int points) { }
    public record LastWeek(String weekStart, List<Winner> results) { }
    public record Game(String weekStart, Instant weekEnd, String today, Instant serverTime, int dailyLimit,
                       int remainingRolls, List<Player> players, List<Roll> recentRolls, LastWeek lastWeek) { }
    public record Outcome(Game game, Roll roll) { }
    private record Standing(FamilyMember member, int position, int rolls) { }

    private final JdbcTemplate db;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final RaceRandom random;

    public RaceService(JdbcTemplate db, TransactionTemplate transactions, Clock clock, RaceRandom random) {
        this.db = db; this.transactions = transactions; this.clock = clock; this.random = random;
    }

    public Game game(String viewer) {
        return transactions.execute(tx -> {
            Instant now = clock.instant();
            LocalDate today = now.atZone(ZONE).toLocalDate();
            settle(week(today));
            return snapshot(viewer, today, now);
        });
    }

    public Outcome roll(String member, String requestId) {
        try {
            if (!UUID.fromString(requestId).toString().equalsIgnoreCase(requestId)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException error) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "주사위 요청 번호가 올바르지 않아요.");
        }
        // One SQLite connection serializes the quota check, random draw and write together.
        return transactions.execute(tx -> {
            Instant now = clock.instant();
            LocalDate today = now.atZone(ZONE).toLocalDate(), week = week(today);
            settle(week);
            var existing = db.query("SELECT id, member_id FROM race_rolls WHERE request_id = ?", (row, index) -> {
                if (!row.getString("member_id").equals(member))
                    throw new ApiException(HttpStatus.CONFLICT, "다른 주사위 요청 번호를 사용해 주세요.");
                return row.getLong("id");
            }, requestId);
            if (!existing.isEmpty()) return new Outcome(snapshot(member, today, now), rollById(existing.getFirst()));
            int used = used(member, today);
            if (used >= DAILY_LIMIT) throw new ApiException(HttpStatus.CONFLICT, "오늘의 주사위를 모두 굴렸어요. 자정에 다시 만나요!");
            int before = position(member, week), dice = random.dice();
            String event = random.event();
            int intended = switch (event) { case "DOUBLE" -> dice * 2; case "BACK" -> -3; default -> dice; };
            int after = Math.max(0, before + intended);
            db.update("""
                    INSERT INTO race_rolls(request_id, member_id, week_start, play_day, turn, dice, event, movement, position, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, requestId, member, week.toString(), today.toString(), used + 1, dice, event, after - before, after, now.toEpochMilli());
            long id = db.queryForObject("SELECT last_insert_rowid()", Long.class);
            return new Outcome(snapshot(member, today, now), rollById(id));
        });
    }

    private static LocalDate week(LocalDate day) { return day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)); }
    private int used(String member, LocalDate day) {
        return db.queryForObject("SELECT count(*) FROM race_rolls WHERE member_id = ? AND play_day = ?", Integer.class, member, day.toString());
    }
    private int position(String member, LocalDate week) {
        return db.query("SELECT position FROM race_rolls WHERE member_id = ? AND week_start = ? ORDER BY id DESC LIMIT 1",
                (row, index) -> row.getInt(1), member, week.toString()).stream().findFirst().orElse(0);
    }
    private List<Standing> standings(LocalDate week) {
        return db.query("""
                SELECT m.id, m.display_name, coalesce(r.position, 0) AS position,
                    (SELECT count(*) FROM race_rolls x WHERE x.member_id = m.id AND x.week_start = ?) AS rolls
                FROM members m LEFT JOIN race_rolls r ON r.id =
                    (SELECT max(x.id) FROM race_rolls x WHERE x.member_id = m.id AND x.week_start = ?)
                ORDER BY position DESC, (rolls > 0) DESC, m.id
                """, (row, index) -> new Standing(new FamilyMember(row.getString("id"), row.getString("display_name")),
                row.getInt("position"), row.getInt("rolls")), week.toString(), week.toString());
    }
    private static int rank(Standing player, List<Standing> players) {
        return player.rolls() == 0 ? 0 : 1 + (int) players.stream()
                .filter(other -> other.rolls() > 0 && other.position() > player.position()).count();
    }
    private static int prize(int rank) { return switch (rank) { case 1 -> 100; case 2 -> 60; case 3 -> 30; default -> 0; }; }

    private void settle(LocalDate currentWeek) {
        var weeks = db.query("""
                SELECT DISTINCT r.week_start FROM race_rolls r
                WHERE r.week_start < ? AND NOT EXISTS
                    (SELECT 1 FROM race_rewards p WHERE p.week_start = r.week_start AND p.member_id = r.member_id)
                ORDER BY r.week_start
                """, (row, index) -> LocalDate.parse(row.getString(1)), currentWeek.toString());
        for (LocalDate week : weeks) {
            var players = standings(week);
            for (Standing player : players) if (player.rolls() > 0) {
                int rank = rank(player, players);
                db.update("INSERT OR IGNORE INTO race_rewards VALUES (?, ?, ?, ?, ?)", week.toString(), player.member().id(),
                        player.position(), rank, prize(rank));
            }
        }
    }

    private Game snapshot(String viewer, LocalDate today, Instant now) {
        LocalDate week = week(today);
        var standings = standings(week);
        var players = standings.stream().map(player -> new Player(player.member(), player.position(), rank(player, standings),
                used(player.member().id(), today), db.queryForObject("SELECT coalesce(sum(points), 0) FROM race_rewards WHERE member_id = ?",
                Integer.class, player.member().id()))).toList();
        var recent = db.query("SELECT id FROM race_rolls WHERE week_start = ? ORDER BY id DESC LIMIT 10",
                (row, index) -> row.getLong(1), week.toString()).stream().map(this::rollById).toList();
        LocalDate lastWeek = week.minusWeeks(1);
        var winners = db.query("""
                SELECT p.*, m.display_name FROM race_rewards p JOIN members m ON m.id = p.member_id
                WHERE p.week_start = ? ORDER BY p.rank, p.member_id
                """, (row, index) -> new Winner(new FamilyMember(row.getString("member_id"), row.getString("display_name")),
                row.getInt("position"), row.getInt("rank"), row.getInt("points")), lastWeek.toString());
        return new Game(week.toString(), week.plusWeeks(1).atStartOfDay(ZONE).toInstant(), today.toString(), now, DAILY_LIMIT,
                DAILY_LIMIT - used(viewer, today), players, recent, winners.isEmpty() ? null : new LastWeek(lastWeek.toString(), winners));
    }

    private Roll rollById(long id) {
        return db.queryForObject("""
                SELECT r.*, m.display_name FROM race_rolls r JOIN members m ON m.id = r.member_id WHERE r.id = ?
                """, (row, index) -> new Roll(row.getLong("id"), new FamilyMember(row.getString("member_id"), row.getString("display_name")),
                row.getString("week_start"), row.getString("play_day"), row.getInt("dice"), row.getString("event"),
                row.getInt("movement"), row.getInt("position"), Instant.ofEpochMilli(row.getLong("created_at"))), id);
    }
}
