/*
 *  This file is part of hijack-infinity.
 *
 *  hijack-infinity is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  hijack-infinity is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with hijack-infinity.  If not, see <http://www.gnu.org/licenses/>.
 */

package umich.hijack.core;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.ArrayBlockingQueue;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Environment;

/*
 * AudioInterface General Concepts
 *
 * Goal of this interface: Hook up to the audio subsystem in android
 * and process the incoming data and sends output data.
 *
 * Serial data in HiJack is manchester encoded (1 -> 10, 0 -> 01) and
 * frequency-shift key modulated. This means we have one frequency that
 * represents a 1, and twice that frequency represents a 0.
 *
 * The purpose of the AudioInterface class is to process the raw data
 * and provide primitives to a manchester coding engine to process the
 * data and decode it into data usable by the host application.
 *
 * Inputs: Two defined interfaces:
 *
 * OutgoingSource: Contains a function that returns true or false. Is
 * repeatedly invoked by the output engine to determine the next
 * frequency/bit.
 *
 * IncomingSink: Is sent a length/value primitive with the length of the
 * last sustained measured frequency and if it was a high or low frequency.
 */

public class AudioReceiver {

	///////////////////////////////////////////////
	// Constants
	///////////////////////////////////////////////

	// Most Android devices support 'CD-quality' sampling frequencies.
	final private int SAMPLE_FREQUENCY = 44100;

	// HiJack is powered by default at 10kHz
	// This can be adjusted by higher layers if the hijack board is not
	// sufficiently powered.
	private int _powerFrequency = 10000;

	// IO is FSK-modulated at either 613 or 1226 Hz (0 / 1)
	private int _ioBaseFrequency = 613;

	// How much to divide the amplitude of the power signal by. This is useful
	// for phones that can supply a significant amount of power from the
	// audio device and interfere with the other signals.
	private final int _powerSignalDivisor = 3;

	// This sets the number of samples that are requested from the microphone
	// at once.
	private final static int BUF_SAMPLE_LEN = 8000;

	///////////////////////////////////////////////
	// Main interfaces
	///////////////////////////////////////////////

	// These objects handle incoming and outgoing bits. OutgoingSource is called
	// to get the next bit to send over the audio channel and IncomingSink
	// is called when a bit is received from HiJack.
	private OutgoingSource _source = null;
	private IncomingSink _sink = null;

	// Android classes for audio input and output
	AudioTrack _audioTrack;
	AudioRecord _audioRecord;

	// These threads handle recording and playing the audio signals
	Thread _outputThread;
	Thread _inputThread;

	// This thread processes the incoming audio data buffers. The _inputThread
	// puts the buffers in a queue and this thread pulls them from the queue
	// and processes them.
	Thread _audioProcessThread;

	// This queue transports data between the _inputThread and the
	// _audioProcessThread
	private final ArrayBlockingQueue<audioBuffer> audioProcessQueue =
			new ArrayBlockingQueue<audioBuffer>(128);

	// This object is returned from the _inputThread. It contains a buffer
	// of samples from the microphone and the number of samples in that buffer.
	class audioBuffer {
		public int numSamples;
		public short[] buffer;
	}

	///////////////////////////////////////////////
	// Output state
	///////////////////////////////////////////////

	// For performance reasons we batch update the audio output buffer with this
	// many manchester bits. This allows us to load the next buffer while
	// the current audio is playing.
	final private int _bitsInBuffer = 100;

	// These buffers hold samples of the waveforms that are played on the
	// output audio channel. They are precomputed for performance.
	private short[] _outHighHighBuffer;
	private short[] _outLowLowBuffer;
	private short[] _outHighLowBuffer;
	private short[] _outLowHighBuffer;
	private short[] _outFloatingBuffer;

	// This is the x axis value for the power signal being played on the audio
	// channel. This is global state because it needs to be kept between calls
	// to fill the output buffer so we maintain a smooth signal.
	private int _powerFrequencyPos = 0;

	// This is the buffer used to hold samples for playing on the audio
	// hardware.
	private short[] _stereoBuffer;

	// These keep state about the audio subsystem
	private boolean _isInitialized = false;
	private boolean _isRunning = false;
	private boolean _stop = false;

	///////////////////////////////////////////////
	// Input state
	///////////////////////////////////////////////

	// Store previous microphone samples so we can look for edges.
	private int _previousInSample = 0;
	private int _secondPreviousInSample = 0;

	// Keep track of the last edge found so we can look for the opposite next
	private EdgeType _inSignalLastEdge = EdgeType.FALLING;

	// Keeps track of the number of samples between the last edge and the
	// current sample. Used to keep track of the edge spacing for upper layers
	// to process.
	private int _edgeDistance = 0;

