package com.example.mdviewer

/** Single source of truth for the file types this app can open. */
object SupportedTypes {

    private val textExtensions = setOf(
        "md", "markdown", "txt", "log", "csv", "tsv",
        "json", "json5", "xml", "yaml", "yml", "toml", "ini", "conf", "cfg",
        "env", "properties", "html", "htm", "css", "scss", "less",
        "js", "jsx", "ts", "tsx", "kt", "kts", "java", "py", "rb", "sh", "bash", "zsh",
        "c", "cpp", "cc", "h", "hpp", "cs", "go", "rs", "swift", "scala", "dart",
        "php", "groovy", "gradle", "sql", "lua"
    )

    fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun isPdf(name: String): Boolean = extensionOf(name) == "pdf"

    fun isText(name: String): Boolean = extensionOf(name) in textExtensions

    fun isSupported(name: String): Boolean = isPdf(name) || isText(name)
}
