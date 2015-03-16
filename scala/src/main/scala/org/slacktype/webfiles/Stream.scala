package org.slacktype.webfiles

import scala.concurrent.ExecutionContext

trait Stream[A] {
  def read(reader: Stream.Reader[A])(implicit executor: ExecutionContext): Unit

  def unread(reader: Stream.Reader[A]): Unit

  def cancel(ex: Throwable): Unit

  def cancel(): Unit = cancel(new Stream.UserCancelledException)

  def pending: Boolean

  def resume(): Unit

  def map[B](f: A => B)(implicit executor: ExecutionContext): Stream[B]

  def filter(f: A => Boolean)(implicit executor: ExecutionContext): Stream[A]

  def >>[B](processor: Stream.Processor[A, B])(implicit executor: ExecutionContext) = {
    processor << this
    processor
  }
}

object Stream {

  trait Reader[-A] {
    def onBuffer(buf: A): Unit

    def onEnd(): Unit

    def onError(ex: Throwable): Unit
  }

  class UserCancelledException extends Exception

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

  trait EmptyReader[A] extends Reader[A] {
    def onBuffer(buf: A) = ()

    def onEnd() = ()

    def onError(ex: Throwable) = ()
  }

  trait Dispatch[A] {
    self: Stream[A] =>

    protected def notifyError(ex: Throwable): Unit

    protected def notifyEnd(): Unit

    protected def notifyBuffer(buf: A): Unit
  }

  trait SingleReaderDispatch[A] extends Dispatch[A] {
    self: Stream[A] =>

    private var reader: EventQueue[A] = _

    def read(reader0: Reader[A])(implicit executor: ExecutionContext) = {
      require(reader == null)
      reader = new EventQueue(reader0, executor)
    }

    def unread(r: Reader[A]) = {
      require(r eq reader.reader)
      reader.dispose()
      reader = null
    }

    def map[B](f: A => B)(implicit executor: ExecutionContext): Stream[B] = {
      val s = new MappedSingleDispatch(f)
      s << this
      s
    }

    def filter(f: A => Boolean)(implicit executor: ExecutionContext): Stream[A] = {
      val s = new FilteredSingleDispatch(f)
      s << this
      s
    }

    protected def notifyError(ex: Throwable) = reader.onError(ex)

    protected def notifyEnd() = reader.onEnd()

    protected def notifyBuffer(buf: A) = reader.onBuffer(buf)
  }

  trait MultipleReaderDispatch[A] extends Dispatch[A] {
    self: Stream[A] =>

    private val readers = scala.collection.concurrent.TrieMap.empty[Reader[A], EventQueue[A]]

    def read(reader: Reader[A])(implicit exectuor: ExecutionContext) = {
      readers.put(reader, new EventQueue(reader, exectuor))
    }

    def unread(r: Reader[A]) = {
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

    def map[B](f: A => B)(implicit executor: ExecutionContext): Stream[B] = {
      val s = new MappedMultipleDispatch(f)
      s << this
      s
    }

    def filter(f: A => Boolean)(implicit executor: ExecutionContext): Stream[A] = {
      val s = new FilteredMultipleDispatch(f)
      s << this
      s
    }
  }

  class SingleThreadExecutionContext(executor: ExecutionContext) extends ExecutionContext {
    private val running = new java.util.concurrent.atomic.AtomicInteger(0)
    private val queue = new java.util.concurrent.ConcurrentLinkedQueue[Runnable]()

    def execute(runnable: Runnable) = {
      queue.offer(runnable)
      val r = running.get()
      if ((r & 1) == 0 &&
        running.compareAndSet(r, r + 1)) {
        Future(process())
      }
    }

    def reportFailure(cause: Throwable) = ()

    private def process() = {
      var run = true
      while (run) {
        val runnable = queue.poll()
        if (runnable == null) {
          val r = running.get()
          running.set(r + 1)
          run = !queue.isEmpty && running.compareAndSet(r + 1, r + 2)
        } else {
          try {
            runnable.run()
          } catch {
            case NonFatal(ex) =>
              reportFailure(ex)
          }
        }
      }
    }

    def dispose() = {
      queue.clear()
    }
  }

  private class EventQueue[A](val reader: Reader[A], executor0: ExecutionContext) {
    private val executor = executor0 match {
      case x: SingleThreadExecutionContext => x
      case x => new SingleThreadExecutionContext(x)
    }

    def dispose() = {
      executor.dispose()
    }

