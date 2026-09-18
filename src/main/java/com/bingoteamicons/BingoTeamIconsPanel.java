package com.bingoteamicons;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.colorpicker.ColorPickerManager;
import net.runelite.client.ui.components.colorpicker.RuneliteColorPicker;

/**
 * Sidebar panel: pick the number of teams, which populates that many
 * sections, each with a name, a color picker and a text box of player names.
 * Whole rosters can be shared as a code or saved as a named preset.
 */
class BingoTeamIconsPanel extends PluginPanel
{
	private static final String NO_PRESET = "— no preset —";

	private final BingoTeamIconsPlugin plugin;
	private final ConfigManager configManager;
	private final ColorPickerManager colorPickerManager;
	private final BingoTeamIconsRosterStore store;
	private final BingoTeamIconsSyncCodec codec;

	private final JPanel teamsContainer = new JPanel();
	private final Map<Integer, JLabel> countLabels = new HashMap<>();
	private final Map<Integer, JTextArea> teamAreas = new HashMap<>();
	private final JSpinner teamCountSpinner;
	private final JComboBox<String> presetCombo = new JComboBox<>();
	private final JButton loadPresetButton = new JButton("Load");
	private final JButton deletePresetButton = new JButton("Delete");

	// every debounced write, so a rebuild can either flush them or drop them rather
	// than let them fire against text areas that no longer exist
	private final List<DebouncedSave> pendingSaves = new ArrayList<>();

	private final Timer onlineRefreshTimer;
	private Map<Integer, Integer> onlineCounts = new HashMap<>();

	/**
	 * True while the panel is repopulating itself from config. Listeners that write
	 * config must bail out, or a programmatic setText/setValue/setSelectedItem is
	 * mistaken for the user typing.
	 */
	private boolean updatingUi;

