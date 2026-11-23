package com.mia_princz.mse.execution.trading.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.mia_princz.mse.execution.trading.application.CommandWriter.WriterHaltException;
import com.mia_princz.mse.execution.trading.application.TradingStore.Market;
import com.mia_princz.mse.execution.trading.domain.RestingOrders;
import com.mia_princz.mse.execution.trading.domain.SimulationName;

/**
 * What starting a simulation asks of the store, in which order, and what the engine holds afterwards, with a store
 * that only records. The same against real PostgreSQL is in {@code SimulationStartIT} and
 * {@code SimulationStartEngineIT}.
 */
class SimulationStartTest {

	private static final String ROUTE = "POST /api/v1/admin/simulations";

	private final TradingStore store = mock(TradingStore.class);

	private final CommandWriter writer = new CommandWriter("simulation-start-test", 8);

	private final UUID operator = UUID.randomUUID();

	/** Each simulation the engine said it had moved to, with the one before it. */
	private final List<List<UUID>> told = new ArrayList<>();

	private RuntimeException listenerFails;

	private final TradingEngine engine = new TradingEngine(store, new TransactionRunner() {

		@Override
		public <T> T inTransaction(Supplier<T> work) {
			return work.get();
		}

	}, writer, Clock.systemUTC(), (account) -> new byte[0], AccountChanges.NONE, WriterTimings.NONE, System::nanoTime,
			new RestingOrders(), TradingEngine.CLOSE_CHUNK_ORDERS, Thread::sleep, (previous, simulation) -> {
				told.add(java.util.Arrays.asList(previous, simulation));
				if (listenerFails != null) {
					throw listenerFails;
				}
			});

	@AfterEach
	void stop() throws InterruptedException {
		writer.close();
	}

	/** The explicit way to the first market: no row to lock or retire, epoch 1, and the start is its command 1. */
	@Test
	void withNoSimulationAStartCreatesTheFirst() throws Exception {
		engine.startEmpty();
		CommandKey key = key("Alpha");
		StartSimulation command = start("Alpha");

		SimulationStart started = engine.startSimulation(key, command).get(5, TimeUnit.SECONDS);

		assertThat(started).isEqualTo(new SimulationStart(SimulationStart.Status.STARTED, command.simulationId(),
				"Alpha", "SETUP", 1));
		verify(store, never()).lockOwnedMarket(any(), anyLong());
		verify(store, never()).retireMarket(any(), anyLong());
		verify(store).startMarket(eq(command.simulationId()), any(), eq("Alpha"), eq(1L));
		verify(store).insertAudit(command.simulationId(), operator, "SIMULATION_START", "APPLIED",
				key.idempotencyKey(), 1);
		verify(store).flush(eq(command.simulationId()), argThat((writes) -> writes.results().size() == 1
				&& writes.results().getFirst().httpStatus() == 201 && writes.results().getFirst().commandSequence() == 1
				&& writes.results().getFirst().key().equals(key)));
		assertThat(engine.simulationId()).isEqualTo(command.simulationId());
		assertThat(engine.simulationIdUnlessClosed()).isEqualTo(command.simulationId());
		assertThat(told).containsExactly(java.util.Arrays.asList(null, command.simulationId()));
	}

