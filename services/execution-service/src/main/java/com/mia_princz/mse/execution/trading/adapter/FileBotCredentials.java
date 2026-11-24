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
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mia_princz.mse.execution.identity.ApiTokens;
import com.mia_princz.mse.execution.trading.application.BotCredentials;

/**
 * Writes each bot's token to its own file in the simulator's secret directory, named {@code <accountId>.token} and
 * readable by its owner only. Each file is written under a temporary name and synced, then renamed into place, and
 * the directory is synced once the names are in place, so a file is either complete or absent and its name survives
 * a power loss. The token is never logged; a failure names the account, not the token. A file is deleted again once
 * its token has been revoked for good, when its market closes.
 * <p>
 * Syncing a file waits for the disk, nearly all of a token's cost, so the files of one batch are written and synced
 * on a small pool of threads at once: concurrent syncs on one file system share its journal commits. Each file still
 * gets its own sync before it is renamed, and the whole batch is durable before {@link #issueAll} returns.
 */
final class FileBotCredentials implements BotCredentials, AutoCloseable {

	static final String SUFFIX = ".token";

	/** Measured on Docker Desktop's ext4: 32 concurrent syncs of 100 files took a tenth of the time of one at a time. */
	static final int SYNC_THREADS = 32;

	/** The most token files open at once: a batch of any size is written and synced this many at a time. */
	static final int OPEN_FILES = 256;

	private static final Logger LOG = LoggerFactory.getLogger(FileBotCredentials.class);

	private final Path directory;

	private final ExecutorService syncing;

	FileBotCredentials(Path directory) {
		this(directory, SYNC_THREADS);
	}

	FileBotCredentials(Path directory, int syncThreads) {
		if (syncThreads < 1) {
			throw new IllegalArgumentException("At least one thread syncs the token files: " + syncThreads);
		}
		this.directory = directory;
		this.syncing = Executors.newFixedThreadPool(syncThreads,
				Thread.ofPlatform().name("bot-token-sync-", 1).daemon().factory());
	}

	@Override
	public byte[] issue(UUID accountId) {
		return issueAll(List.of(accountId)).getFirst();
	}

