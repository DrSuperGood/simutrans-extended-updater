package com.simutrans.updater.client;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;

import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import javax.swing.WindowConstants;

import com.simutrans.updater.Updater;

public class ClientUpdater {
	private final Updater updater;

	private JFrame mainWindow = null;

	private JLabel stateLabel = null;

	private JLabel downloadLabel = null;

	private JTextArea outputArea = null;

	public ClientUpdater(final Updater updater) {
		this.updater = updater;
	}

	/**
	 * Creates the UI. This method must only be called by the Swing thread.
	 */
	private void createUI() {
		// Setup look and feel.
		try {
			UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
		} catch (ClassNotFoundException | InstantiationException | IllegalAccessException
				| UnsupportedLookAndFeelException e) {
			JOptionPane.showMessageDialog(null,
					"Cannot load system look and feel.\nA default will be used.\n" + e.getLocalizedMessage(),
					"Look and Feel Error", JOptionPane.ERROR_MESSAGE);
		}

		JFrame.setDefaultLookAndFeelDecorated(true);
		mainWindow = new JFrame("Updater");
		mainWindow.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
		final BorderLayout layout = new BorderLayout();
		mainWindow.setLayout(layout);
		stateLabel = new JLabel("State:");
		stateLabel.setPreferredSize(new Dimension(256, 32));
		mainWindow.add(stateLabel, BorderLayout.WEST);
		downloadLabel = new JLabel("Downloaded:");
		downloadLabel.setPreferredSize(new Dimension(256, 32));
		mainWindow.add(downloadLabel, BorderLayout.EAST);
		outputArea = new JTextArea();
		outputArea.setRows(16);
		final JScrollPane outputPane = new JScrollPane(outputArea);
		outputPane.setPreferredSize(new Dimension(512, 256));
		mainWindow.add(outputPane, BorderLayout.SOUTH);

		updater.progressSubscription.addSubscription(state -> SwingUtilities
				.invokeLater(() -> stateLabel.setText(String.format("State: %S%n", state.name()))));
		updater.deletedSubscription.addSubscription(filePath -> SwingUtilities.invokeLater(() -> {
			outputArea.append(String.format("Deleted: %S%n", filePath.toString()));
			outputArea.setCaretPosition(outputArea.getDocument().getLength());
		}));
		updater.downloadedSubscription.addSubscription(filePath -> SwingUtilities.invokeLater(() -> {
			outputArea.append(String.format("Downloaded: %S%n", filePath.toString()));
			outputArea.setCaretPosition(outputArea.getDocument().getLength());
		}));
		updater.downloadSubscription.addSubscription(bytes -> SwingUtilities
				.invokeLater(() -> downloadLabel.setText(String.format("Downloaded: %,dbytes%n", bytes))));
		updater.exceptionSubscription.addSubscription(e -> SwingUtilities.invokeLater(() -> {
			outputArea.append(String.format("Exception occured: %S%n", e.toString()));
			outputArea.setCaretPosition(outputArea.getDocument().getLength());
		}));

		mainWindow.pack();
		mainWindow.setResizable(false);
		mainWindow.setVisible(true);
	}

	private final static String HASH_URL = "http://bridgewater-brunel.me.uk/downloads/nightly/nightly.hash";
	private final static String ARCHIVE_URL = "http://bridgewater-brunel.me.uk/downloads/raw/simutrans/";
	private final static String HASH_NAME = "Simutrans Extended.hash";

	public static void main(String[] args) {
		// Application command line state.
		String root = null;
		boolean cl = false;

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
					System.out.println("Simutrans Extended incremental updater.");
					System.out
							.println("Downloads the Simutrans Extended manifest hash file to detect nightly changes.");
					System.out.println("Changed files are deleted and nightly copies are downloaded to replace them.");
					System.out.println(
							"If root folder is not explicitly specified, will assume the user directory is the root.");
					System.out.println("Can be simply run if placed next to Simutrans Extended executable.");
					System.out.println(
							"Can also be run to download a complete install of Simutrans Extended to the root folder.");
					System.out.println("-help -h : Prints a list of available commands.");
					System.out.println("-root -r : Sets the root folder that the application will update.");
					System.out.println("-noui -commandline -cl : Runs the application in command line mode.");
					return;
				} else if (flag.equals("root") || flag.equals("r")) {
					root = args[i++];
				} else if (flag.equals("noui") || flag.equals("commandline") || flag.equals("cl")) {
					cl = true;
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
		if (root == null)
			root = System.getProperty("user.dir");

		// Body.
		try {
			final Path rootPath = Paths.get(root);
			final Updater updater = new Updater(rootPath, new URL(HASH_URL), HASH_NAME, ARCHIVE_URL);

			if (cl) {
				updater.progressSubscription.addSubscription(state -> System.out.println("State: " + state.name()));
				updater.deletedSubscription
						.addSubscription(filePath -> System.out.println("Deleted: " + filePath.toString()));
				updater.downloadedSubscription
						.addSubscription(filePath -> System.out.println("Downloaded: " + filePath.toString()));
				updater.downloadSubscription
						.addSubscription(bytes -> System.out.println(String.format("Downloaded: %,dbytes", bytes)));
				updater.exceptionSubscription.addSubscription(e -> {
					System.out.println("An exception occured:");
					e.printStackTrace(System.out);
				});
			} else {
				final ClientUpdater UI = new ClientUpdater(updater);
				SwingUtilities.invokeAndWait(() -> UI.createUI());
			}

			updater.run();
		} catch (MalformedURLException | InvocationTargetException | InterruptedException e) {
			System.out.println("An exception occured during initialization.");
			e.printStackTrace();
		}
	}
}
