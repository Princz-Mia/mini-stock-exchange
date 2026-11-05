package com.mia_princz.mse.execution.trading.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.mia_princz.mse.execution.identity.ApiTokens;

class FileBotCredentialsTest {

	@TempDir
	Path temporary;

	@Test
	void eachTokenGetsItsOwnCompleteFileAndOnlyItsHashComesBack() throws Exception {
		Path directory = temporary.resolve("secrets");
		FileBotCredentials credentials = new FileBotCredentials(directory);
		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();

		byte[] firstHash = credentials.issue(first);
		byte[] secondHash = credentials.issue(second);

		String token = Files.readString(directory.resolve(first + ".token"), StandardCharsets.US_ASCII).strip();
		assertThat(ApiTokens.wellFormed(token)).isTrue();
		assertThat(firstHash).isEqualTo(ApiTokens.hash(token)).isNotEqualTo(secondHash);
		try (Stream<Path> files = Files.list(directory)) {
			assertThat(files.map((file) -> file.getFileName().toString()))
				.containsExactlyInAnyOrder(first + ".token", second + ".token");
		}
		if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
			assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(directory.resolve(first + ".token"))))
				.isEqualTo("rw-------");
			assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(directory))).isEqualTo("rwx------");
		}
	}

	@Test
	void aFailureNamesTheAccountButNoToken() throws Exception {
		Path notADirectory = Files.writeString(temporary.resolve("file"), "x");
		UUID account = UUID.randomUUID();
		assertThatThrownBy(() -> new FileBotCredentials(notADirectory).issue(account))
			.isInstanceOf(UncheckedIOException.class)
			.hasMessageContaining(account.toString())
			.hasMessageNotContaining(ApiTokens.PREFIX);
	}

}
