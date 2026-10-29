package io.github.hatake716.omochi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class WorkbenchSettingsTest {
    @Test
    fun legacyDefaultsMigrateToCurrentDefaults() {
        val outcome = WorkbenchSettings.migrate(LEGACY_SETTINGS)

        val updated = outcome as WorkbenchSettings.Outcome.Updated
        assertTrue(
            "migrated=${WorkbenchSettings.parse(updated.text)}",
            WorkbenchSettings.jsonEquals(
                WorkbenchSettings.defaults(),
                WorkbenchSettings.parse(updated.text),
            ),
        )
        assertEquals(
            setOf(
                "editor.cursorSmoothCaretAnimation",
                "editor.minimap.enabled",
                "workbench.colorCustomizations",
                "chat.disableAIFeatures",
                "extensions.ignoreRecommendations",
                "workbench.enableExperiments",
                "workbench.settings.enableNaturalLanguageSearch",
                "search.followSymlinks",
                "workbench.tips.enabled",
            ),
            updated.changedKeys.toSet(),
        )
        // 変更しない行は元の書式のまま残る。
        assertTrue(updated.text.startsWith("{\n  \"workbench.startupEditor\": \"none\",\n"))
        assertTrue("  \"editor.fontSize\": 14,\n" in updated.text)
        assertTrue(
            "  \"workbench.colorCustomizations\": {\n" +
                "    \"[Default Light Modern]\": {\n" +
                "      \"titleBar.activeBackground\": \"#F2F2F7\"," in updated.text,
        )
        assertTrue(updated.text.endsWith("  \"workbench.tips.enabled\": false\n}\n"))
        assertEquals(WorkbenchSettings.Outcome.Unchanged, WorkbenchSettings.migrate(updated.text))
    }

    @Test
    fun colorCustomizationsAreScopedToTheLightTheme() {
        val colors = WorkbenchSettings.defaults()["workbench.colorCustomizations"] as Map<*, *>
        assertEquals(setOf("[Default Light Modern]"), colors.keys)
        val palette = colors["[Default Light Modern]"] as Map<*, *>
        assertEquals(64, palette.size)
        assertEquals("#007AFF", palette["focusBorder"])
        assertEquals("#F2F2F7", palette["titleBar.activeBackground"])
        assertEquals("#3C3C4377", palette["scrollbarSlider.activeBackground"])
        palette.values.forEach { value ->
            assertTrue("$value", (value as String).matches(Regex("#[0-9A-F]{6}([0-9A-F]{2})?")))
        }
    }

    @Test
    fun freshDefaultsAreStableAndCarryThePerformanceSettings() {
        val text = WorkbenchSettings.defaultSettingsText()
        val parsed = WorkbenchSettings.parse(text)

        assertTrue(WorkbenchSettings.jsonEquals(WorkbenchSettings.defaults(), parsed))
        assertEquals("off", parsed["editor.cursorSmoothCaretAnimation"])
        assertEquals(false, parsed["editor.minimap.enabled"])
        WorkbenchSettings.additions.forEach { (key, value) ->
            assertTrue(key, WorkbenchSettings.jsonEquals(value, parsed[key]))
        }
        assertEquals(WorkbenchSettings.Outcome.Unchanged, WorkbenchSettings.migrate(text))
    }

    @Test
    fun valuesTheUserChangedAreNeverTouched() {
        val tweakedPalette = WorkbenchSettings.legacyWarmPalette.toMutableMap()
            .apply { put("statusBar.background", "#123456") }
        val settings = linkedMapOf<String, Any?>(
            "editor.cursorSmoothCaretAnimation" to "explicit",
            "editor.minimap.enabled" to false,
            "workbench.colorCustomizations" to tweakedPalette,
        )
        WorkbenchSettings.additions.forEach { (key, value) ->
            settings[key] = if (value is Boolean) !value else value
        }
        val text = WorkbenchSettings.render(settings, "", "  ", "\n") + "\n"

        assertEquals(WorkbenchSettings.Outcome.Unchanged, WorkbenchSettings.migrate(text))
    }

    @Test
    fun legacyPaletteMatchesRegardlessOfKeyOrder() {
        val reversed = LinkedHashMap<String, Any?>().apply {
            WorkbenchSettings.legacyWarmPalette.entries.reversed().forEach { put(it.key, it.value) }
        }
        val text = WorkbenchSettings.render(
            mapOf("workbench.colorCustomizations" to reversed),
            "",
            "  ",
            "\n",
        )

        val updated = WorkbenchSettings.migrate(text) as WorkbenchSettings.Outcome.Updated
        assertTrue(
            WorkbenchSettings.jsonEquals(
                WorkbenchSettings.colorCustomizations,
                WorkbenchSettings.parse(updated.text)["workbench.colorCustomizations"],
            ),
        )
    }

    @Test
    fun lowercaseLegacyColourIsTreatedAsAUserValue() {
        val lowered = WorkbenchSettings.legacyWarmPalette.toMutableMap()
            .apply { put("focusBorder", "#c96954") }
        val text = WorkbenchSettings.render(
            mapOf("workbench.colorCustomizations" to lowered),
            "",
            "  ",
            "\n",
        )

        val updated = WorkbenchSettings.migrate(text) as WorkbenchSettings.Outcome.Updated
        assertFalse("workbench.colorCustomizations" in updated.changedKeys)
    }

    @Test
    fun newKeysFollowTheFileIndentation() {
        val text = "{\n" +
            "    \"editor.minimap.enabled\": true,\n" +
            "    \"files.autoSave\": \"off\"\n" +
            "}\n"

        val updated = WorkbenchSettings.migrate(text) as WorkbenchSettings.Outcome.Updated

        val expected = "{\n" +
            "    \"editor.minimap.enabled\": false,\n" +
            "    \"files.autoSave\": \"off\",\n" +
            "    \"chat.disableAIFeatures\": true,\n" +
            "    \"extensions.autoCheckUpdates\": false,\n" +
            "    \"extensions.autoUpdate\": false,\n" +
            "    \"extensions.ignoreRecommendations\": true,\n" +
            "    \"workbench.enableExperiments\": false,\n" +
            "    \"workbench.settings.enableNaturalLanguageSearch\": false,\n" +
            "    \"search.followSymlinks\": false,\n" +
            "    \"workbench.tips.enabled\": false\n" +
            "}\n"
        assertEquals(expected, updated.text)
    }

    @Test
    fun replacedObjectIsIndentedAtItsKey() {
        val text = "{\n\t\"workbench.colorCustomizations\": " +
            WorkbenchSettings.render(WorkbenchSettings.legacyWarmPalette, "\t", "\t", "\n") +
            "\n}"

        val updated = WorkbenchSettings.migrate(text) as WorkbenchSettings.Outcome.Updated

        assertTrue(
            updated.text.startsWith(
                "{\n\t\"workbench.colorCustomizations\": {\n" +
                    "\t\t\"[Default Light Modern]\": {\n" +
                    "\t\t\t\"titleBar.activeBackground\": \"#F2F2F7\",\n",
            ),
        )
        assertTrue("\t\t}\n\t},\n\t\"chat.disableAIFeatures\": true," in updated.text)
    }

    @Test
    fun emptyObjectReceivesOnlyTheAddedKeys() {
        val updated = WorkbenchSettings.migrate("{}") as WorkbenchSettings.Outcome.Updated

        val parsed = WorkbenchSettings.parse(updated.text)
        assertEquals(WorkbenchSettings.additions.map { it.first }, parsed.keys.toList())
        assertTrue(updated.text.startsWith("{\n  \"chat.disableAIFeatures\": true,\n"))
        assertTrue(updated.text.endsWith("\n}"))
    }

    @Test
    fun filesWithCommentsAreLeftAlone() {
        listOf(
            "{\n  // 自分用のメモ\n  \"editor.minimap.enabled\": true\n}\n",
            "{\n  /* block */ \"editor.minimap.enabled\": true\n}\n",
            "{\n  \"editor.minimap.enabled\": true\n}\n// trailing\n",
        ).forEach { text ->
            val outcome = WorkbenchSettings.migrate(text)
            assertTrue("$outcome", outcome is WorkbenchSettings.Outcome.Skipped)
        }
    }

    @Test
    fun slashesInsideStringsAreNotComments() {
        val text = "{\n  \"http.proxy\": \"http://proxy.example:8080/*x*/\",\n" +
            "  \"path\": \"a\\/b\\u00e9\"\n}\n"

        val updated = WorkbenchSettings.migrate(text) as WorkbenchSettings.Outcome.Updated

        // 既存の値はエスケープ表記も含めて一字一句そのまま残る。
        assertTrue(updated.text.startsWith(text.removeSuffix("\n}\n")))
        assertEquals("http://proxy.example:8080/*x*/", WorkbenchSettings.parse(updated.text)["http.proxy"])
        assertEquals("a/b\u00e9", WorkbenchSettings.parse(updated.text)["path"])
    }

    @Test
    fun invalidOrNonObjectDocumentsAreSkipped() {
        listOf(
            "",
            "   ",
            "[]",
            "\"text\"",
            "{\"a\": }",
            "{\"a\": 1} trailing",
            "{\"a\": 01}",
            "{\"a\": \"\u0001\"}",
            "{\"a\": \"\\x\"}",
            "{\"a\": tru}",
            "{\"a\": 1",
            "{\"a\": " + "[".repeat(10_000),
        ).forEach { text ->
            val outcome = WorkbenchSettings.migrate(text)
            assertTrue("'$text' -> $outcome", outcome is WorkbenchSettings.Outcome.Skipped)
        }
    }

    @Test
    fun trailingCommasAreAcceptedAndKept() {
        val text = "{\n  \"editor.minimap.enabled\": true,\n}\n"

        val updated = WorkbenchSettings.migrate(text) as WorkbenchSettings.Outcome.Updated

        assertTrue(updated.text.startsWith("{\n  \"editor.minimap.enabled\": false,\n  \"chat."))
        assertTrue(updated.text.endsWith("\"workbench.tips.enabled\": false,\n}\n"))
        assertEquals(false, WorkbenchSettings.parse(updated.text)["editor.minimap.enabled"])
    }

    @Test
    fun windowsLineEndingsAndByteOrderMarkArePreserved() {
        val text = "\uFEFF{\r\n  \"editor.minimap.enabled\": true\r\n}\r\n"

        val updated = WorkbenchSettings.migrate(text) as WorkbenchSettings.Outcome.Updated

        assertTrue(updated.text.startsWith("\uFEFF{\r\n"))
        assertFalse(Regex("[^\r]\n").containsMatchIn(updated.text))
        assertEquals(false, WorkbenchSettings.parse(updated.text)["editor.minimap.enabled"])
    }

    @Test
    fun duplicateKeysFollowTheLastValue() {
        val text = "{\n  \"editor.minimap.enabled\": false,\n  \"editor.minimap.enabled\": true\n}"

        val updated = WorkbenchSettings.migrate(text) as WorkbenchSettings.Outcome.Updated

        assertTrue(
            updated.text.startsWith(
                "{\n  \"editor.minimap.enabled\": false,\n  \"editor.minimap.enabled\": false,\n",
            ),
        )
    }

    @Test
    fun jsonEqualityIgnoresKeyOrderAndNumberScale() {
        assertTrue(WorkbenchSettings.jsonEquals(1, WorkbenchSettings.parse("{\"a\": 1.0}")["a"]))
        assertTrue(
            WorkbenchSettings.jsonEquals(
                WorkbenchSettings.parse("{\"a\": {\"x\": 1, \"y\": [true, null]}}"),
                WorkbenchSettings.parse("{\"a\": {\"y\": [true, null], \"x\": 1e0}}"),
            ),
        )
        assertFalse(WorkbenchSettings.jsonEquals(listOf(1, 2), listOf(2, 1)))
        assertFalse(WorkbenchSettings.jsonEquals("true", true))
        assertFalse(WorkbenchSettings.jsonEquals(mapOf("a" to null), emptyMap<String, Any?>()))
    }

    @Test
    fun missingSettingsReceiveDefaultsOnlyDuringSetup() {
        withUserData { settings, marker ->
            assertNull(OmochiRuntime.syncWorkbenchSettings(settings, marker, writeDefaultsIfMissing = false))
            assertFalse(settings.exists())

            OmochiRuntime.syncWorkbenchSettings(settings, marker, writeDefaultsIfMissing = true)

            assertEquals(WorkbenchSettings.defaultSettingsText(), settings.readText())
            assertEquals("version=${WorkbenchSettings.VERSION}\n", marker.readText())
        }
    }

    @Test
    fun existingSettingsAreMigratedOnceAndThenLeftToTheUser() {
        withUserData { settings, marker ->
            settings.parentFile!!.mkdirs()
            settings.writeText(LEGACY_SETTINGS)

            OmochiRuntime.syncWorkbenchSettings(settings, marker, writeDefaultsIfMissing = true)
            val migrated = settings.readText()
            assertTrue(
                WorkbenchSettings.jsonEquals(
                    WorkbenchSettings.defaults(),
                    WorkbenchSettings.parse(migrated),
                ),
            )
            assertEquals("version=${WorkbenchSettings.VERSION}\n", marker.readText())

            // 移行後にユーザーが消したキーや戻した値は、次の起動で復活させない。
            val edited = migrated.replace(",\n  \"workbench.tips.enabled\": false", "")
                .replace("\"editor.minimap.enabled\": false", "\"editor.minimap.enabled\": true")
            settings.writeText(edited)
            assertNull(OmochiRuntime.syncWorkbenchSettings(settings, marker, writeDefaultsIfMissing = false))
            assertEquals(edited, settings.readText())
            assertFalse(File(settings.parentFile, "settings.json.part").exists())
        }
    }

    @Test
    fun declinedFilesAreRetriedAfterTheUserFixesThem() {
        withUserData { settings, marker ->
            settings.parentFile!!.mkdirs()
            val commented = "{\n  // keep\n  \"editor.minimap.enabled\": true\n}\n"
            settings.writeText(commented)

            OmochiRuntime.syncWorkbenchSettings(settings, marker, writeDefaultsIfMissing = false)
            assertEquals(commented, settings.readText())
            assertFalse(marker.exists())

            settings.writeText("{\n  \"editor.minimap.enabled\": true\n}\n")
            OmochiRuntime.syncWorkbenchSettings(settings, marker, writeDefaultsIfMissing = false)
            assertEquals(false, WorkbenchSettings.parse(settings.readText())["editor.minimap.enabled"])
            assertTrue(marker.exists())
        }
    }

    @Test
    fun symbolicLinksAreNotReplaced() {
        withUserData { settings, marker ->
            settings.parentFile!!.mkdirs()
            val target = File(settings.parentFile!!.parentFile, "dotfiles-settings.json")
            target.writeText("{\n  \"editor.minimap.enabled\": true\n}\n")
            Files.createSymbolicLink(settings.toPath(), target.toPath())

            OmochiRuntime.syncWorkbenchSettings(settings, marker, writeDefaultsIfMissing = true)

            assertTrue(Files.isSymbolicLink(settings.toPath()))
            assertEquals("{\n  \"editor.minimap.enabled\": true\n}\n", target.readText())
            assertFalse(marker.exists())
        }
    }

    private fun withUserData(block: (settings: File, marker: File) -> Unit) {
        val root = Files.createTempDirectory("omochi-settings").toFile()
        try {
            block(File(root, "User/settings.json"), File(root, ".omochi-settings-migration"))
        } finally {
            root.deleteRecursively()
        }
    }

    private companion object {
        /** settings.json exactly as Omochi 0.x wrote it (Android JSONObject.toString(2)). */
        val LEGACY_SETTINGS = """
            {
              "workbench.startupEditor": "none",
              "workbench.colorTheme": "Default Light Modern",
              "workbench.iconTheme": "vs-seti",
              "workbench.commandPalette.experimental.suggestCommands": true,
              "workbench.editor.showTabs": "multiple",
              "workbench.editor.tabSizing": "shrink",
              "workbench.list.smoothScrolling": true,
              "workbench.tree.indent": 16,
              "workbench.colorCustomizations": {
                "titleBar.activeBackground": "#E9E4DF",
                "titleBar.activeForeground": "#24262B",
                "activityBar.background": "#EEEAE6",
                "activityBar.foreground": "#24262B",
                "activityBar.inactiveForeground": "#777A82",
                "sideBar.background": "#F4F1ED",
                "sideBar.foreground": "#2F3035",
                "sideBar.border": "#D5CFC9",
                "editorGroupHeader.tabsBackground": "#ECE8E3",
                "tab.activeBackground": "#FFFFFF",
                "tab.inactiveBackground": "#ECE8E3",
                "tab.border": "#D5CFC9",
                "statusBar.background": "#C96954",
                "statusBar.foreground": "#FFFFFF",
                "focusBorder": "#C96954",
                "button.background": "#C96954",
                "button.hoverBackground": "#B85B48",
                "list.activeSelectionBackground": "#D9CFCA",
                "list.hoverBackground": "#E8E2DD"
              },
              "window.commandCenter": true,
              "window.menuBarVisibility": "compact",
              "editor.fontSize": 14,
              "editor.lineHeight": 22,
              "editor.fontLigatures": true,
              "editor.cursorSmoothCaretAnimation": "on",
              "editor.cursorBlinking": "smooth",
              "editor.smoothScrolling": true,
              "editor.stickyScroll.enabled": true,
              "editor.minimap.enabled": true,
              "editor.wordWrap": "on",
              "editor.multiCursorModifier": "ctrlCmd",
              "terminal.integrated.fontSize": 13,
              "terminal.integrated.cursorBlinking": true,
              "terminal.integrated.smoothScrolling": true,
              "terminal.integrated.defaultProfile.linux": "bash",
              "files.autoSave": "afterDelay",
              "files.autoSaveDelay": 1000,
              "files.trimTrailingWhitespace": false,
              "search.useIgnoreFiles": true,
              "git.autofetch": false,
              "git.confirmSync": true,
              "extensions.autoCheckUpdates": false,
              "extensions.autoUpdate": false,
              "telemetry.telemetryLevel": "off"
            }
        """.trimIndent() + "\n"
    }
}
