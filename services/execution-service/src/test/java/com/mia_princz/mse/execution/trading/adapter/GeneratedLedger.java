package com.mia_princz.mse.execution.trading.adapter;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import org.postgresql.PGConnection;
import org.postgresql.copy.CopyIn;
import org.postgresql.copy.CopyManager;

/**
 * A large ledger from a fixed seed, so a failure or a timing repeats exactly. Each account is funded by one genesis
 * batch in cash and every instrument; then come reservations, each moving an amount from available to reserved in
 * one asset, and their releases, mixed in random order, as the writer leaves them. Every reservation is released by
 * the end, so balances equal the genesis amounts with nothing reserved, which matches a market with no open orders.
 *
 * <p>
 * Batch IDs are random UUIDs, as the writer's are, so the batch index is in no relation to the table's order. Rows go
 * in by COPY as the owner with the commit-time balance trigger off: every batch is generated balanced, and the
 * reconciliation under test checks that anyway. The generator runs twice with the same seed, first for the batches and
 * then for their entries, so nothing large is held in memory.
 */
final class GeneratedLedger {

	record Ledger(UUID simulation, List<UUID> accounts, List<UUID> instruments, long batches, long entries) {
	}

	private static final long CASH_CENTS = 10_000_000_000L;

	private static final long SHARES = 10_000_000L;

	/** At most this many reservations wait for their release, so no available amount can run out. */
	private static final int MAX_PENDING = 1_000;

	private static final int CHUNK = 1 << 20;

	private final long seed;

	private final int accountCount;

	private final int instrumentCount;

	private final int reservations;

	private GeneratedLedger(long seed, int accountCount, int instrumentCount, int reservations) {
		this.seed = seed;
		this.accountCount = accountCount;
		this.instrumentCount = instrumentCount;
		this.reservations = reservations;
	}

	/** Writes a market and its ledger through {@code owner}, then analyzes the tables, as autovacuum would. */
	static Ledger write(Connection owner, long seed, int accounts, int instruments, int reservations)
			throws SQLException {
		return new GeneratedLedger(seed, accounts, instruments, reservations).write(owner);
	}

	private Ledger write(Connection owner) throws SQLException {
		boolean autoCommit = owner.getAutoCommit();
		owner.setAutoCommit(false);
		CopyManager copy = owner.unwrap(PGConnection.class).getCopyAPI();
		Ids ids = new Ids(new Random(seed));
		try (Statement statement = owner.createStatement()) {
			statement.execute("ALTER TABLE ledger_entry DISABLE TRIGGER ledger_entry_balanced");
			statement.execute("INSERT INTO simulation (id, state, opened_at) VALUES ('" + ids.simulation
					+ "', 'OPEN', now())");
			try (Rows rows = new Rows(copy, "account (id, simulation_id, kind, display_name)")) {
				rows.add(ids.issuance, ids.simulation, "ISSUANCE", "Issuance");
				for (int i = 0; i < accountCount; i++) {
					rows.add(ids.accounts.get(i), ids.simulation, "BOT", "bot-" + (i + 1));
				}
			}
			try (Rows rows = new Rows(copy, "instrument (id, simulation_id, symbol, reference_price_cents)")) {
				for (int i = 0; i < instrumentCount; i++) {
					rows.add(ids.instruments.get(i), ids.simulation, symbol(i), 10_000);
				}
			}
			try (Rows cash = new Rows(copy, "cash_balance (account_id, account_kind, total_cents, reserved_cents)")) {
				for (UUID account : ids.accounts) {
					cash.add(account, "BOT", CASH_CENTS, 0);
				}
			}
			try (Rows positions = new Rows(copy,
					"position (account_id, account_kind, instrument_id, total_quantity, reserved_quantity)")) {
				for (UUID account : ids.accounts) {
					for (UUID instrument : ids.instruments) {
						positions.add(account, "BOT", instrument, SHARES, 0);
					}
				}
			}
			long[] counts = new long[2];
			try (Rows batches = new Rows(copy, "journal_batch (id, simulation_id, cause, command_sequence)")) {
				generate(new Sink() {

					@Override
					public void batch(UUID id, String cause, long sequence) {
						batches.add(id, ids.simulation, cause, sequence);
						counts[0]++;
					}

					@Override
					public void entry(UUID batch, UUID account, UUID instrument, String bucket, long delta) {
					}

				});
			}
			try (Rows entries = new Rows(copy, "ledger_entry (batch_id, account_id, instrument_id, bucket, delta)")) {
				generate(new Sink() {

					@Override
					public void batch(UUID id, String cause, long sequence) {
					}

					@Override
					public void entry(UUID batch, UUID account, UUID instrument, String bucket, long delta) {
						entries.add(batch, account, instrument, bucket, delta);
						counts[1]++;
					}

				});
			}
			statement.execute("ALTER TABLE ledger_entry ENABLE TRIGGER ledger_entry_balanced");
			owner.commit();
			owner.setAutoCommit(true);
			statement.execute("ANALYZE simulation, account, instrument, cash_balance, position, journal_batch, ledger_entry");
			return new Ledger(ids.simulation, ids.accounts, ids.instruments, counts[0], counts[1]);
		}
		catch (SQLException | RuntimeException ex) {
			if (!owner.getAutoCommit()) {
				owner.rollback();
			}
			throw ex;
		}
		finally {
			owner.setAutoCommit(autoCommit);
		}
	}

