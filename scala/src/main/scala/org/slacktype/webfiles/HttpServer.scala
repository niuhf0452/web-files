package org.slacktype.webfiles

import java.nio.channels._
import scala.concurrent._

object HttpServer {

  import java.net.InetSocketAddress

  def bind(host: String, port: Int)(f: HttpContext => Unit)(implicit executor: ExecutionContext): AutoCloseable = {
    val workers = scala.collection.mutable.Set[HttpWorker]()
    val selector = new HttpSelector
    val serverSocket = ServerSocketChannel.open()
    serverSocket.bind(new InetSocketAddress(host, port))
    selector.request(serverSocket, SelectionKey.OP_ACCEPT) { key =>
      if (key.isAcceptable)
        workers.add(new HttpWorker(serverSocket.accept(), selector, f))
      true
    }
    new AutoCloseable {
      override def close() = {

        serverSocket.close()
      }
    }
  }

  class HttpWorker(conn: SocketChannel, selector: HttpSelector, f: HttpContext => Unit)(implicit executor: ExecutionContext) {
    private var readable = false
    private var writable = false
    private val readBuffer = java.nio.ByteBuffer.allocate(4096)
    private var readPending = true

    conn.configureBlocking(false)

    selector.request(conn, SelectionKey.OP_READ | SelectionKey.OP_WRITE) { key =>
      readable = key.isReadable
      writable = key.isWritable
      if (readable && readPending) {
        readable = false
        readPending = false

      }
      false
    }

    def resumeRead() = {
      if (readable) {
        val len = conn.read(readBuffer)

      } else {
        readPending = true
      }
    }


  }

  trait ByteReceiver {
    def onData(buf: ByteBuffer, next: () => Unit): Unit
  }

  trait HttpContext {
    def request: HttpRequest

    def complete: Unit
  }

}

class HttpSelector(implicit executor: ExecutionContext) {

  import scala.collection.mutable

  @volatile
  private var running = false
  private val selector = Selector.open()
  private val requestMap = mutable.Map[SelectionKey, SelectionKey => Boolean]()

  def request(socket: SelectableChannel, ops: Int)(callback: SelectionKey => Boolean) = {
    selector.synchronized {
      try {
        val key = socket.register(selector, ops)
        requestMap.put(key, callback)
        if (!running) {
          running = true
          Future(run())
        }
      } catch {
        case _: ClosedSelectorException =>
        case _: ClosedChannelException =>
      }
    }
  }

  private def run(): Unit = {
    import scala.collection.JavaConversions._
    try {
      while (running) {
        if (selector.select() > 0) {
          selector.synchronized {
            selector.selectedKeys() foreach { key =>
              val callback = requestMap(key)
              if (!callback(key)) {
                key.cancel()
                requestMap.remove(key)
              }
            }
            if (requestMap.isEmpty)
              running = false
          }
          selector.selectedKeys().clear()
        }
      }
    } catch {
      case _: InterruptedException =>
      case _: ClosedSelectorException =>
        running = false
    }
  }

  def close() = {
    selector.synchronized {
      selector.close()
    }
  }

  case class Request(socket: SelectableChannel, ops: Int, callback: SelectionKey => Boolean)

}

case class HttpHeader(name: String, content: String)

case class HttpRequest(method: String, uri: String, headers: List[HttpHeader])

import java.nio.charset.Charset

trait ByteBuffer {

  def apply(offset: Int): Byte

  def slice(start: Int, end: Int = -1): ByteBuffer

  def sliceString(start: Int, end: Int, charset: Charset): String
}

object ByteBuffer {
  def apply(buf: Array[Byte], start: Int, end: Int): ByteBuffer = {
    new ByteBuffer {
      def apply(offset: Int) = {
        if (start + offset >= end)
          throw new IndexOutOfBoundsException()
        buf(start + offset)
      }

      def slice(start0: Int, end0: Int) = {
        if (start + start0 > end || start + end0 > end)
          throw new IndexOutOfBoundsException()
        ByteBuffer(buf, start + start0, if (end0 == -1) end else start + end0)
      }

      def sliceString(start0: Int, end: Int, charset: Charset) =
        new String(buf, start + start0, end - start0, charset).intern()
    }
  }
}

