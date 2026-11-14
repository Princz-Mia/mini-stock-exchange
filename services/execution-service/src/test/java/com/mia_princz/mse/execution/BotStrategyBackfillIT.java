package com.mia_princz.mse.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The migration that tags bots made before tags existed, run as a deployment would meet it: the schema up to the
 * version before, bots in it, then the migration. It needs its own server, so no Spring context runs the migrations
 * first. A deployment holds one simulation, so each case gets its own database, copied from the empty one the init
 * script made, with the same owner and schema grants.
 */
class BotStrategyBackfillIT {

	private static final String OWNER = "execution_owner";

	private static final Instant START = Instant.parse("2026-11-01T10:00:00Z");

	private static PostgreSQLContainer postgres;

	@BeforeAll
	static void start() {
		postgres = new TestcontainersConfiguration().postgresContainer();
		postgres.start();
	}

	@AfterAll
	static void stop() {
		postgres.stop();
	}

	@Test
	void existingBotsAreTaggedByCreationOrder() throws Exception {
		// Seven bots, made out of name order: the tags follow creation, not names.
		int[] order = { 3, 1, 4, 7, 5, 2, 6 };
		List<Bot> bots = new ArrayList<>();
		for (int at = 0; at < order.length; at++) {
			bots.add(new Bot(UUID.randomUUID(), "bot-000" + order[at], START.plusSeconds(at)));
		}
		Map<String, String> tags = backfill("backfill_seven", bots);

		assertThat(tags.remove("Issuance")).as("only bots are tagged").isNull();
		assertThat(tags.get("bot-0003")).as("made first").isEqualTo("MARKET_MAKER");
		assertThat(tags.get("bot-0006")).as("made last").isEqualTo("MOMENTUM");
		assertThat(tags.values()).containsExactly("MARKET_MAKER", "NOISE", "NOISE", "NOISE", "NOISE", "NOISE",
				"MOMENTUM");
	}

	@Test
	void aFifthRoundsDown() throws Exception {
		// A fifth of eight is 1.6: one market maker and one momentum bot, not two of each.
		assertThat(backfill("backfill_eight", inOrder(8)).values()).containsExactly(null, "MARKET_MAKER", "NOISE",
				"NOISE", "NOISE", "NOISE", "NOISE", "NOISE", "MOMENTUM");
	}

	@Test
	void fewerThanFiveAreAllNoise() throws Exception {
		assertThat(backfill("backfill_four", inOrder(4)).values()).containsExactly(null, "NOISE", "NOISE", "NOISE",
				"NOISE");
	}

	@Test
	void theDemoPopulationSplitsFourTwelveFour() throws Exception {
		List<String> tags = new ArrayList<>(backfill("backfill_twenty", inOrder(20)).values());
		assertThat(tags.remove(0)).as("issuance").isNull();
		assertThat(tags.subList(0, 4)).containsOnly("MARKET_MAKER");
		assertThat(tags.subList(4, 16)).containsOnly("NOISE");
		assertThat(tags.subList(16, 20)).containsOnly("MOMENTUM");
	}

	@Test
	void botsMadeInTheSameInstantAreOrderedByIdAsRunsPickThem() throws Exception {
		// Bots provisioned in one command once shared their transaction's timestamp; runs break the tie by id. The
		// ids are inserted from highest to lowest, so neither insertion nor name order agrees with them.
		List<Bot> bots = new ArrayList<>();
		for (int n = 6; n >= 1; n--) {
			bots.add(new Bot(UUID.fromString("00000000-0000-4000-8000-00000000000" + n), "bot-000" + (7 - n), START));
		}
		Map<String, String> tags = backfill("backfill_tied", bots);

		assertThat(tags.get("bot-0006")).as("lowest id").isEqualTo("MARKET_MAKER");
		assertThat(tags.get("bot-0001")).as("highest id").isEqualTo("MOMENTUM");
		assertThat(tags.values()).containsExactly(null, "MARKET_MAKER", "NOISE", "NOISE", "NOISE", "NOISE",
				"MOMENTUM");
	}

	private static List<Bot> inOrder(int count) {
		List<Bot> bots = new ArrayList<>();
		for (int n = 1; n <= count; n++) {
			bots.add(new Bot(UUID.randomUUID(), "bot-%04d".formatted(n), START.plusSeconds(n)));
		}
		return bots;
	}

	/**
	 * Migrates a fresh database to the version before tags, adds an issuance account and the bots, migrates to the
	 * tags, and returns each account's tag in the order runs pick accounts.
	 */
	private static Map<String, String> backfill(String database, List<Bot> bots) throws Exception {
		try (Connection admin = DriverManager.getConnection(TestcontainersConfiguration.jdbcUrl(postgres, "postgres"),
				postgres.getUsername(), postgres.getPassword())) {
			admin.createStatement()
				.execute("CREATE DATABASE " + database + " TEMPLATE execution_db OWNER " + OWNER);
		}
		String url = TestcontainersConfiguration.jdbcUrl(postgres, database);
		migrate(url, "20");

		UUID simulation = UUID.randomUUID();
		try (Connection owner = owner(url)) {
			try (PreparedStatement insert = owner
				.prepareStatement("INSERT INTO simulation (id, state) VALUES (?, 'SETUP')")) {
				insert.setObject(1, simulation);
				insert.executeUpdate();
			}
			account(owner, simulation, UUID.randomUUID(), "ISSUANCE", "Issuance", START.minusSeconds(3600));
			for (Bot bot : bots) {
				account(owner, simulation, bot.id(), "BOT", bot.name(), bot.created());
			}
		}

		migrate(url, "21");

		Map<String, String> tags = new LinkedHashMap<>();
		try (Connection owner = owner(url); ResultSet rows = owner.createStatement().executeQuery("""
				SELECT display_name, bot_strategy FROM account ORDER BY created_at, id""")) {
			while (rows.next()) {
				tags.put(rows.getString("display_name"), rows.getString("bot_strategy"));
			}
		}
		return tags;
	}

	private static Connection owner(String url) throws Exception {
		return DriverManager.getConnection(url, OWNER, TestcontainersConfiguration.EXECUTION_OWNER_PASSWORD);
	}

	private static void migrate(String url, String target) {
		Flyway.configure()
			.dataSource(url, OWNER, TestcontainersConfiguration.EXECUTION_OWNER_PASSWORD)
			.locations("classpath:db/migration")
			.target(target)
			.load()
			.migrate();
	}

	private static void account(Connection owner, UUID simulation, UUID id, String kind, String name, Instant created)
			throws Exception {
		try (PreparedStatement insert = owner.prepareStatement("""
				INSERT INTO account (id, simulation_id, kind, display_name, created_at) VALUES (?, ?, ?, ?, ?)""")) {
			insert.setObject(1, id);
			insert.setObject(2, simulation);
			insert.setString(3, kind);
			insert.setString(4, name);
			insert.setTimestamp(5, Timestamp.from(created));
			insert.executeUpdate();
		}
	}

	private record Bot(UUID id, String name, Instant created) {
	}

}
