/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.app.simoslogger

import android.bluetooth.BluetoothGatt
import android.graphics.Color
import android.os.Environment
import java.util.*

// Message types sent from the BluetoothChatService Handler
val MESSAGE_STATE_CHANGE    = 1
val MESSAGE_TASK_CHANGE     = 2
val MESSAGE_TOAST           = 3
val MESSAGE_READ            = 4
val MESSAGE_READ_VIN        = 5
val MESSAGE_READ_DTC        = 6
val MESSAGE_READ_LOG        = 7
val MESSAGE_WRITE_LOG       = 8
val MESSAGE_ECU_INFO        = 9
val MESSAGE_FLASH_RESPONSE  = 10

// Constants that indicate the current connection state
val STATE_ERROR         = -1 // we're doing nothing
val STATE_NONE          = 0 // we're doing nothing
val STATE_CONNECTING    = 1 // now initiating an outgoing connection
val STATE_CONNECTED     = 2 // now connected to a remote device

//List of available tasks
val TASK_NONE           = 0
val TASK_FLASHING       = 1
val TASK_LOGGING        = 2 // uploading to remote device
val TASK_RD_VIN         = 3 // download from remote device
val TASK_CLEAR_DTC      = 4
val TASK_GET_ECU_INFO   = 5
val TASK_FLASH_ECU_CAL  = 6

enum class FLASH_ECU_CAL_SUBTASK {
    NONE,
    GET_ECU_BOX_CODE,
    READ_FILE_FROM_STORAGE,
    CHECK_FILE_COMPAT,
    CHECKSUM_BIN,
    COMPRESS_BIN,
    ENCRYPT_BIN,
    CLEAR_DTC,
    OPEN_EXTENDED_DIAGNOSTIC,
    CHECK_PROGRAMMING_PRECONDITION, //routine 0x0203
    SA2SEEDKEY,
    WRITE_WORKSHOP_LOG,
    FLASH_BLOCK,
    CHECKSUM_BLOCK, //0x0202
    VERIFY_PROGRAMMING_DEPENDENCIES,
    RESET_ECU;

    fun next(): FLASH_ECU_CAL_SUBTASK {
        val vals = FLASH_ECU_CAL_SUBTASK.values()
        return vals[(this.ordinal+1) % vals.size];
    }
}

val TASK_END_DELAY      = 1000
val TASK_END_TIMEOUT    = 5000

//Intent constants
val REQUEST_LOCATION_PERMISSION = 1
val REQUEST_READ_STORAGE        = 2
val REQUEST_WRITE_STORAGE       = 3

//Service info
val CHANNEL_ID = "BTService"
val CHANNEL_NAME = "BTService"

//BT functions
val BT_STOP_SERVICE     = 0
val BT_START_SERVICE    = 1
val BT_DO_CONNECT       = 2
val BT_DO_DISCONNECT    = 3
val BT_DO_CHECK_VIN     = 4
val BT_DO_CHECK_PID     = 5
val BT_DO_STOP_PID      = 6
val BT_DO_CLEAR_DTC     = 7
val BT_DO_GET_ECU_INFO  = 8
val BT_DO_FLASH_ECU_CAL = 9

//BLE settings
val BLE_DEVICE_NAME      = "BLE_TO_ISOTP"
val BLE_GATT_MTU_SIZE    = 512
val BLE_SCAN_PERIOD      = 5000L
val BLE_CONNECTION_PRIORITY = BluetoothGatt.CONNECTION_PRIORITY_HIGH
val BLE_THREAD_PRIORITY = 5 //Priority (max is 10)

//ISOTP bridge UUIDS
val BLE_CCCD_UUID    = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
val BLE_SERVICE_UUID = UUID.fromString("0000abf0-0000-1000-8000-00805f9b34fb")
val BLE_DATA_TX_UUID = UUID.fromString("0000abf1-0000-1000-8000-00805f9b34fb")
val BLE_DATA_RX_UUID = UUID.fromString("0000abf2-0000-1000-8000-00805f9b34fb")
val BLE_CMD_TX_UUID  = UUID.fromString("0000abf3-0000-1000-8000-00805f9b34fb")
val BLE_CMD_RX_UUID  = UUID.fromString("0000abf4-0000-1000-8000-00805f9b34fb")

