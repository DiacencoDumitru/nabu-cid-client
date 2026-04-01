package org.ddev.nabucidclient.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.ddev.nabucidclient.domain.NodeRepository
import org.ddev.nabucidclient.network.FetchResult

private const val DEFAULT_MULTIADDRESS =
    "/dns4/ipfs.infra.cf.team/tcp/4001/p2p/12D3KooWKiqj21VphU2eE25438to5xeny6eP6d3PXT93ZczagPLT"
private const val DEFAULT_CID = "QmTBimFzPPP2QsB7TQGc2dr4BZD4i7Gm2X1mNtb6DqN9Dr"

data class MainUiState(
    val multiAddress: String = DEFAULT_MULTIADDRESS,
    val cid: String = DEFAULT_CID,
    val pingIntervalSeconds: String = "3",
    val isBusy: Boolean = false,
    val isPingRunning: Boolean = false,
    val connectionStatus: String = "Не подключено",
    val latencyMs: Long? = null,
    val cidResult: String = "",
    val errorMessage: String? = null
)

class MainViewModel(
    private val repository: NodeRepository = NodeRepository()
) : ViewModel() {

    private val _state = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = _state.asStateFlow()

    private var pingJob: Job? = null

    fun onMultiAddressChanged(value: String) {
        _state.value = _state.value.copy(multiAddress = value)
    }

    fun onCidChanged(value: String) {
        _state.value = _state.value.copy(cid = value)
    }

    fun onPingIntervalChanged(value: String) {
        _state.value = _state.value.copy(pingIntervalSeconds = value)
    }

    fun connect() {
        val address = state.value.multiAddress
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = _state.value.copy(isBusy = true, errorMessage = null)
            repository.connect(address)
                .onSuccess { latency ->
                    _state.value = _state.value.copy(
                        isBusy = false,
                        latencyMs = latency,
                        connectionStatus = "Подключено",
                        errorMessage = null
                    )
                }
                .onFailure { throwable ->
                    _state.value = _state.value.copy(
                        isBusy = false,
                        connectionStatus = "Ошибка подключения",
                        errorMessage = userFriendlyError(throwable, "connect")
                    )
                }
        }
    }

    fun fetchCid() {
        val cid = state.value.cid
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = _state.value.copy(isBusy = true, errorMessage = null)
            repository.fetchByCid(cid)
                .onSuccess { result ->
                    _state.value = _state.value.copy(
                        isBusy = false,
                        connectionStatus = "CID получен от ноды",
                        cidResult = renderFetchResult(result),
                        errorMessage = null
                    )
                }
                .onFailure { throwable ->
                    _state.value = _state.value.copy(
                        isBusy = false,
                        errorMessage = userFriendlyError(throwable, "fetch")
                    )
                }
        }
    }

    fun startPing() {
        val interval = state.value.pingIntervalSeconds.toIntOrNull()
        if (interval == null || interval < 1) {
            _state.value = _state.value.copy(errorMessage = "Интервал пинга должен быть не меньше 1 секунды")
            return
        }
        if (pingJob?.isActive == true) return
        pingJob = viewModelScope.launch(Dispatchers.IO) {
            _state.value = _state.value.copy(isPingRunning = true, errorMessage = null)
            while (true) {
                repository.ping()
                    .onSuccess { latency ->
                        _state.value = _state.value.copy(
                            latencyMs = latency,
                            connectionStatus = "Нода доступна",
                            errorMessage = null
                        )
                    }
                    .onFailure { throwable ->
                        _state.value = _state.value.copy(
                            connectionStatus = "Нода недоступна",
                            errorMessage = userFriendlyError(throwable, "ping")
                        )
                    }
                delay(interval * 1000L)
            }
        }
    }

    fun stopPing() {
        pingJob?.cancel()
        pingJob = null
        _state.value = _state.value.copy(isPingRunning = false)
    }

    override fun onCleared() {
        stopPing()
        repository.shutdown()
        super.onCleared()
    }

    private fun renderFetchResult(result: FetchResult): String {
        val preview = result.textPreview ?: "Текст не распознан, ниже Base64-превью."
        return buildString {
            appendLine("Источник: Nabu/remote node")
            appendLine("CID: ${result.cid}")
            appendLine("Размер: ${result.sizeBytes} bytes")
            appendLine()
            appendLine("Содержимое:")
            appendLine(preview)
            appendLine()
            appendLine("Base64:")
            appendLine(result.base64Preview)
        }
    }

    private fun userFriendlyError(throwable: Throwable, operation: String): String {
        val message = throwable.message.orEmpty()
        val lower = message.lowercase()
        return when {
            "некорректный cid" in lower -> "Некорректный CID. Проверьте идентификатор и попробуйте снова."
            "сначала подключитесь к ноде" in lower -> "Сначала выполните подключение к ноде."
            "таймаут" in lower || "timeout" in lower -> "Превышено время ожидания ответа от ноды."
            "unable to resolve host" in lower || "unknown host" in lower || "недоступна" in lower ->
                "Нода недоступна. Проверьте интернет и адрес ноды."
            operation == "ping" -> "Ошибка пинга. Проверьте соединение с нодой."
            operation == "fetch" -> "Не удалось получить данные по CID от ноды."
            else -> if (message.isBlank()) "Сетевая ошибка. Попробуйте снова." else message
        }
    }
}
