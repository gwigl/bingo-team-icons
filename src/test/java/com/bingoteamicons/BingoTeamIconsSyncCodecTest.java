package com.bingoteamicons;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class BingoTeamIconsSyncCodecTest
{
	private final BingoTeamIconsSyncCodec codec = new BingoTeamIconsSyncCodec(new Gson());

	private static BingoTeamIconsSyncCodec.SyncTeam team(String name, String color, String... members)
	{
		BingoTeamIconsSyncCodec.SyncTeam team = new BingoTeamIconsSyncCodec.SyncTeam();
		team.name = name;
		team.color = color;
		team.members = BingoTeamIconsSyncCodec.encodeMembers(Arrays.asList(members));
		return team;
	}

	private static BingoTeamIconsSyncCodec.SyncPayload payload(String label, BingoTeamIconsSyncCodec.SyncTeam... teams)
	{
		BingoTeamIconsSyncCodec.SyncPayload payload = new BingoTeamIconsSyncCodec.SyncPayload();
		payload.label = label;
		payload.teams = new ArrayList<>(Arrays.asList(teams));
		return payload;
	}

	@Test
	public void roundTripsARoster() throws Exception
	{
		BingoTeamIconsSyncCodec.SyncPayload original = payload("Summer Bingo",
			team("Red Dragons", "#FF0000", "Zezima", "B0aty", "Woox"),
			team("Blue Whales", "#0000FF", "Lynx Titan", "Settled"));

		BingoTeamIconsSyncCodec.SyncPayload decoded = codec.decode(codec.encode(original));

		assertEquals("Summer Bingo", decoded.label);
		assertEquals(2, decoded.teams.size());
		assertEquals("Red Dragons", decoded.teams.get(0).name);
		assertEquals("#FF0000", decoded.teams.get(0).color);
		// members come back standardized and sorted
		assertEquals(Arrays.asList("b0aty", "woox", "zezima"), decoded.teams.get(0).memberList());
		assertEquals(Arrays.asList("lynx titan", "settled"), decoded.teams.get(1).memberList());
	}

	@Test
	public void membersRoundTripSortedWithSharedPrefixes()
	{
		List<String> names = Arrays.asList("iron mammal", "iron man", "iron", "ironbar", "zezima");
		List<String> decoded = BingoTeamIconsSyncCodec.decodeMembers(
			BingoTeamIconsSyncCodec.encodeMembers(names));

		assertEquals(Arrays.asList("iron", "iron mammal", "iron man", "ironbar", "zezima"), decoded);
	}

	@Test
	public void membersAreDeduplicated()
	{
		List<String> decoded = BingoTeamIconsSyncCodec.decodeMembers(
			BingoTeamIconsSyncCodec.encodeMembers(Arrays.asList("Zezima", "zezima", "ZEZIMA", " zezima ")));

		assertEquals(1, decoded.size());
		assertEquals("zezima", decoded.get(0));
	}

	@Test
	public void toleratesWrappedAndPaddedCodes() throws Exception
	{
		String code = codec.encode(payload("Wrapped", team("A", "#010203", "zezima", "woox")));

		StringBuilder wrapped = new StringBuilder();
		for (int i = 0; i < code.length(); i += 30)
		{
			wrapped.append(code, i, Math.min(i + 30, code.length())).append('\n');
		}

		BingoTeamIconsSyncCodec.SyncPayload decoded = codec.decode("  " + wrapped + "  ");
		assertEquals(Arrays.asList("woox", "zezima"), decoded.teams.get(0).memberList());
	}

	@Test
	public void rejectsNonCode()
	{
		assertDecodeFails("hello world", "doesn't look like");
	}

	@Test
	public void rejectsEmptyInput()
	{
		assertDecodeFails("   ", "No code");
	}

	@Test
	public void rejectsFutureVersionBeforeInflating()
	{
		assertDecodeFails("BTI9:abcdef", "newer version");
	}

	@Test
	public void rejectsCorruptPayload() throws Exception
	{
		String code = codec.encode(payload("X", team("A", "#010203", "zezima")));
		// chop the middle out, keeping valid Base64 characters
		String damaged = code.substring(0, code.length() / 2) + code.substring(code.length() / 2 + 8);
		assertDecodeFails(damaged, "damaged");
	}

	@Test
	public void rejectsNonBase64Body()
	{
		assertDecodeFails(BingoTeamIconsSyncCodec.PREFIX + "!!!!not base64!!!!", "damaged");
	}

	@Test
	public void dropsNamesOutsideTheRsnCharacterSet()
	{
		// a name carrying a comma would split into two entries once written to config
		List<String> decoded = BingoTeamIconsSyncCodec.decodeMembers(
			BingoTeamIconsSyncCodec.encodeMembers(Arrays.asList("good name", "bad,name", "waaaaaaaaaaaaytoolong")));

		assertEquals(Arrays.asList("good name"), decoded);
	}

	@Test
	public void dropsHostileMembersFromADecodedPayload() throws Exception
	{
		// hand-built payload, as a malicious sharer could produce
		BingoTeamIconsSyncCodec.SyncPayload hostile = new BingoTeamIconsSyncCodec.SyncPayload();
		hostile.label = "evil";
		hostile.teams = new ArrayList<>();
		BingoTeamIconsSyncCodec.SyncTeam team = new BingoTeamIconsSyncCodec.SyncTeam();
		team.name = "T";
		team.color = "#000000";
		team.members = "zezima|has,comma|<img=3>|" + String.join("", java.util.Collections.nCopies(50, "x"));
		hostile.teams.add(team);

		BingoTeamIconsSyncCodec.SyncPayload decoded = codec.decode(codec.encode(hostile));
		assertEquals(Arrays.asList("zezima"), decoded.teams.get(0).memberList());
	}

	@Test
	public void clampsAndStripsLabels()
	{
		assertEquals("Plain", BingoTeamIconsSyncCodec.sanitizeLabel("  Plain  "));
		assertEquals("bold", BingoTeamIconsSyncCodec.sanitizeLabel("<col=ff0000>bold</col>"));
		assertEquals("", BingoTeamIconsSyncCodec.sanitizeLabel(null));
		assertTrue(BingoTeamIconsSyncCodec.sanitizeLabel(
			"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa").length() <= BingoTeamIconsSyncCodec.MAX_LABEL_CHARS);
	}

	@Test
	public void mergeMatchesTeamsByNameAndKeepsLocalColour()
	{
		BingoTeamIconsSyncCodec.SyncPayload existing = payload("",
			team("Red", "#111111", "zezima"),
			team("Blue", "#222222", "woox"));
		BingoTeamIconsSyncCodec.SyncPayload incoming = payload("Event",
			team("red", "#999999", "settled"),
			team("Green", "#333333", "b0aty"));

		BingoTeamIconsRosterStore.MergePlan plan = BingoTeamIconsRosterStore.planMerge(existing, incoming);

		assertTrue(plan.fits());
		assertEquals(3, plan.result.teams.size());
		// case-insensitive name match, local colour and casing preserved
		assertEquals("Red", plan.result.teams.get(0).name);
		assertEquals("#111111", plan.result.teams.get(0).color);
		assertEquals(Arrays.asList("settled", "zezima"), plan.result.teams.get(0).memberList());
		assertEquals("Green", plan.result.teams.get(2).name);
		assertEquals(1, plan.addedTeams);
		assertEquals(1, plan.updatedTeams);
	}

	@Test
	public void mergeReusesSlotsTheUserNeverSetUp()
	{
		BingoTeamIconsSyncCodec.SyncPayload existing = payload("",
			team("Red", "#111111", "zezima"),
			team("", null));
		BingoTeamIconsSyncCodec.SyncPayload incoming = payload("", team("Green", "#333333", "b0aty"));

		BingoTeamIconsRosterStore.MergePlan plan = BingoTeamIconsRosterStore.planMerge(existing, incoming);

		assertEquals(2, plan.result.teams.size());
		assertEquals("Green", plan.result.teams.get(1).name);
	}

	@Test
	public void mergeReportsOverflowInsteadOfDroppingTeams()
	{
		BingoTeamIconsSyncCodec.SyncPayload existing = new BingoTeamIconsSyncCodec.SyncPayload();
		existing.teams = new ArrayList<>();
		for (int i = 1; i <= BingoTeamIconsPlugin.MAX_TEAMS; i++)
		{
			existing.teams.add(team("Local " + i, "#111111", "player" + i));
		}

		BingoTeamIconsSyncCodec.SyncPayload incoming = payload("", team("Extra", "#333333", "b0aty"));
		BingoTeamIconsRosterStore.MergePlan plan = BingoTeamIconsRosterStore.planMerge(existing, incoming);

		assertFalse(plan.fits());
		assertEquals(Arrays.asList("Extra"), plan.overflowLabels);
		assertEquals(BingoTeamIconsPlugin.MAX_TEAMS, plan.result.teams.size());
	}

	@Test
	public void unnamedTeamsNeverMatchEachOther()
	{
		BingoTeamIconsSyncCodec.SyncPayload existing = payload("", team("", "#111111", "zezima"));
		BingoTeamIconsSyncCodec.SyncPayload incoming = payload("", team("", "#222222", "woox"));

		BingoTeamIconsRosterStore.MergePlan plan = BingoTeamIconsRosterStore.planMerge(existing, incoming);

		// the existing slot has members, so it is not free; the incoming team appends
		assertEquals(2, plan.result.teams.size());
	}

	@Test
	public void rejectsTooManyTeams() throws Exception
	{
		BingoTeamIconsSyncCodec.SyncPayload oversized = new BingoTeamIconsSyncCodec.SyncPayload();
		oversized.label = "";
		oversized.teams = new ArrayList<>();
		for (int i = 0; i <= BingoTeamIconsPlugin.MAX_TEAMS; i++)
		{
			oversized.teams.add(team("T" + i, "#000000", "player" + i));
		}

		assertDecodeFails(codec.encode(oversized), "teams");
	}

	private void assertDecodeFails(String code, String expectedFragment)
	{
		try
		{
			codec.decode(code);
			fail("expected decode to fail for: " + code);
		}
		catch (BingoTeamIconsSyncCodec.SyncCodeException ex)
		{
			assertTrue("message was: " + ex.getMessage(),
				ex.getMessage().toLowerCase().contains(expectedFragment.toLowerCase()));
		}
	}
}
