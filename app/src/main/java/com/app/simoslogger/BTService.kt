package com.app.simoslogger

import android.app.*
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import android.widget.Toast
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Semaphore
import android.R.attr.name





// Header we expect to receive on BLE packets
class BLEHeader {
    var hdID: Int = BLE_HEADER_ID
    var cmdFlags: Int = 0
    var rxID: Int = BLE_HEADER_RX
    var txID: Int = BLE_HEADER_TX
    var cmdSize: Int = 0
    var tickCount: Int = 0

    fun isValid(): Boolean {
        return hdID == BLE_HEADER_ID
    }

    fun toByteArray(): ByteArray {
        val bArray = ByteArray(8)
        bArray[0] = (hdID and 0xFF).toByte()
        bArray[1] = (cmdFlags and 0xFF).toByte()
        bArray[2] = (rxID and 0xFF).toByte()
        bArray[3] = ((rxID and 0xFF00) shr 8).toByte()
        bArray[4] = (txID and 0xFF).toByte()
        bArray[5] = ((txID and 0xFF00) shr 8).toByte()
        bArray[6] = (cmdSize and 0xFF).toByte()
        bArray[7] = ((cmdSize and 0xFF00) shr 8).toByte()

        return bArray
    }

    fun fromByteArray(bArray: ByteArray) {
        hdID = bArray[0] and 0xFF
        cmdFlags = bArray[1] and 0xFF
        rxID = ((bArray[3] and 0xFF) shl 8) + (bArray[2] and 0xFF)
        txID = ((bArray[5] and 0xFF) shl 8) + (bArray[4] and 0xFF)
        cmdSize = ((bArray[7] and 0xFF) shl 8) + (bArray[6] and 0xFF)
        tickCount = ((rxID  and 0xFFFF) shl 16) + (txID  and 0xFFFF)
    }
}

class BTService: Service() {
    //constants
    val TAG = "BTService"

    // Member fields
    private var mScanning: Boolean = false
    private var mState: Int = STATE_NONE
    private var mErrorStatus: String = ""
    private val mWriteSemaphore: Semaphore = Semaphore(1)
    private val mReadQueue = ConcurrentLinkedQueue<ByteArray>()
    private val mWriteQueue = ConcurrentLinkedQueue<ByteArray>()
    private var mBluetoothGatt: BluetoothGatt? = null
    private var mBluetoothDevice: BluetoothDevice? = null
    private var mConnectionThread: ConnectionThread? = null
    private var mLogWriteState: Boolean = false