case class ParserOptions(maxLengthOfRequestLine: Int,
                         maxLengthOfHeader: Int,
                         encoding: Charset)

trait HttpParser {

  trait ParserState {
    def apply(buf: ByteBuffer): ParserState
  }

  val options = ParserOptions(
    maxLengthOfRequestLine = 1024,
    maxLengthOfHeader = 1024,
    encoding = Charset.forName("ASCII"))

  class ParserContext {
    var url: String = _
    var method: String = _
    var headers: List[HttpHeader] = Nil
    var expectLength = 0
    var entity: ByteBuffer = _

    def getHeader(name: String) = {
      val name_ = name.toLowerCase
      headers.find(_.name.toLowerCase == name_).map(_.content)
    }
  }

  sealed trait EntityLength

  object EntityLength {

    case class Fixed(length: Int) extends EntityLength

    case object Chunked extends EntityLength

    case object Remains extends EntityLength

    case object NoEntity extends EntityLength

  }

  val context = new ParserContext

  val requestStart = new ParserState {
    val RequestLine = """^([A-Z]+)\s+(.+)\s+HTTP/1\.1$""".r

    override def apply(buf: ByteBuffer): ParserState = {
      val line = readLine(buf, options.maxLengthOfRequestLine)
      line match {
        case RequestLine(method, url) =>
          context.method = method
          context.url = url
          messageHeaders
        case _ => throw new ParserException("not HTTP/1.1 protocol")
      }
    }
  }

  val messageHeaders = new ParserState {
    val HeaderLine = """^([A-Z\-]+)\s*:\s*(.*)$""".r
    val HeaderValueLine = """^\s+(.+)$""".r

    override def apply(buf: ByteBuffer): ParserState = {
      val line = readLine(buf, options.maxLengthOfHeader)
      line match {
        case "" =>
          context.headers = context.headers.reverse
          entityStart
        case HeaderLine(name, content) =>
          context.headers = HttpHeader(name, content) :: context.headers
          this
        case HeaderValueLine(content) =>
          context.headers.headOption match {
            case Some(x) =>
              val header = HttpHeader(x.name, x.content + content)
              context.headers = header :: context.headers.tail
              this
            case None => throw new ParserException("invalid header")
          }
        case _ => throw new ParserException("invalid header")
      }
    }
  }

  val entityStart = new ParserState {
    override def apply(buf: ByteBuffer): ParserState = {
      context.getHeader("Transfer-Encoding") match {
        case Some(x) =>
          if (x == "identity") identity else chunkStart
        case None =>
          context.getHeader("Content-Length") match {
            case Some(x) =>
              context.expectLength = x.toInt
              lengthFixed
            case None =>
              requestEnd
          }
      }
    }
  }

  val lengthFixed = new ParserState {
    override def apply(buf: ByteBuffer): ParserState = {
      context.entity = buf.slice(0, context.expectLength)
      requestEnd
    }
  }

  val chunkStart = new ParserState {
    override def apply(buf: ByteBuffer): ParserState = ???
  }

  val identity = new ParserState {
    override def apply(buf: ByteBuffer): ParserState = ???
  }

  val requestEnd = new ParserState {
    override def apply(buf: ByteBuffer): ParserState = ???
  }

  def readLine(buf: ByteBuffer, maxLength: Int) = {
    val end = lineEnd(buf, 0, maxLength)
    val s = buf.sliceString(0, end, options.encoding)
    //buf.truncate(end)
    s
  }

  def lineEnd(buf: ByteBuffer, offset: Int, maxLength: Int): Int = {
    import scala.annotation.tailrec
    @tailrec
    def find(offset: Int, limit: Int): Int = {
      if (offset >= limit) throw new ParserException("request line exceed limit")
      if (buf(offset) == '\r') {
        if (buf(offset + 1) == '\n')
          offset + 2
        else
          throw new ParserException("missing '\\n'")
      } else
        find(offset + 1, limit)
    }
    find(offset, offset + maxLength)
  }

}

class ParserException(message: String) extends Exception(message)

class HttpException(code: Int, message: String) extends Exception(message)

case class StatusCode(code: Int, message: String) {
  def raise = throw new HttpException(code, message)
}

object StatusCode {
  val BadRequest = StatusCode(400, "Bad Request")
  val NotFound = StatusCode(404, "Not Found")
}

