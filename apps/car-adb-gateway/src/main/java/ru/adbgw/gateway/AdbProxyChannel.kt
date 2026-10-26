package ru.adbgw.gateway

import dev.denza.disharebridge.LocalAdbClient
import org.apache.sshd.client.future.DefaultOpenFuture
import org.apache.sshd.client.future.OpenFuture
import org.apache.sshd.common.SshConstants
import org.apache.sshd.common.channel.Channel
import org.apache.sshd.common.channel.ChannelFactory
import org.apache.sshd.common.channel.ChannelOutputStream
import org.apache.sshd.common.channel.ChannelPipedInputStream
import org.apache.sshd.common.channel.exception.SshChannelOpenException
import org.apache.sshd.common.session.Session
import org.apache.sshd.common.util.buffer.Buffer
import org.apache.sshd.server.channel.AbstractServerChannel
import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

const val RAW_ADBD_PORT = 5555
const val SMART_SOCKET_PORT = 5037
private const val ADBD_CONNECT_TIMEOUT_MS = 3_000
private const val PIPE_BUFFER = 32 * 1024

/**
 * Produces the inner gateway's only `direct-tcpip` channel: the in-process ADB proxy.
 *
 * <p>No listening socket is created; every ADB channel is a tunnelled `direct-tcpip` open that this
 * factory turns into an [AdbProxyChannel]. There is exactly one listener on the head unit,
 * `127.0.0.1:2222` (CAG-001); any process that reaches a loopback port otherwise gets nothing.
 */
class AdbDirectTcpipFactory(
    private val endpointProvider: () -> AdbEndpoint?,
    private val adbHandshake: AdbHandshakeProxy,
    private val onSupportEvent: (String) -> Unit,
) : ChannelFactory {
    override fun getName(): String = "direct-tcpip"

    override fun createChannel(session: Session): Channel =
        AdbProxyChannel(endpointProvider, adbHandshake, onSupportEvent)
}

/**
 * A `direct-tcpip` channel that terminates ADB on the car side.
 *
 * <p>For port [RAW_ADBD_PORT] it authenticates to raw adbd with the app's own ADB key (so the
 * remote computer never has to approve a second prompt on a car nobody is sitting in), then pipes.
 * For port [SMART_SOCKET_PORT] it is a plain pass-through to the current endpoint. Any other port
 * is refused. The requested host is ignored; the channel always connects to the endpoint the
 * supervisor currently holds, since the CLI froze the host at pairing.
 *
 * <p>Window accounting: incoming client bytes are consumed by the base channel as they arrive and
 * handed to [pipeIn]; the worker releases the local window — and so sends `WINDOW_ADJUST` — only as
 * it reads them, so the computer cannot outrun adbd. Outgoing bytes go through [pipeOut]
 * ([ChannelOutputStream]), which blocks on the remote window and chunks by packet size. EOF in
 * either direction half-closes the other: the client's `CHANNEL_EOF` becomes adbd's TCP
 * shut-down-output; adbd's end-of-stream closes [pipeOut], which sends `CHANNEL_EOF` to the client.
 * When either side fully ends, the channel closes gracefully and the socket is torn down.
 */
