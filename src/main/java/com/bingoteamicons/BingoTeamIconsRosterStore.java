package com.bingoteamicons;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

/**
 * Reads and writes the roster config keys, and owns the saved preset blob.
 *
 * <p>Keeping this out of the panel means the merge logic is plain data in, plain
 * data out, so it can be reasoned about without a Swing component in scope.
 */
@Singleton
@Slf4j
class BingoTeamIconsRosterStore
{
	static final String PRESETS_KEY = "presets";

	private static final int MAX_PRESETS = 20;
	private static final int MAX_PRESETS_JSON_CHARS = 64 * 1024;

	private final ConfigManager configManager;
	private final Gson gson;

	@Inject
	BingoTeamIconsRosterStore(ConfigManager configManager, Gson gson)
	{
		this.configManager = configManager;
		this.gson = gson;
	}

	static class PresetLimitException extends Exception
	{
		PresetLimitException(String message)
		{
			super(message);
		}
	}

	/** Outcome of merging an incoming roster into the configured one, computed before anything is written. */
	static class MergePlan
	{
		BingoTeamIconsSyncCodec.SyncPayload result;
		final List<String> overflowLabels = new ArrayList<>();
		int updatedTeams;
		int addedTeams;
		int addedPlayers;

		boolean fits()
		{
			return overflowLabels.isEmpty();
		}
	}

	int teamCount()
	{
		String stored = configManager.getConfiguration(BingoTeamIconsConfig.GROUP, BingoTeamIconsConfig.TEAM_COUNT_KEY);
		if (stored != null)
		{
			try
			{
				return clampTeamCount(Integer.parseInt(stored));
			}
			catch (NumberFormatException ex)
			{
				log.debug("invalid stored team count: {}", stored);
			}
		}
		return 2;
	}

	static int clampTeamCount(int count)
	{
		return Math.min(Math.max(count, 1), BingoTeamIconsPlugin.MAX_TEAMS);
	}

	/** Never null; empty when the user has not named the team. */
	String teamLabel(int team)
	{
		String stored = configManager.getConfiguration(BingoTeamIconsConfig.GROUP, BingoTeamIconsPanel.teamLabelKey(team));
		return stored == null ? "" : BingoTeamIconsSyncCodec.sanitizeLabel(stored);
	}

	String teamNames(int team)
	{
		String stored = configManager.getConfiguration(BingoTeamIconsConfig.GROUP, BingoTeamIconsPanel.teamNamesKey(team));
		return stored == null ? "" : stored;
	}

	private String teamColorHex(int team)
	{
		String stored = configManager.getConfiguration(BingoTeamIconsConfig.GROUP, BingoTeamIconsPanel.teamColorKey(team));
		return stored == null || stored.isEmpty() ? null : stored;
	}

	/** The configured roster, slots 1..teamCount. */
	BingoTeamIconsSyncCodec.SyncPayload read(String label)
	{
		BingoTeamIconsSyncCodec.SyncPayload payload = new BingoTeamIconsSyncCodec.SyncPayload();
		payload.label = BingoTeamIconsSyncCodec.sanitizeLabel(label);
		payload.teams = new ArrayList<>();

		int count = teamCount();
		for (int team = 1; team <= count; team++)
		{
			BingoTeamIconsSyncCodec.SyncTeam syncTeam = new BingoTeamIconsSyncCodec.SyncTeam();
			syncTeam.name = teamLabel(team);
			syncTeam.color = teamColorHex(team);
			syncTeam.members = BingoTeamIconsSyncCodec.encodeMembers(splitNames(teamNames(team)));
			payload.teams.add(syncTeam);
		}
		return payload;
	}

	/**
	 * Writes a roster into slots 1..n. Slots past n are left alone rather than
	 * cleared, matching what lowering the team count already does — they are
	 * inert because every reader stops at teamCount.
	 *
	 * <p>Callers must wrap this in {@link BingoTeamIconsPlugin#applyBulkConfigChange}
	 * so the ~30 writes propagate once instead of once each.
	 */
	void write(BingoTeamIconsSyncCodec.SyncPayload payload)
	{
		int count = clampTeamCount(payload.teams.size());
		for (int team = 1; team <= count; team++)
		{
			BingoTeamIconsSyncCodec.SyncTeam syncTeam = payload.teams.get(team - 1);

			configManager.setConfiguration(BingoTeamIconsConfig.GROUP, BingoTeamIconsPanel.teamLabelKey(team),
				syncTeam.name == null ? "" : syncTeam.name);
			configManager.setConfiguration(BingoTeamIconsConfig.GROUP, BingoTeamIconsPanel.teamNamesKey(team),
				String.join(", ", syncTeam.memberList()));

			if (syncTeam.color != null && !syncTeam.color.isEmpty())
			{
				configManager.setConfiguration(BingoTeamIconsConfig.GROUP, BingoTeamIconsPanel.teamColorKey(team),
					syncTeam.color);
			}
		}

		configManager.setConfiguration(BingoTeamIconsConfig.GROUP, BingoTeamIconsConfig.TEAM_COUNT_KEY, count);
	}

	static List<String> splitNames(String blob)
	{
		List<String> names = new ArrayList<>();
		if (blob == null || blob.isEmpty())
		{
			return names;
		}

		for (String name : blob.split("[,\n]"))
		{
			String trimmed = name.trim();
			if (!trimmed.isEmpty())
			{
				names.add(trimmed);
			}
		}
		return names;
	}

