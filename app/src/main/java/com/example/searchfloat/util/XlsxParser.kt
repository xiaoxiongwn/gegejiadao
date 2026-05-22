package com.example.searchfloat.util

import android.content.Context
import android.net.Uri
import com.example.searchfloat.data.Question
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.util.zip.ZipInputStream

data class ParsedRow(val title: String, val category: String, val content: String)

object XlsxParser {

    fun parse(context: Context, uri: Uri): List<ParsedRow> {
        val sharedStrings = mutableListOf<String>()
        val rows = mutableListOf<ParsedRow>()

        context.contentResolver.openInputStream(uri)?.use { stream ->
            ZipInputStream(stream).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name == "xl/sharedStrings.xml") {
                        sharedStrings.addAll(parseSharedStrings(zis))
                    }
                    entry = zis.nextEntry
                }
            }
        }

        context.contentResolver.openInputStream(uri)?.use { stream ->
            ZipInputStream(stream).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name == "xl/worksheets/sheet1.xml") {
                        rows.addAll(parseSheet(zis, sharedStrings))
                    }
                    entry = zis.nextEntry
                }
            }
        }

        return rows
    }

    fun parseCsv(context: Context, uri: Uri): List<ParsedRow> {
        val rows = mutableListOf<ParsedRow>()
        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
            val lines = reader.readLines()
            if (lines.isEmpty()) return rows

            val header = parseCsvLine(lines[0])
            val colMap = buildColumnMap(header)

            lines.drop(1).forEach { line ->
                val cols = parseCsvLine(line)
                if (cols.isEmpty()) return@forEach
                val row = extractRow(cols, colMap)
                if (row.title.isNotBlank()) rows.add(row)
            }
        }
        return rows
    }

    private fun parseSharedStrings(input: InputStream): List<String> {
        val strings = mutableListOf<String>()
        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(input, "UTF-8")

        var currentText = StringBuilder()
        var inT = false

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "t") {
                        inT = true
                        currentText = StringBuilder()
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inT) currentText.append(parser.text)
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "t") {
                        inT = false
                    } else if (parser.name == "si") {
                        strings.add(currentText.toString())
                    }
                }
            }
            parser.next()
        }
        return strings
    }

    private fun parseSheet(input: InputStream, sharedStrings: List<String>): List<ParsedRow> {
        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(input, "UTF-8")

        val allRows = mutableListOf<List<String>>()
        var currentRow = mutableListOf<String>()
        var currentCellValue = StringBuilder()
        var inV = false
        var cellType = ""
        var lastColIndex = -1
        var currentColIndex = 0

        fun colIndexFromRef(ref: String): Int {
            val letters = ref.takeWhile { it.isLetter() }
            var idx = 0
            for (c in letters) {
                idx = idx * 26 + (c.uppercaseChar() - 'A' + 1)
            }
            return idx - 1
        }

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "c" -> {
                            cellType = parser.getAttributeValue(null, "t") ?: ""
                            val ref = parser.getAttributeValue(null, "r") ?: ""
                            currentColIndex = colIndexFromRef(ref)
                        }
                        "v" -> {
                            inV = true
                            currentCellValue = StringBuilder()
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inV) currentCellValue.append(parser.text)
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "v" -> {
                            inV = false
                            val value = currentCellValue.toString()
                            val realValue = if (cellType == "s") {
                                val index = value.toIntOrNull() ?: -1
                                if (index in sharedStrings.indices) sharedStrings[index] else value
                            } else {
                                value
                            }
                            // 填充空列
                            while (currentRow.size < currentColIndex) {
                                currentRow.add("")
                            }
                            if (currentRow.size == currentColIndex) {
                                currentRow.add(realValue)
                            } else if (currentColIndex < currentRow.size) {
                                currentRow[currentColIndex] = realValue
                            }
                        }
                        "row" -> {
                            if (currentRow.isNotEmpty()) {
                                allRows.add(currentRow.toList())
                            }
                            currentRow = mutableListOf()
                        }
                    }
                }
            }
            parser.next()
        }

        if (allRows.isEmpty()) return emptyList()

        // 第一行作为表头，构建列映射
        val header = allRows[0]
        val colMap = buildColumnMap(header)

        return allRows.drop(1).mapNotNull { cols ->
            val row = extractRow(cols, colMap)
            if (row.title.isNotBlank()) row else null
        }
    }

    private fun buildColumnMap(header: List<String>): Map<String, Int> {
        val map = mutableMapOf<String, Int>()
        header.forEachIndexed { index, name ->
            val key = name.trim().replace(" ", "").replace("\u3000", "")
            map[key] = index
        }
        return map
    }

    private fun extractRow(cols: List<String>, colMap: Map<String, Int>): ParsedRow {
        // 尝试识别各种列名变体
        val title = getColValue(cols, colMap, listOf("题干", "题目", "标题", "问题", "试题", "题目内容", "question", "title"))
        val answer = getColValue(cols, colMap, listOf("答案", "正确答案", "answer", "ans"))
        val options = getColValue(cols, colMap, listOf("选项", "选择项", "choices", "options"))
        val category = getColValue(cols, colMap, listOf("题目分类", "分类", "类别", "题型", "category", "type", "一级纲要", "二级纲要"))

        // 组装内容：选项 + 答案
        val contentBuilder = StringBuilder()
        if (options.isNotBlank()) {
            contentBuilder.append("选项：\n").append(options.replace("|", "\n")).append("\n\n")
        }
        if (answer.isNotBlank()) {
            contentBuilder.append("答案：").append(answer)
        }
        val content = contentBuilder.toString().trim()

        return ParsedRow(
            title = title,
            category = category,
            content = if (content.isNotBlank()) content else answer
        )
    }

    private fun getColValue(cols: List<String>, colMap: Map<String, Int>, keys: List<String>): String {
        for (key in keys) {
            val idx = colMap[key]
            if (idx != null && idx < cols.size) {
                return cols[idx].trim()
            }
        }
        return ""
    }

    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        for (char in line) {
            when {
                char == '"' -> inQuotes = !inQuotes
                char == ',' && !inQuotes -> {
                    result.add(sb.toString())
                    sb.clear()
                }
                else -> sb.append(char)
            }
        }
        result.add(sb.toString())
        return result
    }
}
