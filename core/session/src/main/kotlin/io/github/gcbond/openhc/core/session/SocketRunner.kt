package io.github.gcbond.openhc.core.session

import java.io.Closeable
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

/**
 * 用 java.net 阻塞 I/O 执行 [SessionLogic] 给出的动作，安卓和电脑通用（不引用安卓类）。
 *
 * 线程：
 * - 一个**协议线程**：会话的所有方法、全部写操作、事件回调都在这个线程上串行执行；
 * - 每个监听、连接、UDP socket 一个线程：监听线程 accept 到连接后就接着当这条连接的读线程，
 *   连接线程 connect 成功后同样接着读。读到的数据经队列交给协议线程。
 * - 每个监听只接受一条连接；之后监听仍开着（端口留着，不被系统分给别的监听），另起一个线程把再连进来的立刻关掉并记日志，
 *   免得对端以为连上了、却一直没人理。
 *
 * 读缓冲：每条连接固定几块缓冲轮流用，协议线程处理完一块才还回去，不为每次读另外分配或复制；
 * 协议线程跟不上时读线程会停下来等，由 TCP 自己做流控，内存有上限。
 *
 * 关闭顺序：会话结束时自己会按层由上到下输出关闭动作（见 [ReceiverSession]），执行器照做；
 * 之后协议线程再把还没关的资源按创建的反序关掉（正常情况下已经没有了），等所有线程退出，才算结束。
 * [close] 先请会话正常收尾，超时（例如某次写被对端卡住）就从调用线程直接关掉全部 socket。
 *
 * 用法：`start()` 之后，用 [stop] 请求结束（非阻塞），[awaitTermination] 等结束；或者直接 [close]。
 */
