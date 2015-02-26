package org.slacktype.webfiles

object Pipe {

  import scala.concurrent._
  import scala.util.control._

  trait Catcher {
    def onError(ex: Throwable): Unit
  }

  trait Receiver[A] extends Catcher {
    def onReceive(buf: A): Unit

    def onEnd(): Unit
  }

  trait Sender[A] extends Catcher {
    def onDrain(): Unit
  }

  trait Cancellable {
    def cancel(ex: Throwable): Unit

    def cancel(): Unit = cancel(new UserCancelledException)
  }

  trait Readable[A] extends Cancellable {
    self =>
    def read(receiver: Receiver[A])(implicit executor: ExecutionContext): Unit

    def unread(): Unit

    def watch(receiver: Receiver[A])(implicit executor: ExecutionContext): Unit

    def unwatch(receiver: Receiver[A]): Unit

    def readPending: Boolean

    def resume(): Unit

    def map[B](f: A => B): Readable[B] = new MappedReadable(this, f)
  }

  trait Writable[A] extends Cancellable {
    def write(sender: Sender[A])(implicit executor: ExecutionContext): Unit

    def unwrite(): Unit

    def writePending: Boolean

    def write(buf: A): Unit

    def end(): Unit

    def <<(upstream: Readable[A])(implicit executor: ExecutionContext): Unit =
      new Forwarder(upstream, this)
  }

  trait Pipe[A, B] extends Writable[A] with Readable[B] {
    def >>[C](downstream: Pipe[B, C])(implicit executor: ExecutionContext): Pipe[A, C] = {
      downstream << this
      new Piped(this, downstream)
    }
  }

  class UserCancelledException extends Exception

  trait EmptyReceiver[A] extends Receiver[A] {
    def onReceive(buf: A) = ()

    def onEnd() = ()

    def onError(ex: Throwable) = ()
  }

  trait Cancellable0 extends Cancellable {
    protected var cancelException: Option[Throwable] = None

    def cancel(ex: Throwable) = this.synchronized {
      if (cancelException.isEmpty)
        cancelException = Some(ex)
    }
  }

  trait ReadWatchable[A] {
    private var watchers0 = Map.empty[Receiver[A], ReceiverWrap[A]]

    def watch(receiver: Receiver[A])(implicit executor: ExecutionContext) = this.synchronized {
      watchers0 += receiver -> new ReceiverWrap(receiver)
    }

    def unwatch(receiver: Receiver[A]) = this.synchronized {
      watchers0 -= receiver
    }

    def watchers = watchers0.values
  }

  trait Readable0[A] extends Readable[A] with Cancellable0 with ReadWatchable[A] {
    var readPending = false
    protected var readEnd = false

    private var receiver0: ReceiverWrap[A] = _

    def receiver = receiver0.receiver

    def read(r: Receiver[A])(implicit e: ExecutionContext) = {
      receiver0 = new ReceiverWrap(r)
    }

    def unread() = {
      receiver0 = null
    }

