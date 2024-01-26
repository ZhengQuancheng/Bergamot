package lltriscv.core.retire

import chisel3._
import chisel3.util._
import lltriscv.core._
import lltriscv.core.record.ROBTableRetireIO
import lltriscv.core.record.RegisterUpdateIO
import lltriscv.core.record.CSRsWriteIO
import lltriscv.core.record.ExceptionRequestIO
import lltriscv.core.record.StoreQueueRetireIO
import lltriscv.cache.FlushCacheIO
import lltriscv.core.fetch.BranchPredictorUpdateIO
import lltriscv.core.execute.UpdateLoadReservationIO

/*
 * Instruction retire
 *
 * When an instruction is in a non speculative and committed status, it will be retired,
 * and the retired instruction will actually change the core status.
 * It will detect the results of retired instructions, such as whether they cause prediction failures,
 * whether they trigger exceptions, and write memory.
 *
 * Copyright (C) 2024-2025 LoveLonelyTime
 */

/** Instruction retire
  *
  * @param depth
  *   ROB table depth
  */
class InstructionRetire(depth: Int) extends Module {
  require(depth > 0, "ROB table depth must be greater than 0")
  val io = IO(new Bundle {
    // Retired interface
    val retired = Flipped(DecoupledIO(DataType.receipt))
    // Table retire interface
    val tableRetire = Flipped(new ROBTableRetireIO(depth))
    // Register update interface
    val update = new RegisterUpdateIO()
    // Predictor update interface
    val predictorUpdate = new BranchPredictorUpdateIO()
    // Store queue interface
    val store = new StoreQueueRetireIO()
    // Recovery interface
    val recover = Output(new Bool())
    val correctPC = Output(DataType.address)
    // CSR write interface
    val csr = new CSRsWriteIO()
    // Exception interface
    val exception = new ExceptionRequestIO()

    val updateLoadReservation = new UpdateLoadReservationIO()

    // Flush interface
    val dCacheFlush = new FlushCacheIO()
    val iCacheFlush = new FlushCacheIO()
    val tlbFlush = new FlushCacheIO()
  })

  private object Status extends ChiselEnum {
    val retire, dcache, icache, tlb = Value
  }

  private val statusReg = RegInit(Status.retire)

  private val flushID = RegInit(0.U)

