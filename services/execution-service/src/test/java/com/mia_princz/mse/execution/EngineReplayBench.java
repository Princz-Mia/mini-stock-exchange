package com.mia_princz.mse.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.mia_princz.mse.execution.trading.application.CancelOrder;
import com.mia_princz.mse.execution.trading.application.CancelResult;
import com.mia_princz.mse.execution.trading.application.CommandKey;
import com.mia_princz.mse.execution.trading.application.PlaceOrder;
import com.mia_princz.mse.execution.trading.application.PlacementResult;
import com.mia_princz.mse.execution.trading.application.TradingEngine;
import com.mia_princz.mse.execution.trading.domain.OrderType;
import com.mia_princz.mse.execution.trading.domain.Price;
import com.mia_princz.mse.execution.trading.domain.Quantity;
import com.mia_princz.mse.execution.trading.domain.Side;

/**
 * The writer path on identical data: a seeded sequence of orders and cancels sent one at a time through the
 * application's own engine, store and writer, committing to real PostgreSQL with the outbox relay running. JMH covers
 * the matcher alone; this covers everything a command does inside the writer, database included, so a change to that
 * path can be compared before and after.
 *
 * <p>
 * The sequence is identical on every run: the start state is the same, the generator is seeded, and the only choice
 * that depends on answers (which resting order to cancel) depends on the engine's answers, which a correct change must
 * not alter. Each run prints a digest of every answer, so two runs can show they did the same work.
 *
 * <p>
 * Not part of the build; run it on its own:
 * {@code mvnw -pl services/execution-service verify -Dtest=None -Dsurefire.failIfNoSpecifiedTests=false
 * -Dit.test=EngineReplayBench}. It writes {@code target/engine-replay-<time>.txt}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "mse.test.database=engine-replay")
class EngineReplayBench {

	private static final long SEED = 20261108L;

	private static final int INSTRUMENTS = 5;

	private static final int ACCOUNTS = 200;

	private static final int WARM_UP = 2_000;

	private static final int MEASURED = 10_000;

	private static final long MID_CENTS = 10_000;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	PlatformTransactionManager transactionManager;

	@Autowired
	TradingEngine engine;

	private final List<UUID> instruments = new ArrayList<>();

	private final List<UUID> accounts = new ArrayList<>();

	@Test
	void replay() throws Exception {
		new TransactionTemplate(transactionManager).executeWithoutResult((status) -> market());
		engine.start();
		Random random = new Random(SEED);
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		List<Resting> resting = new ArrayList<>();
		long[] latencies = new long[MEASURED];
		int[] counts = new int[4];
		long measuredStart = 0;
		for (int i = 0; i < WARM_UP + MEASURED; i++) {
			if (i == WARM_UP) {
				measuredStart = System.nanoTime();
			}
			long began = System.nanoTime();
			String answer = step(i, random, resting, counts);
			long took = System.nanoTime() - began;
			if (i >= WARM_UP) {
				latencies[i - WARM_UP] = took;
			}
			digest.update(answer.getBytes(StandardCharsets.UTF_8));
		}
		long wall = System.nanoTime() - measuredStart;
		String report = report(latencies, wall, counts, HexFormat.of().formatHex(digest.digest()));
		System.out.println(report);
		Path out = Path.of("target", "engine-replay-" + Instant.now().toString().replace(':', '-') + ".txt");
		Files.writeString(out, report);

		assertThat(counts[3]).as("commands that failed outright").isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM trades", Long.class)).as("the workload trades").isPositive();
	}

	private record Resting(UUID orderId, UUID accountId) {
	}

	/** One command, chosen by the seeded generator; returns its answer in a stable form for the digest. */
	private String step(int index, Random random, List<Resting> resting, int[] counts) throws Exception {
		int kind = random.nextInt(100);
		if (kind < 30 && !resting.isEmpty()) {
			Resting target = resting.remove(random.nextInt(resting.size()));
			CancelResult result = engine.cancel(key(target.accountId(), index),
					new CancelOrder(target.orderId(), target.accountId())).get();
			counts[1]++;
			return "C" + result.status() + result.canceledQuantity() + result.commandSequence();
		}
		Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
		OrderType type = kind >= 90 ? OrderType.MARKET_PROTECTED : OrderType.LIMIT;
		long ticks = type == OrderType.MARKET_PROTECTED ? 20 : random.nextInt(21) - 10;
		UUID account = accounts.get(random.nextInt(ACCOUNTS));
		UUID instrument = instruments.get(random.nextInt(INSTRUMENTS));
		PlaceOrder order = new PlaceOrder(new UUID(SEED, index), account, instrument, side, type,
				new Price(side == Side.BUY ? MID_CENTS + ticks : MID_CENTS - ticks),
				new Quantity(1 + random.nextInt(10)));
		PlacementResult result;
		try {
			result = engine.place(key(account, index), order).get();
		}
		catch (Exception ex) {
			counts[3]++;
			return "F";
		}
		counts[result.rejected() ? 2 : 0]++;
		if (result.working() > 0) {
			resting.add(new Resting(order.orderId(), account));
		}
		return "P" + result.state() + result.rejection() + result.filled() + "/" + result.working() + "/"
				+ result.canceled() + "@" + result.commandSequence();
	}

	private static CommandKey key(UUID account, int index) {
		byte[] hash = new byte[32];
		hash[0] = (byte) index;
		return new CommandKey(account, new UUID(~SEED, index), "bench", hash);
	}

	private static String report(long[] latencies, long wall, int[] counts, String digest) {
		long[] sorted = latencies.clone();
		Arrays.sort(sorted);
		double mean = Arrays.stream(sorted).average().orElse(0) / 1e6;
		return """
				Engine replay: %d warm-up then %d measured commands, one at a time
				  placed %d, rejected %d, cancels %d, failed %d
				  throughput %.0f commands/s
				  latency ms: mean %.3f, p50 %.3f, p95 %.3f, p99 %.3f, max %.3f
				  answers digest %s
				""".formatted(WARM_UP, MEASURED, counts[0], counts[2], counts[1], counts[3],
				MEASURED / (wall / 1e9), mean, ms(sorted, 0.50), ms(sorted, 0.95), ms(sorted, 0.99),
				sorted[sorted.length - 1] / 1e6, digest);
	}

	private static double ms(long[] sorted, double fraction) {
		return sorted[Math.max(0, (int) Math.ceil(fraction * sorted.length) - 1)] / 1e6;
	}

	/** An open market with five instruments at 100.00 and 200 bot accounts, each with cash and shares of every one. */
	private void market() {
		UUID simulation = UUID.randomUUID();
		jdbc.update("INSERT INTO simulation (id, state, opened_at) VALUES (?, 'OPEN', now())", simulation);
		UUID issuance = UUID.randomUUID();
		jdbc.update("INSERT INTO account (id, simulation_id, kind, display_name) VALUES (?, ?, 'ISSUANCE', 'Issuance')",
				issuance, simulation);
		for (int i = 0; i < INSTRUMENTS; i++) {
			UUID instrument = new UUID(SEED, -1 - i);
			instruments.add(instrument);
			jdbc.update("INSERT INTO instrument (id, simulation_id, symbol, reference_price_cents) VALUES (?, ?, ?, ?)",
					instrument, simulation, "SYM" + (char) ('A' + i), MID_CENTS);
		}
		String post = "INSERT INTO ledger_entry (batch_id, account_id, instrument_id, bucket, delta) VALUES (?, ?, ?, 'AVAILABLE', ?)";
		for (int a = 0; a < ACCOUNTS; a++) {
			UUID account = new UUID(~SEED, -1 - a);
			accounts.add(account);
			jdbc.update("INSERT INTO account (id, simulation_id, kind, display_name) VALUES (?, ?, 'BOT', ?)", account,
					simulation, "bot-%04d".formatted(a + 1));
			UUID batch = UUID.randomUUID();
			jdbc.update("INSERT INTO journal_batch (id, simulation_id, cause, command_sequence) VALUES (?, ?, 'GENESIS', 1)",
					batch, simulation);
			jdbc.update(post, batch, issuance, null, -100_000_000L);
			jdbc.update(post, batch, account, null, 100_000_000L);
			jdbc.update("""
					INSERT INTO cash_balance (account_id, account_kind, total_cents, reserved_cents)
					VALUES (?, 'BOT', 100000000, 0)""", account);
			for (UUID instrument : instruments) {
				jdbc.update(post, batch, issuance, instrument, -10_000);
				jdbc.update(post, batch, account, instrument, 10_000);
				jdbc.update("""
						INSERT INTO position (account_id, account_kind, instrument_id, total_quantity, reserved_quantity)
						VALUES (?, 'BOT', ?, 10000, 0)""", account, instrument);
			}
		}
	}

}
