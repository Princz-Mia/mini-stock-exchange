package com.mia_princz.mse.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The migration that tags bots made before tags existed, run as a deployment would meet it: the schema up to the
 * version before, bots in it, then the migration. It needs its own server, so no Spring context runs the migrations
 * first.
 */
class BotStrategyBackfillIT {

	private static final String OWNER = "execution_owner";

	@Test
	void existingBotsAreTaggedByCreationOrder() throws Exception {
		try (PostgreSQLContainer postgres = new TestcontainersConfiguration().postgresContainer()) {
			postgres.start();
			String url = TestcontainersConfiguration.jdbcUrl(postgres, "execution_db");
			migrate(url, "20");

			UUID simulation = UUID.randomUUID();
			try (Connection owner = DriverManager.getConnection(url, OWNER,
					TestcontainersConfiguration.EXECUTION_OWNER_PASSWORD)) {
				try (PreparedStatement insert = owner.prepareStatement(
						"INSERT INTO simulation (id, state) VALUES (?, 'SETUP')")) {
					insert.setObject(1, simulation);
					insert.executeUpdate();
				}
				account(owner, simulation, "ISSUANCE", "Issuance", Instant.parse("2026-11-01T00:00:00Z"));
				// Seven bots, made out of name order: the tags follow creation, and one fifth of seven rounds down to one.
				Instant start = Instant.parse("2026-11-01T10:00:00Z");
				int[] order = { 3, 1, 4, 7, 5, 2, 6 };
				for (int at = 0; at < order.length; at++) {
					account(owner, simulation, "BOT", "bot-000" + order[at], start.plusSeconds(at));
				}
			}

			migrate(url, "21");

			Map<String, String> tags = new LinkedHashMap<>();
			try (Connection owner = DriverManager.getConnection(url, OWNER,
					TestcontainersConfiguration.EXECUTION_OWNER_PASSWORD);
					ResultSet rows = owner.createStatement().executeQuery("""
							SELECT display_name, kind, bot_strategy FROM account ORDER BY created_at, id""")) {
				while (rows.next()) {
					tags.put(rows.getString("display_name"), rows.getString("bot_strategy"));
				}
			}
			assertThat(tags.remove("Issuance")).as("only bots are tagged").isNull();
			assertThat(tags.get("bot-0003")).as("made first").isEqualTo("MARKET_MAKER");
			assertThat(tags.get("bot-0006")).as("made last").isEqualTo("MOMENTUM");
			assertThat(tags.values()).containsExactly("MARKET_MAKER", "NOISE", "NOISE", "NOISE", "NOISE", "NOISE",
					"MOMENTUM");
		}
	}

	private static void migrate(String url, String target) {
		Flyway.configure()
			.dataSource(url, OWNER, TestcontainersConfiguration.EXECUTION_OWNER_PASSWORD)
			.locations("classpath:db/migration")
			.target(target)
			.load()
			.migrate();
	}

	private static UUID account(Connection owner, UUID simulation, String kind, String name, Instant created)
			throws Exception {
		UUID id = UUID.randomUUID();
		try (PreparedStatement insert = owner.prepareStatement("""
				INSERT INTO account (id, simulation_id, kind, display_name, created_at) VALUES (?, ?, ?, ?, ?)""")) {
			insert.setObject(1, id);
			insert.setObject(2, simulation);
			insert.setString(3, kind);
			insert.setString(4, name);
			insert.setTimestamp(5, Timestamp.from(created));
			insert.executeUpdate();
		}
		return id;
	}

}
