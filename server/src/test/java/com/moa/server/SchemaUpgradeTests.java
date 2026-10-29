package com.moa.server;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.sqlite.SQLiteDataSource;
import static org.junit.jupiter.api.Assertions.*;

class SchemaUpgradeTests {
    @TempDir Path root;

    @Test void upgradeAddsGameTablesAndPreservesExistingFamilyAlbumAndLogin() {
        var source = new SQLiteDataSource();
        source.setUrl("jdbc:sqlite:" + root.resolve("legacy.db"));
        new ResourceDatabasePopulator(new ClassPathResource("schema-v02.sql")).execute(source);
        var db = new JdbcTemplate(source);
        db.update("UPDATE members SET display_name = '우리 엄마' WHERE id = 'mom'");
        db.update("INSERT INTO sessions VALUES ('existing-token-hash', 'mom', 9999999999999)");
        db.update("INSERT INTO invites VALUES ('existing-code-hash', 'dad', 9999999999999)");
        db.update("INSERT INTO posts VALUES (42, 'existing-request', 'mom', '소중한 사진', 123456)");
        db.update("INSERT INTO photos VALUES ('original-photo', 42, 0, 'image/png', 123, 10, 20)");
        db.update("INSERT INTO likes VALUES (42, 'dad')");
        var upgrade = new ResourceDatabasePopulator(new ClassPathResource("schema.sql"));
        upgrade.execute(source);
        upgrade.execute(source);
        assertEquals(5, db.queryForObject("SELECT count(*) FROM members", Integer.class));
        assertEquals("우리 엄마", db.queryForObject("SELECT display_name FROM members WHERE id = 'mom'", String.class));
        assertEquals("mom", db.queryForObject("SELECT member_id FROM sessions WHERE token_hash = 'existing-token-hash'", String.class));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM invites", Integer.class));
        assertEquals("소중한 사진", db.queryForObject("SELECT caption FROM posts WHERE id = 42", String.class));
        assertEquals("original-photo", db.queryForObject("SELECT id FROM photos", String.class));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM likes", Integer.class));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM race_rolls", Integer.class));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM race_rewards", Integer.class));
    }
}
