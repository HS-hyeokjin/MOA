package com.moa.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moa.app.data.ServerRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class ConnectionState { IDLE, CHECKING, CONNECTED, UNAVAILABLE }

class HomeViewModel : ViewModel() {
    private val repository = ServerRepository()
    private val connection = MutableStateFlow(ConnectionState.IDLE)
    val connectionState = connection.asStateFlow()

    fun checkConnection() {
        if (connection.value == ConnectionState.CHECKING) return
        connection.value = ConnectionState.CHECKING
        viewModelScope.launch {
            connection.value = try {
                repository.checkConnection()
                ConnectionState.CONNECTED
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ConnectionState.UNAVAILABLE
            }
        }
    }
}
