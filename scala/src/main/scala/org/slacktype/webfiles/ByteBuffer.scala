package org.slacktype.webfiles

import java.nio.charset.Charset

trait ByteBuffer {
  def length: Int

  def apply(offset: Int): Byte

  def slice(start: Int, end: Int): ByteBuffer

  def slice(start: Int): ByteBuffer = slice(start, length - start)

  def sliceString(start: Int, end: Int, charset: Charset): String

  def copy(start: Int, end: Int, arr: Array[Byte], offset: Int): Unit

  def concat(bb: ByteBuffer): ByteBuffer

  def toJavaByteBuffer: java.nio.ByteBuffer
}

object ByteBuffer {

  import java.nio.{ByteBuffer => JByteBuffer}

  val empty: ByteBuffer = new ByteBuffer {
    def length = 0

    def apply(offset: Int) = throw new IndexOutOfBoundsException

    def slice(start: Int, end: Int) = this

    def sliceString(start: Int, end: Int, charset: Charset) = ""

    def copy(start: Int, end: Int, arr: Array[Byte], offset: Int) = ()

    def concat(bb: ByteBuffer) = bb

    val toJavaByteBuffer = JByteBuffer.allocate(0)
  }

  def apply(buf: Array[Byte], start: Int, end: Int): ByteBuffer = {
    new ByteBuffer1(buf, start, end)
  }

  def apply(buf: Array[Byte]): ByteBuffer = apply(buf, 0, buf.length)

  class ByteBuffer1(buf: Array[Byte], start: Int, end: Int) extends ByteBuffer {
    val length = end - start

    def apply(offset: Int) = {
      buf(start + offset)
    }

    def slice(start0: Int, end0: Int) = {
      if (end0 > start0)
        ByteBuffer(buf, start + start0, start + end0)
      else
        empty
    }

    def sliceString(start0: Int, end0: Int, charset: Charset) = {
      new String(buf, start + start0, end - start0, charset).intern()
    }

    def copy(start0: Int, end0: Int, arr: Array[Byte], offset: Int) = {
      Array.copy(buf, start + start0, arr, offset, end0 - start0)
    }

    def concat(bb: ByteBuffer) = new ByteBuffer2(this, bb, 0, length + bb.length)

    def toJavaByteBuffer = JByteBuffer.wrap(buf, start, end - start)
  }

  class ByteBuffer2(buf1: ByteBuffer, buf2: ByteBuffer, start: Int, end: Int) extends ByteBuffer {
    private val break1 = buf1.length

    val length = end - start

    def apply(offset: Int) = {
      val off = start + offset
      if (off >= break1)
        buf2(off - break1)
      else
        buf1(off)
    }

    def slice(start0: Int, end0: Int) = {
      if (end0 > start0) {
        val start1 = start + start0
        val end1 = start + end0
        if (start1 >= break1)
          buf2.slice(start1 - break1, end1 - break1)
        else if (end1 > break1)
          new ByteBuffer2(buf1.slice(start1), buf2.slice(0, end1 - buf1.length), 0, end1 - start1)
        else
          buf1.slice(start1, end1)
      } else
        empty
    }

    def sliceString(start0: Int, end0: Int, charset: Charset) = {
      val start1 = start + start0
      val end1 = start + end0
      if (start1 >= break1)
        buf2.sliceString(start1 - break1, end1 - break1, charset)
      else {
        if (end1 > break1) {
          val arr = new Array[Byte](start1 - end1)
          buf1.copy(start1, break1, arr, 0)
          buf2.copy(0, end1 - break1, arr, break1 - start1)
          new String(arr, 0, arr.length).intern()
        } else
          buf1.sliceString(start1, end1, charset)
      }
    }

    def copy(start0: Int, end0: Int, arr: Array[Byte], offset: Int) = {
      val start1 = start + start0
      val end1 = start + end0
      if (start1 >= break1)
        buf2.copy(start1 - break1, end1 - break1, arr, offset)
      else {
        if (end1 > break1) {
          buf1.copy(start1, break1, arr, offset)
          buf2.copy(0, end1 - break1, arr, offset + break1 - start1)
        } else
          buf1.copy(start1, end1, arr, offset)
      }
    }

    def concat(bb: ByteBuffer) = new ByteBuffer2(this, bb, 0, length + bb.length)

    def toJavaByteBuffer = {
      val arr = new Array[Byte](length)
      copy(0, length, arr, 0)
      JByteBuffer.wrap(arr)
    }
  }

}
