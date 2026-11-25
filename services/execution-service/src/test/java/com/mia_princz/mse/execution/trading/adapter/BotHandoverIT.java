package com.mia_princz.mse.execution.trading.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Stream;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

import tools.jackson.databind.json.JsonMapper;

import com.mia_princz.mse.execution.TestcontainersConfiguration;
import com.mia_princz.mse.execution.identity.ApiTokens;
import com.mia_princz.mse.execution.trading.application.AddInstrument;
import com.mia_princz.mse.execution.trading.application.BotStrategy;
import com.mia_princz.mse.execution.trading.application.BotsResult;
import com.mia_princz.mse.execution.trading.application.CommandKey;
import com.mia_princz.mse.execution.trading.application.CommandWriter;
import com.mia_princz.mse.execution.trading.application.InstrumentResult;
import com.mia_princz.mse.execution.trading.application.ProvisionAccount;
import com.mia_princz.mse.execution.trading.application.ProvisionBots;
import com.mia_princz.mse.execution.trading.application.TradingEngine;
import com.mia_princz.mse.execution.trading.application.TransactionRunner;
import com.mia_princz.mse.execution.trading.domain.Price;

/**
 * Bot tokens are handed over before the command that creates their bots is queued, and bots are inserted in one
 * statement. Checked at the moment that matters, against real files and PostgreSQL: just before each commit, inside
 * its transaction, every bot the commit would create has its token in its own file under its final name. Files whose
 * bots are not made are gone by the time the answer comes, and bots list in the order they were made and named.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "mse.test.database=bot-handover")
class BotHandoverIT {

	private static final String BOTS_ROUTE = "POST /api/v1/admin/bots";

	@Autowired
	DataSource dataSource;

	@Autowired
	PlatformTransactionManager transactionManager;

	@Autowired
	JdbcTemplate jdbc;

	@TempDir
	Path temporary;

	private Path tokens;

	private FileBotCredentials credentials;

	private CommandWriter writer;

	private CheckedTransactions transactions;

	private TradingEngine engine;

	private UUID operator;

	private UUID nova;

	/** The simulation each test sets up; those of the tests before it are closed. */
	private UUID simulation;

	/** Before each commit, every bot the transaction sees must already have its token in place; a failure rolls back. */
	private final class CheckedTransactions implements TransactionRunner {

		private final TransactionRunner real = new SpringTransactionRunner(transactionManager);

		private final List<String> problems = new ArrayList<>();

		private volatile boolean rollBackNext;

		/** The next transaction commits, and then its connection is lost before the commit is confirmed. */
		private volatile boolean unknownAfterNextCommit;

		private int commitsChecked;

		@Override
		public <T> T inTransaction(Supplier<T> work) {
			T committed = real.inTransaction(() -> {
				T done = work.get();
				check();
				if (rollBackNext) {
					rollBackNext = false;
					throw new IllegalStateException("injected failure before the commit");
				}
				return done;
			});
			if (unknownAfterNextCommit) {
				unknownAfterNextCommit = false;
				throw new TransactionRunner.CommitOutcomeUnknownException(
						new SQLException("injected: connection lost after the commit"));
			}
			return committed;
		}

		private void check() {
			commitsChecked++;
			List<Map<String, Object>> bots = jdbc.queryForList("""
					SELECT a.id, t.token_hash FROM account a JOIN api_token t ON t.account_id = a.id
					WHERE a.kind = 'BOT' AND a.simulation_id = ?""", simulation);
			for (Map<String, Object> bot : bots) {
				Path file = tokens.resolve(bot.get("id") + ".token");
				if (!Files.exists(file)) {
					problems.add("no file for " + bot.get("id") + " before its commit");
					continue;
				}
				try {
					String token = Files.readString(file, StandardCharsets.US_ASCII).strip();
					if (!java.util.Arrays.equals(ApiTokens.hash(token), (byte[]) bot.get("token_hash"))) {
						problems.add("the file of " + bot.get("id") + " holds another token");
					}
				}
				catch (IOException ex) {
					throw new UncheckedIOException(ex);
				}
			}
		}

	}

	@BeforeEach
	void anEmptyMarketInSetup() throws Exception {
		jdbc.update("""
				UPDATE simulation SET state = 'CLOSED', opened_at = coalesce(opened_at, now()), closed_at = now(),
				    writer_epoch = 0 WHERE state <> 'CLOSED' OR writer_epoch <> 0""");
		operator = UUID.randomUUID();
		jdbc.update("INSERT INTO app_user (id, username, password_hash) VALUES (?, ?, 'unused')", operator,
				"handover-" + operator.toString().substring(0, 8));
		jdbc.update("INSERT INTO user_role (user_id, role) VALUES (?, 'OPERATOR')", operator);
		simulation = UUID.randomUUID();
		jdbc.update("INSERT INTO simulation (id, state, writer_epoch, name) VALUES (?, 'SETUP', 0, ?)", simulation,
				"Handover " + simulation);
		jdbc.update("INSERT INTO account (id, simulation_id, kind, display_name) VALUES (?, ?, 'ISSUANCE', 'Issuance')",
				UUID.randomUUID(), simulation);
		tokens = temporary.resolve("secrets");
		credentials = new FileBotCredentials(tokens);
		writer = new CommandWriter("handover-writer", 100);
		transactions = new CheckedTransactions();
		engine = new TradingEngine(new JdbcTradingStore(JdbcClient.create(dataSource), JsonMapper.builder().build()),
				transactions, writer, Clock.systemUTC(), credentials);
		engine.start();
		InstrumentResult added = engine
			.addInstrument(key("POST /api/v1/admin/instruments"),
					new AddInstrument(UUID.randomUUID(), "NOVA", null, Price.parse("100.00"), operator))
			.get(5, TimeUnit.SECONDS);
		nova = added.instrumentId();
	}

	@AfterEach
	void stop() throws InterruptedException {
		writer.close(Duration.ofSeconds(5));
		credentials.close();
	}

	@Test
	void everyBotsTokenIsInPlaceBeforeTheCommitThatCreatesItAndBotsListInTheOrderTheyWereMade() throws Exception {
		// One, a part, and more than the files written at a time, so that a batch is written in several rounds.
		for (int count : List.of(1, 40, 100)) {
			BotsResult made = provision(key(BOTS_ROUTE), count, BotStrategy.NOISE);
			assertThat(made.status()).isEqualTo(BotsResult.Status.CREATED);
		}
		BotsResult more = engine
			.provisionBots(key(BOTS_ROUTE), new ProvisionBots(100, 0, holdings(), BotStrategy.MOMENTUM, operator))
			.get(10, TimeUnit.SECONDS);
		assertThat(more.lastBot()).isEqualTo("bot-0241");
		for (int i = 0; i < 2; i++) {
			assertThat(provision(key(BOTS_ROUTE), 100, BotStrategy.MARKET_MAKER).status())
				.isEqualTo(BotsResult.Status.CREATED);
		}

		assertThat(transactions.problems).isEmpty();
		assertThat(transactions.commitsChecked).isGreaterThanOrEqualTo(6);
		assertThat(tokenFiles()).hasSize(441).allMatch((name) -> name.endsWith(".token"));
		List<String> names = jdbc.queryForList("""
				SELECT display_name FROM account WHERE kind = 'BOT' AND simulation_id = ? ORDER BY created_at, id""",
				String.class, simulation);
		assertThat(names).hasSize(441);
		for (int i = 0; i < names.size(); i++) {
			assertThat(names.get(i)).as("the bot made %d-th", i + 1).isEqualTo(String.format("bot-%04d", i + 1));
		}
		assertThat(jdbc.queryForObject("""
				SELECT count(DISTINCT created_at) FROM account WHERE kind = 'BOT' AND simulation_id = ?""", Long.class,
				simulation))
			.as("no two bots share a creation time").isEqualTo(441);
		// Each bot is funded in its own balanced genesis batch, cash and shares, without cash when it got none.
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM journal_batch b WHERE b.cause = 'GENESIS' AND b.simulation_id = ?
				    AND EXISTS (SELECT 1 FROM initial_allocation a JOIN account c ON c.id = a.account_id
				                WHERE a.journal_batch_id = b.id AND c.kind = 'BOT')""", Long.class, simulation)).isEqualTo(441);
		assertThat(jdbc.queryForList("""
				SELECT c.display_name || ' ' || coalesce(i.symbol, 'CASH') || ' ' || a.amount
				FROM initial_allocation a JOIN account c ON c.id = a.account_id LEFT JOIN instrument i ON i.id = a.instrument_id
				WHERE c.simulation_id = ? AND c.display_name IN ('bot-0141', 'bot-0142', 'bot-0441') ORDER BY 1""",
				String.class, simulation))
			.containsExactly("bot-0141 CASH 5000000", "bot-0141 NOVA 20", "bot-0142 NOVA 20", "bot-0441 CASH 5000000",
					"bot-0441 NOVA 20");
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM (SELECT batch_id FROM ledger_entry GROUP BY batch_id, instrument_id
				                      HAVING sum(delta) <> 0) unbalanced""", Long.class)).isZero();
	}

	/**
	 * Bots list in the order they were made even when the clock alone would not say so: a bot already in the market
	 * whose time is ahead of the clock (a clock set back since, say) still comes before the bots made after it, and
	 * each bot made by one statement gets a time of its own. The clock alone would put them all before it, and give
	 * many of one statement the same time on a fast enough database.
	 */
	@Test
	void botsListAfterTheNewestBotWhateverTheClockSays() throws Exception {
		jdbc.update("""
				INSERT INTO account (id, simulation_id, kind, display_name, bot_strategy, created_at)
				VALUES (?, ?, 'BOT', 'bot-0001', 'NOISE', clock_timestamp() + interval '1 hour')""", UUID.randomUUID(),
				simulation);

		assertThat(provision(key(BOTS_ROUTE), 50, BotStrategy.MARKET_MAKER).lastBot()).isEqualTo("bot-0051");

		List<String> names = jdbc.queryForList("""
				SELECT display_name FROM account WHERE kind = 'BOT' AND simulation_id = ? ORDER BY created_at, id""",
				String.class, simulation);
		assertThat(names).hasSize(51);
		for (int i = 0; i < names.size(); i++) {
			assertThat(names.get(i)).isEqualTo(String.format("bot-%04d", i + 1));
		}
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM account a WHERE a.simulation_id = ? AND a.kind = 'BOT'
				    AND a.created_at = (SELECT max(created_at) FROM account WHERE simulation_id = a.simulation_id
				                        AND kind = 'BOT' AND created_at < a.created_at) + interval '1 microsecond'""",
				Long.class, simulation)).as("each a microsecond after the one before").isEqualTo(50);
	}

	/**
	 * Files of bots that are not made are deleted before the answer: for a stored answer given again, a refusal, and
	 * a transaction that rolls back. Their tokens would authenticate nothing; they would only pile up.
	 */
	@Test
	void theFilesOfBotsThatAreNotMadeAreGoneByTheAnswer() throws Exception {
		CommandKey first = key(BOTS_ROUTE);
		assertThat(provision(first, 5, BotStrategy.NOISE).status()).isEqualTo(BotsResult.Status.CREATED);
		assertThat(tokenFiles()).hasSize(5);

		assertThat(provision(first, 5, BotStrategy.NOISE).lastBot()).as("the stored answer").isEqualTo("bot-0005");
		assertThat(tokenFiles()).as("a stored answer given again makes no bot and keeps no file").hasSize(5);

		BotsResult refused = engine
			.provisionBots(key(BOTS_ROUTE), new ProvisionBots(3, 100, List.of(new ProvisionAccount.Holding(
					UUID.randomUUID(), 1)), BotStrategy.NOISE, operator))
			.get(10, TimeUnit.SECONDS);
		assertThat(refused.status()).isEqualTo(BotsResult.Status.UNKNOWN_INSTRUMENT);
		assertThat(tokenFiles()).hasSize(5);

		transactions.rollBackNext = true;
		Throwable failed = catchThrowable(() -> provision(key(BOTS_ROUTE), 7, BotStrategy.NOISE));
		assertThat(failed).isInstanceOf(ExecutionException.class);
		assertThat(failed.getCause()).hasMessageContaining("injected failure");
		assertThat(tokenFiles()).as("a transaction that rolled back keeps no file").hasSize(5);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM account WHERE kind = 'BOT' AND simulation_id = ?",
				Long.class, simulation)).isEqualTo(5);
		assertThat(transactions.problems).isEmpty();
	}

	/**
	 * A commit whose outcome is unknown may have made its bots, so their files are kept: here it did commit, and a
	 * file deleted now would leave a bot that exists with a token nobody can ever read. The writer halts. A command
	 * that was never queued, refused by a writer that no longer takes commands, makes no bot, and its files are gone
	 * before it is refused.
	 */
	@Test
	void theFilesOfACommitWhoseOutcomeIsUnknownStayAndThoseOfACommandNeverQueuedGo() throws Exception {
		transactions.unknownAfterNextCommit = true;
		Throwable lost = catchThrowable(() -> provision(key(BOTS_ROUTE), 6, BotStrategy.NOISE));

		assertThat(lost).isInstanceOf(ExecutionException.class);
		assertThat(lost.getCause()).isInstanceOf(CommandWriter.WriterHaltException.class);
		List<UUID> made = jdbc.queryForList("SELECT id FROM account WHERE kind = 'BOT' AND simulation_id = ?",
				UUID.class, simulation);
		assertThat(made).as("the commit went through").hasSize(6);
		assertThat(tokenFiles()).as("every committed bot keeps its token file")
			.containsExactlyInAnyOrderElementsOf(made.stream().map((id) -> id + ".token").toList());
		for (UUID bot : made) {
			String token = Files.readString(tokens.resolve(bot + ".token"), StandardCharsets.US_ASCII).strip();
			assertThat(jdbc.queryForObject("SELECT token_hash FROM api_token WHERE account_id = ?", byte[].class, bot))
				.isEqualTo(ApiTokens.hash(token));
		}

		writer.close(Duration.ofSeconds(5));
		Throwable refused = catchThrowable(() -> engine.provisionBots(key(BOTS_ROUTE),
				new ProvisionBots(4, 50_000_00, holdings(), BotStrategy.NOISE, operator)));
		assertThat(refused).isInstanceOf(CommandWriter.WriterStoppedException.class);
		assertThat(tokenFiles()).as("the four tokens of the command never queued are gone").hasSize(6);
		assertThat(transactions.problems).isEmpty();
	}

	private BotsResult provision(CommandKey key, int count, BotStrategy strategy) throws Exception {
		return engine.provisionBots(key, new ProvisionBots(count, 50_000_00, holdings(), strategy, operator))
			.get(10, TimeUnit.SECONDS);
	}

	private List<ProvisionAccount.Holding> holdings() {
		return List.of(new ProvisionAccount.Holding(nova, 20));
	}

	private CommandKey key(String route) {
		return new CommandKey(operator, UUID.randomUUID(), route, new byte[32]);
	}

	private List<String> tokenFiles() throws IOException {
		if (!Files.exists(tokens)) {
			return List.of();
		}
		try (Stream<Path> files = Files.list(tokens)) {
			return files.map((file) -> file.getFileName().toString()).toList();
		}
	}

}
