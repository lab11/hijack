package umich.hijack.core;

import java.util.ArrayList;

// TODO ADD CALLBACKS

public class HiJackIO implements PktRecvCb {
	// Keep a pointer to some module that can transmit packets.
	private PktTransmitter _pktTx;

	// Keep a pointer to the packet dispatcher as well
	private final PacketDispatch _dispatcher;

	// Maximum index of digital and analog pins. The assumption is that the
	// analog pins are both analog and digital and the analog pins are the
	// lowered numbered ones: 0-MAX_ANALOG_PIN.
	private final static int MAX_DIGITAL_PIN = 7;
	private final static int MAX_ANALOG_PIN = 3;

	// Keep track of all the upper layers that want to know about incoming
	// hijack state packets
	private final ArrayList<PktRecvCb> _digitalReadCb;
	private final ArrayList<PktRecvCb> _analogReadCb;
	private final ArrayList<PktRecvCb> _interruptCb;

	// Set GPIOs as either inputs or outputs
	public enum PinMode {
		INPUT,
		OUTPUT
	}

	// Status of a digital pin, either high or low
	public enum PinVal {
		LOW,
		HIGH
	}

	// Exception is thrown when the pin index is out of range
	public class HiJackIOPinException extends Exception {
		private static final long serialVersionUID = 1L;
	}

	public HiJackIO (PacketDispatch dispatch) {
		_dispatcher = dispatch;

		_digitalReadCb = new ArrayList<PktRecvCb>();
		_analogReadCb = new ArrayList<PktRecvCb>();
		_interruptCb = new ArrayList<PktRecvCb>();

		// Set us up to receive HiJackIO packets from the peripheral
		_dispatcher.registerIncomingPacketListener(this, PacketType.HIJACKIO);
	}

	// Set a GPIO as an input or output
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

	// Set a GPIO high or low
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

	// Get the pin state (high or low) of a GPIO
	public void digitalRead(int pin) throws HiJackIOPinException {
		if (!isValid(pin)) {
			throw new HiJackIOPinException();
		}

		HiJackIOPacket pkt = new HiJackIOPacket();
		pkt.cmd = HiJackIOCommand.DIGITAL_READ;
		pkt.pin = pin;
		_pktTx.sendPacket(pkt);
	}

	// Write a value to a DAC
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

	// Read an ADC pin
	public void analogRead(int pin) throws HiJackIOPinException {
		if (!isValidAnalog(pin)) {
			throw new HiJackIOPinException();
		}

		HiJackIOPacket pkt = new HiJackIOPacket();
		pkt.cmd = HiJackIOCommand.ANALOG_READ;
		pkt.pin = pin;
		_pktTx.sendPacket(pkt);
	}

	// Disable interrupts for a specific pin
	public void disableInterrupt(int pin) throws HiJackIOPinException {
		if (!isValid(pin)) {
			throw new HiJackIOPinException();
		}

		HiJackIOPacket pkt = new HiJackIOPacket();
		pkt.cmd = HiJackIOCommand.DISABLE_INTERRUPT;
		pkt.pin = pin;
		_pktTx.sendPacket(pkt);
	}

	// Enable a rising edge interrupt on a GPIO
	public void enableRisingInterrupt(int pin) throws HiJackIOPinException {
		if (!isValid(pin)) {
			throw new HiJackIOPinException();
		}

		HiJackIOPacket pkt = new HiJackIOPacket();
		pkt.cmd = HiJackIOCommand.ENABLE_INTERRUPT_RISING;
		pkt.pin = pin;
		_pktTx.sendPacket(pkt);
	}

	// Enable a falling edge interrupt on a GPIO
	public void enableFallingInterrupt(int pin) throws HiJackIOPinException {
		if (!isValid(pin)) {
			throw new HiJackIOPinException();
		}

		HiJackIOPacket pkt = new HiJackIOPacket();
		pkt.cmd = HiJackIOCommand.ENABLE_INTERRUPT_FALLING;
		pkt.pin = pin;
		_pktTx.sendPacket(pkt);
	}


	// Configure the module that is responsible for transmitting packets
	public void registerPacketTransmitter (PktTransmitter ptx) {
		_pktTx = ptx;
	}

	//public void register

	// Callback for incoming HiJackIO packets
	@Override
	public void recvPacket(Packet packet) {
	}

	// Internal function that checks if a pin index is valid
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