class AdbProxyChannel(
    private val endpointProvider: () -> AdbEndpoint?,
    private val adbHandshake: AdbHandshakeProxy,
    private val onSupportEvent: (String) -> Unit,
) : AbstractServerChannel("direct-tcpip", emptyList(), null) {
    private val streamLog = LoggerFactory.getLogger("CarAdbGateway")
    private val closing = AtomicBoolean(false)

    @Volatile private var adbdSocket: Socket? = null
    @Volatile private var pipeIn: ChannelPipedInputStream? = null
    @Volatile private var pipeOut: ChannelOutputStream? = null

    override fun doInit(buffer: Buffer): OpenFuture {
        val future = DefaultOpenFuture(this, this)
        buffer.string // requested host: frozen at pairing, deliberately ignored
        val requestedPort = buffer.int
        buffer.string // originator address
        buffer.int // originator port

        val terminate = when (requestedPort) {
            RAW_ADBD_PORT -> true
            SMART_SOCKET_PORT -> false
            else -> {
                onSupportEvent("Отклонён канал к порту $requestedPort")
                future.setException(
                    SshChannelOpenException(
                        channelId,
                        SshConstants.SSH_OPEN_ADMINISTRATIVELY_PROHIBITED,
                        "Connection denied",
                    ),
                )
                close(true)
                return future
            }
        }

        val endpoint = endpointProvider()
        if (endpoint == null) {
            onSupportEvent("Канал ADB отклонён: адрес ADB ещё не найден")
            future.setException(
                SshChannelOpenException(
                    channelId,
                    SshConstants.SSH_OPEN_CONNECT_FAILED,
                    "ADB endpoint is not ready",
                ),
            )
            close(true)
            return future
        }

        val incoming = ChannelPipedInputStream(this, localWindow)
        val outgoing = ChannelOutputStream(
            this,
            remoteWindow,
            streamLog,
            SshConstants.SSH_MSG_CHANNEL_DATA,
            true,
        )
        pipeIn = incoming
        pipeOut = outgoing

        signalChannelOpenSuccess()
        future.setOpened()

        val worker = Thread(
            { runProxy(endpoint, terminate, incoming, outgoing) },
            "cag-adb-proxy-$channelId",
        )
        worker.isDaemon = true
        worker.start()

        notifyStateChanged("openSuccess")
        return future
    }

    private fun runProxy(
        endpoint: AdbEndpoint,
        terminate: Boolean,
        incoming: ChannelPipedInputStream,
        outgoing: ChannelOutputStream,
    ) {
        val socket = Socket()
        try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(endpoint.host, endpoint.port), ADBD_CONNECT_TIMEOUT_MS)
        } catch (error: IOException) {
            onSupportEvent("Не удалось подключиться к ADB ${endpoint.host}:${endpoint.port}")
            closeQuietly(socket)
            terminate()
            return
        }
        adbdSocket = socket
        val adbdIn = socket.getInputStream()
        val adbdOut = socket.getOutputStream()

        if (terminate) {
            try {
                adbHandshake.terminate(incoming, outgoing, adbdIn, adbdOut)
            } catch (error: LocalAdbClient.AdbProxySignatureRejectedException) {
                onSupportEvent("ADB отклонил ключ приложения; канал закрыт")
                terminate()
                return
            } catch (error: LocalAdbClient.AdbProxyTlsException) {
                onSupportEvent("ADB запросил STLS; канал закрыт")
                terminate()
                return
            } catch (error: Throwable) {
                onSupportEvent("Рукопожатие ADB не удалось: ${error.message ?: error.javaClass.simpleName}")
                terminate()
                return
            }
        }

        val reader = Thread({ pumpAdbdToClient(adbdIn, outgoing) }, "cag-adb-rx-$channelId")
        reader.isDaemon = true
        reader.start()
        pumpClientToAdbd(incoming, adbdOut, socket)
    }

    /** adbd -> client. Ends the channel when adbd closes. */
    private fun pumpAdbdToClient(adbdIn: InputStream, outgoing: ChannelOutputStream) {
        val buffer = ByteArray(PIPE_BUFFER)
        try {
            while (true) {
                val read = adbdIn.read(buffer)
                if (read < 0) break
                outgoing.write(buffer, 0, read)
                outgoing.flush()
            }
            runCatching { outgoing.close() } // sends CHANNEL_EOF to the client
        } catch (error: IOException) {
            // adbd socket or the channel closed under us
        } finally {
            terminate()
        }
    }

    /** client -> adbd. A client EOF half-closes adbd's write side. */
    private fun pumpClientToAdbd(incoming: InputStream, adbdOut: OutputStream, socket: Socket) {
        val buffer = ByteArray(PIPE_BUFFER)
        try {
            while (true) {
                val read = incoming.read(buffer)
                if (read < 0) break
                adbdOut.write(buffer, 0, read)
                adbdOut.flush()
            }
            runCatching { socket.shutdownOutput() }
        } catch (error: IOException) {
            // the channel or adbd socket closed under us
            terminate()
        }
    }

    override fun handleEof() {
        super.handleEof()
        runCatching { pipeIn?.eof() }
    }

    override fun doWriteData(data: ByteArray, off: Int, len: Long) {
        // The base already consumed the local window for these bytes; the pipe releases it as the
        // worker reads them.
        pipeIn?.receive(data, off, len.toInt())
    }

    override fun doWriteExtendedData(data: ByteArray, off: Int, len: Long) {
        throw UnsupportedOperationException("direct-tcpip channel does not support extended data")
    }

    private fun terminate() {
        if (closing.compareAndSet(false, true)) {
            close(false)
        }
    }

    override fun preClose() {
        closing.set(true)
        closeQuietly(adbdSocket)
        runCatching { pipeIn?.close() }
        runCatching { pipeOut?.close() }
        super.preClose()
    }

    private fun closeQuietly(socket: Socket?) {
        if (socket == null) return
        runCatching { socket.close() }
    }
}
