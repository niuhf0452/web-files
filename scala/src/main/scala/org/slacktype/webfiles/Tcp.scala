package org.slacktype.webfiles

import java.net._

trait TcpConnection {
  def remoteAddress: SocketAddress

  def localAddress: SocketAddress

  def input: Stream[ByteBuffer]

  def setOutput(out: Stream[ByteBuffer]): Unit

  def close(): Unit
}

trait TcpServer {
  def close(): Unit
}

object Tcp {

  import java.nio.channels._
  import scala.concurrent._

  trait Selectable {
    def onSelection(): Unit
  }

  class Selector()(implicit executor: ExecutionContext) {
    private val selector = Selector.open()
    private var running = true
    @volatile
    private var hasWakeup = false

    def register(channel: SelectableChannel, sel: Selectable, ops: Int = 0) = {
      this.synchronized {
        internalWakeup()
        channel.register(selector, ops, sel)
      }
    }

    def wakeup() = {
      if (!hasWakeup) {
        this.synchronized {
          internalWakeup()
        }
      }
    }

    private def internalWakeup() = {
      if (!hasWakeup) {
        hasWakeup = true
        selector.wakeup()
      }
    }

    Future {
      try {
        while (running) {
          this.synchronized {
            hasWakeup = false
          }
          selector.select()
          hasWakeup = true
          if (running) {
            val keys = selector.selectedKeys()
            if (!keys.isEmpty) {
              val itor = keys.iterator()
              while (itor.hasNext) {
                val sel = itor.next().attachment().asInstanceOf[Selectable]
                sel.onSelection()
              }
              keys.clear()
            }
          }
        }
      } catch {
        case _: InterruptedException =>
        case _: ClosedSelectorException =>
          running = false
      }
    }

    def close() = {
      running = false
      wakeup()
    }
  }

  class Server(options: Options, callback: TcpConnection => Unit)(implicit executor: ExecutionContext) extends TcpServer with Selectable {
    private val selector = new Selector()
    private val serverSocket = ServerSocketChannel.open()
    serverSocket.configureBlocking(false)
    serverSocket.bind(options.address)
    private val selectionKey = selector.register(serverSocket, this, SelectionKey.OP_ACCEPT)

    def onSelection() = {
      if (selectionKey.isAcceptable) {
        val socket = serverSocket.accept()
        socket.configureBlocking(false)
        val conn = new Connection(socket, selector)
        Future(callback(conn))
      }
    }

    def close() = {
      selector.close()
    }
  }

  class Connection(socket: SocketChannel, selector: Selector)(implicit executor: ExecutionContext) extends TcpConnection with Selectable with Stream.Stream1[ByteBuffer] {
    private val selectionKey = selector.register(socket, this)
    private var output: Stream[ByteBuffer] = _
    private var writeBuffer: java.nio.ByteBuffer = _
    private var inputClosed = false
    private var outputClosed = false
    private val readBuffer = java.nio.ByteBuffer.allocate(1024 * 16)

    override protected def read0() = {
      if (socket.read(readBuffer) == -1) {
        shutdownInput()
        readEnd = true
        completeRead(null)
      } else {
        readBuffer.flip()
        val buf =
          if (readBuffer.remaining() > 0) {
            val arr = new Array[Byte](readBuffer.remaining())
            readBuffer.get(arr)
            ByteBuffer(arr)
          } else null
        readBuffer.clear()
        if (buf != null)
          completeRead(buf)
        else if ((selectionKey.interestOps() & SelectionKey.OP_READ) == 0) {
          selectionKey.interestOps(selectionKey.interestOps() | SelectionKey.OP_READ)
          selector.wakeup()
        }
      }
    }

    def remoteAddress = socket.getRemoteAddress

    def localAddress = socket.getLocalAddress

    def input = this

    def setOutput(out: Stream[ByteBuffer]) = {
      require(output == null)
      output = out
      out.read(new StreamReader[ByteBuffer] {
        def onError(ex: Throwable) = shutdownOutput()

        def onEnd() = shutdownOutput()

        def onBuffer(buf: ByteBuffer) = {
          writeBuffer = buf.toJavaByteBuffer
          write()
        }
      })
      out.resume()
    }

    private def write() = {
      socket.write(writeBuffer)
      if (writeBuffer.remaining() > 0) {
        if ((selectionKey.interestOps() & SelectionKey.OP_WRITE) == 0) {
          selectionKey.interestOps(selectionKey.interestOps() | SelectionKey.OP_WRITE)
          selector.wakeup()
        }
      } else {
        writeBuffer = null
        output.resume()
      }
    }

    def onSelection() = {
      if (selectionKey.isReadable) {
        selectionKey.interestOps(selectionKey.interestOps() & ~SelectionKey.OP_READ)
        input.read0()
      }
      if (selectionKey.isWritable) {
        selectionKey.interestOps(selectionKey.interestOps() & ~SelectionKey.OP_WRITE)
        write()
      }
    }

    private def shutdownInput() = {
      socket.shutdownInput()
      this.synchronized {
        inputClosed = true
        if (outputClosed)
          socket.close()
      }
    }

    private def shutdownOutput() = {
      socket.shutdownOutput()
      this.synchronized {
        outputClosed = true
        if (inputClosed)
          socket.close()
      }
    }

    def close() = {
      selectionKey.cancel()
      if (!inputClosed) {
        socket.shutdownInput()
        inputClosed = true
      }
      if (!outputClosed) {
        socket.shutdownOutput()
        outputClosed = true
      }
      socket.close()
    }
  }

  case class Options(address: InetSocketAddress, inputBufferLength: Int = 4096)

  def listen(options: Options)(f: TcpConnection => Unit)(implicit executor: ExecutionContext): TcpServer = {
    new Server(options, f)
  }
}
