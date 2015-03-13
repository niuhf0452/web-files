package org.slacktype.webfiles

import java.nio.charset.Charset
import org.slacktype.webfiles.HttpParser.{Options, Event}

import scala.concurrent._

object HttpParser {

  case class Options(response: Boolean,
                     maxLengthOfRequestLine: Int = 1024,
                     maxLengthOfStatusLine: Int = 1024,
                     maxLengthOfHeader: Int = 1024,
                     maxLengthOfChunkHead: Int = 100,
                     encoding: Charset = Charset.forName("ASCII"))

  sealed trait EntityLength

  object EntityLength {

    case class Fixed(length: Int) extends EntityLength

    case object Chunked extends EntityLength

    case object Remains extends EntityLength

    case object NoEntity extends EntityLength

  }

  trait ParserState extends (() => ParserState)

  def lineEnd(buf: ByteBuffer, offset: Int, maxLength: Int): Int = {
    import scala.annotation.tailrec
    @tailrec
    def find(offset: Int, limit: Int): Int = {
      if (offset >= limit) throw new ParserException("request line exceed limit")
      if (offset >= buf.length) throw NotEnoughData
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

  case object NotEnoughData extends Exception with scala.util.control.NoStackTrace

  case class Header(name: String, content: String)

  sealed trait Event

  sealed trait MessageStart extends Event

  sealed trait MessageEnd extends Event

  sealed trait MessageData extends Event

  case class RequestStart(method: String, url: String, headers: List[(String, String)]) extends MessageStart

  case object RequestEnd extends MessageEnd

  case class ResponseStart(statusCode: Int, statusText: String, headers: List[(String, String)]) extends MessageStart

  case object ResponseEnd extends MessageEnd

  case class EntityData(buf: ByteBuffer) extends MessageData

  class ParserException(message: String) extends Exception(message)

}

trait HttpParser {

  import HttpParser._

  def options: Options

  protected var url: String = _
  protected var method: String = _
  protected var statusCode: Int = _
  protected var statusText: String = _
  protected var headers: List[(String, String)] = Nil
  protected var expectLength = 0
  protected var buffer: ByteBuffer = ByteBuffer.empty
  protected var offset: Int = 0
  private var state: ParserState = messageStart

  protected def getHeader(name: String) = {
    val name_ = name.toLowerCase
    headers.find(_._1.toLowerCase == name_).map(_._2)
  }

  protected def readLine(maxLength: Int) = {
    val end = lineEnd(buffer, offset, maxLength)
    val s = buffer.sliceString(offset, end, options.encoding)
    offset = end
    s
  }

  def process(buf: ByteBuffer) = {
    buffer = buffer.concat(buf)
    try {
      while (true)
        state = state()
    } catch {
      case NotEnoughData =>
        if (offset > 0)
          buffer = buffer.slice(offset)
    }
  }

  def processEnd() = {
    if (state == identity) {
      state = messageEnd()
    } else if (state != messageStart || buffer.length > 0) {
      throw new ParserException("unexpected end")
    }
  }

  protected def messageStart: ParserState = {
    if (options.response) responseStart else requestStart
  }

  private val RequestLine = """^([A-Z]+)\s+(.+)\s+HTTP/1\.1$""".r

  protected val requestStart: ParserState = new ParserState {
    override def apply() = {
      val line = readLine(options.maxLengthOfRequestLine)
      line match {
        case RequestLine(method0, url0) =>
          method = method0
          url = url0
          messageHeaders
        case _ => throw new ParserException("not HTTP/1.1 protocol")
      }
    }
  }

  private val StatusLine = """^HTTP/1\\.1\s([0-9]+)\s(.+)$""".r

  protected val responseStart: ParserState = new ParserState {
    override def apply() = {
      val line = readLine(options.maxLengthOfStatusLine)
      line match {
        case StatusLine(code, text) =>
          statusCode = code.toInt
          statusText = text
          messageHeaders
        case _ => throw new ParserException("not HTTP/1.1 protocol")
      }
    }
  }

  protected val messageEnd: ParserState = new ParserState {
    override def apply() = {
      resolve(if (options.response) ResponseEnd else RequestEnd)
      messageStart
    }
  }

  private val HeaderLine = """^([A-Z\-]+)\s*:\s*(.*)$""".r
  private val HeaderValueLine = """^\s+(.+)$""".r

  protected def parseHeaders(next: ParserState): ParserState = new ParserState {
    override def apply() = {
      val line = readLine(options.maxLengthOfHeader)
      line match {
        case "" =>
          headers = headers.reverse
          next
        case HeaderLine(name, content) =>
          headers = (name, content) :: headers
          this
        case HeaderValueLine(content) =>
          headers.headOption match {
            case Some(x) =>
              headers = (x._1, x._2 + content) :: headers.tail
              this
            case None => throw new ParserException("invalid header")
          }
        case _ => throw new ParserException("invalid header")
      }
    }
  }

  protected val entityStart: ParserState = new ParserState {
    override def apply() = {
      resolve(RequestStart(method, url, headers))
      method = null
      url = null
      headers = Nil
      getHeader("Transfer-Encoding") match {
        case Some(x) =>
          if (x == "identity") identity else chunkStart
        case None =>
          getHeader("Content-Length") match {
            case Some(x) =>
              expectLength = x.toInt
              lengthFixed
            case None =>
              messageEnd
          }
      }
    }
  }

  protected val messageHeaders = parseHeaders(entityStart)

  protected def expectContent(next: ParserState): ParserState = new ParserState {
    override def apply() = {
      val len = expectLength.min(buffer.length - offset)
      val end = offset + len
      val buf = buffer.slice(offset, end)
      offset = end
      expectLength -= len
      resolve(EntityData(buf))
      if (expectLength > 0)
        throw NotEnoughData
      next
    }
  }

  protected val lengthFixed: ParserState = expectContent(messageEnd)

  private val ChunkHead = "^([0-9]+)(;.*)?$".r

  protected val chunkStart: ParserState = new ParserState {
    override def apply() = {
      val line = readLine(options.maxLengthOfChunkHead)
      line match {
        case ChunkHead(size, _) =>
          expectLength = size.toInt
          if (expectLength > 0)
            chunkData
          else
            chunkTrailer
        case _ => throw new ParserException("invalid chunk format")
      }
    }
  }

  protected val chunkEnd: ParserState = new ParserState {
    override def apply() = {
      if (buffer(offset) != 0x0d || buffer(offset + 1) != 0x0a)
        throw new ParserException("invalid chunk format")
      chunkStart
    }
  }

  protected val chunkData: ParserState = expectContent(chunkEnd)

  protected val chunkTrailer: ParserState = parseHeaders(messageEnd)

  protected val identity: ParserState = new ParserState {
    override def apply() = {
      val buf =
        if (offset > 0)
          buffer.slice(offset)
        else
          buffer
      offset += buf.length
      resolve(EntityData(buf))
      identity
    }
  }

  protected def resolve(e: Event): Unit
}
