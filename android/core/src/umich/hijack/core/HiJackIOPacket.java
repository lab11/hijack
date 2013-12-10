package umich.hijack.core;

public class HiJackIOPacket extends Packet {

	public HiJackIOCommand cmd;
	public int pin;
	public int pinValue;
	public int adcValue;

	private final static int HEADER_BYTE_IDX = 0;
	private final static int PINVALUE_BYTE_IDX = 1;
	private final static int ADCVALUE_UPPERBYTE_IDX = 1;
	private final static int ADCVALUE_LOWERBYTE_IDX = 2;

	private final static int HIO_CMD_MASK = 0xF << 4;
	private final static int HIO_CMD_OFFSET = 4;
	private final static int HIO_PIN_MASK = 0xF;


	@Override
	public boolean processReceivedPacket () {
		// Do the initial processing of the stock header and checksum
		super.processReceivedPacket();

		if (super.length < 1) {
			return false;
		}

		// Parse HiJackIO header byte
		cmd = HiJackIOCommand.values()[(super.data[HEADER_BYTE_IDX] & HIO_CMD_MASK) >> HIO_CMD_OFFSET];
		pin = super.data[HEADER_BYTE_IDX] & HIO_PIN_MASK;

		if (super.length == 2) {
			pinValue = super.data[PINVALUE_BYTE_IDX];
		} else if (super.length == 3) {
			adcValue = (super.data[ADCVALUE_UPPERBYTE_IDX] << 8) | super.data[ADCVALUE_LOWERBYTE_IDX];
		} else {
			return false;
		}

		return true;
	}

	@Override
	public void compressToBuffer () {

		super.length = 1;
		super.data[HEADER_BYTE_IDX] = 0;
		super.data[HEADER_BYTE_IDX] |= (cmd.ordinal() << HIO_CMD_OFFSET) & HIO_CMD_MASK;
		super.data[HEADER_BYTE_IDX] |= pin & HIO_PIN_MASK;

		if (cmd == HiJackIOCommand.DIGITAL_WRITE) {
			super.data[PINVALUE_BYTE_IDX] = pinValue;
			super.length++;
		} else if (cmd == HiJackIOCommand.ANALOG_WRITE) {
			super.data[ADCVALUE_UPPERBYTE_IDX] = (adcValue >> 8) & 0xFF;
			super.data[ADCVALUE_LOWERBYTE_IDX] = adcValue  & 0xFF;
			super.length += 2;
		}

		super.compressToBuffer();
	}

}
