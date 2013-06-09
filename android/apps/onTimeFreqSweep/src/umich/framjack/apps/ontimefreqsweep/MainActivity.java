package umich.framjack.apps.ontimefreqsweep;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

import umich.framjack.core.FramingEngine;
import umich.framjack.core.OnByteSentListener;
import umich.framjack.core.OnBytesAvailableListener;
import umich.framjack.core.SerialDecoder;
import umich.framjack.core.FramingEngine.IncomingPacketListener;
import umich.framjack.core.FramingEngine.OutgoingByteListener;
import android.os.Bundle;
import android.app.Activity;
import android.view.Menu;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

public class MainActivity extends Activity {

	private SerialDecoder _serialDecoder;
	private FramingEngine _framer;
	
	private boolean _nextFlag = false;
	
	// Log file for saving 
	private File logfile;
	private Date date_now;
	private SimpleDateFormat sdf;
	private FileWriter fw;
	private BufferedWriter logfile_bw;
	
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        // Create a log file for this run of the application
        newLogFile();        
        
		_framer = new FramingEngine();
		_serialDecoder = new SerialDecoder();
		
		_serialDecoder.registerBytesAvailableListener(_bytesAvailableListener);
		_serialDecoder.registerByteSentListener(_byteSentListener);
		_framer.registerIncomingPacketListener(_incomingPacketListener);
		_framer.registerOutgoingByteListener(_outgoingByteListener);
		
		System.out.println("woah");
		
		final Button b1 = (Button)findViewById(R.id.button1);
		b1.setOnClickListener(new View.OnClickListener() {
			
			@Override
			public void onClick(View v) {
				EditText startFreqEditText = (EditText)findViewById(R.id.startFreq);
				EditText endFreqEditText = (EditText)findViewById(R.id.endFreq);
				EditText msStepEditText = (EditText)findViewById(R.id.stepMs);
				EditText hzStepEditText = (EditText)findViewById(R.id.stepHz);
				final TextView currFreqTextView = (TextView)findViewById(R.id.currFreq);
			
				final int startFreq = Integer.parseInt(startFreqEditText.getText().toString());
				final int endFreq = Integer.parseInt(endFreqEditText.getText().toString());
				final int msStep = Integer.parseInt(msStepEditText.getText().toString());
				final int hzStep = Integer.parseInt(hzStepEditText.getText().toString());
				
				b1.setText("Running...");
				
				
				
				Thread t = new Thread(new Runnable() {
					int currentFreq = startFreq;
					
					@Override
					public void run() {
						
						while (currentFreq <= endFreq) {
							_serialDecoder.setPowerFreq(currentFreq);
							currentFreq += hzStep;
							
							runOnUiThread(new Runnable() {
								public void run () {
									currFreqTextView.setText(Integer.toString(currentFreq) + " Hz");
								}
							});
							
							// Add note in log that the frequency has changed
							addToLog("set frequency: " + Integer.toString(currentFreq) + " Hz\n");
							
							if (msStep == 0) {
								while (!_nextFlag) {
									try {
										Thread.sleep(100);
									} catch (InterruptedException e) {
										e.printStackTrace();
									}										
								}
								
								_nextFlag = false;
							}
							else {
								try {
									Thread.sleep(msStep);
								} catch (InterruptedException e) {
									e.printStackTrace();
								}									
							}
						}
						
						try {
							logfile_bw.close();
						} catch (IOException e) {
							e.printStackTrace();
						}
						
						MainActivity.this.runOnUiThread(new Runnable() {
							@Override
							public void run() {
								b1.setText("Start");
							}
						});
					}
				});
				
				t.start();
			}
		});
		
		final Button b2 = (Button)findViewById(R.id.button2);
		b2.setOnClickListener(new OnClickListener() {

			@Override
			public void onClick(View v) {
				_nextFlag = true;
			}
		});
    }

    @Override
    public void onPause() {
    	_serialDecoder.stop();
    	super.onPause();
    }
    
    @Override
    public void onResume() {
    	_serialDecoder.start();
    	newLogFile();
    	System.out.println("RESUME");
    	super.onResume();
    }
    
	///////////////////////////////////////////////
	// Listeners
	///////////////////////////////////////////////	
	private OutgoingByteListener _outgoingByteListener = new OutgoingByteListener() {
		public void OutgoingByteTransmit(int[] outgoingRaw) {
			synchronized (MainActivity.this) {
				//_pendingTransmitBytes += outgoingRaw.length;
			}
			
			for (int i = 0; i < outgoingRaw.length; i++) {
				_serialDecoder.sendByte(outgoingRaw[i]);
			}
		}
	};
	
	private IncomingPacketListener _incomingPacketListener = new IncomingPacketListener() {
		public void IncomingPacketReceive(int[] packet) {
			for (int i = 0; i < packet.length; i++) {
				System.out.print(Integer.toHexString(packet[i]) + " ");
			}
			System.out.println();
			//decodeAndUpdate(packet);
		}
	};
	
	private OnByteSentListener _byteSentListener = new OnByteSentListener() {
		public void onByteSent() {
			synchronized (MainActivity.this) {
				//_pendingTransmitBytes--;
				//if (_pendingTransmitBytes == 0) {
				//	int[] toSend = encode();
				//	for (int i = 0; i < toSend.length; i++) {
				//		_framer.transmitByte(toSend[i]);
				//	}
				//	_framer.transmitEnd();
				//}
			}	
		}
	};
	
	private OnBytesAvailableListener _bytesAvailableListener = new OnBytesAvailableListener() {
		public void onBytesAvailable(int count) {
			while(count > 0) {
				int byteVal = _serialDecoder.readByte();
				//System.out.println("Received: " + byteVal);
				_framer.receiveByte(byteVal);
				count--;
			}
		}
	};
	
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.activity_main, menu);
        return true;
    }
    
    private void newLogFile () {
    	// Create a log file for this run of the application
        sdf = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");
        date_now = new Date();
        String date_now_str = sdf.format(date_now);
        System.out.println("on_time_freq_sweep_log_" + date_now_str + ".txt");
        System.out.println(getExternalFilesDir(null));
        logfile = new File(getExternalFilesDir(null), "on_time_freq_sweep_log_" + date_now_str + ".txt");
        if (logfile.isFile()) {
        	System.out.println("yeah file");
        } else {
        	System.out.println("NOOOOPE");
        }
        try {
        	fw = new FileWriter(logfile.getAbsoluteFile());
        } catch (IOException e) {
        	System.out.println("no go");
        	e.printStackTrace();
        }
        logfile_bw = new BufferedWriter(fw);
    }
    
    private void addToLog (String s) {
    	// Add note in log
		try {
			logfile_bw.write(s);
		} catch (IOException e) {
			e.printStackTrace();
		}
    }
}
