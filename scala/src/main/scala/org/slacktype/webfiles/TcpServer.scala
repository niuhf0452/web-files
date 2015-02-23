package org.slacktype.webfiles

import java.net._

trait TcpConnection {
  def remoteAddress: SocketAddress

  def localAddress: SocketAddress

  def input: AsyncDequeue[ByteBuffer]

  def output: AsyncEnqueue[ByteBuffer]

  def close(): Unit
}

trait TcpServer {
  def close(): Unit
}

object TcpServer {

  import java.nio.channels._
  import scala.concurrent._

  case class Options(address: InetSocketAddress, inputBufferLength: Int = 4096)

  def listen(options: Options)(f: TcpConnection => Unit)(implicit executor: ExecutionContext): TcpServer = {
    new Server(options)(f)
  }

  private class Server(options: Options)(f: TcpConnection => Unit)(implicit executor: ExecutionContext) extends TcpServer {

    import AsyncQueue._

    private val selector = Selector.open()
    private val serverSocket = ServerSocketChannel.open()
    @volatile
    private var running = true
    serverSocket.configureBlocking(false)
    serverSocket.bind(options.address)
    private val acceptKey = serverSocket.register(selector, SelectionKey.OP_ACCEPT)

    Future {
      try {
        while (running) {
          selector.select()
          val keys = selector.selectedKeys()
          if (!keys.isEmpty) {
            if (keys.remove(acceptKey)) {
              var socket = serverSocket.accept()
              while (socket != null) {
                socket.configureBlocking(false)
                val conn = new Connection(socket)
                Future(f(conn))
                socket = serverSocket.accept()
              }
            }
            if (!keys.isEmpty) {
              val itor = keys.iterator()
              while (itor.hasNext) {
                val conn = itor.next().attachment().asInstanceOf[Connection]
                conn.ready()
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


    override def close(): Unit = {
      selector.close()
      serverSocket.close()
    }

    class Connection(socket: SocketChannel) extends TcpConnection with Enqueue[ByteBuffer] with Dequeue[ByteBuffer] {

      import java.nio.{ByteBuffer => JByteBuffer}

      private val inputBuffer = JByteBuffer.wrap(new Array[Byte](options.inputBufferLength))
      private var outputBuffer: JByteBuffer = _
      private val selectionKey: SelectionKey = socket.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE, this)

      def remoteAddress = socket.getRemoteAddress

      def localAddress = socket.getLocalAddress

      def input = this

      def output = this

      override protected def dequeue0() = {
        socket.read(inputBuffer)
        if (inputBuffer.position() > 0) {
          val arr = new Array[Byte](inputBuffer.position())
          Array.copy(inputBuffer.array(), 0, arr, 0, arr.length)
          inputBuffer.clear()
          dequeued(QueueItem(ByteBuffer(arr)))
        } else {
          changeOps(SelectionKey.OP_READ)
        }
      }

      override protected def enqueue0(buf: QueueState[ByteBuffer]) = {
        buf match {
          case QueueItem(x) =>
            outputBuffer = x.toJavaByteBuffer
            writePending()
          case x: QueueEnd =>
            socket.shutdownOutput()
            setEndState(x)
            enqueued()
        }
      }

      private def writePending() = {
        socket.write(outputBuffer)
        if (outputBuffer.remaining() > 0) {
          changeOps(SelectionKey.OP_WRITE)
        } else {
          outputBuffer = null
          enqueued()
        }
      }

      def close() = {
        selectionKey.cancel()
        socket.close()
      }

      private def changeOps(bit: Int) = {
        selectionKey.interestOps(selectionKey.interestOps() | bit)
        selector.wakeup()
      }

      def ready() = {
        if (selectionKey.isValid) {
          if (selectionKey.isWritable) {
            selectionKey.interestOps(selectionKey.interestOps() & ~SelectionKey.OP_WRITE)
            Future(writePending())
          }
          if (selectionKey.isReadable) {
            selectionKey.interestOps(selectionKey.interestOps() & ~SelectionKey.OP_READ)
            Future(dequeue0())
          }
        }
      }
    }

  }

}
