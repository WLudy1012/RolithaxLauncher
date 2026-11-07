package com.wludy.rolithax.launcher.shared.console

internal class PanelXml(private val text: String) {
    private data class Tag(val name: String, val attributes: Map<String, String>, val closed: Boolean)

    private var position = 0
    private val ids = mutableSetOf<String>()

    fun parse(): Pair<String, List<ConsolePanelNode>> {
        if (text.length > 65_536) fail("Layout is too large")
        val root = openTag()
        if (root.name != "panel" || root.closed || root.attributes.keys != setOf("title"))
            fail("Expected <panel title=\"…\">")
        val nodes = children(root.name, 1)
        whitespace()
        if (position != text.length || nodes.isEmpty()) fail("Expected one non-empty panel")
        return root.attributes.getValue("title") to nodes
    }

    private fun children(parent: String, depth: Int): List<ConsolePanelNode> = buildList {
        whitespace()
        while (!text.startsWith("</", position)) {
            add(node(depth))
            whitespace()
        }
        expect("</")
        if (name() != parent) fail("Mismatched closing tag")
        whitespace()
        expect(">")
    }

    private fun node(depth: Int): ConsolePanelNode {
        if (depth > 8 || ids.size >= 128) fail("Layout is too deeply nested or has too many controls")
        val tag = openTag()
        val properties = tag.attributes
        val id = properties["id"]?.takeIf(symbol::matches) ?: fail("Control needs a valid ID")
        if (!ids.add(id)) fail("Duplicate control ID")
        val label = properties["label"] ?: fail("Control needs a label")
        val field = properties["field"]
        val action = properties["action"]
        val base = setOf("id", "label")
        val expected = when (tag.name) {
            "section" -> base
            "text" -> if (field == null) base else base + "field"
            "status", "list", "field" -> base + "field"
            "button" -> base + "action"
            else -> fail("Unknown control")
        }
        if (properties.keys != expected || field != null && !symbol.matches(field) ||
            action != null && !symbol.matches(action)
        ) fail("Unexpected or invalid control attributes")
        val nested = if (tag.closed) emptyList() else if (tag.name == "section") {
            children(tag.name, depth + 1)
        } else {
            whitespace()
            expect("</")
            if (name() != tag.name) fail("Only sections may contain controls")
            whitespace()
            expect(">")
            emptyList()
        }
        return ConsolePanelNode(id, tag.name, label, field, action, nested)
    }

    private fun openTag(): Tag {
        whitespace()
        expect("<")
        val tagName = name()
        val attributes = linkedMapOf<String, String>()
        while (true) {
            val separated = whitespace()
            if (consume("/>")) return Tag(tagName, attributes, true)
            if (consume(">")) return Tag(tagName, attributes, false)
            if (!separated || attributes.size >= 4) fail("Expected an attribute or tag end")
            val key = name()
            whitespace()
            expect("=")
            whitespace()
            if (key in attributes) fail("Duplicate attribute")
            attributes[key] = attribute()
        }
    }

    private fun attribute(): String {
        val quote = text.getOrNull(position)?.takeIf { it == '"' || it == '\'' }
            ?: fail("Expected a quoted attribute")
        position++
        return buildString {
            while (text.getOrNull(position) != quote) {
                val char = text.getOrNull(position++) ?: fail("Unclosed attribute")
                if (length >= 256 || char == '<' || char.isISOControl())
                    fail("Invalid attribute value")
                append(if (char == '&') entity() else char)
            }
            position++
            if (isBlank()) fail("Attributes cannot be blank")
        }
    }

    private fun entity(): Char {
        val end = text.indexOf(';', position)
        if (end !in position..position + 4) fail("Unsupported XML entity")
        val name = text.substring(position, end)
        position = end + 1
        return when (name) {
            "amp" -> '&'
            "lt" -> '<'
            "gt" -> '>'
            "quot" -> '"'
            "apos" -> '\''
            else -> fail("Unsupported XML entity")
        }
    }

    private fun name(): String {
        val start = position
        while (text.getOrNull(position)?.let { it in 'a'..'z' || it in '0'..'9' || it == '-' } == true)
            position++
        return text.substring(start, position).takeIf(symbol::matches) ?: fail("Invalid XML name")
    }

    private fun whitespace(): Boolean {
        val start = position
        while (text.getOrNull(position) in listOf(' ', '\t', '\r', '\n')) position++
        return position != start
    }

    private fun consume(token: String): Boolean {
        if (!text.startsWith(token, position)) return false
        position += token.length
        return true
    }

    private fun expect(token: String) {
        if (!consume(token)) fail("Expected '$token'")
    }

    private fun fail(reason: String): Nothing =
        invalid("ui.xml", 1 + text.take(position).count { it == '\n' }, reason)
}
