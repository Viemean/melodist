package org.melodist.api

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer

internal object MqttProtocol {
    private const val CONNECT: Byte = 0x10
    private const val CONNACK: Byte = 0x20
    private const val SUBSCRIBE: Byte = (0x82).toByte()
    private const val SUBACK: Byte = (0x90).toByte()
    private const val PUBLISH: Byte = 0x30

    private const val AUTH_METHOD: Byte = 0x15
    private const val USER_PROPERTY: Byte = 0x26
    private const val SERVER_REFERENCE: Byte = 0x1C
    private const val REASON_STRING: Byte = 0x1F

    /**
     * 构建 MQTT 5.0 CONNECT 控制报文。
     *
     * @param clientId 客户端唯一标识符
     * @param authMethod 扩展认证方法名称，未启用增强认证时传 `null`
     * @param userProperties 用户自定义属性键值对列表
     * @param keepAlive 保活心跳周期（秒），默认 45 秒
     * @return 编码后的完整二进制报文字节数组
     */
    fun buildConnectPacket(
        clientId: String,
        authMethod: String? = null,
        userProperties: List<Pair<String, String>> = emptyList(),
        keepAlive: Int = 45,
    ): ByteArray {
        val payload = ByteArrayOutputStream()
        val dos = DataOutputStream(payload)

        dos.writeShort(4)
        dos.write("MQTT".toByteArray(Charsets.UTF_8))
        dos.writeByte(5)
        dos.writeByte(0x02)
        dos.writeShort(keepAlive)

        val props = buildProperties(authMethod, userProperties)
        writeVariableByteInt(dos, props.size)
        dos.write(props)

        dos.writeShort(clientId.length)
        dos.write(clientId.toByteArray(Charsets.UTF_8))

        val variableAndPayload = payload.toByteArray()
        val packet = ByteArrayOutputStream()
        packet.write(CONNECT.toInt())
        writeVariableByteInt(DataOutputStream(packet), variableAndPayload.size)
        packet.write(variableAndPayload)
        return packet.toByteArray()
    }

    /**
     * 构建 MQTT 5.0 SUBSCRIBE 订阅报文。
     *
     * @param packetId 报文标识符
     * @param topic 待订阅的主题过滤器字符串
     * @param userProperties 用户自定义属性键值对列表
     * @return 编码后的完整二进制订阅报文字节数组
     */
    fun buildSubscribePacket(
        packetId: Int,
        topic: String,
        userProperties: List<Pair<String, String>> = emptyList(),
    ): ByteArray {
        val payload = ByteArrayOutputStream()
        val dos = DataOutputStream(payload)

        dos.writeShort(packetId)

        val props = buildProperties(null, userProperties)
        writeVariableByteInt(dos, props.size)
        dos.write(props)

        dos.writeShort(topic.length)
        dos.write(topic.toByteArray(Charsets.UTF_8))
        dos.writeByte(0)

        val variableAndPayload = payload.toByteArray()
        val packet = ByteArrayOutputStream()
        packet.write(SUBSCRIBE.toInt())
        writeVariableByteInt(DataOutputStream(packet), variableAndPayload.size)
        packet.write(variableAndPayload)
        return packet.toByteArray()
    }

    data class MqttMessage(
        val type: Byte,
        val serverReference: String? = null,
        val userProperties: Map<String, String> = emptyMap(),
        val payload: ByteArray? = null,
    )

    /**
     * 解析接收到的 MQTT 协议二进制报文并提取消息结构。
     *
     * @param data 原始字节数组
     * @return 解码后的 [MqttMessage] 实例；当输入为空或解析遇到未识别报文时返回对应的基本消息或 `null`
     */
    fun parsePacket(data: ByteArray): MqttMessage? {
        if (data.isEmpty()) return null
        val buf = ByteBuffer.wrap(data)
        val typeByte = (buf.get().toInt() and 0xF0).toByte()
        decodeVariableByteInt(buf)

        return when (typeByte) {
            CONNACK -> parseConnack(buf)
            SUBACK -> MqttMessage(type = SUBACK)
            PUBLISH -> parsePublish(buf)
            else -> MqttMessage(type = typeByte)
        }
    }

    private fun parseConnack(buf: ByteBuffer): MqttMessage {
        buf.get()
        val reasonCode = buf.get().toInt() and 0xFF

        if (buf.remaining() <= 0) return MqttMessage(CONNACK)

        val propsLen = decodeVariableByteInt(buf)
        val propsEnd = buf.position() + propsLen
        var serverRef: String? = null

        while (buf.position() < propsEnd && buf.hasRemaining()) {
            when (buf.get()) {
                SERVER_REFERENCE -> serverRef = readUtf8String(buf)
                REASON_STRING -> readUtf8String(buf)
                USER_PROPERTY -> {
                    readUtf8String(buf)
                    readUtf8String(buf)
                }
                else -> break
            }
        }

        if (reasonCode == 0x9D && serverRef != null) {
            return MqttMessage(CONNACK, serverRef)
        }
        return MqttMessage(CONNACK)
    }

    private fun parsePublish(buf: ByteBuffer): MqttMessage {
        readUtf8String(buf)
        val propsLen = decodeVariableByteInt(buf)
        val propsEnd = buf.position() + propsLen
        val userProps = mutableMapOf<String, String>()

        while (buf.position() < propsEnd && buf.hasRemaining()) {
            when (buf.get()) {
                USER_PROPERTY -> {
                    val key = readUtf8String(buf)
                    val value = readUtf8String(buf)
                    userProps[key] = value
                }
                else -> break
            }
        }
        val payload = ByteArray(buf.remaining())
        buf.get(payload)
        return MqttMessage(PUBLISH, null, userProps, payload)
    }

    private fun buildProperties(
        authMethod: String?,
        userProperties: List<Pair<String, String>>,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val dos = DataOutputStream(out)
        if (authMethod != null) {
            dos.writeByte(AUTH_METHOD.toInt())
            dos.writeShort(authMethod.length)
            dos.write(authMethod.toByteArray(Charsets.UTF_8))
        }
        for ((key, value) in userProperties) {
            dos.writeByte(USER_PROPERTY.toInt())
            dos.writeShort(key.length)
            dos.write(key.toByteArray(Charsets.UTF_8))
            dos.writeShort(value.length)
            dos.write(value.toByteArray(Charsets.UTF_8))
        }
        return out.toByteArray()
    }

    private fun writeVariableByteInt(
        dos: DataOutputStream,
        value: Int,
    ) {
        var v = value
        do {
            var byte = v % 128
            v /= 128
            if (v > 0) byte = byte or 0x80
            dos.writeByte(byte)
        } while (v > 0)
    }

    private fun decodeVariableByteInt(buf: ByteBuffer): Int {
        var value = 0
        var multiplier = 1
        var byte: Int
        do {
            byte = buf.get().toInt() and 0xFF
            value += (byte and 0x7F) * multiplier
            multiplier *= 128
        } while (byte and 0x80 != 0)
        return value
    }

    private fun readUtf8String(buf: ByteBuffer): String {
        val len = buf.short.toInt() and 0xFFFF
        val bytes = ByteArray(len)
        buf.get(bytes)
        return String(bytes, Charsets.UTF_8)
    }
}
