package com.bingoteamicons;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * Generates the colored, numbered badge images used as chat icons,
 * plus the sidebar navigation icon.
 */
final class TeamIconFactory
{
	private static final Color[] TEAM_COLORS = {
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

	private static final int BADGE_SIZE = 14;

	private TeamIconFactory()
	{
	}

	static Color teamColor(int teamNumber)
	{
		return TEAM_COLORS[(teamNumber - 1) % TEAM_COLORS.length];
	}

	static BufferedImage createBadge(int teamNumber)
	{
		BufferedImage image = new BufferedImage(BADGE_SIZE, BADGE_SIZE, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

		Color color = teamColor(teamNumber);
		g.setColor(color);
		g.fillOval(0, 0, BADGE_SIZE - 1, BADGE_SIZE - 1);
		g.setColor(Color.BLACK);
		g.setStroke(new BasicStroke(1f));
		g.drawOval(0, 0, BADGE_SIZE - 1, BADGE_SIZE - 1);

		String text = String.valueOf(teamNumber);
		int fontSize = text.length() > 1 ? 9 : 11;
		g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, fontSize));
		FontMetrics fm = g.getFontMetrics();
		int x = (BADGE_SIZE - fm.stringWidth(text)) / 2;
		int y = (BADGE_SIZE - fm.getHeight()) / 2 + fm.getAscent();

		// yellow and cyan are too light for white text
		boolean lightBackground = (color.getRed() * 299 + color.getGreen() * 587 + color.getBlue() * 114) / 1000 > 150;
		g.setColor(lightBackground ? Color.BLACK : Color.WHITE);
		g.drawString(text, x, y);

		g.dispose();
		return image;
	}

	static BufferedImage createPanelIcon()
	{
		final int size = 16;
		BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		// 2x2 bingo-card grid using the first four team colors
		int cell = 7;
		g.setColor(teamColor(1));
		g.fillRect(0, 0, cell, cell);
		g.setColor(teamColor(2));
		g.fillRect(cell + 2, 0, cell, cell);
		g.setColor(teamColor(3));
		g.fillRect(0, cell + 2, cell, cell);
		g.setColor(teamColor(4));
		g.fillRect(cell + 2, cell + 2, cell, cell);

		g.dispose();
		return image;
	}
}
