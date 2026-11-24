package com.mia_princz.mse.execution.trading.adapter.web;

import java.time.Duration;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mia_princz.mse.execution.trading.application.BotJobResult;
import com.mia_princz.mse.execution.trading.application.CommandKey;
import com.mia_princz.mse.execution.trading.application.StartBotJob;
import com.mia_princz.mse.execution.trading.application.StopBotJob;
import com.mia_princz.mse.execution.trading.application.TradingEngine;

/**
 * Bot jobs: any number of bots created in the background during SETUP, with progress every operator's screen reads.
 * Starting and stopping are setup commands on the writer, with an idempotency key, and audited. The job's parts are
 * commands the exchange sends itself, one after another, so other commands are taken between them.
 */
@RestController
@RequestMapping("/api/v1/admin/bot-jobs")
class BotJobController {

	static final String START_ROUTE = "POST /api/v1/admin/bot-jobs";

	static final String STOP_ROUTE = "POST /api/v1/admin/bot-jobs/{jobId}/stop";

	private final TradingEngine engine;

	private final TradingQueries queries;

	private final Duration timeout;

	BotJobController(TradingEngine engine, TradingQueries queries,
			@Value("${mse.trading.command-timeout:5s}") Duration timeout) {
		this.engine = engine;
		this.queries = queries;
		this.timeout = timeout;
	}

	/**
	 * Answers once the job is recorded, before any bot is made, with the job as it is when answered: a retry of the
	 * key shows how far the same job has got.
	 */
	@PostMapping
	ResponseEntity<BotJobViews.BotJobView> start(
			@RequestHeader(name = "Idempotency-Key", required = false) String keyHeader, @RequestBody BotJobBody body,
			Authentication authentication) {
		UUID key = CommandCalls.idempotencyKey(keyHeader);
		BotJobBody.Valid job = body.validate();
		UUID operator = queries.userId(authentication.getName()).orElseThrow();
		CommandKey commandKey = new CommandKey(operator, key, START_ROUTE, job.hash());
		StartBotJob command = new StartBotJob(UUID.randomUUID(), job.requested(), job.cashCents(), job.holdings(),
				operator);
		BotJobResult result = CommandCalls.await(key, timeout, () -> engine.startBotJob(commandKey, command));
		String sequence = Long.toString(result.commandSequence());
		return switch (result.status()) {
			case STARTED -> ResponseEntity.status(HttpStatus.ACCEPTED)
				.body(BotJobViews.of(queries.botJob(result.job().id()).orElse(result.job())));
			case NOT_IN_SETUP -> throw new ApiException(HttpStatus.CONFLICT, "MARKET_NOT_IN_SETUP",
					"Bots can only be created before the market opens")
				.with("commandSequence", sequence);
			case JOB_RUNNING -> throw new ApiException(HttpStatus.CONFLICT, "BOT_JOB_RUNNING",
					"Bots are already being created: " + BotJobViews.progress(result.job())
							+ ". Wait for that job to finish, or stop it")
				.with("commandSequence", sequence)
				.with("botJob", BotJobViews.of(queries.botJob(result.job().id()).orElse(result.job())));
			case UNKNOWN_INSTRUMENT -> throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "UNKNOWN_INSTRUMENT",
					"A holding names an instrument this market does not have")
				.with("commandSequence", sequence);
			default -> throw new IllegalStateException("Not an answer to starting a job: " + result.status());
		};
	}

	/** The newest job of the current simulation, whatever its state; no content when it has had none. */
	@GetMapping("/current")
	ResponseEntity<BotJobViews.BotJobView> current() {
		return queries.currentBotJob()
			.map((job) -> ResponseEntity.ok(BotJobViews.of(job)))
			.orElse(ResponseEntity.noContent().build());
	}

	@GetMapping("/{jobId}")
	BotJobViews.BotJobView job(@PathVariable UUID jobId) {
		return BotJobViews.of(queries.botJob(jobId).orElseThrow(BotJobController::notFound));
	}

	/** Stops the job after the part being made, if any: the bots made stay, and the rest are not made. */
	@PostMapping("/{jobId}/stop")
	BotJobViews.BotJobView stop(@RequestHeader(name = "Idempotency-Key", required = false) String keyHeader,
			@PathVariable UUID jobId, Authentication authentication) {
		UUID key = CommandCalls.idempotencyKey(keyHeader);
		UUID operator = queries.userId(authentication.getName()).orElseThrow();
		CommandKey commandKey = new CommandKey(operator, key, STOP_ROUTE, CommandCalls.sha256(jobId.toString()));
		BotJobResult result = CommandCalls.await(key, timeout,
				() -> engine.stopBotJob(commandKey, new StopBotJob(jobId, operator)));
		String sequence = Long.toString(result.commandSequence());
		return switch (result.status()) {
			case STOPPED -> BotJobViews.of(result.job());
			case NOT_RUNNING -> throw new ApiException(HttpStatus.CONFLICT, "BOT_JOB_NOT_RUNNING",
					"The job is " + result.job().state().name().toLowerCase() + "; only a running job can be stopped")
				.with("commandSequence", sequence)
				.with("botJob", BotJobViews.of(result.job()));
			case NOT_FOUND -> throw notFound().with("commandSequence", sequence);
			default -> throw new IllegalStateException("Not an answer to stopping a job: " + result.status());
		};
	}

	private static ApiException notFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "BOT_JOB_NOT_FOUND", "No such bot job in this simulation");
	}

}
