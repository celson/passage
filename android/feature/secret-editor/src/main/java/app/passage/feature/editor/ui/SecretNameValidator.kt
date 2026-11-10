package app.passage.feature.editor.ui

/** The secret name becomes a file name in the vault, so it cannot carry path syntax. */
internal object SecretNameValidator {

    /** Returns an error message for an invalid [name], or null when it is acceptable. */
    fun validate(name: String): String? {
        if (name.isEmpty()) return "O nome do segredo é obrigatório"
        val invalid = name == "." || name == ".." ||
            name.any { it == '/' || it == '\\' || it == '\u0000' } ||
            name.startsWith(".git")
        return if (invalid) "Nome inválido: não use \"/\", \"\\\", \".\", \"..\" nem nomes iniciados por \".git\"" else null
    }
}
