package org.slacktype.webfiles

trait AsyncReadable[A] {
  def onReceive(f: A => Unit): AutoCloseable

  def onError(f: Throwable => Unit): AutoCloseable

  def resume(): Unit
}

trait AsyncWritable[A] {
  def onDrain(f: () => Unit): Unit

  def write(buf: A): Unit
}

trait AsyncStream[A, B] extends AsyncWritable[A] with AsyncReadable[B]

object AsyncStream {

  import scala.collection.mutable

  trait Readable[A] extends AsyncReadable[A] {
    private val receiveListeners = mutable.Set[A => Unit]()

    def onReceive(f: A => Unit) = {
      receiveListeners.add(f)
      new AutoCloseable {
        override def close() = {
        }
      }
    }

    def offReceive(f: A => Unit) = {
      receiveListeners.remove(f)
    }

    def resume() = {
    }

    protected def read(buf: A) = {
    }

    protected def read(): Unit
  }

}