	/**
	 * After a close: the closed row is locked by this writer's epoch, then retired, and only then is the new one
	 * written, with that same epoch and everything its first command leaves under the new simulation's ID. The engine
	 * moves over once all of that is done.
	 */
	@Test
	void afterACloseTheClosedRowIsRetiredAndTheNewSimulationTakesTheWritersEpoch() throws Exception {
		UUID closed = loaded("CLOSED", 7);
		CommandKey key = key("Beta");
		StartSimulation command = start("Beta");

		SimulationStart started = engine.startSimulation(key, command).get(5, TimeUnit.SECONDS);

		assertThat(started.status()).isEqualTo(SimulationStart.Status.STARTED);
		assertThat(started.commandSequence()).isEqualTo(1);
		InOrder order = inOrder(store);
		order.verify(store).lockOwnedMarket(closed, 7);
		order.verify(store).storedInAnySimulation(key, SimulationStart.class);
		order.verify(store).simulationNameTaken("Beta");
		order.verify(store).retireMarket(closed, 7);
		order.verify(store).startMarket(eq(command.simulationId()), any(), eq("Beta"), eq(7L));
		order.verify(store)
			.insertAudit(command.simulationId(), operator, "SIMULATION_START", "APPLIED", key.idempotencyKey(), 1);
		order.verify(store).flush(eq(command.simulationId()), any());
		verify(store, never()).open(any(), anyLong(), any(), any());
		verify(store, never()).flush(eq(closed), any());
		assertThat(engine.simulationId()).isEqualTo(command.simulationId());
		assertThat(engine.simulationIdUnlessClosed()).as("the new simulation is not closed")
			.isEqualTo(command.simulationId());
		assertThat(told).containsExactly(List.of(closed, command.simulationId()));
	}

	/** A simulation that has not closed refuses the start as one of its own commands, and nothing else is written. */
	@Test
	void whileTheCurrentSimulationHasNotClosedAStartIsItsOwnRefusedCommand() throws Exception {
		for (String state : List.of("SETUP", "OPEN", "HALTED")) {
			UUID current = loaded(state, 3);
			CommandKey key = key("Beta");
			when(store.open(current, 3, key, SimulationStart.class))
				.thenReturn(Optional.of(new TradingStore.Opening<>(market(current, state, 3), 12, Optional.empty())));

			SimulationStart refused = engine.startSimulation(key, start("Beta")).get(5, TimeUnit.SECONDS);

			assertThat(refused).as(state)
				.isEqualTo(new SimulationStart(SimulationStart.Status.NOT_CLOSED, null, "Beta", state, 12));
			verify(store).insertAudit(current, operator, "SIMULATION_START", "NOT_ALLOWED", key.idempotencyKey(), 12);
			verify(store).flush(eq(current), argThat((writes) -> writes.results().size() == 1
					&& writes.results().getFirst().httpStatus() == 409));
			assertThat(engine.simulationId()).isEqualTo(current);
		}
		verify(store, never()).retireMarket(any(), anyLong());
		verify(store, never()).startMarket(any(), any(), anyString(), anyLong());
		assertThat(told).isEmpty();
	}

	/** Names differ, so a closed simulation refuses a start under a name that is taken, again as its own command. */
	@Test
	void aTakenNameIsRefusedInTheClosedSimulation() throws Exception {
		UUID closed = loaded("CLOSED", 2);
		CommandKey key = key("Alpha");
		when(store.simulationNameTaken("Alpha")).thenReturn(true);
		when(store.open(closed, 2, key, SimulationStart.class))
			.thenReturn(Optional.of(new TradingStore.Opening<>(market(closed, "CLOSED", 2), 41, Optional.empty())));

		SimulationStart refused = engine.startSimulation(key, start("Alpha")).get(5, TimeUnit.SECONDS);

		assertThat(refused).isEqualTo(new SimulationStart(SimulationStart.Status.NAME_TAKEN, null, "Alpha", "CLOSED", 41));
		verify(store).insertAudit(closed, operator, "SIMULATION_START", "NOT_ALLOWED", key.idempotencyKey(), 41);
		verify(store, never()).retireMarket(any(), anyLong());
		verify(store, never()).startMarket(any(), any(), anyString(), anyLong());
		assertThat(engine.simulationIdUnlessClosed()).as("still the closed one").isNull();
		assertThat(told).isEmpty();
	}

