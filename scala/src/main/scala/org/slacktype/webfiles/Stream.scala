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

  def >>[B](processor: Stream.Processor[A, B])(implicit executor: ExecutionContext) = {
    processor << this
    processor
  }
}

class UserCancelledException extends Exception

trait StreamReader[-A] {
  def onBuffer(buf: A): Unit

  def onEnd(): Unit

  def onError(ex: Throwable): Unit
}

object Stream {

  import scala.concurrent._
  import scala.util.control._

  def handleError[A](f: Throwable => Unit) = {
    new EmptyReader[A] {
      override def onError(ex: Throwable) = f(ex)
    }
  }

  def handleEnd[A](f: => Unit) = {
    new EmptyReader[A] {
      override def onEnd() = f
    }
  }

  trait EmptyReader[A] extends StreamReader[A] {
    def onBuffer(buf: A) = ()

    def onEnd() = ()

    def onError(ex: Throwable) = ()
  }

  trait Stream0[A] extends Stream[A] {
    private val readers = scala.collection.concurrent.TrieMap.empty[StreamReader[A], ReaderWrap[A]]

    def read(r: StreamReader[A])(implicit e: ExecutionContext) = {
      readers.put(r, new ReaderWrap(r))
    }

    def unread(r: StreamReader[A]) = {
      readers.remove(r) match {
        case Some(x) => x.dispose()
        case None =>
      }
    }

    protected def notifyError(ex: Throwable) = {
      readers foreach (_._2.onError(ex))
    }

    protected def notifyEnd() = {
      readers foreach (_._2.onEnd())
    }

    protected def notifyBuffer(buf: A) = {
      readers foreach (_._2.onBuffer(buf))
    }
  }

  trait Stream1[A] extends Stream0[A] {
    private var pending0 = false
    private var isEnd = false

    def pending = pending0

    def cancel(ex: Throwable) = {
      assert(!pending0 && !isEnd)
      pending0 = true
      completeRead(ex)
    }

    def resume() = {
      assert(!pending0 && !isEnd)
      try {
        pending0 = true
        read()
      } catch {
        case NonFatal(ex) =>
          completeRead(ex)
      }
    }

    protected def completeRead(buf: A): Unit = {
      assert(pending0)
      pending0 = false
      notifyBuffer(buf)
    }

    protected def completeRead(): Unit = {
      assert(pending0)
      pending0 = false
      isEnd = true
      notifyEnd()
    }

    protected def completeRead(ex: Throwable): Unit = {
      assert(pending0)
      pending0 = false
      isEnd = true
      notifyError(ex)
    }

    protected def read(): Unit
  }

  private class ReaderWrap[A](val reader: StreamReader[A])(implicit executor: ExecutionContext) {

    import java.util.concurrent.atomic.AtomicReference
    import scala.collection.immutable

    private val ref = new AtomicReference[immutable.Queue[() => Unit]](null)

    private def pipe(f: () => Unit) = {
      var origin: immutable.Queue[() => Unit] = null
      var q: immutable.Queue[() => Unit] = null
      do {
        origin = ref.get()
        q = if (origin == null) immutable.Queue(f) else origin.enqueue(f)
      } while (!ref.compareAndSet(origin, q))
      if (origin == null)
        Future(process())
    }

    private def process() = {
      var run = true
      while (run) {
        val q = ref.get()
        if (q.isEmpty) {
          if (ref.compareAndSet(q, null))
            run = false
        } else {
          val (f, q0) = q.dequeue
          if (ref.compareAndSet(q, q0))
            f()
        }
      }
    }

    def dispose() = {
      ref.set(null)
    }

    def onError(ex: Throwable) = {
      pipe(() => reader.onError(ex))
    }

    def onEnd() = {
      pipe(() => reader.onEnd())
    }

    def onBuffer(buf: A) = {
      pipe(() => reader.onBuffer(buf))
    }
  }

  class Mapped[A, B](origin: Stream[A], f: A => B)(implicit executor: ExecutionContext) extends StreamReader[A] with Stream0[B] {
    origin.read(this)

    def onBuffer(buf: A) = notifyBuffer(f(buf))

    def onEnd() = notifyEnd()

    def onError(ex: Throwable) = notifyError(ex)

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
        notifyBuffer(buf)
      } else
        origin.resume()
    }

    def onEnd() = notifyEnd()

    def onError(ex: Throwable) = notifyError(ex)

    def pending = origin.pending

    def resume() = origin.resume()

    def cancel(ex: Throwable) = origin.cancel(ex)

    override def filter(f0: A => Boolean)(implicit executor: ExecutionContext): Stream[A] =
      origin.filter(x => f(x) && f0(x))(executor)
  }

  trait Processor[A, B] extends StreamReader[A] with Stream[B] {
    def upstream: Stream[A]

    def <<(upstream: Stream[A])(implicit executor: ExecutionContext): Unit

    def unbind(): Unit
  }

  trait Processor0[A, B] extends Processor[A, B] with Stream1[B] {
    private var input: Stream[A] = _

    protected def process(buf: A): Unit

    protected def processEnd(): Unit

    def upstream = input

    def <<(upstream: Stream[A])(implicit executor: ExecutionContext) = {
      require(input == null)
      input = upstream
      upstream.read(this)
    }

    def unbind() = {
      input.unread(this)
      input = null
    }

    override def cancel(ex: Throwable) = {
      input.cancel(ex)
    }

    def onBuffer(buf: A) = {
      try {
        process(buf)
      } catch {
        case NonFatal(ex) =>
          cancel(ex)
      }
    }

    def onEnd() = {
      try {
        processEnd()
      } catch {
        case NonFatal(ex) =>
          onError(ex)
      }
    }

    def onError(ex: Throwable) = {
      completeRead(ex)
    }
  }

  trait Processor1[A, B] extends Processor0[A, B] {

    import scala.collection.mutable

    protected val outputs = mutable.Queue[B]()
    private var inputEnd = false

    protected def process0(buf: A): Unit

    protected def processEnd0(): Unit

    protected def process(buf: A) = {
      process0(buf)
      read()
    }

    protected def processEnd() = {
      inputEnd = true
      processEnd0()
      read()
    }

    override def completeRead(ex: Throwable) = {
      super.completeRead(ex)
      outputs.clear()
    }

    override protected def read() = {
      if (outputs.nonEmpty)
        completeRead(outputs.dequeue())
      else if (inputEnd) {
        completeRead()
      } else
        upstream.resume()
    }
  }

  trait Slicer[A] extends Processor[A, A] with Stream0[A] {
    private var input: Stream[A] = _
    private var end = false

    protected def isEnd(buf: A): Boolean

    def <<(upstream: Stream[A])(implicit executor: ExecutionContext) = {
      require(input == null)
      input = upstream
      upstream.read(this)
    }

    def onBuffer(buf: A) = {
      if (isEnd(buf)) {
        end = true
        input.unread(this)
        notifyEnd()
      } else
        notifyBuffer(buf)
    }

    def onEnd() = notifyEnd()

    def onError(ex: Throwable) = notifyError(ex)

    def pending = {
      require(!end)
      input.pending
    }

    def resume() = {
      require(!end)
      input.resume()
    }

    def cancel(ex: Throwable) = {
      require(!end)
      input.cancel(ex)
    }
  }

}