	/** Every batch and entry in order; the same seed gives the same sequence, IDs included. */
	private void generate(Sink sink) {
		Random random = new Random(seed);
		Ids ids = new Ids(random);
		long sequence = 0;
		for (UUID account : ids.accounts) {
			UUID batch = uuid(random);
			sink.batch(batch, "GENESIS", ++sequence);
			sink.entry(batch, ids.issuance, null, "AVAILABLE", -CASH_CENTS);
			sink.entry(batch, account, null, "AVAILABLE", CASH_CENTS);
			for (UUID instrument : ids.instruments) {
				sink.entry(batch, ids.issuance, instrument, "AVAILABLE", -SHARES);
				sink.entry(batch, account, instrument, "AVAILABLE", SHARES);
			}
		}
		List<Reservation> pending = new ArrayList<>();
		for (int i = 0; i < reservations; i++) {
			UUID account = ids.accounts.get(random.nextInt(accountCount));
			int asset = random.nextInt(instrumentCount + 1);
			UUID instrument = asset == instrumentCount ? null : ids.instruments.get(asset);
			long amount = 1 + random.nextInt(1_000);
			UUID batch = uuid(random);
			sink.batch(batch, "RESERVE", ++sequence);
			sink.entry(batch, account, instrument, "AVAILABLE", -amount);
			sink.entry(batch, account, instrument, "RESERVED", amount);
			pending.add(new Reservation(account, instrument, amount));
			while (pending.size() > MAX_PENDING || (!pending.isEmpty() && random.nextBoolean())) {
				sequence = release(sink, random, pending, sequence);
			}
		}
		while (!pending.isEmpty()) {
			sequence = release(sink, random, pending, sequence);
		}
	}

	private static long release(Sink sink, Random random, List<Reservation> pending, long sequence) {
		int index = random.nextInt(pending.size());
		Reservation released = pending.get(index);
		pending.set(index, pending.get(pending.size() - 1));
		pending.remove(pending.size() - 1);
		UUID batch = uuid(random);
		sink.batch(batch, "RELEASE", sequence + 1);
		sink.entry(batch, released.account(), released.instrument(), "RESERVED", -released.amount());
		sink.entry(batch, released.account(), released.instrument(), "AVAILABLE", released.amount());
		return sequence + 1;
	}

	/** A version 4 UUID from the generator, so the same seed names the same rows. */
	static UUID uuid(Random random) {
		long high = (random.nextLong() & ~0xF000L) | 0x4000L;
		long low = (random.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L;
		return new UUID(high, low);
	}

	private static String symbol(int index) {
		StringBuilder symbol = new StringBuilder("GEN");
		for (int rest = index; ; rest = rest / 26 - 1) {
			symbol.insert(3, (char) ('A' + rest % 26));
			if (rest < 26) {
				return symbol.toString();
			}
		}
	}

	private record Reservation(UUID account, UUID instrument, long amount) {
	}

	private interface Sink {

		void batch(UUID id, String cause, long sequence);

		void entry(UUID batch, UUID account, UUID instrument, String bucket, long delta);

	}

	/** The market's IDs, drawn first from the generator. */
	private final class Ids {

		final UUID simulation;

		final UUID issuance;

		final List<UUID> accounts = new ArrayList<>();

		final List<UUID> instruments = new ArrayList<>();

		Ids(Random random) {
			simulation = uuid(random);
			issuance = uuid(random);
			for (int i = 0; i < accountCount; i++) {
				accounts.add(uuid(random));
			}
			for (int i = 0; i < instrumentCount; i++) {
				instruments.add(uuid(random));
			}
		}

	}

	/** One table's rows in COPY's text format, sent a chunk at a time. */
	private static final class Rows implements AutoCloseable {

		private final CopyIn copy;

		private final StringBuilder chunk = new StringBuilder(CHUNK + 1_024);

		Rows(CopyManager manager, String table) {
			try {
				this.copy = manager.copyIn("COPY " + table + " FROM STDIN");
			}
			catch (SQLException ex) {
				throw new IllegalStateException(ex);
			}
		}

		void add(Object... values) {
			for (int i = 0; i < values.length; i++) {
				chunk.append(i == 0 ? "" : "\t").append(values[i] == null ? "\\N" : values[i]);
			}
			chunk.append('\n');
			if (chunk.length() >= CHUNK) {
				flush();
			}
		}

		private void flush() {
			byte[] bytes = chunk.toString().getBytes(StandardCharsets.UTF_8);
			try {
				copy.writeToCopy(bytes, 0, bytes.length);
			}
			catch (SQLException ex) {
				throw new IllegalStateException(ex);
			}
			chunk.setLength(0);
		}

		@Override
		public void close() throws SQLException {
			flush();
			copy.endCopy();
		}

	}

}
