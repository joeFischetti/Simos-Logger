package com.app.simoslogger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import android.widget.*
import androidx.core.content.ContextCompat.startForegroundService
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import java.util.Arrays


class FlashingViewModel : ViewModel() {
    var mConversationArrayAdapter: ArrayAdapter<String>? = null
    var mConnectedDeviceName: String? = null
}

class FlashingFragment : Fragment() {
    private val TAG = "FlashingFragment"
    private lateinit var mViewModel: FlashingViewModel

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_flashing, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        //Get view model
        mViewModel = ViewModelProvider(this).get(FlashingViewModel::class.java)

        if(mViewModel.mConversationArrayAdapter == null)
            mViewModel.mConversationArrayAdapter = ArrayAdapter(requireActivity(), R.layout.message)
        view.findViewById<ListView>(R.id.bt_message).adapter = mViewModel.mConversationArrayAdapter
        view.findViewById<ListView>(R.id.bt_message).setBackgroundColor(Color.WHITE)


        view.findViewById<Button>(R.id.buttonBack2).setOnClickListener {
            findNavController().navigateUp()
        }

        view.findViewById<Button>(R.id.buttonCheckVIN).setOnClickListener {
            // Get the message bytes and tell the BluetoothChatService to write
            val serviceIntent = Intent(context, BTService::class.java)
            serviceIntent.action = BT_DO_CHECK_VIN.toString()
            startForegroundService(this.requireContext(), serviceIntent)
        }

        view.findViewById<Button>(R.id.buttonGetECUInfo).setOnClickListener {
            // Get the message bytes and tell the BluetoothChatService to write
            val serviceIntent = Intent(context, BTService::class.java)
            serviceIntent.action = BT_DO_GET_ECU_INFO.toString()
            startForegroundService(this.requireContext(), serviceIntent)
        }

        view.findViewById<Button>(R.id.buttonClearDTC).setOnClickListener {
            // Get the message bytes and tell the BluetoothChatService to write
            val serviceIntent = Intent(context, BTService::class.java)
            serviceIntent.action = BT_DO_CLEAR_DTC.toString()
            startForegroundService(this.requireContext(), serviceIntent)
        }

        //Set background color
        view.setBackgroundColor(Settings.colorList[COLOR_BG_NORMAL])
    }

    override fun onResume() {
        super.onResume()

        val filter = IntentFilter()
        filter.addAction(MESSAGE_STATE_CHANGE.toString())
        //filter.addAction(MESSAGE_READ.toString())
        filter.addAction(MESSAGE_READ_VIN.toString())
        filter.addAction(MESSAGE_READ_DTC.toString())
        filter.addAction(MESSAGE_ECU_INFO.toString())
        this.activity?.registerReceiver(mBroadcastReceiver, filter)
    }

    override fun onPause() {
        super.onPause()

        this.activity?.unregisterReceiver(mBroadcastReceiver)
    }

    private val mBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            when (intent.action) {
                MESSAGE_STATE_CHANGE.toString() -> {
                    when (intent.getIntExtra("newState", -1)) {
                        STATE_CONNECTED -> {
                            val cDevice = intent.getStringExtra("cDevice")
                            mViewModel.mConnectedDeviceName = cDevice
                        }
                    }
                }
                MESSAGE_READ.toString() -> {
                    val readBuff = intent.getByteArrayExtra("readBuffer")

                    // construct a string from the valid bytes in the buffer
                    if(readBuff != null) {
                        val readString = String(readBuff)
                        mViewModel.mConversationArrayAdapter?.add(mViewModel.mConnectedDeviceName.toString() + ":  $readString")

                        val btMessage = view?.findViewById<ListView>(R.id.bt_message)!!
                        btMessage.setSelection(btMessage.adapter.count - 1)
                    }
                }
                MESSAGE_READ_VIN.toString() -> {
                    val readBuff = intent.getByteArrayExtra("readBuffer")

                    // construct a string from the valid bytes in the buffer
                    if(readBuff != null) {
                        val readString = String(readBuff)
                        mViewModel.mConversationArrayAdapter?.add("VIN: $readString")

                        val btMessage = view?.findViewById<ListView>(R.id.bt_message)!!
                        btMessage.setSelection(btMessage.adapter.count - 1)
                    }
                }
                MESSAGE_READ_DTC.toString() -> {
                    val readBuff = intent.getByteArrayExtra("readBuffer")

                    // construct a string from the valid bytes in the buffer
                    if(readBuff != null) {
                        val readString = String(readBuff)
                        mViewModel.mConversationArrayAdapter?.add("DTC: $readString")

                        val btMessage = view?.findViewById<ListView>(R.id.bt_message)!!
                        btMessage.setSelection(btMessage.adapter.count - 1)
                    }
                }
                MESSAGE_ECU_INFO.toString() -> {
                    val readBuff = intent.getByteArrayExtra("readBuffer")

                    if(readBuff != null){
                        val pid = readBuff.copyOfRange(1,3)
                        val value = readBuff.copyOfRange(2, readBuff.size)

                        var name: String = ""

                        ECU_INFO_LIST.forEach { key, value ->
                            if (Arrays.equals(pid, value)) {
                                name = key
                            }
                        }

                        val readString = String(readBuff)
                        mViewModel.mConversationArrayAdapter?.add("$name: $readString")

                        val btMessage = view?.findViewById<ListView>(R.id.bt_message)!!
                        btMessage.setSelection(btMessage.adapter.count -1)
                    }
                }
            }
        }
    }
}