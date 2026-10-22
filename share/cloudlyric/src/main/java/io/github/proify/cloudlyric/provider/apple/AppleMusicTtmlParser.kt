/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.cloudlyric.provider.apple

import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.max

object AppleMusicTtmlParser {
    private const val DEFAULT_LINE_DURATION_MS = 2_000L
    private const val DEFAULT_WORD_DURATION_MS = 400L
    private const val BACKGROUND_ROLE = "x-bg"

    private data class ParsedLine(
        val key: String?,
        val start: Long,
        val end: Long,
        val words: List<ParsedWord>,
        val agent: String?
    )

    private data class ParsedWord(
        val start: Long,
        val end: Long,
        val text: String,
        val isBackground: Boolean
    )

    private data class TempWord(
        val start: Long,
        val end: Long?,
        val text: String,
        val agent: String?,
        val isBackground: Boolean
    )

    private data class ParsedAgent(
        val id: String,
        val type: Long
    )

    fun parse(ttml: String): List<RichLyricLine> {
        if (ttml.isBlank()) return emptyList()

        val document = parseDocument(ttml) ?: return emptyList()
        val agents = parseAgents(document)
        val agentDirectionMap = computeAgentDirections(agents)
        val originalLines = parseOriginalLines(document)
        if (originalLines.isEmpty()) return emptyList()

        val translatedByKey = parseMetadataMap(document, "translations")
        val romanizationByKey = parseMetadataMap(document, "transliterations")

        return originalLines.map { line ->
            val mainWords = line.words
                .filterNot { it.isBackground }
                .map { it.toLyricWord() }
            val backgroundWords = line.words
                .filter { it.isBackground }
                .map { it.toLyricWord() }

            RichLyricLine(
                begin = line.start,
                end = line.end,
                duration = line.end - line.start,
                text = joinWords(mainWords).ifBlank {
                    joinWords(backgroundWords)
                },
                words = mainWords.takeIf { it.isNotEmpty() }?.toMutableList()
            ).apply {
                secondary = joinWords(backgroundWords).takeIf { it.isNotBlank() }
                secondaryWords = backgroundWords.takeIf { it.isNotEmpty() }?.toMutableList()
                translation = line.key?.let(translatedByKey::get)?.takeIf { it.isNotBlank() }
                roma = line.key?.let(romanizationByKey::get)?.takeIf { it.isNotBlank() }
                isAlignedRight = agentDirectionMap[line.agent] == LyricDirection.RIGHT
            }
        }
    }

    private fun ParsedWord.toLyricWord(): LyricWord = LyricWord(
        begin = start,
        end = end,
        duration = end - start,
        text = text
    )

    private fun parseDocument(ttml: String): Document? {
        return runCatching {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            factory.setFeatureSafely("http://apache.org/xml/features/disallow-doctype-decl", true)
            factory.setFeatureSafely("http://xml.org/sax/features/external-general-entities", false)
            factory.setFeatureSafely("http://xml.org/sax/features/external-parameter-entities", false)

            val builder = factory.newDocumentBuilder()
            builder.parse(InputSource(StringReader(ttml)))
        }.getOrNull()
    }

    private fun parseAgents(document: Document): List<ParsedAgent> {
        val root = document.documentElement ?: return emptyList()
        return collectElementsByName(root, "agent")
            .mapNotNull { element ->
                val id = attribute(element, "xml:id")
                    ?: attribute(element, "id")
                    ?: return@mapNotNull null
                val type = attribute(element, "type")?.toLongOrNull() ?: 0L
                ParsedAgent(id = id, type = type)
            }
            .distinctBy { it.id }
    }

    private fun computeAgentDirections(agents: List<ParsedAgent>): Map<String, LyricDirection> {
        val personAgents = agents.filter { it.type == 1L }
        if (personAgents.size < 2) return emptyMap()

        return buildMap {
            put(personAgents[0].id, LyricDirection.DEFAULT)
            put(personAgents[1].id, LyricDirection.RIGHT)
        }
    }

