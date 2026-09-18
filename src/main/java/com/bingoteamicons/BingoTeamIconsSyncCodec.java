package com.bingoteamicons;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import net.runelite.client.util.Text;

/**
 * Encodes and decodes the shareable roster code.
 *
 * <p>Wire format is {@code BTI1:} followed by Base64 of DEFLATE-compressed JSON.
 *
 * <p>Real player names are close to random over a 39-character alphabet, so there
 * is very little redundancy to remove: measured against 700 live hiscore names, a
 * roster of that size encodes to roughly 5,000 characters however it is packed,
 * which is why sharing also supports a file. Sorting each list is the one cheap
 * win, and DEFLATE is already within a few percent of the entropy floor.
 */
class BingoTeamIconsSyncCodec
{
	static final String PREFIX = "BTI1:";

	/** Codes come from strangers, so cap what we will inflate. */
	private static final int MAX_INFLATED_BYTES = 512 * 1024;
	private static final int MAX_CODE_CHARS = 1024 * 1024;
	private static final int MAX_MEMBERS = 20_000;
	static final int MAX_LABEL_CHARS = 24;

	// Rosters are persisted as a [,\n] separated blob, so a name containing either
	// would silently split into two entries on the next read. Restricting to the
	// real RSN character set keeps decode idempotent with rebuildPlayerTeams.
	private static final int MAX_NAME_CHARS = 12;
	private static final Pattern VALID_NAME = Pattern.compile("[a-z0-9 _-]{1," + MAX_NAME_CHARS + "}");

	// Outside the name charset, and not escaped by Gson. A newline separator would
	// be written as "\n", costing a character per name.
	private static final char MEMBER_SEPARATOR = '|';

	private final Gson gson;

	BingoTeamIconsSyncCodec(Gson gson)
	{
		this.gson = gson;
	}

	static class SyncTeam
	{
		String name;
		String color;
		/** Front-coded, newline separated. Use {@link #memberList()} to read. */
		String members;

		List<String> memberList()
		{
			return decodeMembers(members);
		}
	}

	static class SyncPayload
	{
		String label;
		List<SyncTeam> teams;
	}

	static class SyncCodeException extends Exception
	{
		SyncCodeException(String message)
		{
			super(message);
		}
	}

	String encode(SyncPayload payload)
	{
		byte[] json = gson.toJson(payload).getBytes(StandardCharsets.UTF_8);
		return PREFIX + Base64.getEncoder().encodeToString(deflate(json));
	}

	SyncPayload decode(String code) throws SyncCodeException
	{
		if (code == null || code.trim().isEmpty())
		{
			throw new SyncCodeException("No code entered.");
		}

		if (code.length() > MAX_CODE_CHARS)
		{
			throw new SyncCodeException("That code is too large to be a team list.");
		}

		// pasted codes routinely arrive wrapped across lines
		String compact = code.replaceAll("\\s+", "");

		if (!compact.startsWith(PREFIX))
		{
			if (compact.matches("(?i)^BTI\\d+:.*"))
			{
				throw new SyncCodeException(
					"That code was made by a newer version of the plugin. Update Bingo Team Icons and try again.");
			}
			throw new SyncCodeException("That doesn't look like a team code. It should start with " + PREFIX);
		}

		byte[] compressed;
		try
		{
			compressed = Base64.getDecoder().decode(compact.substring(PREFIX.length()));
		}
		catch (IllegalArgumentException ex)
		{
			throw new SyncCodeException("The code is damaged. Copy it again, making sure you get all of it.");
		}

		byte[] json;
		try
		{
			json = inflate(compressed);
		}
		catch (DataFormatException ex)
		{
			throw new SyncCodeException("The code is damaged or incomplete. Copy it again, making sure you get all of it.");
		}

		SyncPayload payload;
		try
		{
			payload = gson.fromJson(new String(json, StandardCharsets.UTF_8), SyncPayload.class);
		}
		catch (JsonParseException ex)
		{
			throw new SyncCodeException("The code is damaged and could not be read.");
		}

		if (payload == null || payload.teams == null || payload.teams.isEmpty())
		{
			throw new SyncCodeException("That code contains no teams.");
		}

		if (payload.teams.size() > BingoTeamIconsPlugin.MAX_TEAMS)
		{
			throw new SyncCodeException("That code contains " + payload.teams.size()
				+ " teams, but only " + BingoTeamIconsPlugin.MAX_TEAMS + " are supported.");
		}

		payload.label = sanitizeLabel(payload.label);

		int total = 0;
		for (SyncTeam team : payload.teams)
		{
			if (team == null)
			{
				throw new SyncCodeException("The code is damaged and could not be read.");
			}

			team.name = sanitizeLabel(team.name);
			total += team.memberList().size();
			if (total > MAX_MEMBERS)
			{
				throw new SyncCodeException("That code contains too many players.");
			}
		}

		return payload;
	}

