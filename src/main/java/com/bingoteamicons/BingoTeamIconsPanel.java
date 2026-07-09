package com.bingoteamicons;

import java.awt.BorderLayout;
import java.awt.Dimension;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.SpinnerNumberModel;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/**
 * Sidebar panel: pick the number of teams, which populates that many
 * text boxes, one per team, holding the team's player names.
 */
class BingoTeamIconsPanel extends PluginPanel
{
	private final ConfigManager configManager;
	private final JPanel teamsContainer = new JPanel();

	BingoTeamIconsPanel(ConfigManager configManager, BingoTeamIconsConfig config)
	{
		this.configManager = configManager;

		setBorder(new EmptyBorder(10, 10, 10, 10));
		setLayout(new BorderLayout());

		JPanel content = new JPanel();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));

		JLabel title = new JLabel("Bingo Team Icons");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setAlignmentX(LEFT_ALIGNMENT);
		content.add(title);
		content.add(Box.createVerticalStrut(8));

		JLabel help = new JLabel("<html>Player names, separated by commas or new lines. Icons show next to names in chat.</html>");
		help.setFont(FontManager.getRunescapeSmallFont());
		help.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		help.setAlignmentX(LEFT_ALIGNMENT);
		content.add(help);
		content.add(Box.createVerticalStrut(10));

		int teamCount = Math.min(Math.max(config.teamCount(), 1), BingoTeamIconsPlugin.MAX_TEAMS);

		JPanel spinnerRow = new JPanel(new BorderLayout(6, 0));
		spinnerRow.setOpaque(false);
		spinnerRow.setAlignmentX(LEFT_ALIGNMENT);
		spinnerRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
		JLabel spinnerLabel = new JLabel("Number of teams");
		JSpinner spinner = new JSpinner(new SpinnerNumberModel(teamCount, 1, BingoTeamIconsPlugin.MAX_TEAMS, 1));
		spinner.setPreferredSize(new Dimension(50, 26));
		spinner.addChangeListener(e ->
		{
			int count = (Integer) spinner.getValue();
			configManager.setConfiguration(BingoTeamIconsConfig.GROUP, BingoTeamIconsConfig.TEAM_COUNT_KEY, count);
			rebuildTeamSections(count);
		});
		spinnerRow.add(spinnerLabel, BorderLayout.CENTER);
		spinnerRow.add(spinner, BorderLayout.EAST);
		content.add(spinnerRow);
		content.add(Box.createVerticalStrut(10));

		teamsContainer.setLayout(new BoxLayout(teamsContainer, BoxLayout.Y_AXIS));
		teamsContainer.setOpaque(false);
		teamsContainer.setAlignmentX(LEFT_ALIGNMENT);
		content.add(teamsContainer);

		add(content, BorderLayout.NORTH);
		rebuildTeamSections(teamCount);
	}

	static String teamNamesKey(int team)
	{
		return "team" + team + "Names";
	}

	private void rebuildTeamSections(int teamCount)
	{
		teamsContainer.removeAll();
		for (int team = 1; team <= teamCount; team++)
		{
			teamsContainer.add(createTeamSection(team));
			teamsContainer.add(Box.createVerticalStrut(10));
		}
		teamsContainer.revalidate();
		teamsContainer.repaint();
	}

	private JPanel createTeamSection(int team)
	{
		JPanel section = new JPanel(new BorderLayout(0, 4));
		section.setOpaque(false);
		section.setAlignmentX(LEFT_ALIGNMENT);

		JLabel header = new JLabel("Team " + team, new ImageIcon(TeamIconFactory.createBadge(team)), JLabel.LEFT);
		header.setFont(FontManager.getRunescapeBoldFont());
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

		String saved = configManager.getConfiguration(BingoTeamIconsConfig.GROUP, teamNamesKey(team));
		if (saved != null)
		{
			namesArea.setText(saved);
		}

		namesArea.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				save();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				save();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				save();
			}

			private void save()
			{
				configManager.setConfiguration(BingoTeamIconsConfig.GROUP, teamNamesKey(team), namesArea.getText());
			}
		});

		section.add(namesArea, BorderLayout.CENTER);
		return section;
	}
}