class SocketRunner(
    private val session: SessionLogic,
    /** 会话事件，在协议线程上回调；回调里不要阻塞太久，抛出的异常只记日志。 */
    private val events: (SessionEvent) -> Unit,
    private val options: Options = Options(),
) : Closeable {

    class Options(
        /** 主动连接的超时（毫秒）。 */
        val connectTimeoutMillis: Int = 3_000,
        /** 每块读缓冲的大小。 */
        val readBufferBytes: Int = 64 * 1024,
        /** 每条连接轮流使用的读缓冲块数。 */
        val buffersPerConnection: Int = 2,
        val listenBacklog: Int = 4,
        /** [close] 等会话正常收尾的时间，以及收尾后等各线程退出的时间（毫秒）。 */
        val stopGraceMillis: Long = 3_000,
        /** 线程名前缀。 */
        val threadName: String = "openhc-session",
        /** 执行器自己的诊断日志（不含收发的内容）。 */
        val log: (String) -> Unit = {},
        /** 执行器已彻底结束（全部资源与线程收尾完毕）后的可选通知回调，省去外部单独阻塞等待守护线程。 */
        val onTerminated: () -> Unit = {},
    ) {
        init {
            require(connectTimeoutMillis > 0) { "connectTimeoutMillis 必须为正" }
            require(readBufferBytes >= 1024) { "readBufferBytes 至少 1024" }
            require(buffersPerConnection >= 1) { "buffersPerConnection 至少 1" }
            require(listenBacklog >= 1) { "listenBacklog 至少 1" }
            require(stopGraceMillis > 0) { "stopGraceMillis 必须为正" }
        }
    }

    private sealed class Input {
        class Accepted(val id: Int, val remoteHost: String) : Input()
        class Connected(val id: Int) : Input()
        class Data(val id: Int, val buffer: ByteArray, val length: Int, val pool: BufferPool) : Input()
        class Datagram(val id: Int, val buffer: ByteArray, val length: Int, val pool: BufferPool) : Input()
        class Closed(val id: Int) : Input()
        class Failed(val id: Int, val message: String) : Input()
        class Task(val block: (Long) -> List<SessionOutput>) : Input()
        object Stop : Input()
    }

    /** 双优先级输入队列：控制、音频与交互任务优先于视频大数据块出队；同优先级内部严格先进先出。 */
    private class InputQueue {
        private val lock = ReentrantLock()
        private val notEmpty = lock.newCondition()
        private val high = java.util.ArrayDeque<Input>()
        private val normal = java.util.ArrayDeque<Input>()

        fun add(input: Input, highPriority: Boolean) {
            lock.lock()
            try {
                val wasEmpty = high.isEmpty() && normal.isEmpty()
                if (highPriority) high.addLast(input) else normal.addLast(input)
                if (wasEmpty) notEmpty.signal()
            } finally {
                lock.unlock()
            }
        }

        fun poll(): Input? {
            lock.lock()
            try {
                return high.pollFirst() ?: normal.pollFirst()
            } finally {
                lock.unlock()
            }
        }

        fun poll(timeoutMillis: Long): Input? {
            var remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMillis.coerceAtLeast(0))
            lock.lock()
            try {
                while (true) {
                    val next = high.pollFirst() ?: normal.pollFirst()
                    if (next != null) return next
                    if (remaining <= 0L) return null
                    remaining = notEmpty.awaitNanos(remaining)
                }
            } finally {
                lock.unlock()
            }
        }
    }

    /** 一条连接的读缓冲：固定几块轮流用。资源关闭时放入零长哨兵唤醒阻塞读线程，日常无超时轮询开销。 */
    private class BufferPool(count: Int, size: Int) {
        private val free = ArrayBlockingQueue<ByteArray>(count + 1)
        private val closedFlag = AtomicBoolean(false)

        init {
            repeat(count) { free.add(ByteArray(size)) }
        }

        /** 等一块空闲缓冲；资源已关闭时返回 null。 */
        fun take(closed: AtomicBoolean): ByteArray? {
            if (closed.get() || closedFlag.get()) return null
            val buffer = try {
                free.take()
            } catch (e: InterruptedException) {
                return null
            }
            if (buffer === SENTINEL || closed.get() || closedFlag.get()) {
                if (buffer === SENTINEL) free.offer(SENTINEL)
                return null
            }
            return buffer
        }

        fun give(buffer: ByteArray) {
            if (!closedFlag.get() && buffer !== SENTINEL) {
                free.offer(buffer)
            }
        }

        fun close() {
            if (closedFlag.compareAndSet(false, true)) {
                free.clear()
                free.offer(SENTINEL)
            }
        }

        private companion object {
            val SENTINEL = ByteArray(0)
        }
    }

    private sealed class WriteItem {
        class Bytes(val data: ByteArray) : WriteItem()
        object End : WriteItem()
    }

    private abstract inner class Resource(val id: Int) {
        /** 先置位再关 socket：读线程据此知道异常是本机关闭造成的，不再上报。 */
        val closed = AtomicBoolean(false)

        open fun close() = forceClose()

        fun forceClose() {
            if (closed.compareAndSet(false, true)) {
                closeSocket()
                onForceClose()
                synchronized(lock) { allResources.remove(this) }
            }
        }

        protected open fun onForceClose() {}

        protected abstract fun closeSocket()
    }

    private inner class ListenerResource(id: Int, val server: ServerSocket) : Resource(id) {
        override fun closeSocket() = closeQuietly(server)
    }

    private inner class ConnectionResource(
        id: Int,
        val channel: SessionChannel,
        val socket: Socket,
        buffers: Int,
        size: Int,
    ) : Resource(id) {
        val pool = BufferPool(buffers, size)
        private val writeQueue = ArrayBlockingQueue<WriteItem>(MAX_QUEUED_WRITES)
        private val writerStarted = AtomicBoolean(false)

        fun enqueueWrite(bytes: ByteArray): Boolean {
            if (closed.get()) return true
            if (writerStarted.compareAndSet(false, true)) {
                val writer = newThread(channel, id) { writeLoop() }
                val keep = synchronized(lock) {
                    val ok = !closingAll && !closed.get()
                    if (ok) threads += writer
                    ok
                }
                if (keep) writer.start() else return true
            }
            return writeQueue.offer(WriteItem.Bytes(bytes))
        }

        override fun close() {
            if (abandoned || !writerStarted.get()) {
                forceClose()
                return
            }
            if (!writeQueue.offer(WriteItem.End)) forceClose()
        }

        override fun onForceClose() {
            pool.close()
            writeQueue.clear()
            writeQueue.offer(WriteItem.End)
        }

        private fun writeLoop() {
            val out = try {
                socket.getOutputStream()
            } catch (e: IOException) {
                if (!closed.get()) post(Input.Failed(id, "取输出流失败：${describe(e)}"), channel)
                forceClose()
                return
            }
            var coalesceBuf: ByteArray? = null
            while (!abandoned && !closed.get()) {
                val item = try {
                    writeQueue.take()
                } catch (e: InterruptedException) {
                    break
                }
                when (item) {
                    is WriteItem.Bytes -> {
                        if (closed.get()) break
                        val first = item.data
                        val next = writeQueue.peek() as? WriteItem.Bytes
                        var payload = first
                        var payloadLen = first.size
                        if (next != null && first.size + next.data.size <= WRITE_COALESCE_BYTES) {
                            val buf = coalesceBuf ?: ByteArray(WRITE_COALESCE_BYTES).also { coalesceBuf = it }
                            System.arraycopy(first, 0, buf, 0, first.size)
                            var filled = first.size
                            while (true) {
                                val peeked = writeQueue.peek() as? WriteItem.Bytes ?: break
                                if (filled + peeked.data.size > WRITE_COALESCE_BYTES) break
                                writeQueue.poll()
                                System.arraycopy(peeked.data, 0, buf, filled, peeked.data.size)
                                filled += peeked.data.size
                            }
                            payload = buf
                            payloadLen = filled
                        }
                        try {
                            out.write(payload, 0, payloadLen)
                        } catch (e: IOException) {
                            if (!closed.get()) {
                                post(Input.Failed(id, "写 $payloadLen 字节失败：${describe(e)}"), channel)
                            }
                            break
                        }
                    }
                    WriteItem.End -> break
                }
            }
            forceClose()
        }

        override fun closeSocket() = closeQuietly(socket)
    }

    private inner class UdpResource(
        id: Int,
        val channel: SessionChannel,
        val socket: DatagramSocket,
        buffers: Int,
        size: Int,
    ) : Resource(id) {
        val pool = BufferPool(buffers, size)
        val recvPacket = DatagramPacket(ByteArray(0), 0)
        override fun onForceClose() = pool.close()
        override fun closeSocket() = closeQuietly(socket)
    }

    private val queue = InputQueue()

    /** 同步动作的结果（监听开好、写失败等），在当前这批动作执行完后、处理下一个外部输入之前交给会话。只在协议线程上用。 */
    private val immediate = java.util.ArrayDeque<() -> List<SessionOutput>>()

    private val lock = Any()
    private val resources = ConcurrentHashMap<Int, Resource>()
    private val allResources = LinkedHashSet<Resource>()
    private val threads = LinkedHashSet<Thread>()
    private var closingAll = false
    private var started = false

    @Volatile
    private var loopThread: Thread? = null

    /** [close] 等不到正常收尾时置位：协议线程不再等会话结束，直接退出。 */
    @Volatile
    private var abandoned = false
    private val terminated = CountDownLatch(1)

    /** 协议线程因意外异常结束时的异常（会话自己的错误不算，那些以 [SessionEvent.Ended] 结束）。 */
    @Volatile
    var failure: Throwable? = null
        private set

    val isTerminated: Boolean get() = terminated.count == 0L

    /** 启动协议线程；会话的 [SessionLogic.start] 在协议线程上调用。只能调用一次。 */
    fun start() {
        synchronized(lock) {
            check(!started) { "start 只能调用一次" }
            started = true
        }
        val thread = Thread({ runLoop() }, options.threadName)
        thread.isDaemon = true
        thread.priority = Thread.MAX_PRIORITY
        loopThread = thread
        thread.start()
    }

    /** 请求本机结束会话（会话按协议收尾）。不阻塞，可以从任何线程调用、重复调用。 */
    fun stop() {
        queue.add(Input.Stop, highPriority = true)
    }

    /** 在协议线程上执行 [task]（例如 `session.sendInput(…)`），执行它返回的动作。参数是当前单调时钟毫秒。 */
    fun submit(task: (Long) -> List<SessionOutput>) {
        queue.add(Input.Task(task), highPriority = true)
    }

    /** 等协议线程结束（全部资源已关闭、全部线程已退出）。 */
    fun awaitTermination(timeoutMillis: Long): Boolean = terminated.await(timeoutMillis, TimeUnit.MILLISECONDS)

    /**
     * 结束并等待：先 [stop] 请会话正常收尾；[Options.stopGraceMillis] 内没结束，就从当前线程直接关掉全部 socket
     * （卡住的写随之失败），再等一次。在协议线程上（例如事件回调里）调用时只请求结束、不等待。
     */
    override fun close() {
        var unstarted = false
        synchronized(lock) {
            if (!started) {
                started = true
                unstarted = true
            }
        }
        if (unstarted) {
            terminated.countDown()
            notifyTerminated()
            return
        }
        stop()
        if (Thread.currentThread() === loopThread) return
        if (awaitTermination(options.stopGraceMillis)) return
        options.log("会话没有按时结束，强制关闭全部连接")
        abandoned = true
        val all = synchronized(lock) { allResources.toList() }
        for (resource in all) resource.forceClose()
        queue.add(Input.Stop, highPriority = true)
        if (!awaitTermination(options.stopGraceMillis)) options.log("强制关闭后协议线程仍未结束")
    }

    // ---------------- 协议线程 ----------------

    private fun runLoop() {
        try {
            execute(session.start(now()))
            drainImmediate()
            while (!session.isEnded && !abandoned) {
                var input = queue.poll(waitMillis())
                var burst = 0
                while (input != null && !session.isEnded && !abandoned) {
                    dispatch(input)
                    drainImmediate()
                    burst++
                    if (burst >= MAX_DRAIN_BURST) {
                        burst = 0
                        val due = session.nextTickAtMillis
                        if (!session.isEnded && due != null) {
                            val currentNow = now()
                            if (currentNow >= due) {
                                execute(session.onTick(currentNow))
                                drainImmediate()
                            }
                        }
                    }
                    input = queue.poll()
                }
                if (abandoned) break
                val due = session.nextTickAtMillis
                if (!session.isEnded && due != null) {
                    val now = now()
                    if (now >= due) {
                        execute(session.onTick(now))
                        drainImmediate()
                    }
                }
            }
        } catch (t: Throwable) {
            failure = t
            options.log("协议线程意外结束：${describe(t)}")
        } finally {
            closeEverything()
            joinThreads()
            forceCloseAll()
            terminated.countDown()
            notifyTerminated()
        }
    }

    private fun notifyTerminated() {
        try {
            options.onTerminated()
        } catch (e: Exception) {
            options.log("结束回调抛出异常（${describe(e)}），已忽略")
        }
    }

    private fun waitMillis(): Long {
        val due = session.nextTickAtMillis ?: return IDLE_WAIT_MILLIS
        return (due - now()).coerceIn(0, IDLE_WAIT_MILLIS)
    }

    private fun dispatch(input: Input) {
        when (input) {
            is Input.Accepted -> execute(session.onAccepted(input.id, input.remoteHost, now()))
            is Input.Connected -> execute(session.onConnected(input.id, now()))
            is Input.Data -> {
                val outputs = try {
                    session.onData(input.id, input.buffer, 0, input.length, now())
                } finally {
                    input.pool.give(input.buffer)
                }
                execute(outputs)
            }
            is Input.Datagram -> {
                val outputs = try {
                    session.onDatagram(input.id, input.buffer, 0, input.length, now())
                } finally {
                    input.pool.give(input.buffer)
                }
                execute(outputs)
            }
            is Input.Closed -> execute(session.onClosed(input.id, now()))
            is Input.Failed -> execute(session.onIoError(input.id, input.message, now()))
            is Input.Task -> execute(input.block(now()))
            Input.Stop -> execute(session.stop(now()))
        }
    }

    private fun drainImmediate() {
        while (immediate.isNotEmpty() && !session.isEnded && !abandoned) {
            execute(immediate.removeFirst().invoke())
        }
        immediate.clear()
    }

    private fun execute(outputs: List<SessionOutput>) {
        for (output in outputs) {
            when (output) {
                is SessionAction.Listen -> listen(output)
                is SessionAction.Connect -> connect(output)
                is SessionAction.Write -> write(output)
                is SessionAction.Close -> closeResource(output.id)
                is SessionAction.OpenUdp -> openUdp(output)
                is SessionAction.BindUdp -> bindUdp(output)
                is SessionAction.SendDatagram -> sendDatagram(output)
                is SessionEvent -> deliver(output)
            }
        }
    }

    private fun deliver(event: SessionEvent) {
        try {
            events(event)
        } catch (e: Exception) {
            options.log("事件回调抛出异常（${describe(e)}），已忽略")
        }
    }

    private fun listen(action: SessionAction.Listen) {
        val server = ServerSocket()
        try {
            server.reuseAddress = true
            server.bind(InetSocketAddress(InetAddress.getByName(action.host), action.port), options.listenBacklog)
        } catch (e: IOException) {
            closeQuietly(server)
            val message = "在 ${action.host}:${action.port} 开监听失败：${describe(e)}"
            immediate.add { session.onIoError(action.id, message, now()) }
            return
        }
        val listener = ListenerResource(action.id, server)
        val thread = newThread(action.channel, action.id) { acceptLoop(listener, action) }
        if (!register(listener, thread)) {
            closeQuietly(server)
            return
        }
        thread.start()
        val port = server.localPort
        immediate.add { session.onListening(action.id, port, now()) }
    }

    private fun connect(action: SessionAction.Connect) {
        val socket = Socket()
        try {
            socket.tcpNoDelay = true
            applySocketQos(socket, action.channel)
            if (action.channel == SessionChannel.VIDEO && isDefaultBuffering()) {
                socket.receiveBufferSize = VIDEO_SOCKET_RCVBUF_BYTES
            }
        } catch (e: IOException) {
            // 个别平台不支持设套接字选项时照常连接。
        }
        val connection = ConnectionResource(
            action.id,
            action.channel,
            socket,
            buffersFor(action.channel),
            bufferSizeFor(action.channel),
        )
        val thread = newThread(action.channel, action.id) {
            try {
                socket.connect(InetSocketAddress(InetAddress.getByName(action.host), action.port), options.connectTimeoutMillis)
                try {
                    socket.tcpNoDelay = true
                    applySocketQos(socket, action.channel)
                } catch (e: IOException) {
                    // 同上。
                }
            } catch (e: IOException) {
                if (!connection.closed.get()) {
                    post(Input.Failed(action.id, "连接 ${action.host}:${action.port} 失败：${describe(e)}"), action.channel)
                }
                return@newThread
            }
            post(Input.Connected(action.id), action.channel)
            readLoop(connection)
        }
        if (!register(connection, thread)) {
            closeQuietly(socket)
            return
        }
        thread.start()
    }

    private fun write(action: SessionAction.Write) {
        val connection = lookup(action.id) as? ConnectionResource ?: return
        if (!connection.enqueueWrite(action.bytes)) {
            connection.forceClose()
            val message = "写 ${action.bytes.size} 字节失败：发送队列已满"
            immediate.add { session.onIoError(action.id, message, now()) }
        }
    }

    private fun openUdp(action: SessionAction.OpenUdp) {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket(InetSocketAddress(InetAddress.getByName(action.localHost), 0))
            applyDatagramQos(socket, action.channel)
            socket.connect(InetSocketAddress(InetAddress.getByName(action.remoteHost), action.remotePort))
        } catch (e: IOException) {
            socket?.let { closeQuietly(it) }
            val message = "开 UDP（${action.localHost} → ${action.remoteHost}:${action.remotePort}）失败：${describe(e)}"
            immediate.add { session.onIoError(action.id, message, now()) }
            return
        }
        val udp = UdpResource(action.id, action.channel, socket, buffersFor(action.channel), udpBufferSizeFor(action.channel))
        val thread = newThread(action.channel, action.id) { udpLoop(udp) }
        if (!register(udp, thread)) {
            closeQuietly(socket)
            return
        }
        thread.start()
    }

    private fun bindUdp(action: SessionAction.BindUdp) {
        val socket: DatagramSocket
        try {
            socket = DatagramSocket(InetSocketAddress(InetAddress.getByName(action.localHost), action.localPort))
            applyDatagramQos(socket, action.channel)
        } catch (e: IOException) {
            val message = "绑 UDP（${action.localHost}:${action.localPort}）失败：${describe(e)}"
            immediate.add { session.onIoError(action.id, message, now()) }
            return
        }
        val udp = UdpResource(action.id, action.channel, socket, buffersFor(action.channel), udpBufferSizeFor(action.channel))
        val thread = newThread(action.channel, action.id) { udpLoop(udp) }
        if (!register(udp, thread)) {
            closeQuietly(socket)
            return
        }
        thread.start()
        val port = socket.localPort
        immediate.add { session.onListening(action.id, port, now()) }
    }

    private fun sendDatagram(action: SessionAction.SendDatagram) {
        val udp = lookup(action.id) as? UdpResource ?: return
        try {
            udp.socket.send(DatagramPacket(action.bytes, action.bytes.size))
        } catch (e: IOException) {
            if (!udp.closed.get()) {
                val message = "发 UDP 数据报失败：${describe(e)}"
                immediate.add { session.onIoError(action.id, message, now()) }
            }
        }
    }

    private fun closeResource(id: Int) {
        val resource = resources.remove(id) ?: return
        resource.close()
    }

    /** 协议线程退出前：把还没关的资源按创建的反序关掉，之后不再接受新资源。 */
    private fun closeEverything() {
        val force = abandoned || failure != null
        val remaining = synchronized(lock) {
            closingAll = true
            resources.values.toList().also { resources.clear() }
        }
        for (resource in remaining.asReversed()) {
            if (force) resource.forceClose() else resource.close()
        }
    }

    /** 线程等待结束后的兜底强关，防止对端卡死写队列导致套接字残留。 */
    private fun forceCloseAll() {
        val all = synchronized(lock) {
            allResources.toList().also { allResources.clear() }
        }
        for (resource in all.asReversed()) resource.forceClose()
    }

    private fun joinThreads() {
        val all = synchronized(lock) { threads.toList() }
        val deadline = now() + options.stopGraceMillis
        for (thread in all) {
            val left = deadline - now()
            if (left > 0) {
                try {
                    thread.join(left)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
            if (thread.isAlive) options.log("线程 ${thread.name} 没能按时退出")
        }
    }

    // ---------------- 读线程 ----------------

    private fun acceptLoop(listener: ListenerResource, action: SessionAction.Listen) {
        val socket: Socket
        try {
            while (true) {
                val candidate = listener.server.accept()
                val local = candidate.localAddress?.hostAddress
                if (action.requireLocalHost == null || local == action.requireLocalHost) {
                    socket = candidate
                    break
                }
                options.log("${action.channel.label}：本端地址不是 ${action.requireLocalHost} 的连接（经 $local），已关闭")
                closeQuietly(candidate)
            }
        } catch (e: IOException) {
            if (!listener.closed.get()) post(Input.Failed(action.id, "${action.channel.label}监听出错：${describe(e)}"), action.channel)
            return
        }
        try {
            socket.tcpNoDelay = true
            applySocketQos(socket, action.channel)
        } catch (e: IOException) {
            // 个别平台不支持设套接字选项时照常继续。
        }
        val connection = ConnectionResource(
            action.acceptId,
            action.channel,
            socket,
            buffersFor(action.channel),
            bufferSizeFor(action.channel),
        )
        if (!register(connection, null)) {
            closeQuietly(socket)
            return
        }
        rejectExtraConnections(listener, action)
        val address = socket.inetAddress
        val remote = (address as? Inet4Address)?.hostAddress ?: address?.hostAddress ?: ""
        post(Input.Accepted(action.acceptId, remote), action.channel)
        readLoop(connection)
    }

    /** 这个监听已经接受过一条连接：另起线程，之后再连进来的接受后立刻关掉（前 [MAX_REJECT_LOGS] 条记日志），直到监听关闭。 */
    private fun rejectExtraConnections(listener: ListenerResource, action: SessionAction.Listen) {
        if (listener.closed.get()) return
        val thread = newThread(action.channel, action.id) {
            var rejected = 0
            while (true) {
                val extra = try {
                    listener.server.accept()
                } catch (e: IOException) {
                    if (!listener.closed.get()) options.log("${action.channel.label}：等多余连接时出错（${describe(e)}），不再理会")
                    return@newThread
                }
                closeQuietly(extra)
                rejected++
                if (rejected <= MAX_REJECT_LOGS) {
                    options.log(
                        "${action.channel.label}：这个监听已经接受过一条连接，又连进来一条（来自 ${extra.inetAddress?.hostAddress}），" +
                            "已关闭" + if (rejected == MAX_REJECT_LOGS) "；之后的不再记" else "",
                    )
                }
            }
        }
        val keep = synchronized(lock) {
            val ok = !closingAll && !listener.closed.get()
            if (ok) threads += thread
            ok
        }
        if (keep) thread.start()
    }

    private fun readLoop(connection: ConnectionResource) {
        val input = try {
            connection.socket.getInputStream()
        } catch (e: IOException) {
            if (!connection.closed.get()) post(Input.Failed(connection.id, "取输入流失败：${describe(e)}"), connection.channel)
            return
        }
        while (true) {
            val buffer = connection.pool.take(connection.closed) ?: return
            val n = try {
                input.read(buffer)
            } catch (e: IOException) {
                connection.pool.give(buffer)
                if (!connection.closed.get()) post(Input.Failed(connection.id, "读失败：${describe(e)}"), connection.channel)
                return
            }
            if (n < 0) {
                connection.pool.give(buffer)
                if (!connection.closed.get()) post(Input.Closed(connection.id), connection.channel)
                return
            }
            if (n == 0) {
                connection.pool.give(buffer)
                continue
            }
            post(Input.Data(connection.id, buffer, n, connection.pool), connection.channel)
        }
    }

    private fun udpLoop(udp: UdpResource) {
        val packet = udp.recvPacket
        while (true) {
            val buffer = udp.pool.take(udp.closed) ?: return
            packet.setData(buffer, 0, buffer.size)
            try {
                udp.socket.receive(packet)
            } catch (e: IOException) {
                udp.pool.give(buffer)
                if (!udp.closed.get()) post(Input.Failed(udp.id, "收 UDP 失败：${describe(e)}"), udp.channel)
                return
            }
            post(Input.Datagram(udp.id, buffer, packet.length, udp.pool), udp.channel)
        }
    }

    // ---------------- 工具 ----------------

    private fun isDefaultBuffering(): Boolean =
        options.readBufferBytes == DEFAULT_READ_BUFFER_BYTES && options.buffersPerConnection == DEFAULT_BUFFERS_PER_CONN

    private fun buffersFor(channel: SessionChannel): Int {
        if (!isDefaultBuffering()) return options.buffersPerConnection
        return when (channel) {
            SessionChannel.VIDEO -> 8
            SessionChannel.AUDIO -> 8
            SessionChannel.REMOTE_CONTROL, SessionChannel.MIC -> 1
            else -> 2
        }
    }

    private fun bufferSizeFor(channel: SessionChannel): Int {
        if (!isDefaultBuffering()) return options.readBufferBytes
        return when (channel) {
            SessionChannel.VIDEO -> DEFAULT_READ_BUFFER_BYTES
            SessionChannel.AUDIO -> 16 * 1024
            SessionChannel.REMOTE_CONTROL, SessionChannel.MIC -> 4 * 1024
            else -> 16 * 1024
        }
    }

    private fun udpBufferSizeFor(channel: SessionChannel): Int {
        if (!isDefaultBuffering()) return options.readBufferBytes
        return when (channel) {
            SessionChannel.KEEPALIVE -> 1024
            else -> DEFAULT_READ_BUFFER_BYTES
        }
    }

    private fun applySocketQos(socket: Socket, channel: SessionChannel) {
        try {
            socket.trafficClass = trafficClassFor(channel)
        } catch (_: IOException) {
            // 忽略不支持 TOS / DSCP 的内核或协议栈。
        } catch (_: IllegalArgumentException) {
            // 忽略不支持 TOS 的平台。
        }
    }

    private fun applyDatagramQos(socket: DatagramSocket, channel: SessionChannel) {
        try {
            socket.trafficClass = trafficClassFor(channel)
        } catch (_: IOException) {
            // 忽略不支持 TOS / DSCP 的内核或协议栈。
        } catch (_: IllegalArgumentException) {
            // 忽略不支持 TOS 的平台。
        }
    }

    private fun trafficClassFor(channel: SessionChannel): Int = when (channel) {
        SessionChannel.AUDIO,
        SessionChannel.REMOTE_CONTROL,
        SessionChannel.CONTROL,
        SessionChannel.DMSDP_CONTROL,
        SessionChannel.HISIGHT_CONTROL,
        SessionChannel.KEEPALIVE,
        -> TOS_VOICE_EXPEDITED
        SessionChannel.VIDEO -> TOS_VIDEO_AF41
        else -> TOS_LOW_DELAY
    }

    private fun post(input: Input, channel: SessionChannel) {
        queue.add(input, highPriority = channel != SessionChannel.VIDEO)
    }

    private fun lookup(id: Int): Resource? = resources[id]

    /** 登记资源和它的线程；协议线程已经在退出时返回 false，调用方自己关掉 socket。 */
    private fun register(resource: Resource, thread: Thread?): Boolean = synchronized(lock) {
        if (closingAll) return false
        resources[resource.id] = resource
        allResources += resource
        if (thread != null) threads += thread
        true
    }

    private fun newThread(channel: SessionChannel, id: Int, body: () -> Unit): Thread {
        val thread = Thread(
            {
                try {
                    body()
                } finally {
                    synchronized(lock) { threads.remove(Thread.currentThread()) }
                }
            },
            "${options.threadName}-${channel.name.lowercase()}-$id",
        )
        thread.isDaemon = true
        return thread
    }

    private companion object {
        const val DEFAULT_READ_BUFFER_BYTES = 64 * 1024
        const val DEFAULT_BUFFERS_PER_CONN = 2
        const val VIDEO_SOCKET_RCVBUF_BYTES = 512 * 1024
        const val MAX_QUEUED_WRITES = 128
        const val WRITE_COALESCE_BYTES = 8 * 1024
        const val MAX_DRAIN_BURST = 16
        const val TOS_VOICE_EXPEDITED = 0xB8
        const val TOS_VIDEO_AF41 = 0x88
        const val TOS_LOW_DELAY = 0x10

        /** 没有定时任务时协议线程最长等多久再看一眼。 */
        const val IDLE_WAIT_MILLIS = 1_000L

        /** 一个监听上被关掉的多余连接，最多记几条日志。 */
        const val MAX_REJECT_LOGS = 3

        fun now(): Long = System.nanoTime() / 1_000_000L

        fun describe(e: Throwable): String = e.javaClass.simpleName + (e.message?.let { "：$it" } ?: "")

        fun closeQuietly(closeable: Closeable) {
            try {
                closeable.close()
            } catch (e: IOException) {
                // 关闭时的错误没有意义。
            }
        }

        fun closeQuietly(socket: DatagramSocket) {
            socket.close()
        }
    }
}
