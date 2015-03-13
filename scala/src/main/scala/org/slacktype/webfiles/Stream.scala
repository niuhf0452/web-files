package org.slacktype.webfiles

import scala.concurrent.ExecutionContext

trait Stream[A] {
  self =>

  def read(reader: StreamReader[A])(implicit executor: ExecutionContext): Unit

  def unread(reader: StreamReader[A]): Unit

  def cancel(ex: Throwable): Unit

  def cancel(): Unit = cancel(new UserCancelledException)

  def pending: Boolean

  def resume(): Unit

  def map[B](f: A => B)(implicit executor: ExecutionContext): Stream[B] = {
    new Stream.Mapped(this, f)
  }

  def filter(f: A => Boolean)(implicit executor: ExecutionContext): Stream[A] = {
    new Stream.Filtered(this, f)
  }
}

class UserCancelledException extends Exception

trait StreamReader[A] {
  def onBuffer(buf: A): Unit

  def onEnd(): Unit

  def onError(ex: Throwable): Unit
}

object Stream {

  import scala.concurrent._
  import scala.util.control._

  trait EmptyReader[A] extends StreamReader[A] {
    def onBuffer(buf: A) = ()

    def onEnd() = ()

    def onError(ex: Throwable) = ()
  }

  trait Stream0[A] extends Stream[A] {
    private var readers = Map.empty[StreamReader[A], ReaderWrap[A]]

    def read(r: StreamReader[A])(implicit e: ExecutionContext) = this.synchronized {
      readers += r -> new ReaderWrap(r)
    }

    def unread(r: StreamReader[A]) = this.synchronized {
      readers -= r
    }

    protected def notifyError(ex: Throwable) = {
      readers.values foreach (_.onError(ex))
    }

    protected def notifyEnd() = {
      readers.values foreach (_.onEnd())
    }

    protected def notifyBuffer(buf: A) = {
      readers.values foreach (_.onBuffer(buf))
    }
  }

  trait Stream1[A] extends Stream0[A] {
    var pending = false
    protected var readEnd = false
    private var cancelException: Option[Throwable] = None

    def cancel(ex: Throwable) = this.synchronized {
      if (cancelException.isEmpty)
        cancelException = Some(ex)
    }

    def resume() = this.synchronized {
      if (!readEnd && !pending) {
        cancelException match {
          case Some(ex) =>
            readEnd = true
            notifyError(ex)
          case None =>
            try {
              pending = true
              read0()
            } catch {
              case NonFatal(ex) =>
                cancel(ex)
                completeRead(null.asInstanceOf[A])
            }
        }
      }
    }

    protected def completeRead(buf: A): Unit = this.synchronized {
      assert(pending)
      pending = false
      cancelException match {
        case Some(ex) =>
          readEnd = true
          notifyError(ex)
        case None =>
          if (readEnd)
            notifyEnd()
          else
            notifyBuffer(buf)
      }
    }

    protected def read0(): Unit
  }

  private class ReaderWrap[A](val reader: StreamReader[A])(implicit executor: ExecutionContext) {
    private var last = Future.successful(())

    def onError(ex: Throwable) = {
      last = last.andThen {
        case _ => reader.onError(ex)
      }
    }

    def onEnd() = {
      last = last.andThen {
        case _ => reader.onEnd()
      }
    }

    def onBuffer(buf: A) = {
      last = last.andThen {
        case _ => reader.onBuffer(buf)
      }
    }
  }

  class Mapped[A, B](origin: Stream[A], f: A => B)(implicit executor: ExecutionContext) extends StreamReader[A] with Stream0[B] {
    origin.read(this)

    def onBuffer(buf: A) = {
      val x = f(buf)
      this.synchronized {
        notifyBuffer(x)
      }
    }

    def onEnd() = this.synchronized {
      notifyEnd()
    }

    def onError(ex: Throwable) = this.synchronized {
      notifyError(ex)
    }

    def pending = origin.pending

    def resume() = origin.resume()

    def cancel(ex: Throwable) = origin.cancel(ex)

    override def map[C](f0: B => C)(implicit executor: ExecutionContext): Stream[C] =
      origin.map(x => f0(f(x)))(executor)
  }

  class Filtered[A](origin: Stream[A], f: A => Boolean)(implicit executor: ExecutionContext) extends StreamReader[A] with Stream0[A] {
    origin.read(this)

    def onBuffer(buf: A) = {
      if (f(buf)) {
        this.synchronized {
          notifyBuffer(buf)
        }
      } else
        origin.resume()
    }

    def onEnd() = this.synchronized {
      notifyEnd()
    }

    def onError(ex: Throwable) = this.synchronized {
      notifyError(ex)
    }

    def pending = origin.pending

    def resume() = origin.resume()

    def cancel(ex: Throwable) = origin.cancel(ex)

    override def filter(f0: A => Boolean)(implicit executor: ExecutionContext): Stream[A] =
      origin.filter(x => f(x) && f0(x))(executor)
  }

}
