package com.example.mdviewer

import android.graphics.Color
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import java.util.regex.Pattern

/**
 * A lightweight, dependency-free syntax highlighter. It is intentionally generic:
 * it colors comments, strings, numbers and a common keyword set, choosing comment
 * styles from the file extension. Good enough for a viewer — not a full lexer.
 */
object SyntaxHighlighter {

    private data class Palette(
        val comment: Int,
        val string: Int,
        val number: Int,
        val keyword: Int
    )

    private val darkPalette = Palette(
        comment = Color.parseColor("#6A9955"),
        string = Color.parseColor("#CE9178"),
        number = Color.parseColor("#B5CEA8"),
        keyword = Color.parseColor("#569CD6")
    )

    private val lightPalette = Palette(
        comment = Color.parseColor("#008000"),
        string = Color.parseColor("#A31515"),
        number = Color.parseColor("#098658"),
        keyword = Color.parseColor("#0000FF")
    )

    private val keywords = setOf(
        "if", "else", "elif", "for", "while", "do", "switch", "case", "when", "break",
        "continue", "return", "yield", "goto", "try", "catch", "except", "finally", "throw",
        "throws", "raise", "fun", "func", "function", "def", "lambda", "class", "struct",
        "enum", "interface", "object", "trait", "impl", "extends", "implements", "override",
        "abstract", "sealed", "data", "val", "var", "let", "const", "final", "static",
        "public", "private", "protected", "internal", "package", "import", "from", "as",
        "new", "this", "self", "super", "null", "nil", "none", "true", "false", "void",
        "int", "long", "short", "float", "double", "bool", "boolean", "char", "string",
        "in", "is", "and", "or", "not", "async", "await", "suspend", "use", "namespace",
        "echo", "print", "println", "typedef", "typeof", "instanceof", "with", "match"
    )

    private fun isCode(name: String): Boolean = extensionConfig(name) != null

    fun isHighlightable(name: String): Boolean = isCode(name)

    /** Returns a colored copy of [text], or the unchanged text if the type isn't recognized. */
    fun highlight(text: CharSequence, name: String, isDark: Boolean): CharSequence {
        val cfg = extensionConfig(name) ?: return text
        val palette = if (isDark) darkPalette else lightPalette
        val out = SpannableStringBuilder(text)
        val colored = BooleanArray(text.length)

        fun apply(pattern: Pattern, color: Int, group: Int = 0) {
            val m = pattern.matcher(text)
            while (m.find()) {
                val start = m.start(group)
                val end = m.end(group)
                if (start < 0 || end <= start) continue
                if (colored[start]) continue
                out.setSpan(
                    ForegroundColorSpan(color), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                for (i in start until end) colored[i] = true
            }
        }

        // Order matters: comments & strings first so keywords/numbers inside them are skipped.
        cfg.blockComment?.let { apply(it, palette.comment) }
        cfg.lineComment?.let { apply(it, palette.comment) }
        apply(STRING, palette.string)
        apply(NUMBER, palette.number)

        // Keywords (whole-word), skipping already-colored regions.
        val wm = WORD.matcher(text)
        while (wm.find()) {
            val w = wm.group()
            if (w in keywords && !colored[wm.start()]) {
                out.setSpan(
                    ForegroundColorSpan(palette.keyword),
                    wm.start(), wm.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        return out
    }

    private data class Config(val lineComment: Pattern?, val blockComment: Pattern?)

    private val STRING: Pattern =
        Pattern.compile("\"(\\\\.|[^\"\\\\])*\"|'(\\\\.|[^'\\\\])*'|`(\\\\.|[^`\\\\])*`")
    private val NUMBER: Pattern = Pattern.compile("\\b\\d+(\\.\\d+)?\\b")
    private val WORD: Pattern = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*")

    private val SLASH_LINE = Pattern.compile("//[^\\n]*")
    private val HASH_LINE = Pattern.compile("#[^\\n]*")
    private val BLOCK_C = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL)
    private val BLOCK_XML = Pattern.compile("<!--.*?-->", Pattern.DOTALL)
    private val DASH_LINE = Pattern.compile("--[^\\n]*")

    private fun extensionConfig(name: String): Config? {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "kt", "kts", "java", "js", "jsx", "ts", "tsx", "c", "cpp", "cc", "h", "hpp",
            "cs", "go", "rs", "swift", "scala", "dart", "php", "groovy", "gradle" ->
                Config(SLASH_LINE, BLOCK_C)
            "css", "scss", "less" -> Config(null, BLOCK_C)
            "json", "json5" -> Config(SLASH_LINE, BLOCK_C)
            "py", "rb", "sh", "bash", "zsh", "yaml", "yml", "toml", "ini", "conf", "cfg",
            "env", "properties", "pl", "r", "dockerfile", "makefile", "mk" ->
                Config(HASH_LINE, null)
            "xml", "html", "htm", "svg", "xhtml" -> Config(null, BLOCK_XML)
            "sql" -> Config(DASH_LINE, BLOCK_C)
            "lua" -> Config(DASH_LINE, null)
            "txt", "log", "csv", "tsv" -> Config(null, null) // recognized as text, no coloring
            else -> null
        }
    }
}
