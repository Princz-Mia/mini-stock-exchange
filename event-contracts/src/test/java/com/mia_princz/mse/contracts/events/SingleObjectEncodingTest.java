package com.mia_princz.mse.contracts.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.apache.avro.message.BadHeaderException;
import org.apache.avro.message.MissingSchemaException;
import org.apache.avro.util.ClassSecurityValidator;
import org.junit.jupiter.api.Test;

class SingleObjectEncodingTest {

	private static final Instant AT = Instant.parse("2026-11-02T10:15:30.123456Z").truncatedTo(ChronoUnit.MICROS);

	@Test
	void tradeRoundTripsThroughTheSingleObjectFormat() throws Exception {
		TradeExecuted trade = trade();

		byte[] encoded = WireEvents.encode(trade);

		assertThat(encoded[0]).as("single-object marker").isEqualTo((byte) 0xC3);
		assertThat(encoded[1]).as("format version").isEqualTo((byte) 0x01);
		assertThat(WireEvents.decodeTrade(encoded)).isEqualTo(trade);
	}

	@Test
	void candleKeepsExactNotionalBeyondLongRange() throws Exception {
		BigDecimal beyondLong = new BigDecimal("99999999999999999999999999999999999999");
		CandleUpdated candle = CandleUpdated.newBuilder()
			.setEventId(UUID.randomUUID())
			.setComputationEpoch(1)
			.setSimulationId(UUID.randomUUID())
			.setInstrumentId(UUID.randomUUID())
			.setSymbol("NOVA")
			.setBucketStart(AT.truncatedTo(ChronoUnit.MINUTES))
			.setOpenCents(10000)
			.setHighCents(10100)
			.setLowCents(10000)
			.setCloseCents(10100)
			.setVolume(10)
			.setTradeCount(3)
			.setNotionalCents(beyondLong)
			.setSourceMaxTradeSequence(3)
			.setGeneratedAt(AT)
			.build();

		assertThat(WireEvents.decodeCandle(WireEvents.encode(candle)).getNotionalCents()).isEqualTo(beyondLong);
	}

	@Test
	void unknownWriterFingerprintIsRejectedNotDropped() throws Exception {
		byte[] encoded = WireEvents.encode(trade());
		encoded[2] ^= (byte) 0xFF;

		assertThatThrownBy(() -> WireEvents.decodeTrade(encoded)).isInstanceOf(MissingSchemaException.class);
	}

	@Test
	void payloadWithoutTheMarkerIsRejected() {
		assertThatThrownBy(() -> WireEvents.decodeTrade(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 }))
			.isInstanceOf(BadHeaderException.class);
	}

	@Test
	void trustExtendsOnlyToTheContractClasses() throws Exception {
		WireEvents.decodeTrade(WireEvents.encode(trade()));

		ClassSecurityValidator.validate(TradeExecuted.class);
		assertThatThrownBy(() -> ClassSecurityValidator.validate(java.util.ArrayList.class))
			.isInstanceOf(SecurityException.class);
	}

	private static TradeExecuted trade() {
		return TradeExecuted.newBuilder()
			.setEventId(UUID.randomUUID())
			.setSimulationId(UUID.randomUUID())
			.setInstrumentId(UUID.randomUUID())
			.setSymbol("NOVA")
			.setTradeId(UUID.randomUUID())
			.setTradeSequence(1)
			.setCommandSequence(1)
			.setExecutedAt(AT)
			.setPriceCents(10000)
			.setQuantity(3)
			.build();
	}
}
