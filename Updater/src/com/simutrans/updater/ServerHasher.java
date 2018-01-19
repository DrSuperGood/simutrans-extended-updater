package com.simutrans.updater;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Server side hasher to generate hash manifest file.
 * 
 * @author Dr Super Good
 */
public class ServerHasher {
	private final Path root;
	
	private final Path out;
	
	public ServerHasher(Path root, Path out) {
		this.root = root;
		this.out = out;
	}
	
	public void run() throws IOException {
		final HashCache cache = HashCache.fromDirectory(root);
		cache.writeCache(out);
	}

	/**
	 * @param args Command line arguments.
	 */
	public static void main(String[] args) {
		// Application command line state.
		String root = null;
		String out = null;
		String name = null;
		
		// Process arguments.
		int i = 0;
		while (i < args.length) {
			// Parse flag.
			String flag = args[i++].toLowerCase();
			if (flag.length() <= 1 || !flag.startsWith("-")) {
				// Invalid flag.
				System.out.println("Excpected a flag but got: " + flag);
				return;
			}
			flag = flag.substring(1);
			
			// Process flag.
			try {
				if (flag.equals("help") || flag.equals("h")) {
					System.out.println("A simple server hasher for incremental updates");
					System.out.println("Generates a hash manifest file from the contents of a folder.");
					System.out.println("If root folder is not explicitly specified it will hash the working directory.");
					System.out.println("If output folder is not explicitly specified it will output to the root.");
					System.out.println("If name is not explicitly specified it will output to 'manifest.hash'.");
					System.out.println("-help -h : Prints a list of available commands.");
					System.out.println("-root -r : Sets the root folder that the application will generate the manifest file from.");
					System.out.println("-out -o : Sets the output path where the manifest file will be written to.");
					System.out.println("-name -n : Sets the output file name where the manifest file will be written to.");
					return;
				} else if (flag.equals("root") || flag.equals("r")) {
					root = args[i++];
				} else if (flag.equals("out") || flag.equals("o")) {
					out = args[i++];
				} else if (flag.equals("name") || flag.equals("n")) {
					name = args[i++];
				} else {
					System.out.println("Unknown flag: " + flag);
					return;
				}
			} catch (IndexOutOfBoundsException e) {
				System.out.println("Excpected a flag argument: " + flag);
				return;
			}
		}
		
		// Resolve root path.
		if (root == null) root = System.getProperty("user.dir");
		final Path rootPath = Paths.get(root);
		
		// Resolve output path.
		if (name == null) {
			name = "manifest.hash";
		}
		if (out == null) out = root;
		final Path outPath = Paths.get(out, name);
		
		final ServerHasher hasher = new ServerHasher(rootPath, outPath);
		try {
			hasher.run();
		} catch (IOException e) {
			System.out.println("An IOException occured while trying to hash the files.");
			e.printStackTrace();
		}
		System.out.println("Done.");
	}

}