    private fun parseOriginalLines(document: Document): List<ParsedLine> {
        val root = document.documentElement ?: return emptyList()
        val body = findFirstElementByName(root, "body") ?: return emptyList()
        val pElements = collectElementsByName(body, "p")
        if (pElements.isEmpty()) return emptyList()

        val lines = mutableListOf<ParsedLine>()
        var fallbackStart = 0L

        for (element in pElements) {
            val lineKey = attribute(element, "itunes:key") ?: attribute(element, "key")
            val lineAgent = attribute(element, "ttm:agent") ?: attribute(element, "agent")
            val lineStart = parseTimestamp(attribute(element, "begin")) ?: fallbackStart
            val lineEndCandidate = parseTimestamp(attribute(element, "end"))
                ?: parseTimestamp(attribute(element, "dur"))?.let { lineStart + it }

            val tempWords = parseWords(
                lineElement = element,
                lineStart = lineStart,
                lineAgent = lineAgent
            )

            val words = if (tempWords.isNotEmpty()) {
                normalizeWords(tempWords, lineStart, lineEndCandidate)
            } else {
                val lineText = extractText(element).trim()
                if (lineText.isEmpty()) {
                    emptyList()
                } else {
                    val fallbackEnd = lineEndCandidate ?: (lineStart + DEFAULT_LINE_DURATION_MS)
                    listOf(
                        ParsedWord(
                            start = lineStart,
                            end = fallbackEnd,
                            text = lineText,
                            isBackground = false
                        )
                    )
                }
            }
            if (words.isEmpty()) continue

            val lineEnd = max(
                lineEndCandidate ?: words.maxOf { it.end },
                lineStart + 1
            )

            lines += ParsedLine(
                key = lineKey,
                start = lineStart,
                end = lineEnd,
                words = words,
                agent = lineAgent
            )
            fallbackStart = max(fallbackStart, lineEnd)
        }

        return lines
    }

    private fun parseWords(
        lineElement: Element,
        lineStart: Long,
        lineAgent: String?
    ): List<TempWord> {
        val words = mutableListOf<TempWord>()
        var cursor = lineStart
        val children = lineElement.childNodes

        for (index in 0 until children.length) {
            cursor = collectWords(
                node = children.item(index),
                fallbackStart = cursor,
                inheritedAgent = lineAgent,
                inheritedBackground = false,
                words = words
            )
        }

        return words
    }

    private fun collectWords(
        node: Node,
        fallbackStart: Long,
        inheritedAgent: String?,
        inheritedBackground: Boolean,
        words: MutableList<TempWord>
    ): Long {
        val element = node as? Element ?: return fallbackStart
        val name = elementName(element)

        if (!name.equals("span", ignoreCase = true)) {
            var cursor = fallbackStart
            val children = element.childNodes
            for (index in 0 until children.length) {
                cursor = collectWords(
                    node = children.item(index),
                    fallbackStart = cursor,
                    inheritedAgent = inheritedAgent,
                    inheritedBackground = inheritedBackground,
                    words = words
                )
            }
            return cursor
        }

        val spanAgent = attribute(element, "ttm:agent")
            ?: attribute(element, "agent")
            ?: inheritedAgent
        val isBackground = inheritedBackground || hasBackgroundRole(element)
        val children = element.childNodes
        val hasElementChildren = (0 until children.length).any { children.item(it) is Element }

        if (hasElementChildren) {
            var cursor = fallbackStart
            for (index in 0 until children.length) {
                cursor = collectWords(
                    node = children.item(index),
                    fallbackStart = cursor,
                    inheritedAgent = spanAgent,
                    inheritedBackground = isBackground,
                    words = words
                )
            }
            return cursor
        }

        val text = extractText(element)
        if (text.isBlank()) return fallbackStart

        val start = parseTimestamp(attribute(element, "begin")) ?: fallbackStart
        val end = parseTimestamp(attribute(element, "end"))
            ?: parseTimestamp(attribute(element, "dur"))?.let { start + it }

        words += TempWord(
            start = start,
            end = end,
            text = text,
            agent = spanAgent,
            isBackground = isBackground
        )
        return end ?: start
    }

    private fun hasBackgroundRole(element: Element): Boolean {
        val role = attribute(element, "ttm:role") ?: attribute(element, "role") ?: return false
        return role
            .split(Regex("\\s+"))
            .any { it.equals(BACKGROUND_ROLE, ignoreCase = true) }
    }

    private fun normalizeWords(
        words: List<TempWord>,
        lineStart: Long,
        lineEnd: Long?
    ): List<ParsedWord> {
        val sorted = words.sortedBy { it.start }
        return sorted.mapIndexed { index, current ->
            val nextStart = sorted.getOrNull(index + 1)?.start
            val start = current.start.coerceAtLeast(lineStart)
            val end = when {
                current.end != null -> current.end
                nextStart != null -> nextStart
                lineEnd != null -> lineEnd
                else -> start + DEFAULT_WORD_DURATION_MS
            }

            ParsedWord(
                start = start,
                end = max(end, start + 1),
                text = current.text,
                isBackground = current.isBackground
            )
        }
    }

