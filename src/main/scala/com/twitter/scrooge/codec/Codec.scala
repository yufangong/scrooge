package com.twitter.scrooge.codec

import java.io.IOException
import scala.collection.Map
import scala.collection.mutable
import net.lag.naggati.{Decoder, End, ProtocolError, Step}
import net.lag.naggati.Steps._

object Type {
  val STOP = 0
  val VOID = 1
  val BOOL = 2
  val BYTE = 3
  val DOUBLE = 4
  val I16 = 6
  val I32 = 8
  val I64 = 10
  val STRING = 11
  val STRUCT = 12
  val MAP = 13
  val SET = 14
  val LIST = 15
}

object MessageType {
  val CALL = 1
  val REPLY = 2
  val EXCEPTION = 3
}

case class ProtocolException(reason: String) extends IOException(reason)

trait Decodable[T] {
  def decode(f: T => Step): Step
}

/*
def pack_call(method_name, *args)
  [ VERSION_1, MessageTypes::CALL, method_name.size, method_name, 0, pack_struct(args) ].pack("nnNa*Na*")
end
def pack_struct(fields)
  fields.map { |field| pack_field(*field) }.join + [ Types::STOP ].pack("c")
end
def pack_field(name, type, fid, value)
  [ type, fid, pack_value(type, value) ].pack("cna*")
end
s.write_all(pack_call("stats", [ "reset", Types::BOOL, -1, false ]))

*/


case class FieldHeader(ftype: Int, fid: Int)

object Codec {
  val VERSION_1 = 0x8001

  def readBoolean(f: Boolean => Step) = readInt8 { b => f(b != 0) }
  def readByte(f: Byte => Step) = readInt8 { b => f(b) }
  def readI16(f: Short => Step) = readInt16(f)
  def readI32(f: Int => Step) = readInt32(f)
  def readI64(f: Long => Step) = readInt64(f)
  def readDouble(f: Double => Step) = readDoubleBE(f)
  def readString(f: String => Step) = readI32 { len => readByteBuffer(len) { buffer => f(new String(buffer)) } }
  def readBinary(f: Array[Byte] => Step) = readI32 { len => readByteBuffer(len)(f) }

  def readList[T](itemtype: Int)(itemDecoder: (T => Step) => Step)(f: Seq[T] => Step) = {
    val list = new mutable.ListBuffer[T]
    readInt8 { itype =>
      readInt32 { len =>
        if (itype == itemtype) {
          readN(len, itype, itemDecoder) { list += _ } { f(list.toList) }
        } else {
          skipN(len, itype) { f(list.toList) }
        }
      }
    }
  }

  def readMap[K, V](keytype: Int, valuetype: Int)(keyDecoder: (K => Step) => Step)(valueDecoder: (V => Step) => Step)(f: Map[K, V] => Step) = {
    val map = new mutable.HashMap[K, V]
    readInt8 { ktype =>
      readInt8 { vtype =>
        readInt32 { len =>
          if (ktype == keytype && vtype == valuetype) {
            readNPairs(len, ktype, vtype, keyDecoder, valueDecoder) { (k, v) => map(k) = v } { f(map) }
          } else {
            skipNPairs(len, ktype, vtype) { f(map) }
          }
        }
      }
    }
  }

  def readStruct[T](struct: T, f: T => Step)(decoder: PartialFunction[(Int, Int), Step]) = readFieldHeader { fieldHeader =>
    if (fieldHeader.ftype == Type.STOP) {
      f(struct)
    } else {
      decoder(fieldHeader.fid, fieldHeader.ftype)
    }
  }

  private def readN[T](count: Int, ftype: Int, itemDecoder: (T => Step) => Step)(itemProcessor: T => Unit)(f: => Step): Step = {
    if (count == 0) {
      f
    } else {
      itemDecoder { item =>
        itemProcessor(item)
        readN(count - 1, ftype, itemDecoder)(itemProcessor)(f)
      }
    }
  }

  private def readNPairs[T1, T2](count: Int, f1type: Int, f2type: Int, item1Decoder: (T1 => Step) => Step,
                                 item2Decoder: (T2 => Step) => Step)(itemProcessor: (T1, T2) => Unit)(f: => Step): Step = {
    if (count == 0) {
      f
    } else {
      item1Decoder { item1 =>
        item2Decoder { item2 =>
          itemProcessor(item1, item2)
          readNPairs(count - 1, f1type, f2type, item1Decoder, item2Decoder)(itemProcessor)(f)
        }
      }
    }
  }

  def readFieldHeader(f: FieldHeader => Step) = readInt8 { ftype => readInt16BE { fid => f(FieldHeader(ftype, fid)) } }

  def skip(ftype: Int)(f: => Step): Step = ftype match {
    case Type.STOP => f
    case Type.VOID => f
    case Type.BOOL => readBoolean { x => f }
    case Type.BYTE => readByte { x => f }
    case Type.DOUBLE => readDouble { x => f }
    case Type.I16 => readI16 { x => f }
    case Type.I32 => readI32 { x => f }
    case Type.I64 => readI64 { x => f }
    case Type.STRING => readString { x => f }
    case Type.STRUCT => skipStruct(f)
/*    case Type.MAP =>
    case Type.SET =>
    case Type.LIST =>
    */
  }

  private def skipStruct(f: => Step): Step = {
    readStruct[Unit](null, { _ => f }) { case (_, ftype) => Codec.skip(ftype) { skipStruct(f) } }
  }

  private def skipN(count: Int, ftype: Int)(f: => Step): Step = {
    if (count == 0) {
      f
    } else {
      skip(ftype) { skipN(count - 1, ftype)(f) }
    }
  }

  private def skipNPairs(count: Int, f1type: Int, f2type: Int)(f: => Step): Step = {
    if (count == 0) {
      f
    } else {
      skip(f1type) { skip(f2type) { skipNPairs(count - 1, f1type, f2type)(f) } }
    }
  }


  def readRequest() = {
    readInt64BE { header =>
      val version = (header >> 48) & 0xffff
      if (version != VERSION_1) throw new ProtocolException("Illegal protocol version")
      val messageType = (header >> 32) & 0xffff
      if (messageType != MessageType.CALL) throw new ProtocolException("Expected CALL, got " + messageType)
      val messageNameSize = (header & 0xffffffff)
      readByteBuffer(messageNameSize.toInt) { buffer =>
        val messageName = new String(buffer)
        readInt32BE { sequenceId => End
//          readRequestArgs(messageName, Nil)
        }
      }
    }
  }
}
