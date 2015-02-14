package org.slacktype.webfiles

sealed trait QueueState[+A] {
  def isEnd: Boolean
}

case class QueueItem[A](item: A) extends QueueState[A] {
  def isEnd = false
}

sealed trait QueueEnd extends QueueState[Nothing] {
  def isEnd = true
}

case class QueueError(ex: Throwable) extends QueueEnd

case object QueueFinish extends QueueEnd

trait AsyncStated {
  @inline final def isEnd = endState.isDefined

  def endState: Option[QueueEnd]
}

trait AsyncEnqueue[A] extends AsyncStated {
  def enqueuePending: Boolean

  def enqueue(buf: QueueState[A])(f: () => Unit): Unit

  @inline final def enqueue(buf: A)(f: () => Unit): Unit = enqueue(QueueItem(buf))(f)

  @inline final def end()(f: () => Unit): Unit = enqueue(QueueFinish)(f)

  @inline final def <<(upstream: AsyncDequeue[A]): Unit = upstream >> this
}

trait AsyncDequeue[A] extends AsyncStated {
  def dequeuePending: Boolean

  def dequeue(f: QueueState[A] => Unit): Unit

  def >>(downstream: AsyncEnqueue[A]): Unit = {
    def push(): Unit = {
      if (!downstream.isEnd) {
        dequeue { x =>
          downstream.enqueue(x)(push)
        }
      }
    }
    push()
  }
}

trait AsyncQueue[A, B] extends AsyncEnqueue[A] with AsyncDequeue[B] {
  def >>>[C](downstream: AsyncQueue[B, C]): AsyncQueue[A, C] = {
    this >> downstream
    new AsyncQueue.PipedQueue(this, downstream)
  }
}

object AsyncQueue {

  import scala.collection.mutable
  import scala.util.control._

  final class PipedQueue[A, B](first: AsyncQueue[A, _], last: AsyncQueue[_, B]) extends AsyncQueue[A, B] {
    @inline def endState = last.endState

    @inline def enqueuePending = first.enqueuePending

    @inline def dequeuePending = last.dequeuePending

    @inline def enqueue(buf: QueueState[A])(f: () => Unit) =
      first.enqueue(buf)(f)

    @inline def dequeue(f: QueueState[B] => Unit) =
      last.dequeue(f)

    @inline override def >>(downstream: AsyncEnqueue[B]) = last >> downstream

    override def >>>[C](downstream: AsyncQueue[B, C]) = {
      last >> downstream
      new PipedQueue(first, downstream)
    }
  }

  trait Stated extends AsyncStated {
    private var endState0: Option[QueueEnd] = None

    final def endState = endState0

    protected def setEndState(v: QueueEnd) = {
      assert(endState0.isEmpty)
      endState0 = Some(v)
    }
  }

  trait Enqueue[A] extends AsyncEnqueue[A] with Stated {
    private var send: () => Unit = null

    final def enqueuePending = send != null

    final def enqueue(buf: QueueState[A])(f: () => Unit) = this.synchronized {
      require(send == null, "cannot enqueue in parallel")
      send = f
      if (endState.isDefined)
        enqueued()
      else
        try {
          enqueue0(buf)
        } catch {
          case NonFatal(ex) =>
            setEndState(QueueError(ex))
            enqueued()
        }
    }

    protected final def enqueued() = {
      try {
        val f = send
        send = null
        f()
      } catch {
        case NonFatal(ex) =>
          setEndState(QueueError(ex))
      }
    }

    protected def enqueue0(buf: QueueState[A]): Unit
  }

  trait Dequeue[A] extends AsyncDequeue[A] with Stated {
    private var receive: QueueState[A] => Unit = null

    final def dequeuePending = receive != null

    final def dequeue(f: QueueState[A] => Unit) = this.synchronized {
      require(receive == null, "cannot dequeue in parallel")
      receive = f
      endState match {
        case Some(x) => dequeued(x)
        case None =>
          try {
            dequeue0()
          } catch {
            case NonFatal(ex) =>
              dequeued(QueueError(ex))
          }
      }
    }

    protected final def dequeued(buf: QueueState[A]) = {
      buf match {
        case x: QueueEnd => setEndState(x)
        case _ =>
      }
      try {
        val f = receive
        receive = null
        f(buf)
      } catch {
        case NonFatal(ex) =>
          setEndState(QueueError(ex))
      }
    }

    protected def dequeue0(): Unit
  }


  trait Queue[A, B] extends Enqueue[A] with Dequeue[B] {
    protected def enqueue0(buf: QueueState[A]) = {
      buf match {
        case x: QueueItem[A] =>
          enqueue1(x)
          if (dequeuePending) {
            dequeue0()
          }
        case x: QueueEnd =>
          setEndState(x)
          enqueued()
      }
    }

    protected def dequeue0() = {
      val buf = dequeue1()
      if (buf != null && enqueuePending) {
        enqueued()
      }
    }

    protected def enqueue1(buf: QueueItem[A]): Unit

    protected def dequeue1(): QueueItem[B]
  }

  class FlipQueue[A] extends Enqueue[A] with Dequeue[A] {
    private var buffer: QueueItem[A] = null

    protected def enqueue0(buf: QueueState[A]) = {
      buf match {
        case x: QueueItem[A] =>
          if (dequeuePending) {
            dequeued(x)
            enqueued()
          } else {
            buffer = x
          }
        case x: QueueEnd =>
          setEndState(x)
          enqueued()
      }
    }

    protected def dequeue0() = {
      if (buffer != null) {
        val x = buffer
        buffer = null
        dequeued(x)
        enqueued()
      }
    }

  }

  class BufferedQueue[A](limit: Int) extends Enqueue[A] with Dequeue[A] {
    private val buffers = mutable.Queue[QueueItem[A]]()
    private var fullPending = false
    private var emptyPending = false

    protected def enqueue0(buf: QueueState[A]) = {
      buf match {
        case x: QueueItem[A] =>
          if (dequeuePending) {
            dequeued(x)
            enqueued()
          } else {
            buffers.enqueue(x)
            if (buffers.length <= limit || limit == 0)
              enqueued()
            else
              fullPending = true
          }
        case x: QueueEnd =>
          buffers.clear()
          setEndState(x)
          enqueued()
      }
    }

    protected def dequeue0() = {
      if (buffers.nonEmpty) {
        dequeued(buffers.dequeue())
        if (fullPending) {
          fullPending = false
          enqueued()
        }
      } else {
        emptyPending = true
      }
    }
  }

}

