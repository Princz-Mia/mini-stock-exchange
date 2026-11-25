package com.mia_princz.mse.execution.trading.adapter;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import tools.jackson.databind.json.JsonMapper;

import com.mia_princz.mse.execution.trading.application.BotJob;
import com.mia_princz.mse.execution.trading.application.ProvisionAccount;

/** A {@code bot_job} row as a {@link BotJob}, read the same way by the writer and by the operator's reads. */
public final class BotJobRows {

	/** Every column a job is read from, for a SELECT from {@code bot_job}. */
	public static final String COLUMNS = """
			id, simulation_id, started_by, idempotency_key, state, part_size, market_makers, noise, momentum,
			created_market_makers, created_noise, created_momentum, parts_done, cash_cents, holdings::text AS holdings,
			started_at, run_started_at, created_at_run_start, last_part_at, finished_at, failure, resumed""";

	private BotJobRows() {
	}

	public static BotJob read(ResultSet row, JsonMapper json) throws SQLException {
		List<ProvisionAccount.Holding> holdings = Arrays
			.asList(json.readValue(row.getString("holdings"), ProvisionAccount.Holding[].class));
		return new BotJob(row.getObject("id", UUID.class), row.getObject("simulation_id", UUID.class),
				row.getObject("started_by", UUID.class), row.getObject("idempotency_key", UUID.class),
				BotJob.State.valueOf(row.getString("state")), row.getInt("part_size"),
				new BotJob.Counts(row.getInt("market_makers"), row.getInt("noise"), row.getInt("momentum")),
				new BotJob.Counts(row.getInt("created_market_makers"), row.getInt("created_noise"),
						row.getInt("created_momentum")),
				row.getInt("parts_done"), row.getLong("cash_cents"), holdings, instant(row, "started_at"),
				instant(row, "run_started_at"), row.getInt("created_at_run_start"), instant(row, "last_part_at"),
				instant(row, "finished_at"), row.getString("failure"), row.getInt("resumed"));
	}

	static String holdings(BotJob job, JsonMapper json) {
		return json.writeValueAsString(job.holdings());
	}

	static Timestamp timestamp(Instant at) {
		return at == null ? null : Timestamp.from(at);
	}

	private static Instant instant(ResultSet row, String column) throws SQLException {
		Timestamp at = row.getTimestamp(column);
		return at == null ? null : at.toInstant();
	}

}
