package com.mia_princz.mse.marketdata.api;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.mia_princz.mse.marketdata.projection.MarketFeedConsumer;
import com.mia_princz.mse.marketdata.projection.SnapshotProjected;

@Configuration(proxyBeanMethods = false)
class MarketEventsConfiguration {

	@Bean(initMethod = "start", destroyMethod = "close")
	MarketStream marketStream(MarketQueries queries, @Qualifier("marketFeed") ObjectProvider<MarketFeedConsumer> feed,
			Clock clock, MeterRegistry meters, @Value("${mse.market-events.tick:250ms}") Duration tick,
			@Value("${mse.market-events.heartbeat:10s}") Duration heartbeat,
			@Value("${mse.market-events.slow-limit:15s}") Duration slowLimit,
			@Value("${mse.market-events.max-streams:1000}") int maxStreams,
			@Value("${mse.market-events.stream-lifetime:30m}") Duration lifetime) {
		// A new id per process: a client seeing it change knows the service restarted and replaces everything.
		UUID bootId = UUID.randomUUID();
		MarketStream stream = new MarketStream(MarketEventsController.frames(queries, feed, bootId, clock),
				() -> new SseEmitter(lifetime.toMillis()),
				new MarketStream.Settings(tick, heartbeat, slowLimit, maxStreams), clock);
		Gauge.builder("mse.market.events.streams", stream, MarketStream::openStreams)
			.description("Open public market streams")
			.register(meters);
		return stream;
	}

	/** After commit only: a projection change that rolls back is never streamed. */
	@Bean
	ProjectionListener projectionListener(MarketStream stream) {
		return new ProjectionListener(stream);
	}

	static final class ProjectionListener {

		private final MarketStream stream;

		ProjectionListener(MarketStream stream) {
			this.stream = stream;
		}

		@TransactionalEventListener
		void projected(SnapshotProjected event) {
			stream.changed(event.instrumentId());
		}

	}

}