    def onBuffer(buf: A) = {
      executor.execute(new BufferEvent(buf))
    }

    def onEnd() = {
      executor.execute(new EndEvent())
    }

    def onError(ex: Throwable) = {
      executor.execute(new ErrorEvent(ex))
    }

    class BufferEvent(buf: A) extends Runnable {
      def run() = reader.onBuffer(buf)
    }

    class EndEvent() extends Runnable {
      def run() = reader.onEnd()
    }

    class ErrorEvent(ex: Throwable) extends Runnable {
      def run() = reader.onError(ex)
    }

  }

  trait Mapped[A, B] extends Processor[A, B] with SingleSourced[A] {
    self: Dispatch[B] =>

    def mapFunc: A => B

    def onBuffer(buf: A) = notifyBuffer(mapFunc(buf))

    def onEnd() = notifyEnd()

    def onError(ex: Throwable) = notifyError(ex)

    def pending = upstream.pending

    def resume() = upstream.resume()

    def map[C](f0: B => C)(implicit executor: ExecutionContext): Stream[C] = {
      val f = mapFunc
      upstream.map(x => f0(f(x)))(executor)
    }
  }

  class MappedSingleDispatch[A, B](val mapFunc: A => B) extends SingleReaderDispatch[B] with Mapped[A, B]

  class MappedMultipleDispatch[A, B](val mapFunc: A => B) extends MultipleReaderDispatch[B] with Mapped[A, B]

  trait Filtered[A] extends Processor[A, A] with SingleSourced[A] {
    self: Dispatch[A] =>

    def filterFunc: A => Boolean

    def onBuffer(buf: A) = {
      if (filterFunc(buf)) {
        notifyBuffer(buf)
      } else
        upstream.resume()
    }

    def onEnd() = notifyEnd()

    def onError(ex: Throwable) = notifyError(ex)

    def pending = upstream.pending

    def resume() = upstream.resume()

    def filter(f0: A => Boolean)(implicit executor: ExecutionContext): Stream[A] = {
      val f = filterFunc
      upstream.filter(x => f(x) && f0(x))(executor)
    }
  }

  class FilteredSingleDispatch[A](val filterFunc: A => Boolean) extends SingleReaderDispatch[A] with Filtered[A]

  class FilteredMultipleDispatch[A](val filterFunc: A => Boolean) extends MultipleReaderDispatch[A] with Filtered[A]

  trait AbstractStream[A] extends Stream[A] with Dispatch[A] {

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

  trait Processor[A, B] extends Reader[A] with Stream[B] {
    def <<(upstream: Stream[A])(implicit executor: ExecutionContext): Unit

    def unbind(upstream: Stream[A]): Unit
  }

  trait SingleSourced[A] {
    self: Processor[A, _] =>

    private var input: Stream[A] = _

    def upstream = input

    def <<(upstream: Stream[A])(implicit executor: ExecutionContext) = {
      require(input == null)
      input = upstream
      upstream.read(this)
    }

    def unbind(upstream: Stream[A]) = {
      require(upstream eq input)
      input.unread(this)
      input = null
    }

    def cancel(ex: Throwable) = {
      input.cancel(ex)
    }
  }

  trait AbstractProcessor[A, B] extends Processor[A, B] with AbstractStream[B] {
    protected def process(buf: A): Unit

    protected def processEnd(): Unit

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

  trait QueueBasedProcessor[A, B] extends AbstractProcessor[A, B] with SingleSourced[A] {

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

  trait BufferedProcessor[A] extends AbstractProcessor[A, A] with SingleSourced[A] {
    private val buffers = scala.collection.mutable.Queue[A]()
    private var waiting = false
    private var full = false
    private var end = false

    protected def limit(buf: A, inc: Boolean): Boolean

    protected def process(buf: A) = {
      buffers.synchronized {
        if (waiting) {
          waiting = false
          completeRead(buf)
        } else {
          buffers.enqueue(buf)
        }
        if (limit(buf, inc = true))
          full = true
        else
          upstream.resume()
      }
    }

    protected def processEnd() = {
      buffers.synchronized {
        end = true
        if (waiting)
          completeRead()
      }
    }

    protected def read() = {
      buffers.synchronized {
        if (buffers.isEmpty) {
          if (end)
            completeRead()
          else {
            waiting = true
            upstream.resume()
          }
        } else {
          val buf = buffers.dequeue()
          completeRead(buf)
          if (full && !limit(buf, inc = false)) {
            full = false
            upstream.resume()
          }
        }
      }
    }
  }

}
