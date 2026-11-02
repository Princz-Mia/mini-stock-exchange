package com.mia_princz.mse.marketdata;

import java.nio.file.Path;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

	// Digests match compose.yaml (apache/kafka:4.3.2, postgres:18.6).
	static final String KAFKA_IMAGE = "apache/kafka@sha256:852a0d49fc4e656b022e7f23785411802c9a088c78a7c5e39f33969de9c227a7";

	static final String POSTGRES_IMAGE = "postgres@sha256:3a4264520e121176619f54ff867d12774bc81e95e7b84db58d63bccdb6144b81";

	static final String EXECUTION_OWNER_PASSWORD = "test-execution-owner";

	static final String EXECUTION_APP_PASSWORD = "test-execution-app";

	static final String MARKET_DATA_OWNER_PASSWORD = "test-market-data-owner";

	static final String MARKET_DATA_APP_PASSWORD = "test-market-data-app";

	@Bean
	@ServiceConnection
	KafkaContainer kafkaContainer() {
		return new KafkaContainer(DockerImageName.parse(KAFKA_IMAGE).asCompatibleSubstituteFor("apache/kafka"));
	}

	/** Starts from the same init script as Compose, so tests exercise the real roles and grants. */
	@Bean
	PostgreSQLContainer postgresContainer() {
		Path initScript = Path.of("../../infra/postgres/init/10-service-databases.sh").toAbsolutePath().normalize();
		return new PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE).asCompatibleSubstituteFor("postgres"))
			.withDatabaseName("postgres")
			.withEnv("EXECUTION_DB_OWNER_PASSWORD", EXECUTION_OWNER_PASSWORD)
			.withEnv("EXECUTION_DB_APP_PASSWORD", EXECUTION_APP_PASSWORD)
			.withEnv("MARKET_DATA_DB_OWNER_PASSWORD", MARKET_DATA_OWNER_PASSWORD)
			.withEnv("MARKET_DATA_DB_APP_PASSWORD", MARKET_DATA_APP_PASSWORD)
			.withCopyFileToContainer(MountableFile.forHostPath(initScript),
					"/docker-entrypoint-initdb.d/10-service-databases.sh");
	}

	@Bean
	DynamicPropertyRegistrar databaseProperties(PostgreSQLContainer postgres) {
		return (registry) -> {
			registry.add("spring.datasource.url", () -> jdbcUrl(postgres, "market_data_db"));
			registry.add("spring.datasource.password", () -> MARKET_DATA_APP_PASSWORD);
			registry.add("spring.flyway.password", () -> MARKET_DATA_OWNER_PASSWORD);
		};
	}

	static String jdbcUrl(PostgreSQLContainer postgres, String database) {
		return "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + database;
	}

}
