package com.mia_princz.mse.execution.trading.adapter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;

import com.mia_princz.mse.execution.identity.ApiTokens;
import com.mia_princz.mse.execution.trading.application.BotCredentials;

/**
 * Writes each bot's token to its own file in the simulator's secret directory, named {@code <accountId>.token} and
 * readable by its owner only. The file is synced and then renamed into place, so it is either complete or absent.
 * The token is never logged; a failure names the account, not the token.
 */
final class FileBotCredentials implements BotCredentials {

	static final String SUFFIX = ".token";

	private final Path directory;

	FileBotCredentials(Path directory) {
		this.directory = directory;
	}

	@Override
	public byte[] issue(UUID accountId) {
		ApiTokens.Issued issued = ApiTokens.issue();
		try {
			Files.createDirectories(directory, permissions("rwx------"));
			Path temporary = Files.createTempFile(directory, ".", ".partial", permissions("rw-------"));
			try (FileChannel file = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
				file.write(ByteBuffer.wrap((issued.token() + "\n").getBytes(StandardCharsets.US_ASCII)));
				file.force(true);
			}
			Files.move(temporary, directory.resolve(accountId + SUFFIX), StandardCopyOption.ATOMIC_MOVE);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Could not hand over the token for bot account " + accountId, ex);
		}
		return issued.hash();
	}

	/** Owner-only permissions where the file system has them; elsewhere, such as on Windows, the defaults. */
	private static FileAttribute<?>[] permissions(String posix) {
		if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
			return new FileAttribute<?>[0];
		}
		return new FileAttribute<?>[] { PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(posix)) };
	}

}