	/**
	 * A key that has an answer anywhere gets it back and starts nothing, even with the market closed and the name
	 * free: the answer may be in a simulation that is no longer the current one. The same key for another request is
	 * a conflict.
	 */
	@Test
	void aKeyAnsweredInAnySimulationGetsThatAnswerAndStartsNothing() throws Exception {
		UUID closed = loaded("CLOSED", 5);
		CommandKey key = key("Beta");
		SimulationStart first = new SimulationStart(SimulationStart.Status.NOT_CLOSED, null, "Beta", "OPEN", 9);
		when(store.storedInAnySimulation(key, SimulationStart.class))
			.thenReturn(List.of(new TradingStore.StoredResult<>("POST /api/v1/admin/market/open", new byte[32], null),
					new TradingStore.StoredResult<>(ROUTE, key.requestHash(), first)));

		assertThat(engine.startSimulation(key, start("Beta")).get(5, TimeUnit.SECONDS)).isSameAs(first);

		CommandKey other = key("Gamma");
		when(store.storedInAnySimulation(other, SimulationStart.class))
			.thenReturn(List.of(new TradingStore.StoredResult<>(ROUTE, key.requestHash(), null)));
		assertThatThrownBy(() -> engine.startSimulation(other, start("Gamma")).get(5, TimeUnit.SECONDS))
			.hasCauseInstanceOf(IdempotencyConflictException.class);

		verify(store, never()).retireMarket(any(), anyLong());
		verify(store, never()).startMarket(any(), any(), anyString(), anyLong());
		verify(store, never()).insertAudit(any(), any(), anyString(), anyString(), any(), anyLong());
		assertThat(engine.simulationId()).isEqualTo(closed);
		assertThat(told).isEmpty();
		assertThat(writer.isAvailable()).isTrue();
	}

	/** Another writer has taken the market: this one stops at the lock, before it reads or writes anything. */
	@Test
	void aWriterThatNoLongerOwnsTheMarketHaltsAtTheLock() {
		UUID closed = loaded("CLOSED", 5);
		when(store.lockOwnedMarket(closed, 5)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> engine.startSimulation(key("Beta"), start("Beta")).get(5, TimeUnit.SECONDS))
			.hasCauseInstanceOf(WriterHaltException.class);

		verify(store, never()).storedInAnySimulation(any(), any());
		verify(store, never()).retireMarket(any(), anyLong());
		verify(store, never()).startMarket(any(), any(), anyString(), anyLong());
		assertThat(writer.isAvailable()).isFalse();
		assertThat(engine.simulationId()).isEqualTo(closed);
		assertThat(told).isEmpty();
	}

	/** Forgetting is tidying: whoever cannot do it leaves the start applied and the writer running. */
	@Test
	void aListenerThatFailsLeavesTheStartAppliedAndTheWriterRunning() throws Exception {
		engine.startEmpty();
		listenerFails = new IllegalStateException("injected failure while forgetting");
		StartSimulation command = start("Alpha");

		SimulationStart started = engine.startSimulation(key("Alpha"), command).get(5, TimeUnit.SECONDS);

		assertThat(started.status()).isEqualTo(SimulationStart.Status.STARTED);
		assertThat(engine.simulationId()).isEqualTo(command.simulationId());
		assertThat(writer.isAvailable()).isTrue();
		assertThat(told).hasSize(1);
	}

	/** An engine that has taken over a simulation in this state, at this epoch, with nothing in it. */
	private UUID loaded(String state, long epoch) {
		UUID simulation = UUID.randomUUID();
		when(store.lockMarket()).thenReturn(market(simulation, state, epoch - 1));
		when(store.claimWriterEpoch(simulation)).thenReturn(epoch);
		when(store.instruments(simulation)).thenReturn(List.of());
		when(store.reconciliationProblems(simulation)).thenReturn(List.of());
		when(store.restingOrdersByAccount(simulation)).thenReturn(Map.of());
		when(store.tradingAccounts(simulation)).thenReturn(Set.of());
		when(store.lockOwnedMarket(simulation, epoch)).thenReturn(Optional.of(market(simulation, state, epoch)));
		engine.start();
		told.clear();
		return simulation;
	}

	private static Market market(UUID simulation, String state, long epoch) {
		return new Market(simulation, state, epoch, 20, 50_000, 1_000);
	}

	private CommandKey key(String name) {
		byte[] hash = new byte[32];
		hash[0] = (byte) name.hashCode();
		return new CommandKey(operator, UUID.randomUUID(), ROUTE, hash);
	}

	private StartSimulation start(String name) {
		return new StartSimulation(UUID.randomUUID(), new SimulationName(name), operator);
	}

}
