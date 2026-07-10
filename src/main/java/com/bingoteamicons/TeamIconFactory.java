package com.bingoteamicons;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * Generates the colored badge images used as chat icons, plus the sidebar
 * navigation icon. Badges are 11x11 to match the game's rank icon dimensions
 * so they align with other chat icons.
 */
final class TeamIconFactory
{
	private static final Color[] DEFAULT_TEAM_COLORS = {
		new Color(0xE6194B), // red
		new Color(0x4363D8), // blue
		new Color(0x3CB44B), // green
		new Color(0xFFE119), // yellow
		new Color(0x911EB4), // purple
		new Color(0xF58231), // orange
		new Color(0x42D4F4), // cyan
		new Color(0xF032E6), // magenta
		new Color(0x9A6324), // brown
		new Color(0xA9A9A9), // gray
	};

	// matches ChatIconManager.IMAGE_DIMENSION used for rank icons
	static final int BADGE_SIZE = 11;
	private static final Color OUTLINE_COLOR = new Color(33, 33, 33);

	private TeamIconFactory()
	{
	}

	static Color defaultTeamColor(int teamNumber)
	{
		return DEFAULT_TEAM_COLORS[(teamNumber - 1) % DEFAULT_TEAM_COLORS.length];
	}

	static BufferedImage createBadge(Color color)
	{
		BufferedImage image = new BufferedImage(BADGE_SIZE, BADGE_SIZE, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		g.setColor(color);
		g.fillOval(0, 0, BADGE_SIZE - 1, BADGE_SIZE - 1);
		g.setColor(OUTLINE_COLOR);
		g.drawOval(0, 0, BADGE_SIZE - 2, BADGE_SIZE - 2);

		g.dispose();
		return image;
	}

	static BufferedImage createPanelIcon()
	{
		final int size = 16;
		BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		// 2x2 bingo-card grid using the first four default team colors
		int cell = 7;
		g.setColor(defaultTeamColor(1));
		g.fillRect(0, 0, cell, cell);
		g.setColor(defaultTeamColor(2));
		g.fillRect(cell + 2, 0, cell, cell);
		g.setColor(defaultTeamColor(3));
		g.fillRect(0, cell + 2, cell, cell);
		g.setColor(defaultTeamColor(4));
		g.fillRect(cell + 2, cell + 2, cell, cell);

		g.dispose();
		return image;
	}
}