    def resume() = this.synchronized {
      if (!readEnd && !readPending) {
        cancelException match {
          case Some(ex) =>
            readEnd = true
            onReadError(ex)
          case None =>
            try {
              readPending = true
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
      assert(readPending)
      readPending = false
      cancelException match {
        case Some(ex) =>
          readEnd = true
          onReadError(ex)
        case None =>
          if (readEnd)
            onReadEnd()
          else
            onRead(buf)
      }
    }

    protected def onReadError(ex: Throwable) = {
      receiver0.onError(ex)
      watchers foreach (_.onError(ex))
    }

    protected def onReadEnd() = {
      receiver0.onEnd()
      watchers foreach (_.onEnd())
    }

    protected def onRead(buf: A) = {
      receiver0.onRead(buf)
      watchers foreach (_.onRead(buf))
    }

    protected def read0(): Unit
  }

  private class ReceiverWrap[A](val receiver: Receiver[A])(implicit executor: ExecutionContext) {
    private var last = Future.successful(())

    def onError(ex: Throwable) = {
      last = last.andThen {
        case _ =>
          receiver.onError(ex)
      }
    }

    def onEnd() = {
      last = last.andThen {
        case _ =>
          receiver.onEnd()
      }
    }

    def onRead(buf: A) = {
      last = last.andThen {
        case _ =>
          receiver.onReceive(buf)
      }
    }
  }

  class MappedReadable[A, B](origin: Readable[A], f: A => B) extends Readable[B] with ReadWatchable[B] with Receiver[A] {
    private var receiver: Receiver[B] = _

    def read(r: Receiver[B])(implicit executor: ExecutionContext) = {
      receiver = r
      origin.read(this)
    }

    def unread() = origin.unread()

    def readPending = origin.readPending

    def resume() = origin.resume()

    def cancel(ex: Throwable) = origin.cancel(ex)

    def onReceive(buf: A) = {
      val x = f(buf)
      receiver.onReceive(x)
      this.synchronized {
        watchers foreach (_.onRead(x))
      }
    }

    def onEnd() = receiver.onEnd()

    def onError(ex: Throwable) = receiver.onError(ex)

    override def map[C](f0: B => C): Readable[C] = new MappedReadable(origin, x => f0(f(x)))
  }

  trait Writable0[A] extends Writable[A] with Cancellable0 {
    var writePending = false
    protected var writeEnd = false

    private var sender0: SenderWrap = _

    def sender = sender0.sender

    def write(s: Sender[A])(implicit executor: ExecutionContext) = {
      sender0 = new SenderWrap(s)
    }

    def unwrite() = {
      sender0 = null
    }

    def write(buf: A): Unit = write(buf, isEnd = false)

    def end() = write(null.asInstanceOf[A], isEnd = true)

    protected def write(buf: A, isEnd: Boolean): Unit = this.synchronized {
      if (!writeEnd) {
        if (writePending)
          cancel(new IllegalStateException("do NOT call write/end while pending on write"))
        cancelException match {
          case Some(ex) =>
            writeEnd = true
            onWriteError(ex)
          case None =>
            writePending = true
            try {
              if (isEnd) {
                writeEnd = true
                end0()
              } else
                write0(buf)
            } catch {
              case NonFatal(ex) =>
                cancel(ex)
                completeWrite()
            }
        }
      }
    }

    protected def completeWrite(): Unit = this.synchronized {
      assert(writePending)
      writePending = false
      cancelException match {
        case Some(ex) =>
          writeEnd = true
          onWriteError(ex)
        case None =>
          if (!writeEnd)
            onWriteDrain()
      }
    }

    protected def onWriteError(ex: Throwable) = {
      sender0.onError(ex)
    }

    protected def onWriteDrain() = {
      sender0.onDrain()
    }

    protected def write0(buf: A): Unit

    protected def end0(): Unit
  }

  private class SenderWrap(val sender: Sender[_])(implicit executor: ExecutionContext) {
    private var last = Future.successful(())

    def onError(ex: Throwable) = {
      last = last.andThen {
        case _ =>
          sender.onError(ex)
      }
    }

    def onDrain() = {
      last = last.andThen {
        case _ =>
          sender.onDrain()
      }
    }
  }

  class Piped[A, B](first: Pipe[A, _], last: Pipe[_, B]) extends Pipe[A, B] with Readable[B] with Writable[A] {
    def read(receiver: Receiver[B])(implicit executor: ExecutionContext) = last.read(receiver)

    def unread() = last.unread()

    def watch(receiver: Receiver[B])(implicit executor: ExecutionContext) = last.watch(receiver)

    def unwatch(receiver: Receiver[B]) = last.unwatch(receiver)

    def write(sender: Sender[A])(implicit executor: ExecutionContext) = first.write(sender)

    def unwrite() = first.unwrite()

    def readPending = last.readPending

    def resume() = last.resume()

    def writePending = first.writePending

    def write(buf: A) = first.write(buf)

    def end() = first.end()

    def cancel(ex: Throwable) = first.cancel(ex)

    override def <<(upstream: Readable[A])(implicit executor: ExecutionContext) = first << upstream

    override def >>[C](downstream: Pipe[B, C])(implicit executor: ExecutionContext): Pipe[A, C] = {
      downstream << last
      new Piped(first, downstream)
    }
  }

  class Forwarder[A](upstream: Readable[A], downstream: Writable[A])(implicit executor: ExecutionContext) extends Receiver[A] with Sender[A] {
    upstream.read(this)
    downstream.write(this)
    upstream.resume()

    def onReceive(buf: A) = {
      downstream.write(buf)
    }

    def onEnd() = {
      downstream.end()
    }

    def onDrain() = {
      upstream.resume()
    }

    def onError(ex: Throwable) = {
      downstream.cancel(ex)
      upstream.cancel(ex)
    }
  }

  class Flip[A] extends Pipe[A, A] with Readable0[A] with Writable0[A] {
    private var swap: A = _

    protected def read0() = {
      if (writePending) {
        val tmp = swap
        swap = null.asInstanceOf[A]
        completeRead(tmp)
        completeWrite()
      }
    }

    protected def write0(buf: A) = {
      if (readPending) {
        completeRead(buf)
        completeWrite()
      } else
        swap = buf
    }

    protected def end0() = {
      readEnd = true
      if (readPending)
        completeRead(null.asInstanceOf[A])
      completeWrite()
    }
  }

}
