package umich.hijack.core;

public class HiJackIO {
	private PktTransmitter _pktTx;

	private final static int MAX_DIGITAL_PIN = 7;
	private final static int MAX_ANALOG_PIN = 3;

	public enum PinMode {
		INPUT,
		OUTPUT
	}

	public enum PinVal {
		LOW,
		HIGH
	}

	public class HiJackIOPinException extends Exception {
		private static final long serialVersionUID = 1L;
	}

	public void pinMode(int pin, PinMode pm) throws HiJackIOPinException {
		if (!isValid(pin)) {
			throw new HiJackIOPinException();
		}
		HiJackIOPacket pkt = new HiJackIOPacket();
		if (pm == PinMode.INPUT) {
			pkt.cmd = HiJackIOCommand.SET_INPUT;
		} else {
			pkt.cmd = HiJackIOCommand.SET_OUTPUT;
		}
		pkt.pin = pin;

		_pktTx.sendPacket(pkt);
	}

	public void digitalWrite(int pin, PinVal val) throws HiJackIOPinException {
		if (!isValid(pin)) {
			throw new HiJackIOPinException();
		}

		HiJackIOPacket pkt = new HiJackIOPacket();
		pkt.cmd = HiJackIOCommand.DIGITAL_WRITE;
		pkt.pin = pin;
		pkt.pinValue = val.ordinal();
		_pktTx.sendPacket(pkt);
	}

	public void digitalRead(int pin) throws HiJackIOPinException {
		if (!isValid(pin)) {
			throw new HiJackIOPinException();
		}

		HiJackIOPacket pkt = new HiJackIOPacket();
		pkt.cmd = HiJackIOCommand.DIGITAL_READ;
		pkt.pin = pin;
		_pktTx.sendPacket(pkt);
	}

	public void analogWrite(int pin, int val) throws HiJackIOPinException {
		if (!isValidAnalog(pin)) {
			throw new HiJackIOPinException();
		}

		HiJackIOPacket pkt = new HiJackIOPacket();
		pkt.cmd = HiJackIOCommand.ANALOG_WRITE;
		pkt.pin = pin;
		pkt.adcValue = val;
		_pktTx.sendPacket(pkt);
	}

	public void analogRead(int pin) throws HiJackIOPinException {
		if (!isValidAnalog(pin)) {
			throw new HiJackIOPinException();
		}

		HiJackIOPacket pkt = new HiJackIOPacket();
		pkt.cmd = HiJackIOCommand.ANALOG_READ;
		pkt.pin = pin;
		_pktTx.sendPacket(pkt);
	}

	public void disableInterrupt(int pin) throws HiJackIOPinException {
		if (!isValid(pin)) {
			throw new HiJackIOPinException();
		}

		HiJackIOPacket pkt = new HiJackIOPacket();
		pkt.cmd = HiJackIOCommand.DISABLE_INTERRUPT;
		pkt.pin = pin;
		_pktTx.sendPacket(pkt);
	}

	public void enableRisingInterrupt(int pin) throws HiJackIOPinException {
		if (!isValid(pin)) {
			throw new HiJackIOPinException();
		}

		HiJackIOPacket pkt = new HiJackIOPacket();
		pkt.cmd = HiJackIOCommand.ENABLE_INTERRUPT_RISING;
		pkt.pin = pin;
		_pktTx.sendPacket(pkt);
	}

	public void enableFallingInterrupt(int pin) throws HiJackIOPinException {
		if (!isValid(pin)) {
			throw new HiJackIOPinException();
		}

		HiJackIOPacket pkt = new HiJackIOPacket();
		pkt.cmd = HiJackIOCommand.ENABLE_INTERRUPT_FALLING;
		pkt.pin = pin;
		_pktTx.sendPacket(pkt);
	}

	public void registerPacketTransmitter (PktTransmitter ptx) {
		_pktTx = ptx;
	}

	private boolean isValid (int pin) {
		if (pin < 0 || pin > MAX_DIGITAL_PIN) {
			return false;
		}
		return true;
	}

	private boolean isValidAnalog (int pin) {
		if (pin < 0 || pin > MAX_ANALOG_PIN) {
			return false;
		}
		return true;
	}
}
