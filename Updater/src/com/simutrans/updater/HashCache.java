package com.simutrans.updater;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Hashtable;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.Function;

/**
 * A cache of file hashes for a file structure. Used to compare file content
 * changes efficiently.
 * 
 * @author Dr Super Good
 */
public class HashCache {
	private final Map<String, byte[]> hashCache = new Hashtable<String, byte[]>();

	private final Path root;

	public HashCache(Path root) {
		this.root = root;
	}

	private void addFile(final String file, byte[] hash) {
		hashCache.put(file, hash);
	}

	private byte[] getHash(final String file) {
		return hashCache.get(file);
	}

	private boolean hasHash(final String file) throws IOException {
		if (hashCache.containsKey(file)) {
			// Have hash cached.
			return true;
		} else if (root == null) {
			// Not associated with a FileSystem so cannot build hashes.
			return false;
		}

		final Path filePath = root.resolve(file);
		if (!Files.isRegularFile(filePath)) {
			// Not a file so no hash possible.
			return false;
		}

		// Calculate hash.
		final byte[] hash = hashFile(filePath);
		addFile(file, hash);
		return true;
	}

	private byte[] hashFile(final Path file) throws IOException {
		byte[] hash;
		try (final FileChannel fc = FileChannel.open(file, StandardOpenOption.READ)) {
			final long length = fc.size();

			if (length > Integer.MAX_VALUE) {
				System.out.println("File bigger than 4 GB, only first 4 GB hashed.");
			}

			final ByteBuffer bb = ByteBuffer.allocate((int) Math.min(length, Integer.MAX_VALUE));

			while (bb.hasRemaining() && fc.read(bb) != -1)
				;
			bb.flip();

			final MessageDigest hasher = MessageDigest.getInstance("SHA-256");
			hasher.update(bb);
			hash = hasher.digest();
		} catch (NoSuchAlgorithmException e) {
			// This should never fail as Java requires SHA-256 support.
			hash = new byte[0];
		}

		return hash;
	}

	private void processFiles(final Path folder) throws IOException {
		System.out.println("Hashing: " + root.relativize(folder).toString());
		for (Path path : Files.newDirectoryStream(folder)) {
			if (Files.isDirectory(path)) {
				processFiles(path);
			} else {
				byte[] hash = hashFile(path);
				addFile(root.relativize(path).toString(), hash);
				// System.out.println(root.relativize(path).toString() + " : " + new
				// BigInteger(1, hash).toString(16));
			}
		}
	}

	public Path getRoot() {
		return root;
	}

	public List<String> getDifferentFiles(final HashCache newCache) throws IOException {
		final LinkedList<String> results = new LinkedList<String>();

		final Set<String> newFiles = newCache.hashCache.keySet();

		for (String file : newFiles) {
			if (!hasHash(file)) {
				// File did not exist.
				results.add(file);
			} else {
				// Compare hashes for change.
				final byte[] newHash = newCache.getHash(file);
				final byte[] oldHash = getHash(file);
				if (!Arrays.equals(oldHash, newHash)) {
					// File changed.
					results.add(file);
				}
			}
		}

		return results;
	}

	public static HashCache fromDirectory(final Path where) throws IOException {
		final HashCache hc = new HashCache(where);
		hc.processFiles(where);
		return hc;
	}

	public static HashCache atDirectory(final Path file) throws IOException {
		return atDirectory(file, file.getParent(), null);
	}

	/**
	 * Load a stored hash cache file.
	 * 
	 * @param file
	 *            Path to stored hash cache file or null if new one is to made.
	 * @param root
	 *            The root folder from which the hashes are generated. If null then
	 *            no new hashes can be generated.
	 * @param pathTransformFunction
	 *            Function to transform cached file paths. A null value means no
	 *            transformation occurs.
	 * @return A HashCache.
	 * @throws IOException
	 *             If an IO exception occurs when restoring a HashCache.
	 */
	public static HashCache atDirectory(final Path file, final Path root,
			final Function<String, String> pathTransformFunction) throws IOException {
		final HashCache hc = new HashCache(root);

		if (file == null && root == null)
			throw new IllegalArgumentException("Degenerate arguments, no hash cache file to load and no root to hash.");

		if (file != null && Files.isRegularFile(file)) {
			try (final FileChannel fc = FileChannel.open(file, StandardOpenOption.READ)) {
				final long length = fc.size();

				if (length > Integer.MAX_VALUE) {
					throw new IllegalArgumentException("file hash manifest larger than 4 GB");
				}

				final ByteBuffer bb = ByteBuffer.allocate((int) Math.min(length, Integer.MAX_VALUE));

				while (bb.hasRemaining() && fc.read(bb) != -1)
					;
				bb.flip();

				final int count = bb.getInt();
				final Charset utf8decode = Charset.forName("UTF-8");
				for (int i = 0; i < count; i += 1) {
					final byte[] hash = new byte[32];
					bb.get(hash);

					final int strlen = bb.getInt();
					final byte[] utf8str = new byte[strlen];
					bb.get(utf8str);
					String filePath = new String(utf8str, utf8decode);

					if (pathTransformFunction != null)
						filePath = pathTransformFunction.apply(filePath);

					hc.addFile(filePath, hash);
				}
			}
		}

		return hc;
	}

	public void writeCache(final Path file) throws IOException {
		if (Files.isRegularFile(file)) {
			Files.delete(file);
		}

		try (final FileChannel fc = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
			final Set<Entry<String, byte[]>> entries = hashCache.entrySet();
			final int length = entries.size();
			ByteBuffer bb = ByteBuffer.allocate(1 << 14);
			final Charset utf8encode = Charset.forName("UTF-8");

			bb.putInt(length);

			for (Entry<String, byte[]> entry : entries) {
				final byte[] hash = entry.getValue();
				final byte[] utf8str = entry.getKey().getBytes(utf8encode);

				final int entryLen = hash.length + 4 + utf8str.length;

				if (bb.remaining() < entryLen) {
					bb.flip();
					while (bb.hasRemaining())
						fc.write(bb);
					bb.clear();
				}

				if (bb.remaining() < entryLen) {
					bb = ByteBuffer.allocate(entryLen);
				}

				bb.put(hash);
				bb.putInt(utf8str.length);
				bb.put(utf8str);
			}

			bb.flip();
			while (bb.hasRemaining())
				fc.write(bb);
		}
	}
}
