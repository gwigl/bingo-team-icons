package com.bingoteamicons;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import net.runelite.client.util.Filepath;

/**
 * The import and export dialogs. Kept out of the panel so the roster editor
 * stays readable; this is all Swing plumbing and file handling.
 */
final class BingoTeamIconsSyncDialogs
{
	/** Codes longer than this will not fit in a single Discord message. */
	private static final int DISCORD_MESSAGE_LIMIT = 1900;
	private static final int MAX_FILE_CHARS = 1024 * 1024;
	private static final String EXTENSION = "bti";

	enum Choice
	{
		REPLACE,
		MERGE
	}

	private BingoTeamIconsSyncDialogs()
	{
	}

	static void showExport(Component parent, String code)
	{
		JTextArea codeArea = new JTextArea(code, 8, 40);
		codeArea.setEditable(false);
		codeArea.setLineWrap(true);
		codeArea.setWrapStyleWord(false);
		codeArea.setCaretPosition(0);

		JPanel content = new JPanel(new BorderLayout(0, 6));
		content.add(new JLabel("Share this code. Anyone with it can import your teams."), BorderLayout.NORTH);
		content.add(new JScrollPane(codeArea), BorderLayout.CENTER);

		String note = code.length() + " characters."
			+ (code.length() > DISCORD_MESSAGE_LIMIT
			? " That is too long for one Discord message, so save it to a file and attach that instead."
			: "");
		JLabel noteLabel = new JLabel("<html><body style='width:380px'>" + note + "</body></html>");
		content.add(noteLabel, BorderLayout.SOUTH);

		JButton copy = new JButton("Copy to clipboard");
		copy.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard()
			.setContents(new StringSelection(code), null));

		JButton save = new JButton("Save to file...");
		save.addActionListener(e -> saveToFile(parent, code));

