package com.moa.server.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.sqlite.SQLiteConfig;

@Configuration(proxyBeanMethods = false)
public class DatabaseConfiguration {
    @Bean
    Clock clock() { return Clock.systemUTC(); }

    @Bean
    DataSource dataSource(@Value("${moa.data-directory}") String directory) throws IOException {
        Path root = Path.of(directory).toAbsolutePath().normalize();
        Files.createDirectories(root);
        SQLiteConfig sqlite = new SQLiteConfig();
        sqlite.enforceForeignKeys(true);
        sqlite.setBusyTimeout(5_000);
        sqlite.setJournalMode(SQLiteConfig.JournalMode.WAL);
        HikariConfig pool = new HikariConfig();
        pool.setJdbcUrl("jdbc:sqlite:" + root.resolve("moa.db"));
        pool.setDataSourceProperties(sqlite.toProperties());
        // This small, single-server app serializes writes through one connection.
        pool.setMaximumPoolSize(1);
        pool.setMinimumIdle(1);
        pool.setPoolName("moa-sqlite");
        return new HikariDataSource(pool);
    }
}
