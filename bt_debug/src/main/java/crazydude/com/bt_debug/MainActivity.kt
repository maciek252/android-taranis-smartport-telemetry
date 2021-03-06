package crazydude.com.bt_debug

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.lang.Exception
import java.nio.charset.Charset
import java.util.*
import java.util.concurrent.Executors
import kotlin.collections.HashSet

class MainActivity : AppCompatActivity() {

    private val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
    private var broadcastReceiver: BroadcastReceiver? = null
    private var logFile: ByteArray? = null

    companion object {
        private const val REQUEST_ENABLE_BT_BL: Int = 0
        private const val REQUEST_ENABLE_BT_BLE: Int = 1
        private const val REQUEST_SELECT_FILE: Int = 2
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        blConnectButton.setOnClickListener {
            connectBl()
        }

        bleConnectButton.setOnClickListener {
            connectBle()
        }

        bleEmulator.setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT)
            intent.type = "*/*"
            startActivityForResult(intent, REQUEST_SELECT_FILE)
        }

        usbConnect.setOnClickListener {
            val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
            val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
            val driver = drivers.first()
            val connection = usbManager.openDevice(driver.device)
            if (connection != null) {
                val port = driver.ports.first()
                port.open(connection)
                port.setParameters(57600, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                val outputManager =
                    SerialInputOutputManager(port, object : SerialInputOutputManager.Listener {
                        override fun onRunError(e: Exception?) {
                        }

                        override fun onNewData(data: ByteArray?) {
                            Log.d("USBSerial", data?.toString(Charset.forName("utf8")))
                        }
                    })
                Executors.newSingleThreadExecutor().submit(outputManager)
            } else {
                usbManager.requestPermission(driver.device, null)
            }
        }
    }

    @SuppressLint("NewApi")
    private fun startBleServer(data: Uri) {
        val inputStream = contentResolver.openInputStream(data) ?: return

        logFile = inputStream.readBytes()

        var openGattServer: BluetoothGattServer? = null

        val bluetoothGattService = BluetoothGattService(UUID.randomUUID(), BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val characteristic = BluetoothGattCharacteristic(
            UUID.randomUUID(),
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        bluetoothGattService.addCharacteristic(characteristic)

        val devices = HashSet<BluetoothDevice>()

        openGattServer = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).openGattServer(this,
            object : BluetoothGattServerCallback() {

                override fun onCharacteristicReadRequest(
                    device: BluetoothDevice?,
                    requestId: Int,
                    offset: Int,
                    characteristic: BluetoothGattCharacteristic?
                ) {
                    super.onCharacteristicReadRequest(device, requestId, offset, characteristic)
                    openGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, "LOL".toByteArray())
                }

                override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
                    super.onConnectionStateChange(device, status, newState)
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        device?.let { devices.add(it) }
                    } else {
                        devices.remove(device)
                    }
                }
            })
        openGattServer.addService(bluetoothGattService)
        Thread(Runnable {
            val buffer = ByteArray(256)
            val stream = logFile?.inputStream() ?: return@Runnable
            while (!isFinishing) {
                val read = stream.read(buffer)
                if (read < 256) {
                    stream.reset()
                }
                characteristic.value = buffer
                devices.forEach { openGattServer?.notifyCharacteristicChanged(it, characteristic, false) }
                Thread.sleep(250)
            }
        }).start()
    }

    @SuppressLint("NewApi")
    private fun connectBle() {
        if (checkBluetooth(REQUEST_ENABLE_BT_BLE)) {
            val deviceNamesAdapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1)
            val deviceList = ArrayList<BluetoothDevice>()
            val callback = BluetoothAdapter.LeScanCallback { bluetoothDevice, p1, p2 ->
                bluetoothDevice?.let {
                    if (!deviceList.contains(it)) {
                        deviceList.add(it)
                        deviceNamesAdapter.add(it.name ?: it.address)
                    }
                }
            }
            AlertDialog.Builder(this)
                .setAdapter(deviceNamesAdapter) { dialogInterface, i ->
                    connectBle(deviceList[i])
                }
                .setOnDismissListener { bluetoothAdapter.stopLeScan(callback) }
                .show()
            bluetoothAdapter.startLeScan(callback)
        }
    }

    @SuppressLint("NewApi")
    private fun connectBle(bluetoothDevice: BluetoothDevice) {
        bluetoothDevice.connectGatt(this, false,
            object : BluetoothGattCallback() {
                private var fileOutputStream: FileOutputStream? = null

                override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
                    super.onConnectionStateChange(gatt, status, newState)
                    fileOutputStream?.write("State changed $status, $newState\r\n".toByteArray())
                    if (newState == BluetoothGatt.STATE_CONNECTED) {
                        gatt?.discoverServices()
                        fileOutputStream = getLogFileOutputStream()
                        fileOutputStream?.write("Connected as BLE\r\n".toByteArray())
                        disconnectButton.setOnClickListener {
                            gatt?.disconnect()
                        }
                        switchToConnectedState()
                    } else {
                        fileOutputStream?.write("Disconnected\r\n".toByteArray())
                        fileOutputStream?.close()
                        switchToDisconnected()
                        gatt?.close()
                    }
                }

                override fun onCharacteristicChanged(
                    gatt: BluetoothGatt?,
                    characteristic: BluetoothGattCharacteristic?
                ) {
                    super.onCharacteristicChanged(gatt, characteristic)
                    fileOutputStream?.write(characteristic?.value)
                    GlobalScope.launch(Dispatchers.Main) {
                        if (logTextView.text.length > 2048) {
                            logTextView.text = ""
                        }
                        characteristic?.value?.forEach {
                            logTextView.text = "${logTextView.text}:${Integer.toHexString(it.toInt())}"
                        }
                    }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
                    super.onServicesDiscovered(gatt, status)

                    fileOutputStream?.write("On service discovered $status\r\n".toByteArray())

                    val list = gatt?.services?.flatMap { it.characteristics }
                        ?.filter { it.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == BluetoothGattCharacteristic.PROPERTY_NOTIFY }

                    if (list?.isEmpty() != false) {
                        fileOutputStream?.write("No notify services found\r\n".toByteArray())
                    } else {
                        GlobalScope.launch(Dispatchers.Main) {
                            AlertDialog.Builder(this@MainActivity)
                                .setAdapter(
                                    ArrayAdapter<String>(
                                        this@MainActivity,
                                        android.R.layout.simple_list_item_1,
                                        list.map { it.uuid.toString() }
                                    )
                                ) { dialogInterface, i ->
                                    fileOutputStream?.write("Selected service: ${list[i].uuid}\r\n".toByteArray())
                                    gatt.setCharacteristicNotification(list[i], true)
                                }
                                .show()
                        }
                    }
                }
            })
    }

    override fun onDestroy() {
        super.onDestroy()
        broadcastReceiver?.let {
            unregisterReceiver(it)
        }
    }

    private fun connectBl() {
        if (checkBluetooth(REQUEST_ENABLE_BT_BL)) {
            val deviceNamesAdapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1)
            val deviceList = ArrayList<BluetoothDevice>()
            AlertDialog.Builder(this)
                .setAdapter(deviceNamesAdapter) { dialogInterface, i ->
                    connectBl(deviceList[i])
                }
                .setOnDismissListener { bluetoothAdapter.cancelDiscovery() }
                .show()
            broadcastReceiver?.let {
                unregisterReceiver(it)
            }
            broadcastReceiver = object : BroadcastReceiver() {
                override fun onReceive(p0: Context?, intent: Intent?) {
                    intent?.let {
                        val bluetoothDevice = it.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) as BluetoothDevice
                        if (!deviceList.contains(bluetoothDevice)) {
                            deviceList.add(bluetoothDevice)
                            deviceNamesAdapter.add(bluetoothDevice.name ?: bluetoothDevice.address)
                        }
                    }
                }
            }
            registerReceiver(broadcastReceiver, IntentFilter(BluetoothDevice.ACTION_FOUND))
            if (bluetoothAdapter.isDiscovering) {
                bluetoothAdapter.cancelDiscovery()
            }
            bluetoothAdapter.startDiscovery()
        }
    }

    private fun connectBl(bluetoothDevice: BluetoothDevice) {
        bluetoothAdapter.cancelDiscovery()
        try {
/*
            val bluetoothSocket =
                bluetoothDevice.createRfcommSocketToServiceRecord(UUID.fromString("00000000-deca-fade-deca-deafdecaff"))
*/
            val bluetoothSocket =
                bluetoothDevice.createRfcommSocketToServiceRecord(UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"))
            try {
                bluetoothSocket.connect()
                if (bluetoothSocket.isConnected) {
                    val fileOutputStream = getLogFileOutputStream()
                    switchToConnectedState()
                    fileOutputStream.write("Connected as BL\r\n".toByteArray())
                    GlobalScope.launch {
                        while (bluetoothSocket.isConnected) {
                            val data = bluetoothSocket.inputStream.read()
                            fileOutputStream.write(data)
                            withContext(Dispatchers.Main) {
                                if (logTextView.text.length > 2048) {
                                    logTextView.text = ""
                                }
                                logTextView.text = "${logTextView.text}:${Integer.toHexString(data)}"
                            }
                        }
                        fileOutputStream.write("Disconnected\r\n".toByteArray())
                        switchToDisconnected()
                        fileOutputStream.close()
                    }
                } else {
                    Toast.makeText(this, "Failed to connect (no error)", Toast.LENGTH_SHORT).show()
                }
            } catch (e: IOException) {
                Toast.makeText(this, "Failed to connect (${e.message})", Toast.LENGTH_SHORT).show()
            }
        } catch (e: IOException) {
            Toast.makeText(this, "Failed to create socket (${e.message})", Toast.LENGTH_SHORT).show()
        }
    }

    private fun switchToConnectedState() {
        GlobalScope.launch(Dispatchers.Main) {
            buttonsLayout.visibility = View.GONE
            logTextView.visibility = View.VISIBLE
            disconnectButton.visibility = View.VISIBLE
            Toast.makeText(this@MainActivity, "Connected", Toast.LENGTH_SHORT).show()
        }
    }

    private fun switchToDisconnected() {
        GlobalScope.launch(Dispatchers.Main) {
            buttonsLayout.visibility = View.VISIBLE
            logTextView.visibility = View.GONE
            disconnectButton.visibility = View.GONE
            logTextView.text = ""
            Toast.makeText(this@MainActivity, "Disconnected", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getLogFileOutputStream(): FileOutputStream {
        val dir = Environment.getExternalStoragePublicDirectory("BluetoothLogs")
        dir.mkdirs()
        val file = File(dir, "btlog.log")
        return FileOutputStream(file, true)
    }

    private fun checkBluetooth(requestId: Int): Boolean {
        if (!bluetoothAdapter.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            startActivityForResult(enableBtIntent, requestId)
            return false
        } else {
            if (ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_DENIED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(android.Manifest.permission.ACCESS_COARSE_LOCATION),
                    requestId
                )
                return false
            }
            if (ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_DENIED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    requestId
                )
                return false
            }
        }
        return true
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            when (requestCode) {
                REQUEST_ENABLE_BT_BLE -> {
                    connectBle()
                }
                REQUEST_ENABLE_BT_BL -> {
                    connectBl()
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (resultCode == Activity.RESULT_OK) {
            when (requestCode) {
                REQUEST_ENABLE_BT_BL -> {
                    connectBl()
                }
                REQUEST_ENABLE_BT_BLE -> {
                    connectBle()
                }
                REQUEST_SELECT_FILE -> {
                    data?.data?.let { startBleServer(it) }
                }
            }
        }
    }
}
