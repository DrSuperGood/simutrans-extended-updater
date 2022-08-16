package com.simutrans.updater;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public class Updater implements Runnable {
	private final Path root;
	private final URL hashURL;
	private final String hashName;
	private final String archiveURLString;
	private final String skiplistName;
	
	public final SubscriptionSite<ProgressState> progressSubscription = new SubscriptionSite<ProgressState>() {};
	
	public final SubscriptionSite<Path> deletedSubscription = new SubscriptionSite<Path>() {};
	
	public final SubscriptionSite<Path> downloadedSubscription = new SubscriptionSite<Path>() {};
	
	public final SubscriptionSite<Long> downloadSubscription = new SubscriptionSite<Long>() {};
	
	public final SubscriptionSite<Throwable> exceptionSubscription = new SubscriptionSite<Throwable>() {};
	
	public Updater(final Path root, final URL hashURL, final String hashName, final String archiveURLString, String skiplistName) {
		this.root = root;
		this.hashURL = hashURL;
		this.hashName = hashName;
		this.archiveURLString = archiveURLString;
		this.skiplistName = skiplistName;
	}
	
	public enum ProgressState {
		INIT,
		COPYING_HASH_MANIFEST,
		DOWNLOADING_HASH_MANIFEST,
		COMPARING_FILES,
		DELETING_FILES,
		DOWNLOADING_FILES,
		UPDATING_HASH_MANIFEST,
		CLEAN_UP,
		FAIL,
		DONE
	}
	
	private void notifyProgress(final ProgressState state) {
		progressSubscription.notifySubscribers(state);
	}
	
	private void notifyDeleted(final Path filePath) {
		deletedSubscription.notifySubscribers(filePath);
	}
	
	private void notifyDownloaded(final Path filePath) {
		downloadedSubscription.notifySubscribers(filePath);
	}
	
	private void notifyDownload(final long amount) {
		downloadSubscription.notifySubscribers(amount);
	}
	
	private void notifyException(final Throwable ex) {
		exceptionSubscription.notifySubscribers(ex);
	}
	
	private static final String UNRESERVED_CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.~";
	//private static final String RESERVED_CHARACTERS = "!*'();:@&=+$,/?#[]";
	
	private static String convertFilePathToURLPath(String filePath) {
		final StringBuilder urlBuild = new StringBuilder(filePath.length());
		
		filePath.codePoints().forEachOrdered(cp -> {
			if (UNRESERVED_CHARACTERS.indexOf(cp) != -1) {
				// Standard unreserved character.
				urlBuild.appendCodePoint(cp);
			} else if(cp == '\\' || cp == '/') {
				// File path delimiters.
				urlBuild.appendCodePoint('/');
			} else {
				// UTF-8 encoding
				final Charset utf8encode = Charset.forName("UTF-8");
				for (byte b : new String(new int[]{cp}, 0, 1).getBytes(utf8encode)) {
					urlBuild.append(String.format("%%%02x", (int)b & 0xFF));
				}
			}
			
		});
		
		return urlBuild.toString();
	}
	

	@Override
	public void run() {
		notifyProgress(ProgressState.INIT);
		final Path hashFilePath = root.resolve(hashName);
		final Path hashFilePathNew = root.resolve(hashName + ".tmp");
		final Path skipFilePath = root.resolve(skiplistName);
		boolean success = true;
		try (final AsynchronousFileDownloader downloader = new AsynchronousFileDownloader()) {
			if (Files.isRegularFile(hashFilePath)) {
				notifyProgress(ProgressState.COPYING_HASH_MANIFEST);
				Files.copy(hashFilePath, hashFilePathNew, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
			}
			
			notifyProgress(ProgressState.DOWNLOADING_HASH_MANIFEST);
			if (!Files.isRegularFile(hashFilePathNew)) {
				Files.createDirectories(hashFilePathNew.getParent());
				Files.createFile(hashFilePathNew);
			}
			final CompletableFuture<Void> hashDownload = downloader.download(hashURL, hashFilePathNew, false);
			hashDownload.join();
			notifyDownload(downloader.getDownloadedBytes());

			notifyProgress(ProgressState.COMPARING_FILES);
			final HashCache newHashes = HashCache.atDirectory(hashFilePathNew, null, null);
			final HashCache oldHashes = HashCache.atDirectory(hashFilePath, root, null);

			final List<String> newFiles = oldHashes.getDifferentFiles(newHashes);
			final List<String> oldFiles = newHashes.getDifferentFiles(oldHashes);
			
			if (Files.exists(skipFilePath)) {
				final List<String> skipFiles = Files.readAllLines(skipFilePath);
				newFiles.removeAll(skipFiles);
				oldFiles.removeAll(skipFiles);
			}

			if (oldFiles.size() > 0) {
				notifyProgress(ProgressState.DELETING_FILES);
				for (String file : oldFiles) {
					final Path filePath = root.resolve(file);
					Files.deleteIfExists(filePath);
					notifyDeleted(root.relativize(filePath));
				}
			}

			if (newFiles.size() > 0) {
				notifyProgress(ProgressState.DOWNLOADING_FILES);
				final List<CompletableFuture<Void>> fileDownloads = new LinkedList<CompletableFuture<Void>>();
				for (String file : newFiles) {
					final URL fileURL = new URL(archiveURLString + convertFilePathToURLPath(file));
					final Path filePath = root.resolve(file);
					Files.createDirectories(filePath.getParent());
					Files.createFile(filePath);
					final CompletableFuture<Void> fileDownload = downloader.download(fileURL, filePath, true);
					fileDownload.whenComplete((r, ex) -> {
						if (ex == null) {
							notifyDownloaded(root.relativize(filePath));
							notifyDownload(downloader.getDownloadedBytes());
						} else {
							notifyException(ex);
						}
					});
					fileDownloads.add(fileDownload);
				}

				final CompletableFuture<Void> allDownloads = CompletableFuture.allOf(fileDownloads.toArray(new CompletableFuture<?>[0]));
				
				try {
					allDownloads.join();
				} catch (CompletionException e) {
					success = false;
				}
			}

			notifyDownload(downloader.getDownloadedBytes());
			
			notifyProgress(ProgressState.UPDATING_HASH_MANIFEST);
			Files.copy(hashFilePathNew, hashFilePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
		} catch (IOException e) {
			notifyException(e);
		} catch (CompletionException e) {
			success = false;
		} finally {
			notifyProgress(ProgressState.CLEAN_UP);
			try {
				Files.deleteIfExists(hashFilePathNew);
			} catch (IOException e) {
				notifyException(e);
			}
		}
		
		notifyProgress(success ? ProgressState.DONE : ProgressState.FAIL);
	} 
}
