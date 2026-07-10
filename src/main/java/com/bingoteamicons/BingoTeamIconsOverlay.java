package com.bingoteamicons;

import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.WorldView;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;
import net.runelite.client.util.Text;

/**
 * Draws the team badge above rostered players' heads, at the spot a rank icon
 * would occupy next to the overhead name (mirrors PlayerIndicatorsOverlay).
 */
class BingoTeamIconsOverlay extends Overlay
{
	private static final int ACTOR_OVERHEAD_TEXT_MARGIN = 40;

	private final Client client;
	private final BingoTeamIconsPlugin plugin;
	private final BingoTeamIconsConfig config;

	@Inject
	private BingoTeamIconsOverlay(Client client, BingoTeamIconsPlugin plugin, BingoTeamIconsConfig config)
	{
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		setPriority(PRIORITY_MED);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.nameplateIcons() || !plugin.hasRoster())
		{
			return null;
		}

		WorldView wv = client.getTopLevelWorldView();
		if (wv == null)
		{
			return null;
		}

		FontMetrics fm = graphics.getFontMetrics();
		Player localPlayer = client.getLocalPlayer();
		for (Player player : wv.players())
		{
			if (player == localPlayer)
			{
				continue;
			}

			String name = player.getName();
			if (name == null || !wv.contains(player.getLocalLocation()))
			{
				continue;
			}

			Integer team = plugin.teamFor(Text.standardize(name));
			if (team == null)
			{
				continue;
			}

			BufferedImage badge = plugin.badgeImage(team);
			if (badge == null)
			{
				continue;
			}

			Point textLocation = player.getCanvasTextLocation(graphics, name, player.getLogicalHeight() + ACTOR_OVERHEAD_TEXT_MARGIN);
			if (textLocation == null)
			{
				continue;
			}

			Point imageLocation = new Point(
				textLocation.getX() + fm.stringWidth(name) + 2,
				textLocation.getY() - fm.getHeight() / 2 - badge.getHeight() / 2);
			OverlayUtil.renderImageLocation(graphics, imageLocation, badge);
		}

		return null;
	}
}