    //Gatt additional properties
    private fun BluetoothGattCharacteristic.isReadable(): Boolean = containsProperty(BluetoothGattCharacteristic.PROPERTY_READ)
    private fun BluetoothGattCharacteristic.isWritable(): Boolean = containsProperty(BluetoothGattCharacteristic.PROPERTY_WRITE)
    private fun BluetoothGattCharacteristic.isWritableWithoutResponse(): Boolean = containsProperty(BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)
    private fun BluetoothGattCharacteristic.isIndicatable(): Boolean = containsProperty(BluetoothGattCharacteristic.PROPERTY_INDICATE)
    private fun BluetoothGattCharacteristic.isNotifiable(): Boolean = containsProperty(BluetoothGattCharacteristic.PROPERTY_NOTIFY)
    private fun BluetoothGattCharacteristic.containsProperty(property: Int): Boolean = properties and property != 0

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent.action) {
            BT_START_SERVICE.toString() -> {
                doStartService()
            }
            BT_STOP_SERVICE.toString() -> {
                doStopService()
            }
            BT_DO_CONNECT.toString() -> {
                doConnect()
            }
            BT_DO_DISCONNECT.toString() -> {
                doDisconnect()
            }
            BT_DO_CHECK_VIN.toString() -> {
                mConnectionThread?.setTaskState(TASK_RD_VIN)
            }
            BT_DO_GET_ECU_INFO.toString() ->{
                mConnectionThread?.setTaskState(TASK_GET_ECU_INFO)
            }
            BT_DO_CHECK_PID.toString() -> {
                mConnectionThread?.setTaskState(TASK_LOGGING)
            }
            BT_DO_STOP_PID.toString() -> {
                mConnectionThread?.setTaskState(TASK_NONE)
            }
            BT_DO_CLEAR_DTC.toString() -> {
                mConnectionThread?.setTaskState(TASK_CLEAR_DTC)
            }
            BT_DO_FLASH_ECU_CAL.toString() -> {
                mConnectionThread?.setTaskState(TASK_FLASH_ECU_CAL)
            }
        }

        // If we get killed, after returning from here, restart
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        // We don't provide binding, so return null
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        Toast.makeText(this, "service done", Toast.LENGTH_SHORT).show()
        doDisconnect()
    }

    private val mScanCallback = object : ScanCallback() {
        val TAG = "mScanCallback"

        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            result.device?.let { device ->
                DebugLog.i(TAG, "Found BLE device! ${device.name}")

                if (mBluetoothDevice == null && device.name.contains(BLE_DEVICE_NAME, true)) {
                    mBluetoothDevice = device

                    if (mScanning)
                        stopScanning()

                    DebugLog.i(TAG, "Initiating connection to ${device.name}")
                    device.connectGatt(applicationContext, false, mGattCallback, 2)
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            DebugLog.w(TAG, "onScanFailed: code $errorCode")
        }
    }

    private val mGattCallback = object : BluetoothGattCallback() {
        val TAG = "BTGATTCallback"

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)

            //get device name
            val deviceName = gatt.device.name

            //If we are connected to the wrong device close and return
            if(mBluetoothDevice != gatt.device) {
                DebugLog.w(TAG, "Connection made to wrong device, connection closed: $deviceName")
                gatt.safeClose()
                return
            }

            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    DebugLog.i(TAG, "Successfully connected to $deviceName")

                    try {
                        //made connection, store our gatt
                        mBluetoothGatt = gatt

                        //discover gatt table
                        Handler(Looper.getMainLooper()).post {
                            gatt.discoverServices()
                        }
                    } catch (e: Exception) {
                        DebugLog.e(TAG, "Exception while requesting to discover services: ", e)
                        doDisconnect()
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    DebugLog.i(TAG, "Successfully disconnected from $deviceName")

                    //disable the read notification
                    disableNotifications(gatt.getService(BLE_SERVICE_UUID).getCharacteristic(BLE_DATA_RX_UUID))

                    //If gatt doesn't match ours make sure we close it
                    if(gatt != mBluetoothGatt) {
                        gatt.safeClose()
                    }

                    //Do a full disconnect
                    doDisconnect()
                }
            } else {
                DebugLog.i(TAG, "Error $status encountered for $deviceName! Disconnecting...")

                //If gatt doesn't match ours make sure we close it
                if(gatt != mBluetoothGatt) {
                    gatt.safeClose()
                }

                //Set new connection error state
                mErrorStatus = status.toString()

                //Do a full disconnect
                doDisconnect(STATE_ERROR, true)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            super.onServicesDiscovered(gatt, status)

            //If gatt doesn't match ours make sure we close it
            if(gatt != mBluetoothGatt) {
                gatt.safeClose()
                return
            }

            //If success request MTU
            if(status == BluetoothGatt.GATT_SUCCESS) {
                //Request new MTU
                with(gatt) {
                    DebugLog.i(TAG, "Discovered ${services.size} services for ${device.address}")

                    printGattTable()
                    try {
                        requestMtu(BLE_GATT_MTU_SIZE)
                    } catch (e: Exception) {
                        DebugLog.e(TAG,"Exception while discovering services:", e)
                        doDisconnect()
                    }
                }
            } else {
                DebugLog.i(TAG, "Failed to discover services for ${gatt.device.address}")

                //Set new connection error state
                mErrorStatus = status.toString()

                //Do a full disconnect
                doDisconnect(STATE_ERROR, true)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            super.onMtuChanged(gatt, mtu, status)

            DebugLog.i(TAG, "ATT MTU changed to $mtu, success: ${status == BluetoothGatt.GATT_SUCCESS}")

            //get device name
            val deviceName = gatt.device.name
            if(status == BluetoothGatt.GATT_SUCCESS) {

                //Make sure we are on the right connection
                if(gatt != mBluetoothGatt) {
                    DebugLog.i(TAG, "Gatt does not match mBluetoothGatt, closing connection to $deviceName")

                    gatt.safeClose()
                    return
                }

                //Set new connection state
                setConnectionState(STATE_CONNECTED)
                try {
                    mBluetoothGatt?.let { ourGatt ->
                        ourGatt.requestConnectionPriority(BLE_CONNECTION_PRIORITY)
                        enableNotifications(ourGatt.getService(BLE_SERVICE_UUID)!!.getCharacteristic(BLE_DATA_RX_UUID))
                    } ?: error("Gatt is invalid")
                } catch (e: Exception) {
                    DebugLog.e(TAG,"Exception setting mtu", e)
                    doDisconnect()
                }
            } else {
                //If gatt doesn't match ours make sure we close it
                if(gatt != mBluetoothGatt) {
                    gatt.safeClose()
                }

                //Set new connection error state
                mErrorStatus = status.toString()

                //Do a full disconnect
                doDisconnect(STATE_ERROR, true)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
            super.onDescriptorWrite(gatt, descriptor, status)
            when (status) {
                BluetoothGatt.GATT_SUCCESS -> {
                    DebugLog.d("onDescWrite", "success ${descriptor.toString()}")
                }
                else -> {
                    DebugLog.w("onDescWrite", "failed ${descriptor.toString()}")
                }
            }
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            super.onCharacteristicRead(gatt, characteristic, status)
            with(characteristic) {
                when (status) {
                    BluetoothGatt.GATT_SUCCESS -> {
                        DebugLog.d(TAG, "Read characteristic $uuid | length: ${value.count()}")
                    }
                    BluetoothGatt.GATT_READ_NOT_PERMITTED -> {
                        DebugLog.w(TAG, "Read not permitted for $uuid!")
                    }
                    else -> {
                        DebugLog.w(TAG, "Characteristic read failed for $uuid, error: $status")
                    }
                }
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            super.onCharacteristicWrite(gatt, characteristic, status)
            with(characteristic) {
                when (status) {
                    BluetoothGatt.GATT_SUCCESS -> {
                        DebugLog.d("BluetoothGattCallback", "Wrote to characteristic $uuid | length: ${value.count()}")
                    }
                    BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH -> {
                        DebugLog.w("BluetoothGattCallback", "Write exceeded connection ATT MTU!")
                    }
                    BluetoothGatt.GATT_WRITE_NOT_PERMITTED -> {
                        DebugLog.w("BluetoothGattCallback", "Write not permitted for $uuid!")
                    }
                    else -> {
                        DebugLog.w("BluetoothGattCallback", "Characteristic write failed for $uuid, error: $status")
                    }
                }
            }
            mWriteSemaphore.release()
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            super.onCharacteristicChanged(gatt, characteristic)
            with(characteristic) {
                DebugLog.d("BluetoothGattCallback", "Characteristic $uuid changed | length: ${value.count()}")

                //parse packet and check for multiple responses
                val bleHeader = BLEHeader()
                while(value.count() > 0) {
                    bleHeader.fromByteArray(value)
                    value = if(bleHeader.cmdSize+8 <= value.count()) {
                        mReadQueue.add(value.copyOfRange(0, bleHeader.cmdSize + 8))
                        value.copyOfRange(bleHeader.cmdSize + 8, value.count())
                    } else {
                        byteArrayOf()
                    }
                }
            }
        }
    }

    private fun BluetoothGatt.safeClose() {
        //get device name
        val deviceName = this.device.name

        DebugLog.i(TAG, "Closing connection to $deviceName")

        try {
            this.close()
        } catch(e: Exception){
            DebugLog.e(TAG, "Exception while closing connection to $deviceName", e)
        }
    }

    private fun BluetoothGatt.printGattTable() {
        if (services.isEmpty()) {
            DebugLog.w("printGattTable", "No service and characteristic available, call discoverServices() first?")
            return
        }
        services.forEach { service ->
            val characteristicsTable = service.characteristics.joinToString(separator = "\n|--", prefix = "|--") {
                it.uuid.toString()
            }
            DebugLog.d("printGattTable", "\nService ${service.uuid}\nCharacteristics:\n$characteristicsTable")
        }
    }

    private fun writeDescriptor(descriptor: BluetoothGattDescriptor, payload: ByteArray) {
        mBluetoothGatt?.let { gatt ->
            descriptor.value = payload
            gatt.writeDescriptor(descriptor)
        } ?: error("Not connected to a BLE device!")
    }

    private fun enableNotifications(characteristic: BluetoothGattCharacteristic) {
        val payload = when {
            characteristic.isIndicatable() -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            characteristic.isNotifiable() -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            else -> {
                DebugLog.w("ConnectionManager", "${characteristic.uuid} doesn't support notifications/indications")
                return
            }
        }

        characteristic.getDescriptor(BLE_CCCD_UUID)?.let { cccDescriptor ->
            if (mBluetoothGatt?.setCharacteristicNotification(characteristic, true) == false) {
                DebugLog.w("ConnectionManager", "setCharacteristicNotification failed for ${characteristic.uuid}")
                return
            }
            writeDescriptor(cccDescriptor, payload)
        } ?: DebugLog.w("ConnectionManager", "${characteristic.uuid} doesn't contain the CCC descriptor!")
    }

    private fun disableNotifications(characteristic: BluetoothGattCharacteristic) {
        if (!characteristic.isNotifiable() && !characteristic.isIndicatable()) {
            DebugLog.w("ConnectionManager", "${characteristic.uuid} doesn't support indications/notifications")
            return
        }

        characteristic.getDescriptor(BLE_CCCD_UUID)?.let { cccDescriptor ->
            if (mBluetoothGatt?.setCharacteristicNotification(characteristic, false) == false) {
                DebugLog.w("ConnectionManager", "setCharacteristicNotification failed for ${characteristic.uuid}")
                return
            }
            writeDescriptor(cccDescriptor, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
        } ?: DebugLog.w("ConnectionManager", "${characteristic.uuid} doesn't contain the CCC descriptor!")
    }

    @Synchronized
    private fun stopScanning() {
        DebugLog.i(TAG, "Stop Scanning")
        if (mScanning) {
            (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter.bluetoothLeScanner.stopScan(mScanCallback)
            mScanning = false
        }
    }

    @Synchronized
    private fun doStopService() {
        LogFile.close()
        doDisconnect()
        stopForeground(true)
        stopSelf()
    }

    @Synchronized
    private fun doStartService() {
        val serviceChannel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(serviceChannel)

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0)

        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getText(R.string.app_name))
            .setContentText(getText(R.string.app_name))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .build()

        // Notification ID cannot be 0.
        startForeground(1, notification)
    }

    @Synchronized
    private fun doConnect() {
        doDisconnect()

        DebugLog.i(TAG, "Searching for BLE device.")

        val filter = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString(BLE_SERVICE_UUID.toString()))
                .build()
        )
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()

        //set delay to stop scanning
        Handler(Looper.getMainLooper()).postDelayed({
            doTimeout()
        }, BLE_SCAN_PERIOD)

        //Set new connection status
        setConnectionState(STATE_CONNECTING)

        //Start scanning for BLE devices
        (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter.bluetoothLeScanner.startScan(filter, settings, mScanCallback)
        mScanning = true
    }

    @Synchronized
    private fun doDisconnect(newState: Int = STATE_NONE, errorMessage: Boolean = false) {

        //get device name
        val deviceName = mBluetoothDevice?.name ?: "Not connected"
        DebugLog.i(TAG, "Disconnecting from BLE device: $deviceName")

        if (mScanning)
            stopScanning()

        closeConnectionThread()

        mBluetoothGatt?.let {
            it.safeClose()
            mBluetoothGatt = null
        }

        mBluetoothDevice = null

        //Set new connection status
        setConnectionState(newState, errorMessage)
    }

    @Synchronized
    private fun doTimeout() {
        if(mScanning)
            stopScanning()

        if(mState != STATE_CONNECTED) {
            //Set new connection status
            setConnectionState(STATE_NONE)
        }
    }

    @Synchronized
    private fun closeConnectionThread() {
        mConnectionThread?.cancel()
        mConnectionThread = null
    }

    @Synchronized
    private fun createConnectionThread() {
        mConnectionThread = ConnectionThread()
        mConnectionThread?.let { thread ->
            thread.priority = BLE_THREAD_PRIORITY
            thread.start()
        }
    }

    @Synchronized
    private fun setConnectionState(newState: Int, errorMessage: Boolean = false)
    {
        when(newState) {
            STATE_CONNECTED -> {
                closeConnectionThread()
                createConnectionThread()
            }
            STATE_NONE -> {
                closeConnectionThread()
            }
            STATE_ERROR -> {
                closeConnectionThread()
            }
        }
        //Broadcast a new message
        mState = newState
        val intentMessage = Intent(MESSAGE_STATE_CHANGE.toString())
        intentMessage.putExtra("newState", mState)
        intentMessage.putExtra("cDevice", mBluetoothGatt?.device?.name)
        if(errorMessage)
            intentMessage.putExtra("newError", mErrorStatus)
        sendBroadcast(intentMessage)
    }

    private inner class ConnectionThread: Thread() {
        //variables
        private var mTask: Int          = TASK_NONE
        private var mTaskNext: Int      = TASK_NONE
        private var mTaskCount: Int     = 0
        private var mTaskTime: Long     = 0
        private var mTaskTimeNext: Long = 0
        private var mTaskTimeOut: Long  = 0


        init {
            setTaskState(TASK_NONE)
            DebugLog.d(TAG, "create ConnectionThread")
        }

        override fun run() {
            DebugLog.i(TAG, "BEGIN mConnectionThread")

            while (mState == STATE_CONNECTED && !currentThread().isInterrupted) {
                //See if there are any packets waiting to be sent
                if (!mWriteQueue.isEmpty() && mWriteSemaphore.tryAcquire()) {
                    try {
                        val buff = mWriteQueue.poll()
                        buff?.let {
                            DebugLog.c(TAG, buff,true)

                            mBluetoothGatt?.let { gatt ->
                                val txChar = gatt.getService(BLE_SERVICE_UUID)!!.getCharacteristic(BLE_DATA_TX_UUID)
                                val writeType = when {
                                    txChar.isWritable() -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                                    txChar.isWritableWithoutResponse() -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                                    else -> error("Characteristic ${txChar.uuid} cannot be written to")
                                }
                                txChar.writeType = writeType
                                txChar.value = it
                                gatt.writeCharacteristic(txChar)
                            } ?: error("Not connected to a BLE device!")
                        }
                    } catch (e: Exception) {
                        DebugLog.e(TAG, "Exception during write", e)
                        mWriteSemaphore.release()
                        cancel()
                        break
                    }
                }

                //See if there are any packets waiting to be sent
                if (!mReadQueue.isEmpty()) {
                    try {
                        val buff = mReadQueue.poll()
                        if(buff != null)
                            DebugLog.c(TAG, buff,false)
                        
                        when (mTask) {
                            TASK_NONE -> {
                                //Broadcast a new message
                                val intentMessage = Intent(MESSAGE_READ.toString())
                                intentMessage.putExtra("readBuffer", buff!!.copyOfRange(8, buff.size))
                                sendBroadcast(intentMessage)
                            }
                            TASK_RD_VIN -> {
                                val vinBuff = buff!!.copyOfRange(8, buff.size)

                                //was it successful?
                                if(vinBuff[0] == 0x62.toByte()) {
                                    //Broadcast a new message
                                    val intentMessage = Intent(MESSAGE_READ_VIN.toString())
                                    intentMessage.putExtra("readBuffer", vinBuff.copyOfRange(2, vinBuff.size))
                                    sendBroadcast(intentMessage)
                                }

                                setTaskState(TASK_NONE)
                            }
                            TASK_GET_ECU_INFO ->{
                                val ECUInfo = buff!!.copyOfRange(8, buff.size)

                                //hope it's a 62 response...
                                if(ECUInfo[0] == 0x62.toByte()){
                                    val intentMessage = Intent(MESSAGE_ECU_INFO.toString())


                                    intentMessage.putExtra("readBuffer", ECUInfo)
                                    sendBroadcast(intentMessage)

                                }


                            }
                            TASK_CLEAR_DTC -> {
                                //Broadcast a new message
                                val intentMessage = Intent(MESSAGE_READ_DTC.toString())
                                intentMessage.putExtra("readBuffer", buff!!.copyOfRange(8, buff.size))
                                sendBroadcast(intentMessage)

                                setTaskState(TASK_NONE)
                            }
                            TASK_FLASH_ECU_CAL -> {
                                val response = buff!!.copyOfRange(8, buff.size)
                                val intentMessage = Intent(MESSAGE_READ.toString())

                                intentMessage.putExtra("readBuffer", response)
                                sendBroadcast(intentMessage)

                            }
                            TASK_LOGGING -> {
                                //Process frame
                                val result = UDSLogger.processFrame(mTaskCount, buff, applicationContext)

                                //Are we still sending initial frames?
                                if(mTaskCount < UDSLogger.frameCount()) {
                                    //If we failed init abort
                                    if(result != UDS_OK) {
                                        DebugLog.i(TAG, "Unable to initialize logging, UDS Error: $result")
                                        setTaskState(TASK_NONE)
                                    } else { //else continue init
                                        mWriteQueue.add(UDSLogger.buildFrame(mTaskCount))
                                    }
                                }

                                //Broadcast a new message
                                if(mTaskCount % Settings.updateRate == 0) {
                                    val intentMessage = Intent(MESSAGE_READ_LOG.toString())
                                    intentMessage.putExtra("readCount", mTaskCount)
                                    intentMessage.putExtra("readTime", System.currentTimeMillis() - mTaskTime)
                                    intentMessage.putExtra("readResult", result)
                                    sendBroadcast(intentMessage)
                                }

                                //If we changed logging write states broadcast a new message and set LED color
                                if(UDSLogger.isEnabled() != mLogWriteState) {
                                    //Broadcast new message
                                    val intentMessage = Intent(MESSAGE_WRITE_LOG.toString())
                                    intentMessage.putExtra("enabled", UDSLogger.isEnabled())
                                    sendBroadcast(intentMessage)

                                    //Set LED
                                    val bleHeader = BLEHeader()
                                    bleHeader.cmdSize = 4
                                    bleHeader.cmdFlags = BLE_COMMAND_FLAG_SETTINGS or BRG_SETTING_LED_COLOR
                                    var dataBytes = byteArrayOf(
                                        0x00.toByte(),
                                        0x00.toByte(),
                                        0x80.toByte(),
                                        0x00.toByte()
                                    )
                                    if (UDSLogger.isEnabled())
                                        dataBytes = byteArrayOf(
                                            0x00.toByte(),
                                            0x80.toByte(),
                                            0x00.toByte(),
                                            0x00.toByte()
                                        )
                                    val buf = bleHeader.toByteArray() + dataBytes
                                    mWriteQueue.add(buf)

                                    //Update current write state
                                    mLogWriteState = UDSLogger.isEnabled()
                                }
                            }
                        }

                        if(mTaskNext != TASK_NONE) {
                            mTaskTimeNext = System.currentTimeMillis() + TASK_END_DELAY

                            //Write debug log
                            DebugLog.d(TAG, "Packet extended task start delay.")
                        }

                        mTaskCount++
                    } catch (e: Exception) {
                        DebugLog.e(TAG, "Exception during read", e)
                        cancel()
                        break
                    }
                }

                //Ready for next task?
                if(mTaskNext != TASK_NONE) {
                    if(mTaskTimeNext < System.currentTimeMillis()) {
                        DebugLog.i(TAG, "Task finished.")
                        startNextTask()
                    } else if(mTaskTimeOut < System.currentTimeMillis()) {
                        //Write debug log
                        DebugLog.w(TAG, "Task failed to finish.")
                        startNextTask()
                    }
                }
            }
        }

        fun cancel() {
            interrupt()
        }

        @Synchronized
        fun setTaskState(newTask: Int)
        {
            //if we are not connected abort
            if (mState != STATE_CONNECTED) {
                mTask = TASK_NONE
                return
            }

            //queue up next task and set start time
            mTaskTimeNext   = System.currentTimeMillis() + TASK_END_DELAY
            mTaskTimeOut    = System.currentTimeMillis() + TASK_END_TIMEOUT
            mTaskNext       = newTask

            //If we are doing something call for a stop
            if(mTask != TASK_NONE) {
                //Write debug log
                DebugLog.i(TAG, "Task stopped: $mTask")

                //set task to none
                mTask = TASK_NONE

                //Broadcast TASK_NONE
                val intentMessage = Intent(MESSAGE_TASK_CHANGE.toString())
                intentMessage.putExtra("newTask", mTask)
                sendBroadcast(intentMessage)

                //Disable persist mode
                val bleHeader = BLEHeader()
                bleHeader.cmdSize = 0
                bleHeader.cmdFlags = BLE_COMMAND_FLAG_PER_CLEAR
                var buf = bleHeader.toByteArray()

                //Set LED to green
                bleHeader.cmdSize = 4
                bleHeader.cmdFlags = BLE_COMMAND_FLAG_SETTINGS or BRG_SETTING_LED_COLOR
                val dataBytes = byteArrayOf(0x00.toByte(), 0x00.toByte(), 0x80.toByte(), 0x00.toByte())
                buf += bleHeader.toByteArray() + dataBytes
                mWriteQueue.add(buf)
            }
        }

        @Synchronized
        private fun startNextTask() {
            //Broadcast a new message
            mTask       = mTaskNext
            mTaskNext   = TASK_NONE
            mTaskCount  = 0
            mTaskTime   = System.currentTimeMillis()

            //Write debug log
            DebugLog.i(TAG, "Task started: $mTask")

            //send new task
            val intentMessage = Intent(MESSAGE_TASK_CHANGE.toString())
            intentMessage.putExtra("newTask", mTask)
            sendBroadcast(intentMessage)

            when (mTask) {
                TASK_LOGGING -> {
                    //Set persist delay
                    val bleHeader = BLEHeader()
                    bleHeader.cmdSize = 2
                    bleHeader.cmdFlags = BLE_COMMAND_FLAG_SETTINGS or BRG_SETTING_PERSIST_DELAY
                    var dataBytes = byteArrayOf((Settings.persistDelay and 0xFF).toByte(), ((Settings.persistDelay and 0xFF00) shr 8).toByte())
                    var buf = bleHeader.toByteArray() + dataBytes
                    mWriteQueue.add(buf)

                    //Set persist Q delay
                    bleHeader.cmdSize = 2
                    bleHeader.cmdFlags = BLE_COMMAND_FLAG_SETTINGS or BRG_SETTING_PERSIST_Q_DELAY
                    dataBytes = byteArrayOf((Settings.persistQDelay and 0xFF).toByte(), ((Settings.persistQDelay and 0xFF00) shr 8).toByte())
                    buf = bleHeader.toByteArray() + dataBytes
                    mWriteQueue.add(buf)

                    //Write first frame
                    mWriteQueue.add(UDSLogger.buildFrame(0))
                }
                TASK_RD_VIN -> {
                    //Send vin request
                    val bleHeader = BLEHeader()
                    bleHeader.cmdSize = 3
                    bleHeader.cmdFlags = BLE_COMMAND_FLAG_PER_CLEAR
                    val dataBytes = byteArrayOf(0x22.toByte(), 0xF1.toByte(), 0x90.toByte())
                    val buf = bleHeader.toByteArray() + dataBytes
                    mWriteQueue.add(buf)
                }
                TASK_GET_ECU_INFO -> {
                    getECUInfo()
                }
                TASK_CLEAR_DTC -> {
                    clearDTC()
                }
                TASK_FLASH_ECU_CAL -> {
                    //Get the box code from the ecu
                    //read in the file that we're flashing
                    //compare the box code to the bin, make sure it matches
                    //compress/encrypt

                    clearDTC()
                    //Open extended diagnostic session
                    //Check programming precondition, routine 0x0203
                    //Tester present
                    //Pass SA2SeedKey unlock_security_access(17)
                    //Tester present
                    //Write workshop tool log
                    //  0xF15A = 0x20, 0x7, 0x17, 0x42,0x04,0x20,0x42,0xB1,0x3D,
                    //Tester present
                    //FLASH BLOCK
                    //  erase block 0x01 0x05
                    //  request download
                    //  transfer data in blocks
                    //  request transfer exit
                    //  tester present
                    //  run checksum start_routine(0x0202, data=bytes(checksum_data))
                    //Verify programming dependencies, routine 0xFF01
                    //Tester present
                    //Reset ECU
                }

            }
        }

        private fun clearDTC(){
            //Send clear request
            val bleHeader = BLEHeader()
            bleHeader.rxID = 0x7E8
            bleHeader.txID = 0x700
            bleHeader.cmdSize = 1
            bleHeader.cmdFlags = BLE_COMMAND_FLAG_PER_CLEAR
            val dataBytes = byteArrayOf(0x04.toByte())
            val buf = bleHeader.toByteArray() + dataBytes
            mWriteQueue.add(buf)
        }

        private fun getECUInfo(){
            val bleHeader = BLEHeader()
            bleHeader.cmdSize = 3
            bleHeader.cmdFlags = BLE_COMMAND_FLAG_PER_CLEAR

            ECU_INFO_LIST.forEach { key, value ->
                val dataBytes = byteArrayOf(0x22.toByte()) + value
                val buf = bleHeader.toByteArray() + dataBytes
                mWriteQueue.add(buf)
            }
        }


    }
}

