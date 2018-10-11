package net.consensys.pantheon.ethereum.vm;

import net.consensys.pantheon.ethereum.core.Account;
import net.consensys.pantheon.ethereum.core.Address;
import net.consensys.pantheon.ethereum.core.Gas;
import net.consensys.pantheon.ethereum.core.Wei;
import net.consensys.pantheon.util.bytes.Bytes32;
import net.consensys.pantheon.util.bytes.BytesValue;
import net.consensys.pantheon.util.uint.UInt256;

/**
 * A skeleton class for implementing call operations.
 *
 * <p>A call operation creates a child message call from the current message context, allows it to
 * execute, and then updates the current message context based on its execution.
 */
public abstract class AbstractCallOperation extends AbstractOperation {

  public AbstractCallOperation(
      final int opcode,
      final String name,
      final int stackItemsConsumed,
      final int stackItemsProduced,
      final boolean updatesProgramCounter,
      final int opSize,
      final GasCalculator gasCalculator) {
    super(
        opcode,
        name,
        stackItemsConsumed,
        stackItemsProduced,
        updatesProgramCounter,
        opSize,
        gasCalculator);
  }

  /**
   * Returns the additional gas to provide the call operation.
   *
   * @param frame The current message frame
   * @return the additional gas to provide the call operation
   */
  protected abstract Gas gas(MessageFrame frame);

  /**
   * Returns the account the call is being made to.
   *
   * @param frame The current message frame
   * @return the account the call is being made to
   */
  protected abstract Address to(MessageFrame frame);

  /**
   * Returns the value being transferred in the call
   *
   * @param frame The current message frame
   * @return the value being transferred in the call
   */
  protected abstract Wei value(MessageFrame frame);

  /**
   * Returns the apparent value being transferred in the call
   *
   * @param frame The current message frame
   * @return the apparent value being transferred in the call
   */
  protected abstract Wei apparentValue(MessageFrame frame);

  /**
   * Returns the memory offset the input data starts at.
   *
   * @param frame The current message frame
   * @return the memory offset the input data starts at
   */
  protected abstract UInt256 inputDataOffset(MessageFrame frame);

  /**
   * Returns the length of the input data to read from memory.
   *
   * @param frame The current message frame
   * @return the length of the input data to read from memory.
   */
  protected abstract UInt256 inputDataLength(MessageFrame frame);

  /**
   * Returns the memory offset the offset data starts at.
   *
   * @param frame The current message frame
   * @return the memory offset the offset data starts at
   */
  protected abstract UInt256 outputDataOffset(MessageFrame frame);

  /**
   * Returns the length of the output data to read from memory.
   *
   * @param frame The current message frame
   * @return the length of the output data to read from memory.
   */
  protected abstract UInt256 outputDataLength(MessageFrame frame);

  /**
   * Returns the account address the call operation is being performed on
   *
   * @param frame The current message frame
   * @return the account address the call operation is being performed on
   */
  protected abstract Address address(MessageFrame frame);

  /**
   * Returns the account address the call operation is being sent from
   *
   * @param frame The current message frame
   * @return the account address the call operation is being sent from
   */
  protected abstract Address sender(MessageFrame frame);

  /**
   * Returns the gas available to execute the child message call.
   *
   * @param frame The current message frame
   * @return the gas available to execute the child message call
   */
  protected abstract Gas gasAvailableForChildCall(MessageFrame frame);

  /**
   * Returns whether or not the child message call should be static.
   *
   * @param frame The current message frame
   * @return {@code true} if the child message call should be static; otherwise {@code false}
   */
  protected abstract boolean isStatic(MessageFrame frame);

  @Override
  public void execute(final MessageFrame frame) {
    frame.clearReturnData();

    final Address to = to(frame);
    final Account contract = frame.getWorldState().get(to);

    final Account account = frame.getWorldState().get(frame.getRecipientAddress());
    final Wei balance = account.getBalance();
    if (value(frame).compareTo(balance) > 0 || frame.getMessageStackDepth() >= 1024) {
      frame.expandMemory(inputDataOffset(frame).toLong(), inputDataLength(frame).toInt());
      frame.expandMemory(outputDataOffset(frame).toLong(), outputDataLength(frame).toInt());
      frame.incrementRemainingGas(gasAvailableForChildCall(frame));
      frame.popStackItems(getStackItemsConsumed());
      frame.pushStackItem(Bytes32.ZERO);
      return;
    }

    final BytesValue inputData = frame.readMemory(inputDataOffset(frame), inputDataLength(frame));

    final MessageFrame childFrame =
        MessageFrame.builder()
            .type(MessageFrame.Type.MESSAGE_CALL)
            .messageFrameStack(frame.getMessageFrameStack())
            .blockchain(frame.getBlockchain())
            .worldState(frame.getWorldState().updater())
            .initialGas(gasAvailableForChildCall(frame))
            .address(address(frame))
            .originator(frame.getOriginatorAddress())
            .contract(to)
            .gasPrice(frame.getGasPrice())
            .inputData(inputData)
            .sender(sender(frame))
            .value(value(frame))
            .apparentValue(apparentValue(frame))
            .code(new Code(contract != null ? contract.getCode() : BytesValue.EMPTY))
            .blockHeader(frame.getBlockHeader())
            .depth(frame.getMessageStackDepth() + 1)
            .isStatic(isStatic(frame))
            .completer(child -> complete(frame, child))
            .miningBeneficiary(frame.getMiningBeneficiary())
            .build();

    frame.getMessageFrameStack().addFirst(childFrame);
    frame.setState(MessageFrame.State.CODE_SUSPENDED);
  }

  public void complete(final MessageFrame frame, final MessageFrame childFrame) {
    frame.setState(MessageFrame.State.CODE_EXECUTING);

    final UInt256 outputOffset = outputDataOffset(frame);
    final UInt256 outputSize = outputDataLength(frame);
    frame.writeMemory(outputOffset, outputSize, childFrame.getOutputData());

    frame.setReturnData(childFrame.getOutputData());
    frame.addLogs(childFrame.getLogs());
    frame.addSelfDestructs(childFrame.getSelfDestructs());
    frame.incrementGasRefund(childFrame.getGasRefund());

    final Gas gasRemaining = childFrame.getRemainingGas();
    frame.incrementRemainingGas(gasRemaining);

    frame.popStackItems(getStackItemsConsumed());

    if (childFrame.getState() == MessageFrame.State.COMPLETED_SUCCESS) {
      frame.pushStackItem(UInt256.ONE.getBytes());
    } else {
      frame.pushStackItem(Bytes32.ZERO);
    }

    final int currentPC = frame.getPC();
    frame.setPC(currentPC + 1);
  }
}