	/**
	 * Every token of the batch is in its file, synced, under its final name, with the directory synced, once this
	 * returns. If anything fails, the files written so far are deleted where they can be (a file left behind belongs
	 * to an account that is never committed, so its token authenticates nothing) and this throws, naming the first
	 * account whose token could not be handed over.
	 */
	@Override
	public List<byte[]> issueAll(List<UUID> accountIds) {
		List<ApiTokens.Issued> issued = new ArrayList<>(accountIds.size());
		for (int i = 0; i < accountIds.size(); i++) {
			issued.add(ApiTokens.issue());
		}
		List<Path> temporaries = new ArrayList<>(accountIds.size());
		List<Path> placed = new ArrayList<>(accountIds.size());
		// The account a failure is reported for: the first one until the files are being put in place.
		UUID current = accountIds.isEmpty() ? null : accountIds.getFirst();
		try {
			Files.createDirectories(directory, permissions("rwx------"));
			for (int from = 0; from < accountIds.size(); from += OPEN_FILES) {
				int to = Math.min(accountIds.size(), from + OPEN_FILES);
				writeSynced(issued.subList(from, to), accountIds.subList(from, to), temporaries);
			}
			for (int i = 0; i < accountIds.size(); i++) {
				current = accountIds.get(i);
				Path target = directory.resolve(current + SUFFIX);
				Files.move(temporaries.get(i), target, StandardCopyOption.ATOMIC_MOVE);
				temporaries.set(i, null);
				placed.add(target);
			}
			syncDirectory();
		}
		catch (IOException ex) {
			deleteQuietly(temporaries);
			deleteQuietly(placed);
			if (ex instanceof AccountFailure file) {
				throw new UncheckedIOException(failure(file.account, accountIds.size()), (IOException) file.getCause());
			}
			throw new UncheckedIOException(failure(current, accountIds.size()), ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			deleteQuietly(temporaries);
			deleteQuietly(placed);
			throw new UncheckedIOException(failure(current, accountIds.size()),
					new IOException("Interrupted while the token files were written", ex));
		}
		List<byte[]> hashes = new ArrayList<>(issued.size());
		issued.forEach((token) -> hashes.add(token.hash()));
		return hashes;
	}

	private static String failure(UUID account, int batch) {
		return batch == 1 ? "Could not hand over the token for bot account " + account
				: "Could not hand over the tokens of " + batch + " bot accounts, first for bot account " + account;
	}

	/**
	 * Writes each token to its temporary file, owner-only from its creation, then syncs and closes them all on the
	 * pool, adding each path to {@code temporaries} as it is created. Writing every file before syncing any was the
	 * faster order on both file systems measured. A failure is thrown once every sync has ended and every file is
	 * closed, naming the account of the first file that failed.
	 */
	private void writeSynced(List<ApiTokens.Issued> tokens, List<UUID> accounts, List<Path> temporaries)
			throws IOException, InterruptedException {
		List<FileChannel> open = new ArrayList<>(tokens.size());
		for (int i = 0; i < tokens.size(); i++) {
			try {
				Path temporary = Files.createTempFile(directory, ".", ".partial", permissions("rw-------"));
				temporaries.add(temporary);
				FileChannel file = FileChannel.open(temporary, StandardOpenOption.WRITE);
				open.add(file);
				file.write(ByteBuffer.wrap((tokens.get(i).token() + "\n").getBytes(StandardCharsets.US_ASCII)));
			}
			catch (IOException ex) {
				closeQuietly(open);
				throw new AccountFailure(accounts.get(i), ex);
			}
		}
		List<Future<Void>> synced = new ArrayList<>(open.size());
		for (FileChannel file : open) {
			synced.add(syncing.submit(() -> {
				try (file) {
					file.force(true);
				}
				return null;
			}));
		}
		AccountFailure first = null;
		for (int i = 0; i < synced.size(); i++) {
			try {
				synced.get(i).get();
			}
			catch (ExecutionException ex) {
				if (first == null) {
					first = new AccountFailure(accounts.get(i),
							ex.getCause() instanceof IOException io ? io : new IOException(ex.getCause()));
				}
			}
		}
		if (first != null) {
			throw first;
		}
	}

	/** A file that failed, with the account it was for, which the batch's failure names. */
	private static final class AccountFailure extends IOException {

		private static final long serialVersionUID = 1L;

		private final transient UUID account;

		AccountFailure(UUID account, IOException cause) {
			super(cause.getMessage(), cause);
			this.account = account;
		}

	}

	private static void closeQuietly(List<FileChannel> files) {
		for (FileChannel file : files) {
			try {
				file.close();
			}
			catch (IOException ex) {
				// Its file is deleted with the batch's.
			}
		}
	}

	/**
	 * Makes the renames durable: a file's own sync covers its data and its temporary name, and the rename lands with a
	 * later journal commit unless the directory is synced. Windows cannot open a directory to sync it, so where the
	 * file system has no POSIX view, which only development runs on, this is skipped as the permissions are.
	 */
	private void syncDirectory() throws IOException {
		if (!posix()) {
			return;
		}
		try (FileChannel folder = FileChannel.open(directory, StandardOpenOption.READ)) {
			folder.force(true);
		}
	}

	private static void deleteQuietly(List<Path> paths) {
		for (Path path : paths) {
			if (path == null) {
				continue;
			}
			try {
				Files.deleteIfExists(path);
			}
			catch (IOException | RuntimeException ex) {
				// Left behind: it belongs to an account that is never committed.
			}
		}
	}

	/** Deletes each account's token file if it is there. One that can't be deleted is left, and counted in the log. */
	@Override
	public void discard(Collection<UUID> accountIds) {
		int kept = 0;
		for (UUID accountId : accountIds) {
			try {
				Files.deleteIfExists(directory.resolve(accountId + SUFFIX));
			}
			catch (IOException | RuntimeException ex) {
				if (kept++ == 0) {
					LOG.warn("Could not delete the token file of bot account {}; its token is revoked", accountId, ex);
				}
			}
		}
		if (kept > 1) {
			LOG.warn("{} token files of revoked bot tokens could not be deleted", kept);
		}
	}

	@Override
	public void close() {
		syncing.shutdownNow();
	}

	private static boolean posix() {
		return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
	}

	/** Owner-only permissions where the file system has them; elsewhere, such as on Windows, the defaults. */
	private static FileAttribute<?>[] permissions(String posix) {
		if (!posix()) {
			return new FileAttribute<?>[0];
		}
		return new FileAttribute<?>[] { PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(posix)) };
	}

}
