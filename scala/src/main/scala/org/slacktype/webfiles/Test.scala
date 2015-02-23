package org.slacktype.webfiles

object Test {

  def main(args: Array[String]): Unit = {
    import java.net.InetSocketAddress
    import java.nio.charset.Charset
    import scala.concurrent._
    import ExecutionContext.Implicits._

    val charset = Charset.forName("UTF-8")

    TcpServer.listen(TcpServer.Options(address = new InetSocketAddress("localhost", 8000))) { conn =>
      def pull(): Unit = {
        conn.input.dequeue {
          case QueueItem(x) =>
            println("server received: " + x.length + " " + x.sliceString(0, x.length, charset))
            conn.output.enqueue(QueueItem(x))(pull)
          case x =>
            println("end: " + x)
            conn.output.enqueue(QueueFinish)(() => ())
        }
      }
      pull()
    }
    test1()
    scala.io.StdIn.readLine()
  }

  def test0() = {

    import java.net.InetSocketAddress
    import java.nio.channels._
    import scala.concurrent._
    import scala.util.control.NonFatal
    import scala.collection.JavaConversions._
    import ExecutionContext.Implicits._

    val selector = Selector.open()
    val serverSocket = ServerSocketChannel.open()
    serverSocket.bind(new InetSocketAddress("localhost", 8000))
    Future {
      val socket = serverSocket.accept()
      socket.configureBlocking(false)
      socket.register(selector, SelectionKey.OP_READ, socket)
      try {
        while (true) {
          val c = selector.select()
          println("selected: " + c)
          val keys = selector.selectedKeys().toSet
          keys foreach { key =>
            if (key.isReadable) {
              //val s = key.attachment().asInstanceOf[SocketChannel]
              //val bb = java.nio.ByteBuffer.allocate(10)
              //s.read(bb)
              //println("received: " + bb.remaining())
              key.interestOps(0)
            }
          }
          selector.selectedKeys().clear()
        }
      } catch {
        case NonFatal(_) =>
      }
    }
  }

  def test1() = {
    import java.net.InetSocketAddress
    import java.nio.channels._

    val socket = SocketChannel.open(new InetSocketAddress("localhost", 8000))
    val bb = java.nio.ByteBuffer.wrap(new Array[Byte](1024))
    val hello = "hello"
    bb.put(hello.getBytes)
    bb.flip()
    println("client send: " + hello)
    socket.write(bb)
    bb.clear()
    socket.read(bb)
    println("client receive: " + new String(bb.array(), 0, bb.position()))
  }

}