  private val retireEntries =
    VecInit(
      io.tableRetire.entries(io.retired.bits(30, 0) ## 0.U),
      io.tableRetire.entries(io.retired.bits(30, 0) ## 1.U)
    )

  private val retireValid = VecInit(
    retireEntries(0).commit ||
      !retireEntries(0).valid,
    retireEntries(1).commit ||
      !retireEntries(1).valid
  )

  io.recover := false.B
  io.correctPC := 0.U
  io.update.entries.foreach(item => {
    item.rd := 0.U
    item.result := 0.U
  })

  io.predictorUpdate.entries.foreach(item => {
    item.address := 0.U
    item.jump := false.B
    item.pc := 0.U
    item.valid := false.B
  })

  io.store.entries.foreach(item => {
    item.en := false.B
    item.id := 0.U
  })
  io.csr.wen := false.B
  io.csr.address := 0.U
  io.csr.data := 0.U

  io.exception.xret := false.B
  io.exception.trigger := false.B
  io.exception.exceptionPC := 0.U
  io.exception.exceptionVal := 0.U
  io.exception.exceptionCode := 0.U

  io.dCacheFlush.req := false.B
  io.iCacheFlush.req := false.B
  io.tlbFlush.req := false.B

  io.updateLoadReservation.load := false.B
  io.updateLoadReservation.address := 0.U
  io.updateLoadReservation.valid := false.B

  private def gotoExceptionHandler(id: Int) = {
    io.recover := true.B

    io.exception.trigger := true.B
    io.exception.exceptionPC := retireEntries(id).pc
    io.exception.exceptionVal := 0.U
    io.exception.exceptionCode := retireEntries(id).executeResult.exceptionCode
    io.correctPC := io.exception.handlerPC

    io.retired.ready := true.B
    printf("Exception!!!! pc = %d\n", retireEntries(id).pc)
  }

  private def gotoRecoveryPath(id: Int) = {
    io.recover := true.B
    io.correctPC := retireEntries(id).executeResult.real

    io.retired.ready := true.B
    printf(
      "spec violate!!!: pc = %d, sepc = %d, real = %d\n",
      retireEntries(id).pc,
      retireEntries(id).spec,
      retireEntries(id).executeResult.real
    )
  }

  private def gotoXRetPath(id: Int) = {
    io.exception.xret := true.B
    io.recover := true.B
    io.correctPC := io.exception.handlerPC

    io.retired.ready := true.B

    printf(
      "xret !!!: pc = %d\n",
      retireEntries(id).pc
    )
  }

  private def gotoCSRPath(id: Int) = {
    io.recover := true.B
    io.correctPC := retireEntries(id).executeResult.real

    io.retired.ready := true.B
  }

  private def hasException(id: Int) = retireEntries(id).valid && retireEntries(id).executeResult.exception
  private def hasBranch(id: Int) = retireEntries(id).valid && retireEntries(id).executeResult.real =/= retireEntries(id).spec

  private def hasCSR(id: Int) = retireEntries(id).valid && retireEntries(id).executeResult.writeCSR
  private def hasXRet(id: Int) = retireEntries(id).valid && retireEntries(id).executeResult.xret
  private def hasFlush(id: Int) = retireEntries(id).valid && (retireEntries(id).executeResult.flushDCache || retireEntries(id).executeResult.flushICache || retireEntries(id).executeResult.flushTLB)

  private def updateRegister(id: Int) = {
    io.update.entries(id).rd := retireEntries(id).rd
    io.update.entries(id).result := retireEntries(id).executeResult.result

    // Update predictor
    when(retireEntries(id).executeResult.branch) {
      updatePredictor(id)
    }
    printf(
      "retired instruction: pc = %d , r = %d, v = %d\n",
      retireEntries(id).pc,
      retireEntries(id).executeResult.result,
      retireEntries(id).valid
    )
  }

  private def updatePredictor(id: Int) = {
    io.predictorUpdate.entries(id).valid := true.B
    io.predictorUpdate.entries(id).jump := retireEntries(id).executeResult.real =/= retireEntries(id).executeResult.next
    io.predictorUpdate.entries(id).pc := retireEntries(id).pc
    io.predictorUpdate.entries(id).address := retireEntries(id).executeResult.real
  }

  private def retireStoreQueue(id: Int) = {
    when(retireEntries(id).executeResult.write) {
      io.store.entries(id).en := true.B
      io.store.entries(id).id := retireEntries(id).executeResult.writeID
    }
  }

  private def writeCSRs(id: Int) = {
    io.csr.wen := true.B
    io.csr.address := retireEntries(id).executeResult.csrAddress
    io.csr.data := retireEntries(id).executeResult.csrData
  }

  private def updateLoadReservation(id: Int) = {
    when(retireEntries(id).executeResult.sc) {
      io.updateLoadReservation.load := false.B
      io.updateLoadReservation.valid := true.B
    }.elsewhen(retireEntries(id).executeResult.lr) {
      io.updateLoadReservation.load := true.B
      io.updateLoadReservation.address := retireEntries(id).executeResult.lrAddress
      io.updateLoadReservation.valid := true.B
    }
  }

  io.retired.ready := false.B
  when(statusReg === Status.retire) {
    when(io.retired.valid && retireValid(0) && retireValid(1)) {
      when(hasException(0)) { // Exception ?
        gotoExceptionHandler(0)
      }.elsewhen(hasXRet(0)) { // XRet ?
        gotoXRetPath(0)
      }.elsewhen(hasCSR(0)) { // CSR ?
        writeCSRs(0)
        updateRegister(0)
        gotoCSRPath(0)
      }.elsewhen(hasBranch(0)) { // Branch ?
        updateRegister(0)
        gotoRecoveryPath(0)
      }.elsewhen(hasFlush(0)) { // Flush ?
        statusReg := Status.dcache
        flushID := 0.U
      }.otherwise { // Normal 0
        when(retireEntries(0).valid) {
          updateRegister(0)
          updateLoadReservation(0)
          retireStoreQueue(0)
        }

        when(hasException(1)) { // Exception ?
          gotoExceptionHandler(1)
        }.elsewhen(hasXRet(1)) { // XRet ?
          gotoXRetPath(1)
        }.elsewhen(hasCSR(1)) { // CSR ?
          writeCSRs(1)
          updateRegister(1)
          gotoCSRPath(1)
        }.elsewhen(hasBranch(1)) { // Branch ?
          updateRegister(1)
          gotoRecoveryPath(1)
        }.elsewhen(hasFlush(1)) { // Flush ?
          statusReg := Status.dcache
          flushID := 1.U
        }.otherwise { // Normal 1
          when(retireEntries(1).valid) {
            updateLoadReservation(1)
            updateRegister(1)
            retireStoreQueue(1)
          }
          io.retired.ready := true.B
        }
      }
    }
  }

  when(statusReg === Status.dcache) {
    when(retireEntries(flushID).executeResult.flushDCache) {
      io.dCacheFlush.req := true.B

      when(io.dCacheFlush.empty) { // Finish
        statusReg := Status.icache
      }
    }.otherwise {
      statusReg := Status.icache
    }
  }

  when(statusReg === Status.icache) {
    when(retireEntries(flushID).executeResult.flushICache) {
      io.iCacheFlush.req := true.B

      when(io.iCacheFlush.empty) { // Finish
        statusReg := Status.tlb
      }
    }.otherwise {
      statusReg := Status.tlb
    }
  }

  when(statusReg === Status.tlb) {
    when(retireEntries(flushID).executeResult.flushTLB) {
      io.tlbFlush.req := true.B

      when(io.tlbFlush.empty) { // Finish
        statusReg := Status.retire
        printf("Fench pc = %d\n", retireEntries(flushID).pc)
        io.recover := true.B
        io.correctPC := retireEntries(flushID).executeResult.real
        io.retired.ready := true.B
      }
    }.otherwise {
      statusReg := Status.retire
      printf("Fench pc = %d\n", retireEntries(flushID).pc)
      io.recover := true.B
      io.correctPC := retireEntries(flushID).executeResult.real
      io.retired.ready := true.B
    }
  }
}
