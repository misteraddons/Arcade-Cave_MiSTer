/*
 *    __   __     __  __     __         __
 *   /\ "-.\ \   /\ \/\ \   /\ \       /\ \
 *   \ \ \-.  \  \ \ \_\ \  \ \ \____  \ \ \____
 *    \ \_\\"\_\  \ \_____\  \ \_____\  \ \_____\
 *     \/_/ \/_/   \/_____/   \/_____/   \/_____/
 *    ______     ______       __     ______     ______     ______
 *   /\  __ \   /\  == \     /\ \   /\  ___\   /\  ___\   /\__  _\
 *   \ \ \/\ \  \ \  __<    _\_\ \  \ \  __\   \ \ \____  \/_/\ \/
 *    \ \_____\  \ \_____\ /\_____\  \ \_____\  \ \_____\    \ \_\
 *     \/_____/   \/_____/ \/_____/   \/_____/   \/_____/     \/_/
 *
 *  https://joshbassett.info
 *  https://twitter.com/nullobject
 *  https://github.com/nullobject
 *
 *  Copyright (c) 2020 Josh Bassett
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

package cave.dma

import axon.Util
import axon.mem.BurstWriteMemIO
import axon.util.Counter
import cave.types.FrameBufferIO
import cave.Config
import chisel3._
import chisel3.util._

/**
 * A direct memory access (DMA) controller for copying the frame buffer to DDR memory.
 *
 * @param addr The start address of the transfer.
 * @param numWords The number of words to transfer.
 * @param burstLength The length of the DDR burst.
 */
class FrameBufferDMA(addr: Long, numWords: Int, burstLength: Int) extends Module {
  /** The number of bursts */
  private val NUM_BURSTS = numWords / burstLength

  val io = IO(new Bundle {
    /** Swap the frame buffer */
    val swap = Input(Bool())
    /** Start the transfer */
    val start = Input(Bool())
    /** Asserted when the transfer is complete */
    val done = Output(Bool())
    /** Frame buffer port */
    val frameBuffer = new FrameBufferIO
    /** DDR port */
    val ddr = BurstWriteMemIO(Config.ddrConfig.addrWidth, Config.ddrConfig.dataWidth)
  })

  // Registers
  val busyReg = RegInit(false.B)

  // Asserted when the write will succeed
  val effectiveWrite = busyReg && !io.ddr.waitReq

  // Counters
  val (totalCounter, totalCounterDone) = Counter.static(numWords, enable = effectiveWrite)
  val (burstCounter, burstCounterDone) = Counter.static(NUM_BURSTS, enable = io.ddr.burstDone)

  // Control signals
  val write = !io.ddr.waitReq && busyReg
  val done = RegNext(burstCounterDone, false.B)

  // Calculate the DDR address
  val ddrAddr = {
    val mask = 0.U(log2Ceil(burstLength*8).W)
    val offset = io.swap ## burstCounter ## mask
    addr.U + offset
  }

  // Pad the pixel data, so that four 15-bit pixels pack into a 64-bit DDR word
  val pixelData = Util.padWords(io.frameBuffer.dout, 4, Config.FRAME_BUFFER_DATA_WIDTH, Config.ddrConfig.dataWidth/4)

  // Toggle the busy register
  when(io.start) { busyReg := true.B }.elsewhen(burstCounterDone) { busyReg := false.B }

  // Outputs
  io.done := done
  io.frameBuffer.rd := true.B
  io.frameBuffer.addr := Mux(effectiveWrite, totalCounter+&1.U, totalCounter)
  io.ddr.wr := busyReg
  io.ddr.addr := ddrAddr
  io.ddr.mask := Fill(io.ddr.maskWidth, 1.U)
  io.ddr.burstLength := burstLength.U
  io.ddr.din := pixelData

  printf(p"FrameBufferDMA(busy: $busyReg, burstCounter: $burstCounter ($burstCounterDone), totalCounter: $totalCounter ($totalCounterDone))\n")
}