//ISOTP bridge BLE header defaults
val BLE_HEADER_ID = 0xF1
val BLE_HEADER_PT = 0xF2
val BLE_HEADER_RX = 0x7E8
val BLE_HEADER_TX = 0x7E0

//ISOTP bridge command flags
val BLE_COMMAND_FLAG_PER_ENABLE     = 1
val BLE_COMMAND_FLAG_PER_CLEAR		= 2
val BLE_COMMAND_FLAG_PER_ADD		= 4
val BLE_COMMAND_FLAG_SPLIT_PK		= 8
val BLE_COMMAND_FLAG_SETTINGS_GET	= 64
val BLE_COMMAND_FLAG_SETTINGS		= 128

//ISOTP bridge internal settings
val BRG_SETTING_ISOTP_STMIN			= 1
val BRG_SETTING_LED_COLOR			= 2
val BRG_SETTING_PERSIST_DELAY		= 3
val BRG_SETTING_PERSIST_Q_DELAY		= 4
val BRG_SETTING_BLE_SEND_DELAY		= 5
val BRG_SETTING_BLE_MULTI_DELAY		= 6

// UDS22Logger errors
val UDS_OK              = 0
val UDS_ERROR_RESPONSE  = 1
val UDS_ERROR_NULL      = 2
val UDS_ERROR_HEADER    = 3
val UDS_ERROR_CMDSIZE   = 4
val UDS_ERROR_UNKNOWN   = 5

//Logging modes
val UDS_LOGGING_22 = 0
val UDS_LOGGING_3E = 1

val MAX_PIDS        = 100
val CSV_CFG_LINE    = "Name,Unit,Equation,Format,Address,Length,Signed,ProgMin,ProgMax,WarnMin,WarnMax,Smoothing"
val CSV_VALUE_COUNT = 12
val CSV_3E_NAME     = "PIDList3E.csv"
val CSV_22_NAME     = "PIDList22.csv"
val CFG_FILENAME    = "config.cfg"
val DEBUG_FILENAME  = "debug.log"

//Log files
val LOG_NONE            = 0
val LOG_INFO            = 1
val LOG_WARNING         = 2
val LOG_DEBUG           = 4
val LOG_EXCEPTION       = 8
val LOG_COMMUNICATIONS  = 16

//Default settings
val DEFAULT_KEEP_SCREEN_ON      = true
val DEFAULT_INVERT_CRUISE       = false
val DEFAULT_UPDATE_RATE         = 4
val DEFAULT_DIRECTORY           = Environment.DIRECTORY_DOWNLOADS
val DEFAULT_PERSIST_DELAY       = 20
val DEFAULT_PERSIST_Q_DELAY     = 10
val DEFAULT_CALCULATE_HP        = true
val DEFAULT_USE_MS2             = true
val DEFAULT_TIRE_DIAMETER       = 0.632f
val DEFAULT_CURB_WEIGHT         = 1500f
val DEFAULT_DRAG_COEFFICIENT    = 0.000002
val DEFAULT_GEAR_RATIOS         = floatArrayOf(2.92f, 1.79f, 1.14f, 0.78f, 0.58f, 0.46f, 0.0f, 4.77f)
val DEFAULT_COLOR_LIST          = intArrayOf(Color.rgb(255, 255, 255),
                                            Color.rgb(127, 127, 255),
                                            Color.rgb(0,   0,   0),
                                            Color.rgb(0,   255, 0),
                                            Color.rgb(255, 0,   0),
                                            Color.rgb(255, 0,   0),
                                            Color.rgb(100, 0,   255),
                                            Color.rgb(100, 100, 255),
                                            Color.rgb(0,   0,   255),
                                            Color.rgb(255, 255, 0),
                                            Color.rgb(0,   255, 0))
