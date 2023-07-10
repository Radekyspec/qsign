package xyz.cssxsh.mirai.tool

import kotlinx.coroutines.*
import kotlinx.serialization.*
import kotlinx.serialization.builtins.*
import kotlinx.serialization.json.*
import net.mamoe.mirai.internal.spi.*
import net.mamoe.mirai.internal.utils.*
import net.mamoe.mirai.utils.*
import org.asynchttpclient.*
import org.asynchttpclient.netty.ws.*
import org.asynchttpclient.ws.*
import java.security.*
import java.security.spec.*
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.*
import javax.crypto.spec.*
import kotlin.coroutines.*

public class ViVo50(
    private val server: String,
    private val serverIdentityKey: String,
    private val authorizationKey: String,
    coroutineContext: CoroutineContext
) : EncryptService, CoroutineScope {

    public companion object {
        @JvmStatic
        internal val logger: MiraiLogger = MiraiLogger.Factory.create(ViVo50::class)
    }

    override val coroutineContext: CoroutineContext =
        coroutineContext + SupervisorJob(coroutineContext[Job]) + CoroutineExceptionHandler { context, exception ->
            when (exception) {
                is kotlinx.coroutines.CancellationException -> {
                    // ...
                }
                else -> {
                    logger.warning({ "with ${context[CoroutineName]}" }, exception)
                }
            }
        }

    private val client = Dsl.asyncHttpClient(
        DefaultAsyncHttpClientConfig.Builder()
            .setKeepAlive(true)
            .setUserAgent("curl/7.61.0")
            .setRequestTimeout(30_000)
            .setConnectTimeout(30_000)
            .setReadTimeout(180_000)
    )

    private val sharedKey = SecretKeySpec(UUID.randomUUID().toString().substring(0, 16).encodeToByteArray(), "AES")

    private val rsaKeyPair: KeyPair = KeyPairGenerator.getInstance("RSA")
        .apply { initialize(4096) }
        .generateKeyPair()

    private lateinit var session: Session

    private lateinit var channel: EncryptService.ChannelProxy

    private var white: List<String> = emptyList()

    private fun <T> ListenableFuture<Response>.getBody(deserializer: DeserializationStrategy<T>): T {
        val response = get()
        return Json.decodeFromString(deserializer, response.responseBody)
    }

    override fun initialize(context: EncryptServiceContext) {
        val device = context.extraArgs[EncryptServiceContext.KEY_DEVICE_INFO]
        val qimei36 = context.extraArgs[EncryptServiceContext.KEY_QIMEI36]
        val protocol = context.extraArgs[EncryptServiceContext.KEY_BOT_PROTOCOL]
        channel = context.extraArgs[EncryptServiceContext.KEY_CHANNEL_PROXY]

        logger.info("Bot(${context.id}) initialize by $server")

        val token = handshake(uin = context.id)
        val session = Session(token = token, bot = context.id)
        session.websocket()
        coroutineContext.job.invokeOnCompletion { session.close() }
        this.session = session
        session.sendCommand(type = "rpc.initialize", deserializer = JsonElement.serializer()) {
            putJsonObject("extArgs") {
                put("KEY_QIMEI36", qimei36)
                putJsonObject("BOT_PROTOCOL") {
                    putJsonObject("protocolValue") {
                        @Suppress("INVISIBLE_MEMBER")
                        put("ver", MiraiProtocolInternal[protocol].ver)
                    }
                }
            }
            putJsonObject("device") {
                put("display", device.display.toUHexString(""))
                put("product", device.product.toUHexString(""))
                put("device", device.device.toUHexString(""))
                put("board", device.board.toUHexString(""))
                put("brand", device.brand.toUHexString(""))
                put("model", device.model.toUHexString(""))
                put("bootloader", device.bootloader.toUHexString(""))
                put("fingerprint", device.fingerprint.toUHexString(""))
                put("bootId", device.bootId.toUHexString(""))
                put("procVersion", device.procVersion.toUHexString(""))
                put("baseBand", device.baseBand.toUHexString(""))
                putJsonObject("version") {
                    put("incremental", device.version.incremental.toUHexString(""))
                    put("release", device.version.release.toUHexString(""))
                    put("codename", device.version.codename.toUHexString(""))
                    put("sdk", device.version.sdk)
                }
                put("simInfo", device.simInfo.toUHexString(""))
                put("osType", device.osType.toUHexString(""))
                put("macAddress", device.macAddress.toUHexString(""))
                put("wifiBSSID", device.wifiBSSID.toUHexString(""))
                put("wifiSSID", device.wifiSSID.toUHexString(""))
                put("imsiMd5", device.imsiMd5.toUHexString(""))
                put("imei", device.imei)
                put("apn", device.apn.toUHexString(""))
                put("androidId", device.androidId.toUHexString(""))
                @OptIn(MiraiInternalApi::class)
                put("guid", device.guid.toUHexString(""))
            }
        }
        session.sendCommand(type = "rpc.get_cmd_white_list", deserializer = ListSerializer(String.serializer())).also {
            white = checkNotNull(it)
        }

        logger.info("Bot(${context.id}) initialize complete")
    }

    private fun handshake(uin: Long): String {
        val config = client.prepareGet("${server}/service/rpc/handshake/config")
            .execute().getBody(HandshakeConfig.serializer())

        val pKeyRsaSha1 = (serverIdentityKey + config.publicKey)
            .toByteArray().sha1().toUHexString("").lowercase()
        val clientKeySignature = (pKeyRsaSha1 + serverIdentityKey)
            .toByteArray().sha1().toUHexString("").lowercase()

        check(clientKeySignature == config.keySignature) {
            "请检查 serverIdentityKey 是否正确。(client calculated key signature doesn't match the server provides.)"
        }

        val secret = buildJsonObject {
            put("authorizationKey", authorizationKey)
            put("sharedKey", sharedKey.encoded.decodeToString())
            put("botid", uin)
        }.let {
            val text = Json.encodeToString(JsonElement.serializer(), it)
            val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
            val publicKey = KeyFactory.getInstance("RSA")
                .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(config.publicKey)))
            cipher.init(Cipher.ENCRYPT_MODE, publicKey)

            cipher.doFinal(text.encodeToByteArray())
        }


        val result = client.preparePost("${server}/service/rpc/handshake/handshake")
            .setBody(Json.encodeToString(JsonElement.serializer(), buildJsonObject {
                put("clientRsa", Base64.getEncoder().encodeToString(rsaKeyPair.public.encoded))
                put("secret", Base64.getEncoder().encodeToString(secret))
            }))
            .execute().getBody(HandshakeResult.serializer())

        check(result.status == 200) { result.reason }

        return Base64.getDecoder().decode(result.token).decodeToString()
    }

    private fun signature(): Pair<String, String> {
        val current = System.currentTimeMillis().toString()
        val privateSignature = Signature.getInstance("SHA256withRSA")
        privateSignature.initSign(rsaKeyPair.private)
        privateSignature.update(current.encodeToByteArray())

        return current to Base64.getEncoder().encodeToString(privateSignature.sign())
    }

    override fun encryptTlv(context: EncryptServiceContext, tlvType: Int, payload: ByteArray): ByteArray? {
        val command = context.extraArgs[EncryptServiceContext.KEY_COMMAND_STR]

        val hex = session.sendCommand(type = "rpc.tlv", deserializer = String.serializer()) {
            put("tlvType", tlvType)
            putJsonObject("extArgs") {
                put("KEY_COMMAND_STR", command)
            }
            put("content", payload.toUHexString(""))
        } ?: return null

        return hex.hexToBytes()
    }

    override fun qSecurityGetSign(
        context: EncryptServiceContext,
        sequenceId: Int,
        commandName: String,
        payload: ByteArray
    ): EncryptService.SignResult? {
        if (white.isEmpty().not() && commandName !in white) return null

        logger.debug("Bot(${context.id}) sign $commandName")

        val response = session.sendCommand(type = "rpc.sign", deserializer = RpcSignResult.serializer()) {
            put("seqId", sequenceId)
            put("command", commandName)
            putJsonObject("extArgs") {
                // ...
            }
            put("content", payload.toUHexString(""))
        } ?: return null

        return EncryptService.SignResult(
            sign = response.sign.hexToBytes(),
            extra = response.extra.hexToBytes(),
            token = response.token.hexToBytes(),
        )
    }

    private inner class Session(val bot: Long, val token: String) : WebSocketListener, AutoCloseable {
        private var websocket0: WebSocket? = null
        private val packet: MutableMap<String, CompletableFuture<JsonObject>> = ConcurrentHashMap()

        override fun onOpen(websocket: WebSocket) {
            websocket0 = websocket
            logger.info("Session(bot=${bot}) opened")
        }

        override fun onClose(websocket: WebSocket, code: Int, reason: String?) {
            websocket0 = null
            if (code != 1_000) logger.warning("Session(bot=${bot}) closed, $code - $reason")
        }

        override fun onError(cause: Throwable) {
            throw IllegalStateException("Session(bot=${bot}) ${if (websocket0 == null) "open fail" else "error"}", cause)
        }

        override fun onBinaryFrame(payload: ByteArray, finalFragment: Boolean, rsv: Int) {
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, sharedKey)
            val text = cipher.doFinal(payload).decodeToString()

            val json = Json.parseToJsonElement(text).jsonObject
            val id = json["packetId"]!!.jsonPrimitive.content
            packet[id]?.complete(json)

            when (json["packetType"]?.jsonPrimitive?.content) {
                "rpc.service.send" -> {
                    val uin = json["botUin"]!!.jsonPrimitive.long
                    val cmd = json["command"]!!.jsonPrimitive.content
                    launch(CoroutineName(id)) {
                        logger.verbose("Bot(${bot}) sendMessage <- $cmd")

                        val result = channel.sendMessage(
                            remark = json["remark"]!!.jsonPrimitive.content,
                            commandName = cmd,
                            uin = uin,
                            data = json["data"]!!.jsonPrimitive.content.hexToBytes()
                        )

                        if (result == null) {
                            logger.debug("Bot(${bot}) ChannelResult is null")
                            return@launch
                        }
                        logger.verbose("Bot(${bot}) sendMessage -> ${result.cmd}")

                        sendPacket(type = "rpc.service.send", id = id) {
                            put("command", result.cmd)
                            put("data", result.data.toUHexString(""))
                        }
                    }
                }
                "service.interrupt" -> {
                    logger.error("Bot(${bot}) $text")
                }
                else -> {
                    // ...
                }
            }
        }

        private fun open(): WebSocket {
            val (timestamp, signature) = signature()
            return client.prepareGet("${server}/service/rpc/session".replace("http", "ws"))
                .addHeader("Authorization", token)
                .addHeader("X-SEC-Time", timestamp)
                .addHeader("X-SEC-Signature", signature)
                .execute(
                    WebSocketUpgradeHandler
                        .Builder()
                        .addWebSocketListener(this)
                        .build()
                )
                .get() ?: throw IllegalStateException("Session(bot=${bot}) open fail")
        }

        private fun check(): WebSocket? {
            val (timestamp, signature) = signature()
            val response = client.prepareGet("${server}/service/rpc/session/check")
                .addHeader("Authorization", token)
                .addHeader("X-SEC-Time", timestamp)
                .addHeader("X-SEC-Signature", signature)
                .execute().get()

            return when (response.statusCode) {
                204 -> websocket0
                404 -> null
                else -> throw IllegalStateException("Session(bot=${bot}) ${response.responseBody}")
            }
        }

        private fun delete() {
            val (timestamp, signature) = signature()
            val response = client.prepareDelete("${server}/service/rpc/session")
                .addHeader("Authorization", token)
                .addHeader("X-SEC-Time", timestamp)
                .addHeader("X-SEC-Signature", signature)
                .execute().get()

            when (response.statusCode) {
                204 -> websocket0 = null
                404 -> throw NoSuchElementException(toString())
                else -> throw IllegalStateException("Session(bot=${bot}) ${response.responseBody}")
            }
        }

        override fun close() {
            try {
                delete()
            } catch (cause: NoSuchElementException) {
                logger.warning(cause)
            } catch (cause: Throwable) {
                logger.error(cause)
            }
            try {
                websocket0?.sendCloseFrame()
            } catch (cause: Throwable) {
                logger.error(cause)
            }
        }

        @Synchronized
        fun websocket(): WebSocket {
            coroutineContext.ensureActive()
            return check() ?: open()
        }

        fun sendPacket(type: String, id: String, block: JsonObjectBuilder.() -> Unit) {
            val packet = buildJsonObject {
                put("packetId", id)
                put("packetType", type)
                block.invoke(this)
            }
            val text = Json.encodeToString(JsonElement.serializer(), packet)
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, sharedKey)
            websocket().sendBinaryFrame(cipher.doFinal(text.encodeToByteArray()))
        }

        fun <T> sendCommand(
            type: String,
            deserializer: DeserializationStrategy<T>,
            block: JsonObjectBuilder.() -> Unit = {}
        ): T? {

            val uuid = UUID.randomUUID().toString()
            val future = CompletableFuture<JsonObject>()
            packet[uuid] = future

            sendPacket(type = type, id = uuid, block = block)

            val json = future.get(60, TimeUnit.SECONDS)

            json["message"]?.jsonPrimitive?.content?.let {
                throw IllegalStateException(it)
            }

            val response = json["response"] ?: return null

            return Json.decodeFromJsonElement(deserializer, response)
        }

        override fun toString(): String {
            return "Session(bot=${bot}, token=${token})"
        }
    }
}

@Serializable
private data class HandshakeConfig(
    @SerialName("publicKey")
    val publicKey: String,
    @SerialName("timeout")
    val timeout: Long,
    @SerialName("keySignature")
    val keySignature: String
)

@Serializable
private data class HandshakeResult(
    @SerialName("status")
    val status: Int,
    @SerialName("reason")
    val reason: String = "",
    @SerialName("token")
    val token: String = ""
)

@Serializable
private data class RpcSignResult(
    @SerialName("sign")
    val sign: String,
    @SerialName("token")
    val token: String,
    @SerialName("extra")
    val extra: String,
)