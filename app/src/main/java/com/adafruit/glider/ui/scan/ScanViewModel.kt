package com.adafruit.glider.ui.scan

/**
 * Created by Antonio García (antonio@openroad.es)
 */

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.adafruit.glider.BuildConfig
import io.openroad.ble.FileTransferClient
import io.openroad.ble.applicationContext
import io.openroad.ble.filetransfer.BleFileTransferPeripheral
import io.openroad.ble.filetransfer.kFileTransferServiceUUID
import io.openroad.ble.getBluetoothAdapter
import io.openroad.ble.peripheral.BlePeripheral
import io.openroad.ble.scanner.BlePeripheralScanner
import io.openroad.ble.scanner.isManufacturerAdafruit
import io.openroad.utils.LogUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.lang.reflect.Method
import java.util.*

const val kMinRssiToAutoConnect = -80 //-100                    // in dBM
const val kMinTimeDetectingPeripheralForAutoconnect = 1000L     // in millis

class ScanViewModel(
    application: Application,
) : AndroidViewModel(application) {
    // States
    sealed class ScanUiState {
        object Scanning : ScanUiState()
        data class ScanningError(val cause: Throwable) : ScanUiState()
        object RestoringConnection : ScanUiState()
        object SetupConnection : ScanUiState()
        object Connecting : ScanUiState()
        object Connected : ScanUiState()
        object Discovering : ScanUiState()
        object SetupFileTransfer : ScanUiState()

        //data class FileTransferError(val cause: Throwable) : ScanUiState()
        data class FileTransferEnabled(val fileTransferClient: FileTransferClient) : ScanUiState()
        data class FileTransferError(val gattErrorCode: Int) : ScanUiState()
        data class Disconnected(val cause: Throwable?) : ScanUiState()
    }

    // Data - Private
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default
    private val log by LogUtils()
    private var scannerStartingTime = System.currentTimeMillis()
    private var autoConnectJob: Job? = null
    private var stoppingScannerDelayBeforeConnectingJob: Job? = null
    private var fileTransferPeripheralStateJob: Job? = null

    private var blePeripheralScanner =
        BlePeripheralScanner(getApplication(), null, viewModelScope, defaultDispatcher)

    private val _uiState = MutableStateFlow<ScanUiState>(ScanUiState.Scanning)

    // Data
    val uiState = _uiState.asStateFlow()

    private val blePeripherals =
        blePeripheralScanner.blePeripheralsState            // State with all the scanned peripherals

    val numPeripheralsFound = blePeripherals
        .map { blePeripherals ->
            blePeripherals.size
        }

    private val matchingPeripheralsFound = blePeripherals
        .map { blePeripherals ->
            // Only peripherals that are manufactured by Adafruit and include theFileTransfer service
            blePeripherals
                .filter { it.scanRecord?.isManufacturerAdafruit() ?: false }
                /*
            .map {
                log.info("found: ${it.nameOrAddress} -> rssi: ${it.currentRssi}")
                it
            }*/
                .filter {
                    val serviceUuids: List<UUID>? = it.scanRecord?.serviceUuids?.map { it.uuid }
                    serviceUuids?.contains(kFileTransferServiceUUID) ?: false
                }
                .sortedBy { it.createdMillis }

        }

    /*
    private val numMatchingDevicesFound = matchingDevicesFound
        .map { blePeripherals ->
            blePeripherals.size
        }*/

    val numMatchingPeripheralsOutOfRangeFound = matchingPeripheralsFound
        .map { it.filter { it.currentRssi <= kMinRssiToAutoConnect }.size }
    val numMatchingPeripheralsInRangeFound = matchingPeripheralsFound
        .map { it.filter { it.currentRssi > kMinRssiToAutoConnect }.size }


    // region Lifecycle
    init {
        // Listen to scanning errors and map it to the UI state
        viewModelScope.launch {
            blePeripheralScanner.bleErrorException
                .filterNotNull()
                .collect { bleException ->
                    _uiState.update { ScanUiState.ScanningError(bleException) }
                }

        }
    }


    fun onResume() {
        // Start scanning if we are in the scanning state
        if (uiState.value == ScanUiState.Scanning) {

            // Force remove bonding information
            if (BuildConfig.DEBUG || true) {
                try {
                    val bondedDevices = getBluetoothAdapter(applicationContext)?.bondedDevices
                    log.info("Bound devices: $bondedDevices")

                    bondedDevices?.forEach { device ->
                        try {
                            val method = device.javaClass.getMethod("removeBond")
                            val result = method.invoke(device) as Boolean
                            if (result) {
                                log.info("Successfully removed bond")
                            }
                        } catch (e: Exception) {
                            log.info("ERROR: could not remove bond: $e")
                        }

                    }
                } catch (ignored: SecurityException) {
                }
            }

            // Start scanning
            startScanning()
        }
    }

    fun onPause() {
        // Stop scanning if we are in the scanning state
        if (uiState.value == ScanUiState.Scanning) {
            stopScanning()
        }
    }

    // endregion

    // region Actions
    private fun startScanning() {
        scannerStartingTime = System.currentTimeMillis()
        blePeripheralScanner.start()

        // Start a job that checks if we should auto-connect
        stoppingScannerDelayBeforeConnectingJob?.cancel()
        autoConnectJob?.cancel()
        autoConnectJob = matchingPeripheralsFound
            .onEach { blePeripherals ->

                val currentTime = System.currentTimeMillis()
                val selectedPeripheral =
                    blePeripherals

                        /*.map {
                            log.info("found: ${it.nameOrAddress} -> rssi: ${it.currentRssi} - elapsed: ${it.createdMillis - currentTime}")
                            it
                        }*/
                        // Take peripherals that have been matching more than kMinTimeDetectingPeripheralForAutoconnect
                        .filter { currentTime - it.createdMillis > kMinTimeDetectingPeripheralForAutoconnect }
                        // Take the one with higher RSSI
                        .maxByOrNull { it.currentRssi }


                if (selectedPeripheral != null) {
                    // Connect
                    stopScanningAndConnect(selectedPeripheral)
                }
            }
            .flowOn(defaultDispatcher)
            .launchIn(viewModelScope)
    }

    private fun stopScanning() {
        autoConnectJob?.cancel()
        autoConnectJob = null

        blePeripheralScanner.stop()
    }

    private fun stopScanningAndConnect(blePeripheral: BlePeripheral) {
        log.info("Connect to ${blePeripheral.nameOrAddress}")
        _uiState.update { ScanUiState.SetupConnection }

        stopScanning()

        // Wait some time until scanning is really stopped to avoid some connection problems
        val kTimeToWaitForScannerToStopBeforeConnection = 500L
        viewModelScope.launch {
            stoppingScannerDelayBeforeConnectingJob = async(defaultDispatcher) {
                delay(kTimeToWaitForScannerToStopBeforeConnection)
                if (isActive) {
                    connect(blePeripheral)
                }
            }
        }
    }

    private suspend fun connect(blePeripheral: BlePeripheral) {
        // Create a BleFileTransferPeripheral
        //val fileTransferPeripheral = BleFileTransferPeripheral(blePeripheral)
        val fileTransferClient = FileTransferClient(blePeripheral)

        // Link state changes with UI
        fileTransferPeripheralStateJob = viewModelScope.launch {
            //fileTransferPeripheral.fileTransferState
            fileTransferClient.fileTransferState
                .onCompletion { exception ->
                    log.info("fileTransferPeripheralStateJob onCompletion: $exception")
                }
                .collect { fileTransferState ->
                    //log.info("fileTransferPeripheralStateJob when: $fileTransferState")
                    when (fileTransferState) {
                        BleFileTransferPeripheral.FileTransferState.Start -> {}

                        is BleFileTransferPeripheral.FileTransferState.Connecting -> {
                            _uiState.update { ScanUiState.Connecting }
                        }
                        is BleFileTransferPeripheral.FileTransferState.Disconnecting -> {}

                        is BleFileTransferPeripheral.FileTransferState.Disconnected -> {
                            _uiState.update { ScanUiState.Disconnected(fileTransferState.cause) }
                            cancel()
                        }
                        is BleFileTransferPeripheral.FileTransferState.GattError -> {
                            _uiState.update { ScanUiState.FileTransferError(fileTransferState.gattErrorCode) }
                        }

                        BleFileTransferPeripheral.FileTransferState.Discovering -> {
                            _uiState.update { ScanUiState.Discovering }
                        }
                        BleFileTransferPeripheral.FileTransferState.CheckingFileTransferVersion -> {
                            _uiState.update { ScanUiState.SetupFileTransfer }
                        }
                        BleFileTransferPeripheral.FileTransferState.EnablingNotifications -> {
                            _uiState.update { ScanUiState.SetupFileTransfer }
                        }
                        BleFileTransferPeripheral.FileTransferState.Enabled -> {
                            _uiState.update { ScanUiState.FileTransferEnabled(fileTransferClient) }
                        }

                        else -> {
                            log.warning("fileTransferState not managed during connect: $fileTransferState")
                        }
                    }
                }
        }

        // Start connect
        fileTransferClient.connectAndSetup()
    }

    // endregion
}

/*
// region Factory
class ScanViewModelFactory(private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel?> create(modelClass: Class<T>): T {
        return ScanViewModelFactory(defaultDispatcher) as T
    }
}
// endregion
 */