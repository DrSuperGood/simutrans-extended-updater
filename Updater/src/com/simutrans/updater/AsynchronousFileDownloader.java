package com.simutrans.updater;

import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.Channels;
import java.nio.channels.CompletionHandler;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Class to download files from URLs asynchronously. Includes features such as
 * approximate bandwidth and rate measurements.
 * 
 * @author Dr Super Good
 */
public class AsynchronousFileDownloader implements AutoCloseable {
	/**
	 * Connection timeout in milliseconds.
	 */
	public static final int CONNECTION_TIMEOUT = 30000;

	/**
	 * Default connection count. Chosen to be a sensible value allowing some
	 * parallelism without excessive resource usage.
	 */
	public static final int DEFAULT_CONNECTION_COUNT = 16;

	/**
	 * Default buffer length. Chosen to be a sensible value to allow efficient IO
	 * without being excessively large.
	 */
	public static final int DEFAULT_BUFFER_LENGTH = 32 << 10;

	/**
	 * The Executor used to process URL downloads.
	 */
	private final ExecutorService executor;

	/**
	 * An approximation of the bytes remaining to download currently scheduled
	 * files. This might not be accurate if the server modifies the files from the
	 * time the size was queried.
	 */
	private final AtomicLong bytesRemaining;

	/**
	 * The total bytes of files downloaded. This approximately relates to bandwidth
	 * usage but does not factor in communication overhead.
	 */
	private final AtomicLong bytesDownloaded;

	/**
	 * The length of the read buffers used to receive data.
	 */
	private final int bufferLength;

	public AsynchronousFileDownloader() {
		this(DEFAULT_CONNECTION_COUNT, DEFAULT_BUFFER_LENGTH);
	}

	/**
	 * Construct a file downloader with specified parallelism and buffer size.
	 * <p>
	 * The larger connectionCount is, the more downloads are allowed to occur in
	 * parallel. Some parallelism is good for throughput as it counteracts network
	 * and processing latency. Too much is bad as a large number of requests can tax
	 * servers.
	 * <p>
	 * The larger bufferLength is, the more will be downloaded before being written
	 * out.
	 * 
	 * @param connectionCount
	 *            The maximum number of parallel URL connections allowed at any
	 *            given time.
	 * @param bufferLength
	 *            The size of IO buffers used.
	 */
	public AsynchronousFileDownloader(final int connectionCount, final int bufferLength) {
		executor = Executors.newFixedThreadPool(connectionCount);
		this.bufferLength = bufferLength;
		bytesRemaining = new AtomicLong(0L);
		bytesDownloaded = new AtomicLong(0L);
	}

	public long getRemainingBytes() {
		return bytesRemaining.get();
	}

	public long getDownloadedBytes() {
		return bytesDownloaded.get();
	}

	/**
	 * Attempts to shutdown the file downloader.
	 * 
	 * @param timeout
	 *            Timeout in milliseconds to try and shut down.
	 * @throws InterruptedException
	 *             If the downloader could not be shutdown within timeout.
	 */
	public void shutdown(long timeout) {
		executor.shutdown();
		try {
			executor.awaitTermination(timeout, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			// Nothing one can really do.
		}
	}

	/**
	 * Creates a download future that will download the file located at from into a
	 * local file located at to.
	 * <p>
	 * On completion the file at to will contain a copy of the content at from. If
	 * an exception occurs the contents of to might be incomplete.
	 * <p>
	 * Unless always is set, the file will only be downloaded if it does not exist
	 * or the URL has a different creation date or a different content size from the
	 * existing file.
	 * 
	 * @param from
	 *            Source URL.
	 * @param to
	 *            Destination file.
	 * @param always
	 *            If the file should always be downloaded.
	 * @return
	 */
	public CompletableFuture<Void> download(final URL from, final Path to, boolean always) {
		final CompletableFuture<Void> cf = new CompletableFuture<>();

		final Runnable downloadRunable = new Runnable() {
			@Override
			public void run() {
				long expectedSize = 0;

				try {
					// Check connection.
					URLConnection connection;
					connection = from.openConnection();
					connection.setUseCaches(false);
					connection.setConnectTimeout(CONNECTION_TIMEOUT);
					connection.setReadTimeout(CONNECTION_TIMEOUT);
					connection.connect();
					
					expectedSize = connection.getContentLengthLong();
					bytesRemaining.addAndGet(expectedSize);
					
					// Check exiting file.
					final long lastModified = connection.getLastModified();
					try {
						if (!always && Files.isRegularFile(to) && ((FileTime)(Files.getAttribute(to, "lastModifiedTime"))).toMillis() == lastModified) {
							cf.complete(null);
							return;
						}
					} catch (IOException e) {
						// Could not determine, download anyway.
					}

					// Prepare file.
					/*Files.deleteIfExists(to);
					Files.createDirectories(to.getParent());
					Files.createFile(to);*/

					// Open channels.
					final Semaphore writesComplete = new Semaphore(0);
					try (final AsynchronousFileChannel out = AsynchronousFileChannel.open(to, StandardOpenOption.WRITE);
							final ReadableByteChannel in = Channels.newChannel(connection.getInputStream())) {
						boolean eof = false;
						int blocks = 0;
						final AtomicBoolean abort = new AtomicBoolean(false);
						while (!eof) {
							final ByteBuffer buff = ByteBuffer.allocate(bufferLength);
							while (buff.hasRemaining() && !eof) {
								eof = in.read(buff) == -1;
							}
							bytesDownloaded.addAndGet(buff.position());
							bytesRemaining.addAndGet(-buff.position());
							expectedSize -= buff.position();

							// Check if must abort.
							if (abort.get()) {
								return;
							}

							buff.flip();
							out.write(buff, (long) blocks * (long) bufferLength, cf,
									new CompletionHandler<Integer, CompletableFuture<Void>>() {
										final int expected = buff.limit();

										@Override
										public void completed(Integer result, CompletableFuture<Void> attachment) {
											if (result.intValue() != expected) {
												abort.set(true);
												attachment.completeExceptionally(new IOException(
														"AsynchronousFileChannel failed to write all bytes."));
											}

											writesComplete.release();
										}

										@Override
										public void failed(Throwable exc, CompletableFuture<Void> attachment) {
											abort.set(true);
											attachment.completeExceptionally(exc);
											writesComplete.release();
										}
									});

							blocks += 1;
						}

						writesComplete.acquire(blocks);

						// Check if must abort.
						if (abort.get()) {
							return;
						}
					}

					final FileTime time = FileTime.fromMillis(lastModified);
					//Files.setAttribute(to, "creationTime", time);
					Files.setAttribute(to, "lastModifiedTime", time);
				} catch (IOException | InterruptedException e) {
					cf.completeExceptionally(e);
				} finally {
					bytesRemaining.addAndGet(-expectedSize);
				}

				cf.complete(null);
			}
		};

		CompletableFuture.runAsync(downloadRunable, executor);

		return cf;
	}

	@Override
	public void close() {
		shutdown(30000);
	}
}
