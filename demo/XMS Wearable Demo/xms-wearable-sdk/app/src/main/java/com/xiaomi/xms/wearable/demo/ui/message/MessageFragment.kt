package com.xiaomi.xms.wearable.demo.ui.message

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.xiaomi.xms.wearable.Wearable
import com.xiaomi.xms.wearable.demo.databinding.FragmentMessageBinding
import com.xiaomi.xms.wearable.message.MessageApi
import com.xiaomi.xms.wearable.message.OnMessageReceivedListener
import com.xiaomi.xms.wearable.node.Node
import com.xiaomi.xms.wearable.node.NodeApi

class MessageFragment : Fragment() {

    companion object {
        private const val TAG = "MessageFragment"
    }

    private lateinit var dashboardViewModel: MessageViewModel
    private var _binding: FragmentMessageBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    private var nodeApi: NodeApi? = null
    private var messageApi:MessageApi? = null
    override fun onAttach(context: Context) {
        super.onAttach(context)
        nodeApi = Wearable.getNodeApi(context.applicationContext)
        messageApi = Wearable.getMessageApi(context.applicationContext)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        dashboardViewModel =
            ViewModelProvider(this).get(MessageViewModel::class.java)

        _binding = FragmentMessageBinding.inflate(inflater, container, false)
        return binding.root
    }


    var curNode: Node? = null

    private fun ByteArray.toHexString(): String =
        joinToString(" ") { eachByte -> "%02X".format(eachByte) }

    private val messageListener: OnMessageReceivedListener =
        OnMessageReceivedListener { nodeId, message ->
            // Колбэк может прилететь на любом потоке — UI трогаем только через runOnUiThread,
            // и только когда фрагмент жив (_binding != null).
            val text = try {
                String(message, Charsets.UTF_8)
            } catch (_: Throwable) {
                "<bin>"
            }
            val hex = message.toHexString()
            val normalized = text.trim().lowercase()

            Log.d(TAG, "onMessageReceived node=$nodeId, bytes=${message.size}, text='$text', hex=$hex")
            setStatusSafe("recv(${message.size}): $text")

            // Отвечаем статусом на ЛЮБОЕ входящее сообщение (надёжнее для отладки).
            // forceRefreshIp, только если пришло слово refresh — иначе берём из кэша.
            sendPhoneStatus(forceRefreshIp = normalized.contains("refresh"))
        }

    private fun setStatusSafe(text: String) {
        val act = activity ?: return
        act.runOnUiThread {
            _binding?.status?.text = text
        }
    }

    private fun sendPhoneStatus(forceRefreshIp: Boolean) {
        val ctx = context?.applicationContext ?: return
        val node = curNode ?: run {
            setStatusSafe("no connected node")
            return
        }

        // Пока используем синхронный сбор в фоне, чтобы учитывать forceRefreshIp.
        Thread {
            val json = PhoneStatusHelper.collectSync(ctx, forceRefreshIp)
            val bytes = json.toString().toByteArray(Charsets.UTF_8)
            val api = messageApi ?: return@Thread
            api.sendMessage(node.id, bytes)
                .addOnSuccessListener {
                    setStatusSafe("sent status: $json")
                }
                .addOnFailureListener { e ->
                    setStatusSafe("send status failed: ${e.message}")
                }
        }.start()
    }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        getConnectedNodes()
        binding.sendMessage.setOnClickListener {
            curNode?.let { node ->
                val message = binding.inputEditText.text.toString()
                if(message.isEmpty()){
                    Toast.makeText(activity,"please input message",Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }else{
                    messageApi?.sendMessage(node.id,message.toByteArray())?.addOnSuccessListener{
                      binding.status.text = "send message success"
                    }?.addOnFailureListener {
                        binding.status.text = "send message message failed ${it.message}"
                    }
                }
            }
        }

        binding.registerMessageListener.setOnClickListener{
           curNode?.let { node ->
               messageApi?.addListener(node.id,messageListener)?.addOnSuccessListener {
                   binding.status.text = "register Message Listener Success"
                   // Сразу после регистрации — отправим актуальный статус
                   sendPhoneStatus(forceRefreshIp = true)
               }?.addOnFailureListener {
                   binding.status.text = "register Message Listener Failed ${it.message}"
               }
           }
        }

        binding.unRegisterMessageListener.setOnClickListener{
            curNode?.let { node ->
                messageApi?.removeListener(node.id)?.addOnSuccessListener {
                    binding.status.text = "unRegister Message Listener Success"
                }?.addOnFailureListener {
                    binding.status.text = "unRegister Message Listener Failed ${it.message}"
                }
            }
        }
    }

    private fun getConnectedNodes() {
        nodeApi?.connectedNodes?.addOnSuccessListener {
            if (it.size > 0) {
                curNode = it[0]
                binding.status.text = "current node is ${curNode.toString()}"
            }
        }?.addOnFailureListener {
            binding.status.text = "get connected devices failed ${it.message}"
        }
    }


    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}