		JOptionPane.showOptionDialog(parent, content, "Export teams",
			JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null,
			new Object[]{copy, save, "Close"}, copy);
	}

	/** Returns the raw pasted or loaded code, or null if the user cancelled. */
	static String promptForCode(Component parent)
	{
		JTextArea input = new JTextArea(8, 40);
		input.setLineWrap(true);
		input.setWrapStyleWord(false);

		JPanel content = new JPanel(new BorderLayout(0, 6));
		content.add(new JLabel("Paste a team code, or load one from a file."), BorderLayout.NORTH);
		content.add(new JScrollPane(input), BorderLayout.CENTER);

		JButton load = new JButton("Load from file...");
		load.addActionListener(e ->
		{
			String loaded = loadFromFile(parent);
			if (loaded != null)
			{
				input.setText(loaded);
			}
		});

		JPanel south = new JPanel(new BorderLayout());
		south.add(load, BorderLayout.WEST);
		content.add(south, BorderLayout.SOUTH);

		int result = JOptionPane.showConfirmDialog(parent, content, "Import teams",
			JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

		if (result != JOptionPane.OK_OPTION)
		{
			return null;
		}
		return input.getText();
	}

	/**
	 * Shows what the import would do and asks how to apply it. Returns null if the
	 * user cancelled. Merge is not offered when it would not fit, since silently
	 * dropping a team from a bingo roster is the worst available outcome.
	 */
	static Choice confirmImport(Component parent, BingoTeamIconsSyncCodec.SyncPayload incoming,
		BingoTeamIconsRosterStore.MergePlan plan)
	{
		StringBuilder summary = new StringBuilder();
		if (!incoming.label.isEmpty())
		{
			summary.append(incoming.label).append("\n\n");
		}

		int total = 0;
		for (int i = 0; i < incoming.teams.size(); i++)
		{
			BingoTeamIconsSyncCodec.SyncTeam team = incoming.teams.get(i);
			int size = team.memberList().size();
			total += size;
			summary.append("  ")
				.append(BingoTeamIconsRosterStore.displayName(team, i + 1))
				.append(" - ")
				.append(size)
				.append(size == 1 ? " player" : " players")
				.append('\n');
		}
		summary.append('\n').append(incoming.teams.size()).append(" teams, ").append(total).append(" players.\n");

		if (plan.fits())
		{
			summary.append("\nMerging would update ").append(plan.updatedTeams)
				.append(" existing team(s), add ").append(plan.addedTeams)
				.append(" new team(s) and ").append(plan.addedPlayers).append(" new player(s).");
		}
		else
		{
			summary.append("\nThese teams will not fit alongside your existing ones (")
				.append(BingoTeamIconsPlugin.MAX_TEAMS).append(" teams maximum):\n  ")
				.append(String.join(", ", plan.overflowLabels))
				.append("\n\nYou can still replace your teams with this code.");
		}

		JTextArea summaryArea = new JTextArea(summary.toString());
		summaryArea.setEditable(false);
		summaryArea.setOpaque(false);
		summaryArea.setBorder(BorderFactory.createEmptyBorder());

		JScrollPane scroll = new JScrollPane(summaryArea);
		scroll.setPreferredSize(new Dimension(360, 240));
		scroll.setBorder(BorderFactory.createEmptyBorder());

		Object[] options = plan.fits()
			? new Object[]{"Merge", "Replace all", "Cancel"}
			: new Object[]{"Replace all", "Cancel"};

		int choice = JOptionPane.showOptionDialog(parent, scroll, "Import teams",
			JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[0]);

		if (choice < 0)
		{
			return null;
		}

		String picked = (String) options[choice];
		if ("Merge".equals(picked))
		{
			return Choice.MERGE;
		}
		if ("Replace all".equals(picked))
		{
			return Choice.REPLACE;
		}
		return null;
	}

	static String promptForPresetName(Component parent, String initial)
	{
		Object result = JOptionPane.showInputDialog(parent, "Name this set of teams:", "Save preset",
			JOptionPane.PLAIN_MESSAGE, null, null, initial == null ? "" : initial);
		return result == null ? null : result.toString();
	}

	static boolean confirm(Component parent, String title, String message)
	{
		return JOptionPane.showConfirmDialog(parent, message, title,
			JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION;
	}

	static void showError(Component parent, String message)
	{
		JOptionPane.showMessageDialog(parent, message, "Bingo Team Icons", JOptionPane.ERROR_MESSAGE);
	}

	private static void saveToFile(Component parent, String code)
	{
		List<Filepath> picked = new Filepath.Chooser()
			.setIsSave()
			.setAcceptsFiles()
			.setDialogTitle("Save team code")
			.setFileName("bingo-teams." + EXTENSION)
			.addExtensionFilter("Bingo team code (*." + EXTENSION + ")", EXTENSION)
			.setDefaultExtension(EXTENSION)
			.showDialog(parent);

		if (picked.isEmpty())
		{
			return;
		}

		Filepath file = picked.get(0);
		if (file.exists() && !confirm(parent, "Overwrite file",
			file.getFileName() + " already exists. Overwrite it?"))
		{
			return;
		}

		try
		{
			file.write(code);
		}
		catch (IOException ex)
		{
			showError(parent, "Could not save the file: " + ex.getMessage());
		}
	}

	private static String loadFromFile(Component parent)
	{
		List<Filepath> picked = new Filepath.Chooser()
			.setIsOpen()
			.setAcceptsFiles()
			.setDialogTitle("Load team code")
			.addExtensionFilter("Bingo team code (*." + EXTENSION + ")", EXTENSION)
			.showDialog(parent);

		if (picked.isEmpty())
		{
			return null;
		}

		Filepath file = picked.get(0);
		StringBuilder text = new StringBuilder();
		try
		{
			if (file.size() > MAX_FILE_CHARS)
			{
				showError(parent, "That file is too large to be a team code.");
				return null;
			}

			try (BufferedReader reader = file.openBufferedReader())
			{
				char[] buffer = new char[8192];
				int read;
				while ((read = reader.read(buffer)) > 0 && text.length() <= MAX_FILE_CHARS)
				{
					text.append(buffer, 0, read);
				}
			}
		}
		catch (IOException ex)
		{
			showError(parent, "Could not read the file: " + ex.getMessage());
			return null;
		}

		return text.toString();
	}
}
