package com.mia_princz.mse.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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
 * The migrations that tag bots made before tags existed, and then check that runs assign bots by tag, run as a
 * deployment would meet them: the schema up to the version before, data in it, then the migration. It needs its own
 * server, so no Spring context runs the migrations first. A deployment holds one simulation, so each case gets its own
 * database, copied from the empty one the init script made, with the same owner and schema grants.
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

	@Test
	void runsStartedBeforeRunsAssignedByTagKeepTheirAssignments() throws Exception {
		String url = database("runs_before_tags");
		migrate(url, "21");
		String assign = "INSERT INTO bot_assignment (run_id, account_id, account_kind, strategy) VALUES (?, ?, 'BOT', ?)";
		UUID run = UUID.randomUUID();
		UUID noise = UUID.randomUUID();
		try (Connection owner = owner(url)) {
			UUID simulation = UUID.randomUUID();
			UUID user = UUID.randomUUID();
			execute(owner, "INSERT INTO simulation (id, state) VALUES (?, 'SETUP')", simulation);
			execute(owner, "INSERT INTO app_user (id, username, password_hash, role) VALUES (?, 'operator', '{noop}x', 'OPERATOR')",
					user);
			execute(owner, """
					INSERT INTO account (id, simulation_id, kind, display_name, bot_strategy)
					VALUES (?, ?, 'BOT', 'bot-0001', 'NOISE')""", noise, simulation);
			execute(owner, """
					INSERT INTO bot_run (id, simulation_id, desired_state, seed, commands_per_second, max_order_quantity,
					    max_position, started_by, started_at, start_sequence, stop_requested_at)
					VALUES (?, ?, 'STOPPED', 7, 1.00, 10, 100, ?, now(), 1, now())""", run, simulation, user);
			// Assigned by creation order, as runs were then: the oldest bot made the market, whatever its tag says.
			execute(owner, assign, run, noise, "MARKET_MAKER");
		}

		migrate(url, "22");

		try (Connection owner = owner(url)) {
			try (ResultSet rows = owner.createStatement().executeQuery("SELECT strategy FROM bot_assignment")) {
				assertThat(rows.next()).isTrue();
				assertThat(rows.getString(1)).as("kept as it was").isEqualTo("MARKET_MAKER");
			}
			// A new assignment must follow the tag.
			UUID next = UUID.randomUUID();
			execute(owner, """
					INSERT INTO bot_run (id, simulation_id, desired_state, seed, commands_per_second, max_order_quantity,
					    max_position, started_by, started_at, start_sequence)
					SELECT ?, simulation_id, 'RUNNING', 7, 1.00, 10, 100, started_by, now(), 2 FROM bot_run WHERE id = ?""",
					next, run);
			assertThatThrownBy(() -> execute(owner, assign, next, noise, "MARKET_MAKER"))
				.isInstanceOfSatisfying(SQLException.class, (refused) -> assertThat(refused.getSQLState()).isEqualTo("23503"));
			execute(owner, assign, next, noise, "NOISE");
		}
	}

	private static void execute(Connection connection, String sql, Object... parameters) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			for (int i = 0; i < parameters.length; i++) {
				statement.setObject(i + 1, parameters[i]);
			}
			statement.executeUpdate();
		}
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
		String url = database(database);
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

	/** A fresh database with the init script's owner and schema grants, and its URL. */
	private static String database(String name) throws SQLException {
		try (Connection admin = DriverManager.getConnection(TestcontainersConfiguration.jdbcUrl(postgres, "postgres"),
				postgres.getUsername(), postgres.getPassword())) {
			admin.createStatement().execute("CREATE DATABASE " + name + " TEMPLATE execution_db OWNER " + OWNER);
		}
		return TestcontainersConfiguration.jdbcUrl(postgres, name);
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