	/**
	 * Works out where each incoming team would land if merged into the existing
	 * roster. Teams are matched by name; a team with no name matches nothing,
	 * since silently merging two unnamed teams would be unrecoverable.
	 */
	static MergePlan planMerge(BingoTeamIconsSyncCodec.SyncPayload existing, BingoTeamIconsSyncCodec.SyncPayload incoming)
	{
		MergePlan plan = new MergePlan();
		plan.result = new BingoTeamIconsSyncCodec.SyncPayload();
		plan.result.label = incoming.label;
		plan.result.teams = new ArrayList<>(existing.teams);

		for (BingoTeamIconsSyncCodec.SyncTeam incomingTeam : incoming.teams)
		{
			int slot = indexOfLabel(plan.result.teams, incomingTeam.name);
			if (slot >= 0)
			{
				BingoTeamIconsSyncCodec.SyncTeam local = plan.result.teams.get(slot);

				// merge is additive: the local team keeps its own name and colour,
				// so a merge can never repaint teams the user already set up
				LinkedHashSet<String> union = new LinkedHashSet<>(local.memberList());
				int before = union.size();
				union.addAll(incomingTeam.memberList());
				plan.addedPlayers += union.size() - before;

				BingoTeamIconsSyncCodec.SyncTeam merged = new BingoTeamIconsSyncCodec.SyncTeam();
				merged.name = local.name;
				merged.color = local.color;
				merged.members = BingoTeamIconsSyncCodec.encodeMembers(union);
				plan.result.teams.set(slot, merged);
				plan.updatedTeams++;
				continue;
			}

			int free = indexOfUnused(plan.result.teams);
			if (free >= 0)
			{
				plan.result.teams.set(free, incomingTeam);
			}
			else if (plan.result.teams.size() < BingoTeamIconsPlugin.MAX_TEAMS)
			{
				plan.result.teams.add(incomingTeam);
			}
			else
			{
				String name = normalizeLabel(incomingTeam.name).isEmpty()
					? "(unnamed team)"
					: incomingTeam.name.trim();
				plan.overflowLabels.add(name);
				continue;
			}

			plan.addedTeams++;
			plan.addedPlayers += incomingTeam.memberList().size();
		}

		return plan;
	}

	private static int indexOfLabel(List<BingoTeamIconsSyncCodec.SyncTeam> teams, String label)
	{
		String needle = normalizeLabel(label);
		if (needle.isEmpty())
		{
			return -1;
		}

		for (int i = 0; i < teams.size(); i++)
		{
			if (normalizeLabel(teams.get(i).name).equals(needle))
			{
				return i;
			}
		}
		return -1;
	}

	/** A slot the user demonstrably never set up: no name and no members. */
	private static int indexOfUnused(List<BingoTeamIconsSyncCodec.SyncTeam> teams)
	{
		for (int i = 0; i < teams.size(); i++)
		{
			BingoTeamIconsSyncCodec.SyncTeam team = teams.get(i);
			if (normalizeLabel(team.name).isEmpty() && team.memberList().isEmpty())
			{
				return i;
			}
		}
		return -1;
	}

	private static String normalizeLabel(String label)
	{
		return label == null ? "" : label.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
	}

	static String displayName(BingoTeamIconsSyncCodec.SyncTeam team, int slot)
	{
		String name = team == null ? null : team.name;
		return name == null || name.trim().isEmpty() ? "Team " + slot : name.trim();
	}

	LinkedHashMap<String, String> readPresets()
	{
		LinkedHashMap<String, String> presets = new LinkedHashMap<>();
		String json = configManager.getConfiguration(BingoTeamIconsConfig.GROUP, PRESETS_KEY);
		if (json == null || json.isEmpty())
		{
			return presets;
		}

		try
		{
			JsonObject object = gson.fromJson(json, JsonObject.class);
			if (object == null)
			{
				return presets;
			}

			for (Map.Entry<String, JsonElement> entry : object.entrySet())
			{
				JsonElement value = entry.getValue();
				if (value != null && value.isJsonPrimitive())
				{
					presets.put(entry.getKey(), value.getAsString());
				}
			}
		}
		catch (JsonParseException ex)
		{
			// a corrupt blob must not brick the panel; the user can just re-save
			log.debug("discarding unreadable presets value", ex);
		}
		return presets;
	}

	void savePreset(String label, String code) throws PresetLimitException
	{
		String clean = BingoTeamIconsSyncCodec.sanitizeLabel(label);
		if (clean.isEmpty())
		{
			throw new PresetLimitException("Give the preset a name first.");
		}

		LinkedHashMap<String, String> presets = readPresets();
		if (!presets.containsKey(clean) && presets.size() >= MAX_PRESETS)
		{
			throw new PresetLimitException("You already have " + MAX_PRESETS
				+ " saved presets. Delete one before saving another.");
		}

		presets.put(clean, code);

		String json = gson.toJson(presets);
		if (json.length() > MAX_PRESETS_JSON_CHARS)
		{
			throw new PresetLimitException("These rosters are too large to save as a preset. "
				+ "Share them as a file instead.");
		}

		configManager.setConfiguration(BingoTeamIconsConfig.GROUP, PRESETS_KEY, json);
	}

	void deletePreset(String label)
	{
		LinkedHashMap<String, String> presets = readPresets();
		if (presets.remove(label) != null)
		{
			configManager.setConfiguration(BingoTeamIconsConfig.GROUP, PRESETS_KEY, gson.toJson(presets));
		}
	}
}