    private fun parseMetadataMap(document: Document, groupName: String): Map<String, String> {
        val root = document.documentElement ?: return emptyMap()
        val groupedNodes = collectElementsByName(root, groupName)
        if (groupedNodes.isEmpty()) return emptyMap()

        val result = linkedMapOf<String, String>()
        groupedNodes.forEach { group ->
            collectElementsByName(group, "text").forEach { textNode ->
                val key = attribute(textNode, "for") ?: return@forEach
                val value = (attribute(textNode, "text") ?: extractText(textNode)).trim()
                if (value.isNotBlank()) {
                    result[key] = value
                }
            }
        }
        return result
    }

    private fun findFirstElementByName(node: Node, targetName: String): Element? {
        if (node is Element && elementName(node).equals(targetName, ignoreCase = true)) {
            return node
        }
        val children = node.childNodes
        for (index in 0 until children.length) {
            val found = findFirstElementByName(children.item(index), targetName)
            if (found != null) return found
        }
        return null
    }

    private fun collectElementsByName(node: Node, targetName: String): List<Element> {
        val result = mutableListOf<Element>()
        collectElementsByName(node, targetName, result)
        return result
    }

    private fun collectElementsByName(node: Node, targetName: String, result: MutableList<Element>) {
        if (node is Element && elementName(node).equals(targetName, ignoreCase = true)) {
            result += node
        }
        val children = node.childNodes
        for (index in 0 until children.length) {
            collectElementsByName(children.item(index), targetName, result)
        }
    }

    private fun attribute(element: Element, name: String): String? {
        val direct = element.getAttribute(name).takeIf { it.isNotBlank() }
        if (direct != null) return direct

        val simpleName = name.substringAfter(':')
        val fallback = element.getAttribute(simpleName).takeIf { it.isNotBlank() }
        if (fallback != null) return fallback

        val attributes = element.attributes ?: return null
        for (index in 0 until attributes.length) {
            val attrNode = attributes.item(index)
            val localName = attrNode.nodeName.substringAfter(':')
            if (localName.equals(simpleName, ignoreCase = true)) {
                return attrNode.nodeValue?.takeIf { it.isNotBlank() }
            }
        }

        return null
    }

    private fun elementName(element: Element): String {
        return element.localName ?: element.nodeName.substringAfter(':')
    }

    private fun extractText(node: Node): String {
        val builder = StringBuilder()
        appendText(node, builder)
        return builder.toString()
            .replace('\n', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun appendText(node: Node, builder: StringBuilder) {
        when (node.nodeType) {
            Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> builder.append(node.nodeValue)
            else -> {
                val children = node.childNodes
                for (index in 0 until children.length) {
                    appendText(children.item(index), builder)
                }
            }
        }
    }

    private fun parseTimestamp(rawValue: String?): Long? {
        val value = rawValue?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return when {
            value.endsWith("ms", ignoreCase = true) -> {
                value.dropLast(2).trim().toDoubleOrNull()?.toLong()
            }

            value.endsWith("s", ignoreCase = true) -> {
                value.dropLast(1).trim().toDoubleOrNull()?.times(1000.0)?.toLong()
            }

            value.endsWith("m", ignoreCase = true) -> {
                value.dropLast(1).trim().toDoubleOrNull()?.times(60_000.0)?.toLong()
            }

            value.endsWith("h", ignoreCase = true) -> {
                value.dropLast(1).trim().toDoubleOrNull()?.times(3_600_000.0)?.toLong()
            }

            value.contains(":") -> parseClockTimestamp(value)
            else -> value.toDoubleOrNull()?.times(1000.0)?.toLong()
        }
    }

    private fun parseClockTimestamp(value: String): Long? {
        val parts = value.split(":")
        if (parts.isEmpty()) return null

        val seconds = parts.last().toDoubleOrNull() ?: return null
        val minutes = if (parts.size >= 2) parts[parts.size - 2].toDoubleOrNull() ?: 0.0 else 0.0
        val hours = if (parts.size >= 3) parts[parts.size - 3].toDoubleOrNull() ?: 0.0 else 0.0
        return ((hours * 3600) + (minutes * 60) + seconds).times(1000.0).toLong()
    }

    private fun joinWords(words: List<LyricWord>): String {
        return words.joinToString(separator = "") { it.text.orEmpty() }.trim()
    }

    private fun DocumentBuilderFactory.setFeatureSafely(name: String, value: Boolean) {
        runCatching { setFeature(name, value) }
    }

    private enum class LyricDirection {
        DEFAULT, RIGHT
    }
}
