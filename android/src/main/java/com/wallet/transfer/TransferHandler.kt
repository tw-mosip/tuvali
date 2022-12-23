package com.wallet.transfer

import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import com.ble.central.Central
import com.transfer.Chunker
import com.transfer.Semaphore
import com.verifier.GattService
import com.verifier.transfer.message.*
import com.wallet.transfer.message.*
import com.wallet.transfer.message.IMessage
import com.wallet.transfer.message.ResponseTransferCompleteMessage
import java.util.*
import kotlin.time.*

@OptIn(ExperimentalUnsignedTypes::class)
class TransferHandler(looper: Looper, private val central: Central, val serviceUUID: UUID) :
  Handler(looper) {
  private val logTag = "TransferHandler"
  private var readCounter = 0;
  private var chunkCounter = 0;

  enum class States {
    UnInitialised,
    ResponseSizeWritePending,
    ResponseSizeWriteSuccess,
    ResponseSizeWriteFailed,
    ResponseWritePending,
    ResponseWriteFailed,
    TransferComplete,
    PendingSemaphoreAck
  }

  private var currentState: States = States.UnInitialised
  private var chunker: Chunker? = null
  private var responseStartTimeInMillis: Long = 0

  @OptIn(ExperimentalTime::class)
  override fun handleMessage(msg: Message) {
    Log.d(logTag, "Received message to transfer thread handler: ${msg.what} and ${msg.data}")
    when (msg.what) {
      IMessage.TransferMessageTypes.INIT_RESPONSE_TRANSFER.ordinal -> {
        val initResponseTransferMessage = msg.obj as InitResponseTransferMessage
        val responseData = initResponseTransferMessage.data.toUByteArray()
        Log.d(logTag, "Total response size of data ${responseData.size}")
        chunker = Chunker(responseData)
        currentState = States.ResponseSizeWritePending
        this.sendMessage(ResponseSizeWritePendingMessage(responseData.size))
      }
      IMessage.TransferMessageTypes.RESPONSE_SIZE_WRITE_PENDING.ordinal -> {
        val responseSizeWritePendingMessage = msg.obj as ResponseSizeWritePendingMessage
        sendResponseSize(responseSizeWritePendingMessage.size)
      }
      IMessage.TransferMessageTypes.RESPONSE_SIZE_WRITE_SUCCESS.ordinal -> {
        responseStartTimeInMillis = System.currentTimeMillis()
        currentState = States.ResponseSizeWriteSuccess
        initResponseChunkSend()
      }
      IMessage.TransferMessageTypes.RESPONSE_SIZE_WRITE_FAILED.ordinal -> {
        currentState = States.ResponseSizeWriteFailed
      }
      IMessage.TransferMessageTypes.INIT_RESPONSE_CHUNK_TRANSFER.ordinal -> {
        currentState = States.ResponseWritePending
        sendResponseChunk()
      }
      IMessage.TransferMessageTypes.READ_SEMAPHORE_STATUS.ordinal -> {
        currentState = States.PendingSemaphoreAck
        readSemaphoreStatus()
      }
      IMessage.TransferMessageTypes.CHUNK_WRITE_TO_REMOTE_STATUS_UPDATED.ordinal -> {
        val chunkWriteToRemoteStatusUpdatedMessage =
          msg.obj as ChunkWriteToRemoteStatusUpdatedMessage
        when (chunkWriteToRemoteStatusUpdatedMessage.semaphoreCharValue) {
          Semaphore.SemaphoreMarker.ProcessChunkPending.ordinal -> {
            Log.d(logTag, "Semaphore is still pending: ${readCounter++}")
            if(readCounter < 50) {
              readSemaphoreAckDelayed()
            }
          }
          Semaphore.SemaphoreMarker.FailedToRead.ordinal -> {
            Log.d(logTag, "Failed to read semaphore: ${readCounter++}")
            if(readCounter < 50) {
              readSemaphoreAckDelayed()
            }
          }
          Semaphore.SemaphoreMarker.ProcessChunkComplete.ordinal -> {
            currentState = States.ResponseWritePending
            sendResponseChunk()
          }
          Semaphore.SemaphoreMarker.ResendChunk.ordinal -> {
            Log.d(logTag, "handleMessage: resend chunk requested")
          }
          Semaphore.SemaphoreMarker.Error.ordinal -> {
            Log.d(logTag, "handleMessage: chunk marked as error while reading by remote")
            currentState = States.ResponseWriteFailed
          }
        }
      }
      IMessage.TransferMessageTypes.RESPONSE_CHUNK_WRITE_SUCCESS.ordinal -> {
        updateSemaphore(Semaphore.SemaphoreMarker.ProcessChunkPending)
        readSemaphoreAckDelayed()
        chunkCounter++
      }
      IMessage.TransferMessageTypes.RESPONSE_TRANSFER_COMPLETE.ordinal -> {
        // TODO: Let higher level know
        Log.d(logTag, "handleMessage: Successfully transferred vc in ${System.currentTimeMillis() - responseStartTimeInMillis}ms")
        currentState = States.TransferComplete
      }
    }
  }

  private fun updateSemaphore(semaphoreState: Semaphore.SemaphoreMarker) {
    central.write(
      serviceUUID,
      GattService.SEMAPHORE_CHAR_UUID,
      byteArrayOf(semaphoreState.ordinal.toByte())
    )
  }

  private fun readSemaphoreStatus() {
    central.read(serviceUUID, GattService.SEMAPHORE_CHAR_UUID)
  }

  private fun sendResponseChunk() {
    Log.d(logTag, "Writing Chunk: $chunkCounter and is complete: ${chunker?.isComplete()}")
    if (chunker?.isComplete() == true) {
      this.sendMessage(ResponseTransferCompleteMessage())
      return
    }

    val chunkArray = chunker?.next()
    if (chunkArray != null) {
      central.write(
        serviceUUID,
        GattService.RESPONSE_CHAR_UUID,
        byteArrayOf(0, 1, 73, Byte.MAX_VALUE) + chunkArray.toByteArray()
      )

      readCounter = 0
    }
  }

  private fun readSemaphoreAckDelayed() {
    this.sendMessageDelayed(ReadSemaphoreStatusMessage(), 20)
  }

  private fun initResponseChunkSend() {
    Log.d(logTag, "initResponseChunkSend")
    val initResponseChunkTransferMessage = InitResponseChunkTransferMessage()
    this.sendMessage(initResponseChunkTransferMessage)
  }

  fun sendMessage(msg: IMessage) {
    val message = this.obtainMessage()
    message.what = msg.msgType.ordinal
    message.obj = msg
    this.sendMessage(message)
  }


  private fun sendMessageDelayed(msg: IMessage, delayMillis: Long) {
    val message = this.obtainMessage()
    message.what = msg.msgType.ordinal
    message.obj = msg
    this.sendMessageDelayed(message, delayMillis)
  }

  private fun sendResponseSize(size: Int) {
    central.write(
      serviceUUID,
      GattService.RESPONSE_SIZE_CHAR_UUID,
      "$size".toByteArray()
    )
  }

  fun getCurrentState(): States {
    return currentState
  }
}