	BingoTeamIconsPanel(BingoTeamIconsPlugin plugin, ConfigManager configManager,
		ColorPickerManager colorPickerManager, BingoTeamIconsRosterStore store,
		BingoTeamIconsSyncCodec codec)
	{
		this.plugin = plugin;
		this.configManager = configManager;
		this.colorPickerManager = colorPickerManager;
		this.store = store;
		this.codec = codec;

		// coalesce bursts of member join/leave events into one recount
		onlineRefreshTimer = new Timer(300, e ->
			plugin.computeOnlineCounts(counts ->
			{
				onlineCounts = counts;
				countLabels.keySet().forEach(this::updateOnlineCount);
			}));
		onlineRefreshTimer.setRepeats(false);

		setBorder(new EmptyBorder(10, 10, 10, 10));
		setLayout(new BorderLayout());

		JPanel content = new JPanel();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));

		JLabel title = new JLabel("Bingo Team Icons");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setAlignmentX(LEFT_ALIGNMENT);
		content.add(title);
		content.add(Box.createVerticalStrut(8));

		content.add(createPresetRow());
		content.add(Box.createVerticalStrut(4));
		content.add(createSyncRow());
		content.add(Box.createVerticalStrut(10));

		JLabel help = new JLabel("<html>Player names, separated by commas or new lines. Click a team's swatch to change its color.</html>");
		help.setFont(FontManager.getRunescapeSmallFont());
		help.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		help.setAlignmentX(LEFT_ALIGNMENT);
		content.add(help);
		content.add(Box.createVerticalStrut(10));

		int teamCount = store.teamCount();

		JPanel spinnerRow = new JPanel(new BorderLayout(6, 0));
		spinnerRow.setOpaque(false);
		spinnerRow.setAlignmentX(LEFT_ALIGNMENT);
		spinnerRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
		JLabel spinnerLabel = new JLabel("Number of teams");
		teamCountSpinner = new JSpinner(new SpinnerNumberModel(teamCount, 1, BingoTeamIconsPlugin.MAX_TEAMS, 1));
		teamCountSpinner.setPreferredSize(new Dimension(50, 26));
		teamCountSpinner.addChangeListener(e ->
		{
			if (updatingUi)
			{
				return;
			}

			// the rebuild below discards the current text areas, so commit anything
			// the user typed in the last half second before they go
			flushPendingSaves();

			int count = (Integer) teamCountSpinner.getValue();
			configManager.setConfiguration(BingoTeamIconsConfig.GROUP, BingoTeamIconsConfig.TEAM_COUNT_KEY, count);
			rebuildTeamSections(count);
		});
		spinnerRow.add(spinnerLabel, BorderLayout.CENTER);
		spinnerRow.add(teamCountSpinner, BorderLayout.EAST);
		content.add(spinnerRow);
		content.add(Box.createVerticalStrut(10));

		teamsContainer.setLayout(new BoxLayout(teamsContainer, BoxLayout.Y_AXIS));
		teamsContainer.setOpaque(false);
		teamsContainer.setAlignmentX(LEFT_ALIGNMENT);
		content.add(teamsContainer);

		add(content, BorderLayout.NORTH);
		rebuildTeamSections(teamCount);
		reloadPresetCombo();
	}

	/**
	 * A config write held back until the user stops typing. Every config write
	 * retags the chat buffer and redraws the friends and clan lists, which is far
	 * too heavy to run per keystroke.
	 */
	private static final class DebouncedSave
	{
		private final Timer timer;
		private final Runnable write;

		DebouncedSave(Runnable write)
		{
			this.write = write;
			this.timer = new Timer(500, e -> write.run());
			this.timer.setRepeats(false);
		}

		void schedule()
		{
			timer.restart();
		}

		/** Drops a pending write, for when its text area is about to be replaced. */
		void cancel()
		{
			timer.stop();
		}

		/** Writes now, for when the edit must survive the rebuild. */
		void flush()
		{
			if (timer.isRunning())
			{
				timer.stop();
				write.run();
			}
		}
	}

	static String teamNamesKey(int team)
	{
		return "team" + team + "Names";
	}

	static String teamColorKey(int team)
	{
		return "team" + team + "Color";
	}

	static String teamLabelKey(int team)
	{
		return "team" + team + "Label";
	}

	private JPanel createPresetRow()
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 56));

		presetCombo.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		presetCombo.setAlignmentX(LEFT_ALIGNMENT);
		presetCombo.setToolTipText("Saved sets of teams");
		// selection only picks what Load and Delete act on; loading is explicit, so
		// opening the dropdown to delete something never offers to overwrite instead
		presetCombo.addActionListener(e -> updatePresetButtons());
		row.add(presetCombo);
		row.add(Box.createVerticalStrut(4));

		loadPresetButton.setToolTipText("Replace your teams with the selected preset");
		loadPresetButton.addActionListener(e -> onLoadPreset());

		JButton save = new JButton("Save");
		save.setToolTipText("Save these teams under a name");
		save.addActionListener(e -> onSavePreset());

		deletePresetButton.setToolTipText("Delete the selected preset");
		deletePresetButton.addActionListener(e -> onDeletePreset());

		JPanel buttons = new JPanel(new GridLayout(1, 3, 4, 0));
		buttons.setOpaque(false);
		buttons.setAlignmentX(LEFT_ALIGNMENT);
		buttons.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
		buttons.add(loadPresetButton);
		buttons.add(save);
		buttons.add(deletePresetButton);
		row.add(buttons);
		return row;
	}

	private JPanel createSyncRow()
	{
		JButton importButton = new JButton("Import...");
		importButton.setToolTipText("Import teams from a code or file");
		importButton.addActionListener(e -> onImport());

		JButton exportButton = new JButton("Export...");
		exportButton.setToolTipText("Share these teams as a code or file");
		exportButton.addActionListener(e -> onExport());

		return buttonPair(importButton, exportButton);
	}

	private static JPanel buttonPair(JButton left, JButton right)
	{
		JPanel row = new JPanel(new GridLayout(1, 2, 4, 0));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
		row.add(left);
		row.add(right);
		return row;
	}

	private void rebuildTeamSections(int teamCount)
	{
		// anything still pending belongs to text areas about to be discarded; callers
		// that need those edits kept call flushPendingSaves first
		cancelPendingSaves();

		teamsContainer.removeAll();
		countLabels.clear();
		teamAreas.clear();
		for (int team = 1; team <= teamCount; team++)
		{
			teamsContainer.add(createTeamSection(team));
			teamsContainer.add(Box.createVerticalStrut(10));
		}
		teamsContainer.revalidate();
		teamsContainer.repaint();
		refreshOnlineCounts();
	}

	/**
	 * Repopulates every widget from config after an import or preset switch.
	 * Must run on the EDT.
	 */
	private void reloadFromConfig()
	{
		cancelPendingSaves();
		updatingUi = true;
		try
		{
			int count = store.teamCount();
			teamCountSpinner.setValue(count);
			rebuildTeamSections(count);
		}
		finally
		{
			updatingUi = false;
		}
		revalidate();
		repaint();
	}

	/**
	 * Drops every debounced write. Without this, text typed less than the debounce
	 * interval before an import would be written back afterwards, silently
	 * overwriting the roster that was just imported.
	 */
	private void cancelPendingSaves()
	{
		pendingSaves.forEach(DebouncedSave::cancel);
		pendingSaves.clear();
	}

	/** Commits every debounced write, for rebuilds that must not lose an in-flight edit. */
	private void flushPendingSaves()
	{
		pendingSaves.forEach(DebouncedSave::flush);
	}

	@Override
	public void onActivate()
	{
		refreshOnlineCounts();
	}

	void refreshOnlineCounts()
	{
		onlineRefreshTimer.restart();
	}

	private void updateOnlineCount(int team)
	{
		JLabel label = countLabels.get(team);
		JTextArea namesArea = teamAreas.get(team);
		if (label == null || namesArea == null)
		{
			return;
		}

		int total = countNames(namesArea.getText());
		int online = Math.min(onlineCounts.getOrDefault(team, 0), total);
		label.setText(online + "/" + total + " online");
	}

	private JPanel createTeamSection(int team)
	{
		JPanel section = new JPanel(new BorderLayout(0, 4));
		section.setOpaque(false);
		section.setAlignmentX(LEFT_ALIGNMENT);

		JPanel header = new JPanel(new BorderLayout(0, 2));
		header.setOpaque(false);

		JPanel topRow = new JPanel(new BorderLayout(6, 0));
		topRow.setOpaque(false);
		topRow.add(createLabelField(team), BorderLayout.CENTER);
		topRow.add(createColorButton(team), BorderLayout.EAST);
		header.add(topRow, BorderLayout.NORTH);

		JLabel countLabel = new JLabel();
		countLabel.setFont(FontManager.getRunescapeSmallFont());
		countLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		header.add(countLabel, BorderLayout.SOUTH);
		section.add(header, BorderLayout.NORTH);

		JTextArea namesArea = new JTextArea(4, 20);
		namesArea.setLineWrap(true);
		namesArea.setWrapStyleWord(true);
		namesArea.setFont(FontManager.getRunescapeSmallFont());
		namesArea.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		namesArea.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		namesArea.setCaretColor(ColorScheme.LIGHT_GRAY_COLOR);
		namesArea.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
			new EmptyBorder(4, 4, 4, 4)));

		// set the text before attaching the listener, so populating the field is
		// never mistaken for user input
		namesArea.setText(store.teamNames(team));

		countLabels.put(team, countLabel);
		teamAreas.put(team, namesArea);
		updateOnlineCount(team);

		DebouncedSave save = new DebouncedSave(() ->
			configManager.setConfiguration(BingoTeamIconsConfig.GROUP, teamNamesKey(team), namesArea.getText()));
		pendingSaves.add(save);

		namesArea.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				changed();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				changed();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				changed();
			}

			private void changed()
			{
				updateOnlineCount(team);
				if (updatingUi)
				{
					return;
				}
				save.schedule();
			}
		});

		section.add(namesArea, BorderLayout.CENTER);
		return section;
	}

	private JTextField createLabelField(int team)
	{
		JTextField field = new JTextField();
		field.setFont(FontManager.getRunescapeBoldFont());
		field.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		field.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		field.setCaretColor(ColorScheme.LIGHT_GRAY_COLOR);
		field.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
			new EmptyBorder(2, 4, 2, 4)));
		field.setToolTipText("Team name, used to match teams when merging an imported code");

		// a prompt rather than a real value, so a team genuinely named "Team 3"
		// still round-trips; unsupported look and feels simply show no prompt
		field.putClientProperty("JTextField.placeholderText", "Team " + team);
		field.setText(store.teamLabel(team));

		DebouncedSave save = new DebouncedSave(() ->
			configManager.setConfiguration(BingoTeamIconsConfig.GROUP, teamLabelKey(team),
				BingoTeamIconsSyncCodec.sanitizeLabel(field.getText())));
		pendingSaves.add(save);

		field.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				changed();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				changed();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				changed();
			}

			private void changed()
			{
				if (updatingUi)
				{
					return;
				}
				save.schedule();
			}
		});

		return field;
	}

	private static int countNames(String text)
	{
		if (text == null || text.isEmpty())
		{
			return 0;
		}

		int count = 0;
		for (String name : text.split("[,\n]"))
		{
			if (!name.trim().isEmpty())
			{
				count++;
			}
		}
		return count;
	}

	private JButton createColorButton(int team)
	{
		JButton colorButton = new JButton();
		colorButton.setPreferredSize(new Dimension(32, 18));
		colorButton.setBackground(plugin.teamColor(team));
		colorButton.setFocusable(false);
		colorButton.setToolTipText("Team " + team + " icon color");
		colorButton.setBorder(BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR));
		colorButton.addActionListener(e ->
		{
			RuneliteColorPicker picker = colorPickerManager.create(
				SwingUtilities.windowForComponent(this),
				colorButton.getBackground(),
				"Team " + team + " color",
				true);
			picker.setLocationRelativeTo(colorButton);
			picker.setOnColorChange(colorButton::setBackground);
			picker.setOnClose(color -> saveColor(team, color));
			picker.setVisible(true);
		});
		return colorButton;
	}

	private void saveColor(int team, Color color)
	{
		String hex = String.format("#%06X", color.getRGB() & 0xFFFFFF);
		configManager.setConfiguration(BingoTeamIconsConfig.GROUP, teamColorKey(team), hex);
	}

	private void onImport()
	{
		// the merge below reads the roster back out of config, so commit anything
		// still sitting in a debounce timer first
		flushPendingSaves();

		String raw = BingoTeamIconsSyncDialogs.promptForCode(this);
		if (raw == null)
		{
			return;
		}

		BingoTeamIconsSyncCodec.SyncPayload incoming;
		try
		{
			incoming = codec.decode(raw);
		}
		catch (BingoTeamIconsSyncCodec.SyncCodeException ex)
		{
			BingoTeamIconsSyncDialogs.showError(this, ex.getMessage());
			return;
		}

		BingoTeamIconsRosterStore.MergePlan plan = BingoTeamIconsRosterStore.planMerge(store.read(""), incoming);
		BingoTeamIconsSyncDialogs.Choice choice = BingoTeamIconsSyncDialogs.confirmImport(this, incoming, plan);
		if (choice == null)
		{
			return;
		}

		applyRoster(choice == BingoTeamIconsSyncDialogs.Choice.REPLACE ? incoming : plan.result);

		if (!incoming.label.isEmpty())
		{
			offerToSavePreset(incoming.label);
		}
	}

	private void onExport()
	{
		// a code built from a stale read would be missing the team name the user just
		// typed, and the fault would only show up for whoever imports it
		flushPendingSaves();

		String label = BingoTeamIconsSyncDialogs.promptForPresetName(this, selectedPreset());
		if (label == null)
		{
			return;
		}

		BingoTeamIconsSyncDialogs.showExport(this, codec.encode(store.read(label)));
	}

	private void applyRoster(BingoTeamIconsSyncCodec.SyncPayload payload)
	{
		cancelPendingSaves();
		plugin.applyBulkConfigChange(() -> store.write(payload));
		reloadFromConfig();
	}

	private void offerToSavePreset(String label)
	{
		if (!BingoTeamIconsSyncDialogs.confirm(this, "Save preset",
			"Save these teams as a preset named \"" + label + "\"?"))
		{
			return;
		}

		savePresetNamed(label);
	}

	private void onSavePreset()
	{
		String label = BingoTeamIconsSyncDialogs.promptForPresetName(this, selectedPreset());
		if (label != null)
		{
			savePresetNamed(label);
		}
	}

	private void savePresetNamed(String label)
	{
		flushPendingSaves();

		try
		{
			store.savePreset(label, codec.encode(store.read(label)));
		}
		catch (BingoTeamIconsRosterStore.PresetLimitException ex)
		{
			BingoTeamIconsSyncDialogs.showError(this, ex.getMessage());
			return;
		}

		reloadPresetCombo();
		presetCombo.setSelectedItem(BingoTeamIconsSyncCodec.sanitizeLabel(label));
	}

	private void onDeletePreset()
	{
		String selected = selectedPreset();
		if (selected == null)
		{
			return;
		}

		if (BingoTeamIconsSyncDialogs.confirm(this, "Delete preset",
			"Delete the preset \"" + selected + "\"? Your current teams are not affected."))
		{
			store.deletePreset(selected);
			reloadPresetCombo();
		}
	}

	private void onLoadPreset()
	{
		String selected = selectedPreset();
		if (selected == null)
		{
			return;
		}

		String code = store.readPresets().get(selected);
		if (code == null)
		{
			return;
		}

		BingoTeamIconsSyncCodec.SyncPayload payload;
		try
		{
			payload = codec.decode(code);
		}
		catch (BingoTeamIconsSyncCodec.SyncCodeException ex)
		{
			BingoTeamIconsSyncDialogs.showError(this, "That preset could not be read: " + ex.getMessage());
			return;
		}

		if (BingoTeamIconsSyncDialogs.confirm(this, "Load preset",
			"Replace your current teams with \"" + selected + "\"?"))
		{
			applyRoster(payload);
		}
	}

	private void updatePresetButtons()
	{
		boolean hasSelection = selectedPreset() != null;
		loadPresetButton.setEnabled(hasSelection);
		deletePresetButton.setEnabled(hasSelection);
	}

	/** The selected preset name, or null when the placeholder entry is selected. */
	private String selectedPreset()
	{
		Object selected = presetCombo.getSelectedItem();
		return selected == null || NO_PRESET.equals(selected) ? null : selected.toString();
	}

	private void reloadPresetCombo()
	{
		LinkedHashMap<String, String> presets = store.readPresets();
		presetCombo.removeAllItems();
		presetCombo.addItem(NO_PRESET);
		presets.keySet().stream().sorted(String.CASE_INSENSITIVE_ORDER).forEach(presetCombo::addItem);
		presetCombo.setSelectedItem(NO_PRESET);
		updatePresetButtons();
	}
}
