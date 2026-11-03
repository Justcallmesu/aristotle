package io.github.justcallmesu.aristotle.data.chat

import io.github.justcallmesu.aristotle.data.HermesJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The `/` menu from `commands.catalog`, the phone's blocks, the filter and `slash.exec` answers (T15). */
class SlashCatalogTest {
    // The catalog shape (tui_gateway/command_discovery.py:206-250): pairs, canon, commands (desktop meta), skills.
    private val answer = HermesJson.parseToJsonElement(
        """{
          "pairs":[["/status","Show session status"],["/model","Switch model"],["/clear","Clear screen"],["/new","Start a new session"],
                   ["/skills","Skills hub"],["/compress","Compress context"],["/skin","Switch theme"],["/hidden-empty","x"],
                   ["/title","Rename (usage: /title <name>)"],["/gif-search","Find a GIF"],["/status","dupe"],["nonsense","x"]],
          "canon":{"/status":"/status","/reset":"/new","/new":"/new"},
          "commands":{"/status":{"argument_mode":null,"desktop":null},"/model":{"argument_mode":"options","desktop":"hidden"},
                      "/clear":{"argument_mode":null,"desktop":"terminal"},"/new":{"desktop":null},
                      "/skills":{"argument_mode":"options","desktop":null,"desktop_subcommands":["pending","approve"]},
                      "/hidden-empty":{"desktop":null,"desktop_subcommands":[]},
                      "/compress":{"desktop":null},"/skin":{"desktop":null},"/title":{"desktop":null}},
          "skills":{"/gif-search":{"usage":3,"origin":"hub"}},
          "categories":[]
        }""",
    )

    private val catalog = SlashCatalog.of(answer)

    @Test
    fun theMenuIsHermesListWithoutHiddenAndDesktopUnavailableCommands() {
        assertEquals(
            listOf("/status", "/new", "/skills", "/compress", "/skin", "/title", "/gif-search"),
            catalog.commands.map { it.name },
        )
        assertEquals("Show session status", catalog.resolve("/status")!!.description)
        assertTrue(catalog.resolve("/gif-search")!!.skill)
        assertEquals(listOf("pending", "approve"), catalog.resolve("/skills")!!.subcommands)
    }

    @Test
    fun commandsTheDesktopRunsItselfAreBlockedWithAReason() {
        assertEquals(SlashBlock.PhoneControl, catalog.resolve("/new")!!.block)
        assertEquals(SlashBlock.NotOnPhone, catalog.resolve("/compress")!!.block)
        assertEquals(SlashBlock.DesktopApp, catalog.resolve("/skin")!!.block)
        assertNull(catalog.resolve("/status")!!.block)
        assertNull(catalog.resolve("/gif-search")!!.block)
        // An alias resolves through Hermes' canon map.
        assertEquals("/new", catalog.resolve("/RESET")!!.name)
        assertNull(catalog.resolve("/unknown"))
    }

    @Test
    fun theFilterPutsRunnableCommandsFirstThenSkillsThenBlocked() {
        assertEquals(listOf("/status", "/skills", "/gif-search", "/skin", "/compress"), SlashMenu.filter(catalog, "s").map { it.name })
        // Prefix matches before substring matches within a group.
        assertEquals(listOf("/title", "/status"), SlashMenu.filter(catalog, "t").filter { it.block == null }.map { it.name })
        assertEquals(catalog.commands.size, SlashMenu.filter(catalog, "").size)
        assertTrue(SlashMenu.filter(catalog, "zzz").isEmpty())
    }

    @Test
    fun theMenuShowsOnlyWhileTheCommandNameIsTyped() {
        assertEquals("", SlashMenu.query("/"))
        assertEquals("sta", SlashMenu.query("/sta"))
        assertNull(SlashMenu.query("/status now"))
        assertNull(SlashMenu.query("hello /status"))
        assertTrue(SlashMenu.isCommand("  /status"))
        assertFalse(SlashMenu.isCommand("/"))
        assertFalse(SlashMenu.isCommand("/ hello"))
        assertFalse(SlashMenu.isCommand("hello"))
        assertEquals(TypedCommand("/skills", "pending  now"), SlashMenu.typed("/skills   pending  now "))
    }

    @Test
    fun pickingInsertsTheNameAndASpaceButNotABlockedOne() {
        assertEquals("/status ", SlashMenu.insert(catalog.resolve("/status")!!))
        assertNull(SlashMenu.insert(catalog.resolve("/skin")!!))
    }

    @Test
    fun aNarrowedCommandRunsOnlyWithAnAllowedFirstArgument() {
        val skills = catalog.resolve("/skills")!!
        assertNull(SlashMenu.subcommandBlocked(skills, "pending"))
        assertNull(SlashMenu.subcommandBlocked(skills, "APPROVE 3"))
        assertEquals(listOf("pending", "approve"), SlashMenu.subcommandBlocked(skills, ""))
        assertEquals(listOf("pending", "approve"), SlashMenu.subcommandBlocked(skills, "install foo"))
        assertNull(SlashMenu.subcommandBlocked(catalog.resolve("/status")!!, "anything"))
    }

    @Test
    fun slashExecAnswersAreOutputOrATurnToSubmit() {
        fun of(text: String) = SlashAnswer.of(HermesJson.parseToJsonElement(text), "/x")
        assertEquals(SlashAnswer.Output("ok"), of("""{"type":"exec","output":"ok"}"""))
        assertEquals(SlashAnswer.Output(""), of("""{"type":"exec"}"""))
        assertEquals(SlashAnswer.Output("warning: w\nbody"), of("""{"output":"body","warning":"w"}"""))
        // gateway/session_commands.py:476-480 (skill) and :523-528 (send).
        assertEquals(SlashAnswer.Submit("BODY", "/gif-search cats", null), of("""{"type":"skill","name":"gif-search","message":"BODY","display":"/gif-search cats"}"""))
        assertEquals(SlashAnswer.Submit("go", "/x", "Goal set"), of("""{"type":"send","message":"go","notice":"Goal set"}"""))
        assertEquals(SlashAnswer.Output(""), of("""{"type":"send","message":"  "}"""))
    }
}
