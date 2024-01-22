package lltriscv.interconnect

import chisel3._
import chisel3.util._
import lltriscv.utils.CoreUtils
import lltriscv.utils.ChiselUtils._
import lltriscv.bus.SMAReaderIO
import lltriscv.core.record.StoreQueueBypassIO
import lltriscv.core.execute.MemoryAccessLength
import lltriscv.core.DataType

/*
 * SMA interconnect
 *
 * Copyright (C) 2024-2025 LoveLonelyTime
 */

/** SMA with store queue interconnect
  *
  * The read output port of the Memory executing component, bypassing store queue.
  */
class SMAWithStoreQueueInterconnect extends Module {
  val io = IO(new Bundle {
    val in = Flipped(new SMAReaderIO())
    val out = new SMAReaderIO()
    val bypass = new StoreQueueBypassIO()
  })

  io.out.valid := io.in.valid
  io.out.address := io.in.address
  io.out.readType := io.in.readType

  io.in.error := io.out.error
  io.in.ready := io.out.ready

  // Bypass - 32bits
  io.bypass.address := io.in.address
  private val data = Wire(Vec(4, DataType.aByte))
  for (i <- 0 until 4)
    data(i) := Mux(io.bypass.strobe(i), io.bypass.data.refByte(i), io.out.data.refByte(i))

  io.in.data := data(3) ## data(2) ## data(1) ## data(0)
}

/** SMA 2-readers interconnect
  *
  * Priority arbitration, in1 > in2
  */
class SMA2ReaderInterconnect extends Module {
  val io = IO(new Bundle {
    val in1 = Flipped(new SMAReaderIO())
    val in2 = Flipped(new SMAReaderIO())
    val out = new SMAReaderIO()
  })
  private object Status extends ChiselEnum {
    val idle, pending1, pending2 = Value
  }
  private val statusReg = RegInit(Status.idle)

  io.in1.ready := false.B
  io.in1.data := 0.U
  io.in1.error := false.B

  io.in2.ready := false.B
  io.in2.data := 0.U
  io.in2.error := false.B

  io.out.valid := false.B
  io.out.address := 0.U
  io.out.readType := MemoryAccessLength.byte

  when(statusReg === Status.idle) {
    when(io.in1.valid) {
      statusReg := Status.pending1
    }.elsewhen(io.in2.valid) {
      statusReg := Status.pending2
    }
  }

  when(statusReg === Status.pending1) {
    io.out <> io.in1

    when(io.out.ready) {
      statusReg := Status.idle
    }
  }

  when(statusReg === Status.pending2) {
    io.out <> io.in2

    when(io.out.ready) {
      statusReg := Status.idle
    }
  }
}