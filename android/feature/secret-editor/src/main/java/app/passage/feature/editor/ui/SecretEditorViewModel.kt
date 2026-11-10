package app.passage.feature.editor.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.passage.core.domain.model.SecretDetail
import app.passage.core.domain.usecase.GetSecretDetailUseCase
import app.passage.core.domain.usecase.SaveSecretUseCase
import app.passage.feature.editor.generator.PasswordConfig
import app.passage.feature.editor.generator.PasswordGenerator
import app.passage.feature.editor.generator.PasswordStrength
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class EditorSaveState {
    data object Idle : EditorSaveState()
    data object Saving : EditorSaveState()
    data class Success(val savedPath: String) : EditorSaveState()
    data class Error(val message: String) : EditorSaveState()
}

class SecretEditorViewModel(
    private val saveSecretUseCase: SaveSecretUseCase,
    private val getSecretDetailUseCase: GetSecretDetailUseCase
) : ViewModel() {

    var folder: String = ""
        private set

    var isEditMode: Boolean = false
        private set

    val name = MutableStateFlow("")
    val password = MutableStateFlow("")
    val attributes = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val notes = MutableStateFlow("")

    private val _saveState = MutableStateFlow<EditorSaveState>(EditorSaveState.Idle)
    val saveState: StateFlow<EditorSaveState> = _saveState.asStateFlow()

    private val _passwordStrength = MutableStateFlow<Pair<Double, PasswordStrength>>(
        0.0 to PasswordStrength.VERY_WEAK
    )
    val passwordStrength: StateFlow<Pair<Double, PasswordStrength>> = _passwordStrength.asStateFlow()

    fun initialize(folderPath: String, existingSecretPath: String?) {
        if (!existingSecretPath.isNullOrEmpty()) {
            isEditMode = true
            val cleanPath = existingSecretPath.removeSuffix(".age")
            val segments = cleanPath.split("/")
            name.value = segments.last()
            folder = segments.dropLast(1).joinToString("/")

            viewModelScope.launch {
                getSecretDetailUseCase(existingSecretPath).onSuccess { secret ->
                    password.value = secret.password
                    _passwordStrength.value = PasswordGenerator.calculateStrength(secret.password)
                    attributes.value = secret.attributes.map { it.key to it.value }
                    notes.value = secret.notes
                }
            }
        } else {
            isEditMode = false
            folder = folderPath
            // Pre-add common attribute
            attributes.value = listOf("login" to "")
        }
    }

    fun onPasswordChanged(newPassword: String) {
        password.value = newPassword
        _passwordStrength.value = PasswordGenerator.calculateStrength(newPassword)
    }

    fun generateNewPassword(config: PasswordConfig = PasswordConfig()) {
        val generated = PasswordGenerator.generate(config)
        onPasswordChanged(generated)
    }

    fun addAttribute(key: String = "", value: String = "") {
        attributes.value = attributes.value + (key to value)
    }

    fun updateAttribute(index: Int, key: String, value: String) {
        val current = attributes.value.toMutableList()
        if (index in current.indices) {
            current[index] = key to value
            attributes.value = current
        }
    }

    fun removeAttribute(index: Int) {
        val current = attributes.value.toMutableList()
        if (index in current.indices) {
            current.removeAt(index)
            attributes.value = current
        }
    }

    fun save(onSuccess: (savedPath: String) -> Unit) {
        val cleanName = name.value.trim().removeSuffix(".age")
        SecretNameValidator.validate(cleanName)?.let { message ->
            _saveState.value = EditorSaveState.Error(message)
            return
        }

        val relativePath = if (folder.isNotEmpty()) "$folder/$cleanName.age" else "$cleanName.age"

        val attrMap = mutableMapOf<String, String>()
        for ((k, v) in attributes.value) {
            val trimmedKey = k.trim()
            if (trimmedKey.isNotEmpty()) {
                attrMap[trimmedKey] = v.trim()
            }
        }

        val secretDetail = SecretDetail(
            name = cleanName,
            relativePath = relativePath,
            password = password.value,
            attributes = attrMap,
            notes = notes.value.trim()
        )

        viewModelScope.launch {
            _saveState.value = EditorSaveState.Saving
            saveSecretUseCase(relativePath, secretDetail).fold(
                onSuccess = {
                    _saveState.value = EditorSaveState.Success(relativePath)
                    onSuccess(relativePath)
                },
                onFailure = { error ->
                    _saveState.value = EditorSaveState.Error(
                        error.message ?: "Erro ao criptografar e salvar segredo"
                    )
                }
            )
        }
    }
}
