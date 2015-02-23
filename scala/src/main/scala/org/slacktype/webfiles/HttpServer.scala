package org.slacktype.webfiles

import java.nio.channels._
import scala.concurrent._

object HttpServer {

  import java.net.InetSocketAddress

  def bind(host: String, port: Int)(f: HttpContext => Unit)(implicit executor: ExecutionContext): AutoCloseable = {
    null
  }

  trait HttpContext {
    def request: HttpRequest

    def complete: Unit
  }

}

case class HttpHeader(name: String, content: String)

sealed trait HttpMessagePart

case class HttpRequest(method: String, uri: String, headers: List[HttpHeader]) extends HttpMessagePart

class HttpException(code: Int, message: String) extends Exception(message)

case class StatusCode(code: Int, message: String) {
  def raise = throw new HttpException(code, message)
}

object StatusCode {
  val BadRequest = StatusCode(400, "Bad Request")
  val NotFound = StatusCode(404, "Not Found")
}

