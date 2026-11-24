package com.mia_princz.mse.execution.trading.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
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

	/** Only the accounts named lose their files; one that has none, or whose file can't be deleted, stops nothing. */
	@Test
	void discardingDeletesTheAccountsFilesAndNeverFails() throws Exception {
		Path directory = temporary.resolve("secrets");
		FileBotCredentials credentials = new FileBotCredentials(directory);
		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();
		UUID kept = UUID.randomUUID();
		UUID stuck = UUID.randomUUID();
		for (UUID account : List.of(first, second, kept)) {
			credentials.issue(account);
		}
		// A directory with something in it, where a token file would be: it can't be deleted as a file.
		Files.createDirectories(directory.resolve(stuck + ".token").resolve("inside"));

		credentials.discard(List.of(first, UUID.randomUUID(), stuck, second));

		try (Stream<Path> files = Files.list(directory)) {
			assertThat(files.map((file) -> file.getFileName().toString()))
				.containsExactlyInAnyOrder(kept + ".token", stuck + ".token");
		}
		// Nothing was ever written: there is no directory to look in, and that is not a failure either.
		new FileBotCredentials(temporary.resolve("never-made")).discard(List.of(first));
		assertThat(temporary.resolve("never-made")).doesNotExist();
	}

	/**
	 * A batch: each account's file holds the token whose hash comes back in the same place, every file is under its
	 * final name, and no temporary file is left. Fewer threads than files, so that some threads write several.
	 */
	@Test
	void aBatchGetsOneCompleteFileForEachAccountInOrder() throws Exception {
		Path directory = temporary.resolve("secrets");
		List<UUID> accounts = Stream.generate(UUID::randomUUID).limit(50).toList();
		List<byte[]> hashes;
		try (FileBotCredentials credentials = new FileBotCredentials(directory, 4)) {
			hashes = credentials.issueAll(accounts);
		}

		assertThat(hashes).hasSize(50);
		for (int i = 0; i < accounts.size(); i++) {
			String token = Files.readString(directory.resolve(accounts.get(i) + ".token"), StandardCharsets.US_ASCII)
				.strip();
			assertThat(ApiTokens.wellFormed(token)).isTrue();
			assertThat(hashes.get(i)).as("the hash of account %d's own token", i).isEqualTo(ApiTokens.hash(token));
		}
		try (Stream<Path> files = Files.list(directory)) {
			assertThat(files.map((file) -> file.getFileName().toString()))
				.containsExactlyInAnyOrderElementsOf(accounts.stream().map((account) -> account + ".token").toList());
		}
	}

	/**
	 * One file of a batch that cannot be put in place fails the whole batch, naming that account, and the files
	 * already written are deleted: nothing is committed for any of them, so none of their tokens is wanted.
	 */
	@Test
	void aBatchThatFailsLeavesNoFileBehind() throws Exception {
		Path directory = temporary.resolve("secrets");
		List<UUID> accounts = Stream.generate(UUID::randomUUID).limit(10).toList();
		UUID blocked = accounts.get(6);
		// A directory with something in it where the seventh file goes: nothing can be renamed over it.
		Files.createDirectories(directory.resolve(blocked + ".token").resolve("inside"));

		try (FileBotCredentials credentials = new FileBotCredentials(directory, 3)) {
			assertThatThrownBy(() -> credentials.issueAll(accounts)).isInstanceOf(UncheckedIOException.class)
				.hasMessageContaining(blocked.toString())
				.hasMessageNotContaining(ApiTokens.PREFIX);
		}

		try (Stream<Path> files = Files.list(directory)) {
			assertThat(files.map((file) -> file.getFileName().toString())).containsExactly(blocked + ".token");
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