	/**
	 * Trims a user-supplied label and strips anything that could be read as a
	 * formatting tag once it reaches a Swing label or a config value.
	 */
	static String sanitizeLabel(String label)
	{
		if (label == null)
		{
			return "";
		}

		String clean = Text.removeTags(label).replaceAll("[\\p{Cntrl}]", " ").trim();
		return clean.length() > MAX_LABEL_CHARS ? clean.substring(0, MAX_LABEL_CHARS).trim() : clean;
	}

	/**
	 * Standardizes, de-duplicates and sorts names into a separated list.
	 *
	 * <p>Sorting is mostly for the reader: an imported roster shows up alphabetised
	 * in the panel rather than in whatever order the organiser happened to paste.
	 * It also groups shared prefixes where DEFLATE can reach them, but that was
	 * measured at only ~1.6% on real player names — worth having, not worth
	 * building on. Eliding those prefixes by hand was tried and is a net loss: it
	 * costs a byte per name and breaks up the repetition LZ77 feeds on.
	 */
	static String encodeMembers(Iterable<String> names)
	{
		TreeSet<String> sorted = new TreeSet<>();
		for (String name : names)
		{
			String standardized = Text.standardize(name);
			if (VALID_NAME.matcher(standardized).matches())
			{
				sorted.add(standardized);
			}
		}
		return String.join(String.valueOf(MEMBER_SEPARATOR), sorted);
	}

	static List<String> decodeMembers(String encoded)
	{
		List<String> names = new ArrayList<>();
		if (encoded == null || encoded.isEmpty())
		{
			return names;
		}

		for (String entry : encoded.split("\\" + MEMBER_SEPARATOR, -1))
		{
			String name = Text.standardize(entry);
			if (VALID_NAME.matcher(name).matches())
			{
				names.add(name);
			}
		}
		return names;
	}

	private static byte[] deflate(byte[] input)
	{
		Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
		try
		{
			deflater.setInput(input);
			deflater.finish();

			ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, input.length / 3));
			byte[] buffer = new byte[8192];
			while (!deflater.finished())
			{
				out.write(buffer, 0, deflater.deflate(buffer));
			}
			return out.toByteArray();
		}
		finally
		{
			deflater.end();
		}
	}

	private static byte[] inflate(byte[] input) throws DataFormatException
	{
		Inflater inflater = new Inflater();
		try
		{
			inflater.setInput(input);

			ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, input.length * 3));
			byte[] buffer = new byte[8192];
			while (!inflater.finished())
			{
				int read = inflater.inflate(buffer);
				if (read == 0 && (inflater.needsInput() || inflater.needsDictionary()))
				{
					throw new DataFormatException("truncated stream");
				}

				if (out.size() + read > MAX_INFLATED_BYTES)
				{
					throw new DataFormatException("inflated output exceeds cap");
				}
				out.write(buffer, 0, read);
			}
			return out.toByteArray();
		}
		finally
		{
			inflater.end();
		}
	}
}