val DEFAULT_ALWAYS_PORTRAIT     = false
val DEFAULT_DISPLAY_SIZE        = 1f
val DEFAULT_LOG_FLAGS           = LOG_INFO or LOG_WARNING or LOG_EXCEPTION

//TQ/HP Calculations
val KG_TO_N = 9.80665f
val TQ_CONSTANT = 16.3f

//Color index
val COLOR_BG_NORMAL     = 0
val COLOR_BG_WARN       = 1
val COLOR_TEXT          = 2
val COLOR_BAR_NORMAL    = 3
val COLOR_BAR_WARN      = 4
val COLOR_ST_ERROR      = 5
val COLOR_ST_NONE       = 6
val COLOR_ST_CONNECTING = 7
val COLOR_ST_CONNECTED  = 8
val COLOR_ST_LOGGING    = 9
val COLOR_ST_WRITING    = 10

//Normal list used for get ECU info:
val ECU_INFO_LIST   =   mapOf(
    "VIN" to byteArrayOf(0xf1.toByte(), 0x90.toByte()),
    "ASAM/ODX File Identifier" to byteArrayOf(0xF1.toByte(), 0x9E.toByte()),
    "ASAM/ODX File Version" to byteArrayOf(0xF1.toByte(), 0xA2.toByte()),
    "Vehicle Speed" to byteArrayOf(0xF4.toByte(), 0x0D.toByte()),
    "Calibration Version Numbers" to byteArrayOf(0xF8.toByte(), 0x06.toByte()),
    "VW Spare part Number" to byteArrayOf(0xF1.toByte(), 0x87.toByte()),
    "VW ASW Version" to byteArrayOf(0xF1.toByte(), 0x89.toByte()),
    "ECU Hardware Number" to byteArrayOf(0xF1.toByte(), 0x91.toByte()),
    "ECU Hardware Version Number" to byteArrayOf(0xF1.toByte(), 0xA3.toByte()),
    "System Name/Engine type" to byteArrayOf(0xF1.toByte(), 0x97.toByte()),
    "Engine Code" to byteArrayOf(0xF1.toByte(), 0xAD.toByte()),
    "VW Workshop Name" to byteArrayOf(0xF1.toByte(), 0xAA.toByte()),
    "State of Flash Mem" to byteArrayOf(0x04.toByte(), 0x05.toByte()),
    "VW Coding Value" to byteArrayOf(0x06.toByte(), 0x00.toByte())
)


//Additional properties
infix fun Byte.shl(that: Int): Int = this.toInt().shl(that)
infix fun Short.shl(that: Int): Int = this.toInt().shl(that)
infix fun Byte.shr(that: Int): Int = this.toInt().shr(that)
infix fun Short.shr(that: Int): Int = this.toInt().shr(that)
infix fun Byte.and(that: Int): Int = this.toInt().and(that)
infix fun Short.and(that: Int): Int = this.toInt().and(that)
fun Byte.toHex(): String = "%02x".format(this)
fun Byte.toHexS(): String = " %02x".format(this)
fun Short.toHex(): String = "%04x".format(this)
fun Int.toHex(): String = "%08x".format(this)
fun Int.toColorInverse(): Int = Color.WHITE xor this or 0xFF000000.toInt()
fun Int.toColorHex(): String = "%06x".format(this and 0xFFFFFF)
fun Int.toTwo(): String = "%02d".format(this)
fun Int.toArray2(): ByteArray = byteArrayOf((this and 0xFF00 shr 8).toByte(), (this and 0xFF).toByte())
fun Long.toColorInt(): Int = (this.toInt() and 0xFFFFFF) or 0xFF000000.toInt()
fun Long.toHex2(): String = "%04x".format(this)
fun Long.toHex4(): String = "%08x".format(this)
fun Long.toArray4(): ByteArray = byteArrayOf((this and 0xFF000000 shr 24).toByte(), (this and 0xFF0000 shr 16).toByte(), (this and 0xFF00 shr 8).toByte(), (this and 0xFF).toByte())
fun ByteArray.toHex(): String = joinToString(separator = " ") { eachByte -> "%02x".format(eachByte) }