	///////////////////////////////////////////////
	// Debug Stuff
	///////////////////////////////////////////////

	// Set this to true to disable the main audio processing and instead
	// write all samples to an output file.
	private final boolean _debug = false;
	private FileWriter _debugOut;

	@SuppressLint("SimpleDateFormat")
	private String getDebugFileName() {
		File root = Environment.getExternalStorageDirectory();

		SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss");
		String currentDateandTime = sdf.format(new Date());

		File debugFile = new File(root, "debug_" +currentDateandTime+ ".data");

		return debugFile.getAbsolutePath();
	}

	private void startDebug() {
		String fileName = getDebugFileName();
		try {
			_debugOut = new FileWriter(fileName);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private void stopDebug() {
		try {
			_debugOut.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private void writeDebugString(String str) {
		try {
			_debugOut.write(str + "\n");
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	///////////////////////////////////////////////
	// Processors
	///////////////////////////////////////////////

	// This fills the _stereobuffer with audio values to play to the HiJack.
	// It reads from uppers layers to get which bits to send.
	private void updateOutputBuffer() {

		SignalLevel outSignal[] = new SignalLevel[_bitsInBuffer];

		// Read in _bitsInBuffer number of samples from the upper layer
		for (int i = 0; i < _bitsInBuffer; i++) {
			outSignal[i] = _source.getNextManchesterBit();
		}

		synchronized(this) {
			double powerMutiplier = Math.PI * _powerFrequency / SAMPLE_FREQUENCY * 2;

			// Index of the current manchester bit to put on the output buffer
			int currentBit = -2;

			// Holders for the current bits being looked at to determine what
			// output signal to put in the buffer
			SignalLevel thisBit = SignalLevel.FLOATING;
			SignalLevel nextBit = SignalLevel.FLOATING;
			SignalLevel thirdBit = SignalLevel.FLOATING;

			// Our current positions in the buffers we are copying from
			int floatingBitIdx = 0;
			int outBitIdx = 0;

			for (int i = 0; i < _stereoBuffer.length/2; i++) {

				// Each time we have copied the contents of the samples buffer
				// into the output buffer fetch the next manchester bit
				if (i % _outHighHighBuffer.length == 0) {
					currentBit += 2;
					outBitIdx = 0;

					thisBit = outSignal[currentBit];
					nextBit = outSignal[currentBit+1];
					if (currentBit < _bitsInBuffer-2) {
						thirdBit = outSignal[currentBit+2];
					}
				}

				// The buffer for the data when no output packet is being driven
				// on the audio lines. This buffer is longer than the per
				// bit buffers so we have a different index and a different
				// reset point.
				if (i % ((_outFloatingBuffer.length)) == 0) {
					floatingBitIdx = 0;
				}

				// Copy from the correct buffer given what data we are sending
				if (thisBit == SignalLevel.FLOATING) {
					// In between data packets just send a sin wave
					_stereoBuffer[i*2] = _outFloatingBuffer[floatingBitIdx++];

				} else if (thisBit == SignalLevel.LOW &&
						   nextBit == SignalLevel.LOW &&
						   thirdBit == SignalLevel.LOW) {
					// This is the postamble where we need to send consecutive
					// low bits.
					_stereoBuffer[i*2] = Short.MAX_VALUE/2;

				} else if (thisBit == SignalLevel.HIGH &&
						   nextBit == SignalLevel.HIGH) {
					_stereoBuffer[i*2] = _outHighHighBuffer[outBitIdx++];

				} else if (thisBit == SignalLevel.LOW &&
						   nextBit == SignalLevel.LOW) {
					_stereoBuffer[i*2] = _outLowLowBuffer[outBitIdx++];

				} else if (thisBit == SignalLevel.HIGH &&
						   nextBit == SignalLevel.LOW) {
					_stereoBuffer[i*2] = _outHighLowBuffer[outBitIdx++];

				} else if (thisBit == SignalLevel.LOW &&
						   nextBit == SignalLevel.HIGH) {
					_stereoBuffer[i*2] = _outLowHighBuffer[outBitIdx++];

				}

				// Toss the power signal on there. We keep a running signal
				// across calls to this function with the _powerFrequencyPos
				// var to ensure the wave is continuous.
				_stereoBuffer[i*2+1] =  (short) boundToShort(
						Math.sin(powerMutiplier * _powerFrequencyPos++) *
						(Short.MAX_VALUE/_powerSignalDivisor)
					);
			}

			// To prevent eventual overflows.
			_powerFrequencyPos = _powerFrequencyPos %
			                      (SAMPLE_FREQUENCY * _powerFrequency);
		}
	}

	// This function is called on an incoming buffers of data from the
	// microphone. It processes it looking for edges.
	private void processInputBuffer (audioBuffer abuf) {
		for (int i = 0; i < abuf.numSamples; i++) {
			int inSample = abuf.buffer[i];

			if (_debug) {
				writeDebugString("" + inSample);
				continue;
			}

			// Increment the edge distance from the last edge since we are
			// processing a new sample.
			_edgeDistance++;

			// settings that work on shitty phones
			// Try to determine if this audio sample represents an edge in the
			// manchester encoding.
			// Check if the derivative from this point to the last or this point
			// to the second last spikes high enough to register.
			/*if (Math.abs(inSample - previousInSample) > 15000 ||
				(Math.abs(inSample - secondPreviousInSample)/1) > 15000) {

				if (inSample > previousInSample &&
					inSignalLastEdge == EdgeType.FALLING &&
					inSample > 2000) {
					// This is a rising edge
					_sink.handleNextBit(_edgeDistance, EdgeType.RISING);
					_edgeDistance = 0;
					inSignalLastEdge = EdgeType.RISING;
				} else if (inSample < previousInSample &&
					inSignalLastEdge == EdgeType.RISING &&
					inSample < -2000) {
					// Falling edge
					_sink.handleNextBit(_edgeDistance, EdgeType.FALLING);
					_edgeDistance = 0;
					inSignalLastEdge = EdgeType.FALLING;
				}
			}*/

			// settings that work better on the htc one BEATS BY DR DRE
			if (Math.abs(inSample - _previousInSample) > 30000 ||
			    (Math.abs(inSample - _secondPreviousInSample)/1) > 30000) {

				if (inSample > _previousInSample &&
					_inSignalLastEdge == EdgeType.FALLING &&
					inSample > 20000) {
					// This is a rising edge
					_sink.handleNextBit(_edgeDistance, EdgeType.RISING);
					_edgeDistance = 0;
					_inSignalLastEdge = EdgeType.RISING;
				} else if (inSample < _previousInSample &&
					_inSignalLastEdge == EdgeType.RISING &&
					inSample < -20000) {
					// Falling edge
					_sink.handleNextBit(_edgeDistance, EdgeType.FALLING);
					_edgeDistance = 0;
					_inSignalLastEdge = EdgeType.FALLING;
				}
			}

			// Shift the samples for the next iteration
			_secondPreviousInSample = _previousInSample;
			_previousInSample = inSample;
		}
	}

	///////////////////////////////////////////////
	// Audio Interface Threads
	///////////////////////////////////////////////

	// This thread makes sure there is always data ready to send to the audio
	// output.
	Runnable _outputGenerator = new Runnable() {
		@Override
		public void run() {
			Thread.currentThread().setPriority(Thread.NORM_PRIORITY);

			while (!_stop) {
				updateOutputBuffer();
				_audioTrack.write(_stereoBuffer, 0, _stereoBuffer.length);
			}
		}
	};

	// This thread constantly reads from the audio interface getting microphone
	// sample buffers.
	Runnable _inputProcessor = new Runnable() {
		@Override
		public void run() {
			Thread.currentThread().setPriority(Thread.MIN_PRIORITY);

			// Create a bunch of buffers to put audio data into
			short[][] buffers = new short[256][BUF_SAMPLE_LEN];
			// Keep track of the current buffer
			int       bufIdx  = 0;

			while (!_stop) {
				short[] buffer = buffers[bufIdx++ % buffers.length];
				int shortsRead = _audioRecord.read(buffer, 0, buffer.length);
				try {
					audioBuffer ab = new audioBuffer();
					ab.numSamples = shortsRead;
					ab.buffer = buffer;
					audioProcessQueue.put(ab);
				} catch (InterruptedException intexp) { }
			}
		}
	};

	// This thread processes the data from the microphone. It takes the buffers
	// from the queue and calls the edge detection algorithm.
	Runnable _audioProcessor = new Runnable() {
		@Override
		public void run() {

			while (!_stop) {
				try {
					audioBuffer ab = audioProcessQueue.take();
					processInputBuffer(ab);
				} catch (InterruptedException intexp) { }
			}
		}
	};

	///////////////////////////////////////////////
	// Public Interface
	///////////////////////////////////////////////
	public int getPowerFrequency() {
		return _powerFrequency;
	}

	public void setPowerFrequency (int powerFrequency) {
		_powerFrequency = powerFrequency;
	}

	public void setTransmitFrequency (int transmitFrequency) {
		synchronized (this) {
			_ioBaseFrequency = transmitFrequency;
			initialize();
		}
	}

	public int getTransmitFrequency () {
		return _ioBaseFrequency;
	}

	public void registerOutgoingSource (OutgoingSource source) {
		if (_isRunning) {
			throw new UnsupportedOperationException(
					"AudioIO must be stopped to set a new source.");
		}
		_source = source;
		_source.getNextManchesterBit();
	}

	public void registerIncomingSink (IncomingSink sink) {
		if (_isRunning) {
			throw new UnsupportedOperationException(
					"AudioIO must be stopped to set a new sink.");
		}
		_sink = sink;
	}

	public void initialize() {
		// Create buffers to hold what a high and low frequency waveform
		// looks like
		int bufferSize = getBufferSize();

		// The stereo buffer should be large enough to ensure
		// that scheduling doesn't mess it up.
		_stereoBuffer = new short[bufferSize * _bitsInBuffer];

		// Allocate all of the data holding buffers
		_outHighHighBuffer = new short[bufferSize];
		_outHighLowBuffer = new short[bufferSize];
		_outLowHighBuffer = new short[bufferSize];
		_outLowLowBuffer = new short[bufferSize];
		_outFloatingBuffer = new short[bufferSize * _bitsInBuffer];

		for (int i = 0; i < bufferSize; i++) {
			_outHighHighBuffer[i] = (short) (
				boundToShort(Math.sin((double)(i + bufferSize) * (double)2 *
				Math.PI * _ioBaseFrequency / SAMPLE_FREQUENCY) * Short.MAX_VALUE)
			);

			_outHighLowBuffer[i] = (short) (
				boundToShort(Math.sin((double)(i + bufferSize/2) * (double)4 *
				Math.PI * _ioBaseFrequency / SAMPLE_FREQUENCY) * Short.MAX_VALUE)
			);

			_outLowLowBuffer[i] = (short) (
				boundToShort(Math.sin((double)i * (double)2 * Math.PI *
				_ioBaseFrequency / SAMPLE_FREQUENCY) * Short.MAX_VALUE)
			);

			_outLowHighBuffer[i] = (short) (
				boundToShort(Math.sin((double)i * (double)4 * Math.PI *
				_ioBaseFrequency / SAMPLE_FREQUENCY) * Short.MAX_VALUE)
			);
		}

		for (int i = 0; i < bufferSize * _bitsInBuffer; i++) {
			_outFloatingBuffer[i] = (short) (
				boundToShort(Math.sin((i) * Math.PI *
				_ioBaseFrequency / SAMPLE_FREQUENCY / 12.1) * Short.MAX_VALUE)
			);
		}

		_isInitialized = true;
	}

	public void startAudioIO() {
		if (!_isInitialized) {
			initialize();
		}

		if (_isRunning) {
			return;
		}

		_stop = false;

		attachAudioResources();

		_audioRecord.startRecording();
		_audioTrack.play();

		if (_debug) {
			startDebug();
		}

		_outputThread = new Thread(_outputGenerator);
		_inputThread = new Thread(_inputProcessor);
		_audioProcessThread = new Thread(_audioProcessor);

		_outputThread.start();
		_inputThread.start();
		_audioProcessThread.start();
	}

	public void stopAudioIO() {
		_stop = true;

		try {
			_outputThread.join();
			_inputThread.join();
			_audioProcessThread.join();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}

		releaseAudioResources();

		_isInitialized = false;

		if (_debug) {
			stopDebug();
		}

		_isRunning = false;
	}

	///////////////////////////////////////////////
	// Support functions
	///////////////////////////////////////////////

	private void attachAudioResources() {
		_audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC,
			SAMPLE_FREQUENCY,
			AudioFormat.CHANNEL_OUT_STEREO,
			AudioFormat.ENCODING_PCM_16BIT,
			44100,
			AudioTrack.MODE_STREAM);

		int recBufferSize = AudioRecord.getMinBufferSize(SAMPLE_FREQUENCY,
				AudioFormat.CHANNEL_IN_MONO,
				AudioFormat.ENCODING_PCM_16BIT);

		System.out.println("REC BUFFER SIZE: " + recBufferSize);

		_audioRecord = new AudioRecord(MediaRecorder.AudioSource.DEFAULT,
			SAMPLE_FREQUENCY,
			AudioFormat.CHANNEL_IN_MONO,
			AudioFormat.ENCODING_PCM_16BIT,
			recBufferSize);

		//_recBuffer = new short[recBufferSize * 10];
	}

	private void releaseAudioResources() {
		_audioTrack.release();
		_audioRecord.release();

		_audioTrack = null;
		_audioRecord = null;

		_stereoBuffer = null;
		//_recBuffer = null;
	}

	private double boundToShort(double in) {
		return (in >= 32786.0) ? 32786.0 : (in <= -32786.0 ? -32786.0 : in );
	}

	private int getBufferSize() {
		//return _sampleFrequency / _ioBaseFrequency / 2 / 2;
		return SAMPLE_FREQUENCY / _ioBaseFrequency / 2;
	}

}
