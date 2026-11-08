package com.mia_princz.mse.execution.trading.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.mia_princz.mse.execution.trading.domain.Side;

class TapeTest {

	private static TapeTrade trade(long sequence) {
		return new TapeTrade(new UUID(0, sequence), sequence, 10_000 + sequence, 1, Side.BUY,
				Instant.ofEpochSecond(1_700_000_000L, sequence * 1_000));
	}

	private static List<TapeTrade> trades(long from, long to) {
		List<TapeTrade> result = new ArrayList<>();
		for (long sequence = from; sequence <= to; sequence++) {
			result.add(trade(sequence));
		}
		return result;
	}

	private static List<Long> sequences(List<TapeTrade> trades) {
		return trades.stream().map(TapeTrade::tradeSequence).toList();
	}

	@Test
	void theCommandsOwnTradesComeFirstNewestFirstWithoutChangingTheTape() {
		Tape tape = new Tape(5, List.of(trade(3), trade(2), trade(1)));

		assertThat(sequences(tape.with(trades(4, 5)))).containsExactly(5L, 4L, 3L, 2L, 1L);
		assertThat(sequences(tape.with(List.of()))).as("nothing was recorded").containsExactly(3L, 2L, 1L);
	}

	@Test
	void recordedTradesStayAndTheOldestGoBeyondTheCapacity() {
		Tape tape = new Tape(3, List.of());
		tape.add(trades(1, 2));
		tape.add(trades(3, 4));

		assertThat(sequences(tape.with(List.of()))).containsExactly(4L, 3L, 2L);
		assertThat(sequences(tape.with(trades(5, 9)))).as("a command with more trades than the tape holds")
			.containsExactly(9L, 8L, 7L);
	}

	@Test
	void aTapeFromTheDatabaseIsCutToTheCapacity() {
		Tape tape = new Tape(2, List.of(trade(9), trade(8), trade(7)));

		assertThat(sequences(tape.with(List.of()))).containsExactly(9L, 8L);
	}

	@Test
	void aTapeHoldsAtLeastOneTrade() {
		assertThatIllegalArgumentException().isThrownBy(() -> new Tape(0, List.of()));
	}

	/**
	 * Against the database's own answer: seeded commands each make 0 to 7 trades, some roll back. Whatever happened,
	 * the snapshot of the next command equals the newest trades of every committed one, as a query would return them.
	 */
	@Test
	void agreesWithTheNewestCommittedTradesOnSeededCommands() {
		Random random = new Random(20261108L);
		for (int run = 0; run < 200; run++) {
			int capacity = 1 + random.nextInt(60);
			List<TapeTrade> committed = new ArrayList<>();
			int start = random.nextInt(80);
			for (long sequence = 1; sequence <= start; sequence++) {
				committed.add(trade(sequence));
			}
			Tape tape = new Tape(capacity, newest(committed, capacity));
			long next = start + 1;
			for (int command = 0; command < 100; command++) {
				List<TapeTrade> made = trades(next, next + random.nextInt(8) - 1);
				List<TapeTrade> expected = new ArrayList<>(committed);
				expected.addAll(made);
				assertThat(tape.with(made)).as("run %d command %d", run, command).isEqualTo(newest(expected, capacity));
				if (random.nextInt(10) == 0) {
					continue;
				}
				tape.add(made);
				committed.addAll(made);
				next += made.size();
			}
		}
	}

	private static List<TapeTrade> newest(List<TapeTrade> oldestFirst, int capacity) {
		List<TapeTrade> result = new ArrayList<>();
		for (int i = oldestFirst.size() - 1; i >= 0 && result.size() < capacity; i--) {
			result.add(oldestFirst.get(i));
		}
		return result;
	}

}
