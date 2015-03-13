package org.slacktype.webfiles

import org.slacktype.webfiles.Stream.Stream1

object Test {

  def main(args: Array[String]): Unit = {
    import java.net.InetSocketAddress
    import java.nio.charset.Charset
    import scala.concurrent._
    import ExecutionContext.Implicits._

    val charset = Charset.forName("UTF-8")

    Tcp.listen(Tcp.Options(address = new InetSocketAddress("localhost", 8000))) { conn =>
//      conn.input.read(new StreamReader[ByteBuffer] {
//        def onError(ex: Throwable): Unit = ???
//
//        def onEnd() =
//
//        def onBuffer(buf: ByteBuffer): Unit = ???
//      })
//      conn.setOutput(new Stream1[ByteBuffer] {
//        protected def read0(): Unit = ???
//      })
      conn.setOutput(conn.input)
    }
    test1()
    scala.io.StdIn.readLine()
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
