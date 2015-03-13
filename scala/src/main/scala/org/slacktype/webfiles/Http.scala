package org.slacktype.webfiles

object Http {

  import java.net.InetSocketAddress
  import scala.concurrent._

  def bind(host: String, port: Int)(f: ServerContext => Unit)(implicit executor: ExecutionContext): Unit = {
    Tcp.listen(Tcp.Options(new InetSocketAddress(host, port))) { conn =>
      conn.setOutput(conn.input >>
        new EventParser(HttpParser.Options(response = false)) >>
        new RequestParser >>
        new RequestHandler(1, f) >>
        new ResponseWriter)
    }
  }

  trait ServerContext {
    def request: Request

    def send(resp: Response): Unit
  }

  case class Header(name: String, content: String)

  sealed trait Message {
    def headers: List[Header]

    def body: Stream[ByteBuffer]
  }

  sealed trait Request extends Message {
    def method: String

    def url: String

    def findHeader(name: String) = {
      val n = name.toLowerCase
      headers find (_.name == n) map (_.content)
    }

    def keepAlive = findHeader("connection") contains "keep-alive"
  }

  sealed trait Response extends Message {
    def status: StatusCode
  }

  class HttpException(code: Int, message: String) extends Exception(message)

  case class StatusCode(code: Int, message: String) {
    def raise = throw new HttpException(code, message)
  }

  object StatusCode {
    private var codes = scala.collection.immutable.IntMap.empty[StatusCode]

    private def stat(code: Int, message: String) = {
      val v = StatusCode(code, message)
      codes += code -> v
      v
    }

    val BadRequest = stat(400, "Bad Request")
    val NotFound = stat(404, "Not Found")

    def predefined(code: Int) = codes.get(code)
  }

  class EventParser(parserOptions: HttpParser.Options) extends Stream.Processor1[ByteBuffer, HttpParser.Event] {
    private val parser = new HttpParser {
      def options = parserOptions

      protected def resolve(e: HttpParser.Event) = {
        outputs.enqueue(e)
      }
    }

    protected def process0(buf: ByteBuffer) = {
      parser.process(buf)
    }

    protected def processEnd0() = {
      parser.processEnd()
    }
  }

  sealed trait MessageParser[M >: Null] extends Stream.Processor0[HttpParser.Event, M] {
    private var message: MessageBase with M = _

    protected def newMessage(e: HttpParser.MessageStart): MessageBase with M

    protected def process(e: HttpParser.Event) = {
      e match {
        case x: HttpParser.MessageEnd =>
          message.onEnd()
          message = null
          notifyBuffer(null)
        case x: HttpParser.MessageStart =>
          message = newMessage(x)
          completeRead(message)
        case HttpParser.EntityData(x) =>
          message.onBuffer(x)
      }
    }

    protected def processEnd() = ()

    protected def read() = upstream.resume()

    override def completeRead(ex: Throwable) = {
      if (message == null)
        super.completeRead(ex)
      else
        message.onError(ex)
    }

    override def resume() = {
      require(message == null)
      super.resume()
    }

    trait MessageBase extends Message with StreamReader[ByteBuffer] with Stream.Stream1[ByteBuffer] {
      def body = this

      protected def read() = upstream.resume()

      def onEnd() = completeRead()

      def onError(ex: Throwable) = completeRead(ex)

      def onBuffer(buf: ByteBuffer) = completeRead(buf)
    }

  }

  class RequestParser extends MessageParser[Request] {
    protected def newMessage(e: HttpParser.MessageStart) = {
      e match {
        case x: HttpParser.RequestStart =>
          new RequestImpl(x.method, x.url, x.headers map (x => Header(x._1.toLowerCase, x._2)))
        case _ => throw new IllegalStateException()
      }
    }

    class RequestImpl(val method: String, val url: String, val headers: List[Header]) extends MessageBase with Request

  }

  class RequestHandler(pipelineLimit: Int, f: ServerContext => Unit)(implicit executor: ExecutionContext) extends Stream.Processor0[Request, Response] {
    private val pipeline = scala.collection.mutable.Queue[Context]()
    private var waiting = false
    private var end = false

    protected def process(req: Request) = {
      if (req == null) {
        pipeline.synchronized {
          if (pipeline.length < pipelineLimit)
            upstream.resume()
        }
      } else {
        val c = new Context
        c.request = req
        Future(f(c))
        pipeline.synchronized {
          if (waiting) {
            waiting = false
            c.complete()
          } else {
            pipeline.enqueue(c)
          }
        }
      }
    }

    protected def processEnd() = {
      pipeline.synchronized {
        end = true
        if (waiting)
          completeRead()
      }
    }

    protected def read() = {
      pipeline.synchronized {
        if (pipeline.isEmpty) {
          if (end)
            completeRead()
          else {
            waiting = true
            upstream.resume()
          }
        } else {
          pipeline.dequeue().complete()
          if (pipeline.length < pipelineLimit)
            upstream.resume()
        }
      }
    }

    class Context extends ServerContext {
      private var response: Response = _
      private var pending = false
      var request: Request = _

      def send(resp: Response) = this.synchronized {
        if (pending) {
          pending = false
          completeRead(resp)
        } else
          response = resp
      }

      def complete() = this.synchronized {
        if (response == null)
          pending = true
        else
          completeRead(response)
      }
    }

  }

  class ResponseWriter extends Stream.Processor0[Response, ByteBuffer] {
    protected def process(resp: Response) = {
    }

    protected def processEnd() = {

    }

    protected def read() = {
    }
  }

  class ResponseParser extends MessageParser[Response] {
    protected def newMessage(e: HttpParser.MessageStart) = {
      e match {
        case x: HttpParser.ResponseStart =>
          new ResponseImpl(StatusCode(x.statusCode, x.statusText),
            x.headers map (x => Header(x._1.toLowerCase, x._2)))
        case _ => throw new IllegalStateException()
      }
    }

    class ResponseImpl(val status: StatusCode, val headers: List[Header]) extends MessageBase with Response

  }

}


