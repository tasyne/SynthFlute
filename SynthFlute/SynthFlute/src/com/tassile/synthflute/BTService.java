/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tassile.synthflute;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Stack;
import java.util.UUID;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

/**
 * This class does all the work for setting up and managing Bluetooth
 * connections with other devices. It has a thread that listens for
 * incoming connections, a thread for connecting with a device, and a
 * thread for performing data transmissions when connected.
 */
public class BTService {
    // Debugging
    private static final String TAG = "BluetoothChatService";
    private static final boolean D = true;

    // Name for the SDP record when creating server socket
    private static final String NAME_SECURE = "BluetoothChatSecure";
    private static final String NAME_INSECURE = "BluetoothChatInsecure";
    
    //buffer delimiter
    private static final char BUFFER_DELIMITER = (char) 32;  // 10 = ASCII newline. 32 = ASCII space
    private static final char BUFFER_BEGINLIMITER  = (char) 33; // ! = ascii 33

    // Unique UUID for this application
    private static final UUID MY_UUID_SECURE =
        //UUID.fromString("fa87c0d0-afac-11de-8a39-0800200c9a66");
    	UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");
    private static final UUID MY_UUID_INSECURE =
        //UUID.fromString("8ce255c0-200a-11e0-ac64-0800200c9a66");
    		UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");

    // Member fields
    private final BluetoothAdapter mAdapter;
    private final Handler mHandler;
    private AcceptThread mSecureAcceptThread;
    private AcceptThread mInsecureAcceptThread;
    private ConnectThread mConnectThread;
    private ConnectedThread mConnectedThread;
    private int mState;

    // Constants that indicate the current connection state
    public static final int STATE_NONE = 0;       // we're doing nothing
    public static final int STATE_LISTEN = 1;     // now listening for incoming connections
    public static final int STATE_CONNECTING = 2; // now initiating an outgoing connection
    public static final int STATE_CONNECTED = 3;  // now connected to a remote device

	//public Handler mHandler;
	int sr = 44100;
	boolean isRunning = true;
	int sliderval = 100;
	int buffsize = 0;
	AudioTrack audioTrack; // = null;
	//private static final String TAG = "BluetoothChat";
	
	public boolean[] keyArr = new boolean[12]; // holds currently pressed keys
	private Stack<Short> sampleStack = new Stack<Short>(); // makes for a loop 
	private double[] notes = new double[96]; //because (7*12)+11+1 (the +1 is zero'th position)
    
    
    
    
    
    /**
     * Constructor. Prepares a new BluetoothChat session.
     * @param context  The UI Activity Context
     * @param handler  A Handler to send messages back to the UI Activity
     */
    public BTService(Context context, Handler handler) {
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        mState = STATE_NONE;
        mHandler = handler;
    }

    /**
     * Set the current state of the chat connection
     * @param state  An integer defining the current connection state
     */
    private synchronized void setState(int state) {
        if (D) Log.d(TAG, "setState() " + mState + " -> " + state);
        mState = state;

        // Give the new state to the Handler so the UI Activity can update
        mHandler.obtainMessage(SynthFlute.MESSAGE_STATE_CHANGE, state, -1).sendToTarget();
    }

    /**
     * Return the current connection state. */
    public synchronized int getState() {
        return mState;
    }

    /**
     * Start the chat service. Specifically start AcceptThread to begin a
     * session in listening (server) mode. Called by the Activity onResume() */
    public synchronized void start() {
        if (D) Log.d(TAG, "start");

        // Cancel any thread attempting to make a connection
        if (mConnectThread != null) {mConnectThread.cancel(); mConnectThread = null;}

        // Cancel any thread currently running a connection
        if (mConnectedThread != null) {mConnectedThread.cancel(); mConnectedThread = null;}

        setState(STATE_LISTEN);

        // Start the thread to listen on a BluetoothServerSocket
        if (mSecureAcceptThread == null) {
            mSecureAcceptThread = new AcceptThread(true);
            mSecureAcceptThread.start();
        }
        if (mInsecureAcceptThread == null) {
            mInsecureAcceptThread = new AcceptThread(false);
            mInsecureAcceptThread.start();
        }
    }

    /**
     * Start the ConnectThread to initiate a connection to a remote device.
     * @param device  The BluetoothDevice to connect
     * @param secure Socket Security type - Secure (true) , Insecure (false)
     */
    public synchronized void connect(BluetoothDevice device, boolean secure) {
        if (D) Log.d(TAG, "connect to: " + device);

        // Cancel any thread attempting to make a connection
        if (mState == STATE_CONNECTING) {
            if (mConnectThread != null) {mConnectThread.cancel(); mConnectThread = null;}
        }

        // Cancel any thread currently running a connection
        if (mConnectedThread != null) {mConnectedThread.cancel(); mConnectedThread = null;}

        // Start the thread to connect with the given device
        mConnectThread = new ConnectThread(device, secure);
        mConnectThread.start();
        setState(STATE_CONNECTING);
    }

    /**
     * Start the ConnectedThread to begin managing a Bluetooth connection
     * @param socket  The BluetoothSocket on which the connection was made
     * @param device  The BluetoothDevice that has been connected
     */
    public synchronized void connected(BluetoothSocket socket, BluetoothDevice
            device, final String socketType) {
        if (D) Log.d(TAG, "connected, Socket Type:" + socketType);

        // Cancel the thread that completed the connection
        if (mConnectThread != null) {mConnectThread.cancel(); mConnectThread = null;}

        // Cancel any thread currently running a connection
        if (mConnectedThread != null) {mConnectedThread.cancel(); mConnectedThread = null;}

        // Cancel the accept thread because we only want to connect to one device
        if (mSecureAcceptThread != null) {
            mSecureAcceptThread.cancel();
            mSecureAcceptThread = null;
        }
        if (mInsecureAcceptThread != null) {
            mInsecureAcceptThread.cancel();
            mInsecureAcceptThread = null;
        }

        // Start the thread to manage the connection and perform transmissions
        mConnectedThread = new ConnectedThread(socket, socketType);
        mConnectedThread.start();

        // Send the name of the connected device back to the UI Activity
        Message msg = mHandler.obtainMessage(SynthFlute.MESSAGE_DEVICE_NAME);
        Bundle bundle = new Bundle();
        bundle.putString(SynthFlute.DEVICE_NAME, device.getName());
        msg.setData(bundle);
        mHandler.sendMessage(msg);

        setState(STATE_CONNECTED);
    }

    /**
     * Stop all threads
     */
    public synchronized void stop() {
        if (D) Log.d(TAG, "stop");

        if (mConnectThread != null) {
            mConnectThread.cancel();
            mConnectThread = null;
        }

        if (mConnectedThread != null) {
            mConnectedThread.cancel();
            mConnectedThread = null;
        }

        if (mSecureAcceptThread != null) {
            mSecureAcceptThread.cancel();
            mSecureAcceptThread = null;
        }

        if (mInsecureAcceptThread != null) {
            mInsecureAcceptThread.cancel();
            mInsecureAcceptThread = null;
        }
        setState(STATE_NONE);
    }

    /**
     * Write to the ConnectedThread in an unsynchronized manner
     * @param out The bytes to write
     * @see ConnectedThread#write(byte[])
     */
    public void write(byte[] out) {
        // Create temporary object
        ConnectedThread r;
        // Synchronize a copy of the ConnectedThread
        synchronized (this) {
            if (mState != STATE_CONNECTED) return;
            r = mConnectedThread;
        }
        // Perform the write unsynchronized
        r.write(out);
    }

    /**
     * Indicate that the connection attempt failed and notify the UI Activity.
     */
    private void connectionFailed() {
        // Send a failure message back to the Activity
        Message msg = mHandler.obtainMessage(SynthFlute.MESSAGE_TOAST);
        Bundle bundle = new Bundle();
        bundle.putString(SynthFlute.TOAST, "Unable to connect device");
        msg.setData(bundle);
        mHandler.sendMessage(msg);

        // Start the service over to restart listening mode
        BTService.this.start();
    }

    /**
     * Indicate that the connection was lost and notify the UI Activity.
     */
    private void connectionLost() {
        // Send a failure message back to the Activity
        Message msg = mHandler.obtainMessage(SynthFlute.MESSAGE_TOAST);
        Bundle bundle = new Bundle();
        bundle.putString(SynthFlute.TOAST, "Device connection was lost");
        msg.setData(bundle);
        mHandler.sendMessage(msg);

        // Start the service over to restart listening mode
        BTService.this.start();
    }

    /**
     * This thread runs while listening for incoming connections. It behaves
     * like a server-side client. It runs until a connection is accepted
     * (or until cancelled).
     */
    private class AcceptThread extends Thread {
        // The local server socket
        private final BluetoothServerSocket mmServerSocket;
        private String mSocketType;

        public AcceptThread(boolean secure) {
            BluetoothServerSocket tmp = null;
            mSocketType = secure ? "Secure":"Insecure";

            // Create a new listening server socket
            try {
                if (secure) {
                    tmp = mAdapter.listenUsingRfcommWithServiceRecord(NAME_SECURE,
                        MY_UUID_SECURE);
                } else {
                    tmp = mAdapter.listenUsingInsecureRfcommWithServiceRecord(
                            NAME_INSECURE, MY_UUID_INSECURE);
                }
            } catch (IOException e) {
                Log.e(TAG, "Socket Type: " + mSocketType + "listen() failed", e);
            }
            mmServerSocket = tmp;
        }

        public void run() {
            if (D) Log.d(TAG, "Socket Type: " + mSocketType +
                    "BEGIN mAcceptThread" + this);
            setName("AcceptThread" + mSocketType);

            BluetoothSocket socket = null;
        	
        	
            // Listen to the server socket if we're not connected
            while (mState != STATE_CONNECTED) {
                try {
                    // This is a blocking call and will only return on a
                    // successful connection or an exception
                    socket = mmServerSocket.accept();
                } catch (IOException e) {
                    Log.e(TAG, "Socket Type: " + mSocketType + "accept() failed", e);
                    break;
                }

                // If a connection was accepted
                if (socket != null) {
                    synchronized (BTService.this) {
                        switch (mState) {
                        case STATE_LISTEN:
                        case STATE_CONNECTING:
                            // Situation normal. Start the connected thread.
                            connected(socket, socket.getRemoteDevice(),
                                    mSocketType);
                            break;
                        case STATE_NONE:
                        case STATE_CONNECTED:
                            // Either not ready or already connected. Terminate new socket.
                            try {
                                socket.close();
                            } catch (IOException e) {
                                Log.e(TAG, "Could not close unwanted socket", e);
                            }
                            break;
                        }
                    }
                }
            }
            if (D) Log.i(TAG, "END mAcceptThread, socket Type: " + mSocketType);

        }

        public void cancel() {
            if (D) Log.d(TAG, "Socket Type" + mSocketType + "cancel " + this);
            try {
                mmServerSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "Socket Type" + mSocketType + "close() of server failed", e);
            }
        }
    }


    /**
     * This thread runs while attempting to make an outgoing connection
     * with a device. It runs straight through; the connection either
     * succeeds or fails.
     */
    private class ConnectThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final BluetoothDevice mmDevice;
        private String mSocketType;

        public ConnectThread(BluetoothDevice device, boolean secure) {
            mmDevice = device;
            BluetoothSocket tmp = null;
            mSocketType = secure ? "Secure" : "Insecure";

            // Get a BluetoothSocket for a connection with the
            // given BluetoothDevice
            try {
                if (secure) {
                    tmp = device.createRfcommSocketToServiceRecord(
                            MY_UUID_SECURE);
                } else {
                    tmp = device.createInsecureRfcommSocketToServiceRecord(
                            MY_UUID_INSECURE);
                }
            } catch (IOException e) {
                Log.e(TAG, "Socket Type: " + mSocketType + "create() failed", e);
            }
            mmSocket = tmp;
        }

        public void run() {
            Log.i(TAG, "BEGIN mConnectThread SocketType:" + mSocketType);
            setName("ConnectThread" + mSocketType);

            // Always cancel discovery because it will slow down a connection
            mAdapter.cancelDiscovery();

            // Make a connection to the BluetoothSocket
            try {
                // This is a blocking call and will only return on a
                // successful connection or an exception
                mmSocket.connect();
            } catch (IOException e) {
                // Close the socket
                try {
                    mmSocket.close();
                } catch (IOException e2) {
                    Log.e(TAG, "unable to close() " + mSocketType +
                            " socket during connection failure", e2);
                }
                connectionFailed();
                return;
            }

            // Reset the ConnectThread because we're done
            synchronized (BTService.this) {
                mConnectThread = null;
            }

            // Start the connected thread
            connected(mmSocket, mmDevice, mSocketType);
        }

        public void cancel() {
            try {
                mmSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "close() of connect " + mSocketType + " socket failed", e);
            }
        }
    }

    /**
     * This thread runs during a connection with a remote device.
     * It handles all incoming and outgoing transmissions.
     */
    private class ConnectedThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final InputStream mmInStream;
        private final OutputStream mmOutStream;

        public ConnectedThread(BluetoothSocket socket, String socketType) {
            Log.d(TAG, "create ConnectedThread: " + socketType);
            mmSocket = socket;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;

            // Get the BluetoothSocket input and output streams
            try {
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
            } catch (IOException e) {
                Log.e(TAG, "temp sockets not created", e);
            }

            mmInStream = tmpIn;
            mmOutStream = tmpOut;
        }

        public void run() {

    		// precompute the notes
    		double A = 440.0; //A0 Hz
    		int AOff = 48; //A0's offset in the array's indices
    		
    		//int octave = 0;
    		//int note = 0;
    		for(int i=0; i<notes.length; i++){
    			notes[i] = A * Math.pow(Math.pow(2, (1.0/12.0)), i-AOff); //i-AOff
    			Log.d(TAG, "notes["+i+"] = " + notes[i]);
    			/*
    			note++;
    			if(note==12){
    				octave++;
    				note=0;
    			}
    			*/
    		}
    		
    		if(notes[AOff] != A){
    			Log.e(TAG, "Sanity check: notes[" + AOff + "] != " + A + " precomputation of notes likely failed.");
    		}
    			
            // set the buffer size
    		buffsize = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
    		Log.d(TAG, "buffsize: " + buffsize);
    		
    		//buffsize = 64;
    		
    		
            AudioTrack audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC,
                    sr, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, buffsize,
                    AudioTrack.MODE_STREAM);
            //audioTrack.
            //Log.d(TAG, "after setting buffsize to 64, ")
    		
    		//Looper.prepare();
    		//Looper.loop();
    		short samples[] = new short[buffsize];
    		int amp = 10000;
    		double twopi = 8.*Math.atan(1.);
    		double fr = 440.f;
    		double ph = 0.0;
    		
    		// start audio
    		audioTrack.play();
    		/*
    		 * One note will be played at a time, but chords might also be played. Additionally, there will likely be a
    		 * circular ... reverb stuff going on... I suppose this could be done by calculating the gradual falloff of
    		 * a just-finished note in advance... Record that in a stack. Additional notes will have to be included in 
    		 * the stack, by adding the samples to what's already in the stack...
    		 * 
    		 * That is all to say, we may have two methods- one for single notes, one for chords, and at the end of either
    		 * one, we will do some stack processing.
    		 */
    		
    		// synthesis loop
        	int octave = 0;
        	int note = 0;
        	int j = 0;
        	//int i = 0;
        	int offset = 0;
        	
        	
        	
        	
        	
        	
        	
        	// added smaller byte buffer, because why not? - Tas
        	// original implementation
            Log.i(TAG, "BEGIN mConnectedThread");
            setPriority(Thread.MAX_PRIORITY);
            
            // other implmentation
            
            int bSize = 64; //used to be 1024 in original implementation
            int readBufferPosition = 0;
            byte[] readBuffer = new byte[bSize];
            byte[] encodedBytes = new byte[bSize];
            
            
            byte[] buffer = new byte[bSize];
            byte[] smallBuffer = new byte[bSize];
            int bytes = -1;
            int startoffset = 0;
            boolean beginFlag = false;

            // Keep listening to the InputStream while connected
            while (true) {
                try {
                	
                	
                    // Read from the InputStream
                    //bytes = mmInStream.read(buffer);

                	
                    //wait until (space) NEW BUFFERING METHOD CODE CHANGE
                    int bAvail = mmInStream.available();

                    if (bAvail > 0){
                    	byte[] packetBytes = new byte[bAvail];
                    	mmInStream.read(packetBytes);
                        for(int i=0; i<bAvail; i++){
                        	byte b = packetBytes[i];
                        	if(b == BUFFER_DELIMITER && beginFlag){
                        		//byte[] encodedBytes = new byte[readBufferPosition];
                        		encodedBytes = new byte[readBufferPosition];
                        		System.arraycopy(buffer, 0, encodedBytes, 0, encodedBytes.length); //public static void arraycopy (Object src, int srcPos, Object dst, int dstPos, int length)
                        		readBufferPosition = 0;
                        		//mHandler.obtainMessage(SynthFlute.MESSAGE_READ, readBufferPosition, -1, readBuffer).sendToTarget();
                                final String readMessage = new String(readBuffer).replaceAll("[\\D]", "");
                                //Log.d(TAG, "integer to be parsed: " + readMessage);
                                setPressedKeys(Integer.parseInt(readMessage));
                        		beginFlag = false;
                        	} else if (b == BUFFER_BEGINLIMITER){
                        		beginFlag = true;
                        		//encodedBytes = new byte[readBufferPosition];
                        		readBuffer = new byte[bSize]; //erase!
                        	} else {
                            	readBuffer[readBufferPosition++] = b;
                            }
                        }
                    } 
                    
                    

        		    fr =  A; //440 + 440*sliderval; // A = A0
        		    for(int i=0; i < buffsize; i++){
        		    	note = 0;
        		    	octave = 0;
        		    	// I'm guessing we'll do this in here. 
        		    	// check for which note is currently being played (if any) - look it up in the frequency table
        		    	//[0-3] = note, [4-6] = octave, 7,8,9... = PLAYNOTE (could be replaced later with velocity info)
        		    	//fr = notes[];

        		    	for(j=0; j<4; j++){
        		    		if(keyArr[j]){
        		    			note += Math.pow(2, j);
        		    		}
        		    	}
        		    	offset = 0;
        		    	for(j=4; j<7; j++){
        		    		if(keyArr[j]){
        		    			octave += Math.pow(2, offset);
        		    		}
        		    		offset++;
        		    	}
        		    	//compute actual note to play, using note and octave buttons:
        		    	fr = notes[(octave*12)+note];
        			    samples[i] = (short) (amp*Math.sin(ph));
        			    ph += twopi*fr/sr;
        		    }
        		    /*
        		    if(note+octave > 0){
        		    	Log.d(TAG, "note: " + note + " octave: " + octave);
        		    	Log.d(TAG, "freq: " + fr);
        		    }
        	    	*/
        		    audioTrack.write(samples, 0, buffsize);
                    
        		    
        		    
        		    
        		    
        		    
        		    
        		    
        		    
        		    
        		    
        		    
        		    
                	/*
                	String message = "";
                    // Read from the InputStream
                    bytes = mmInStream.read(buffer);
                    if(bytes != -1){
                    	while((bytes==bSize)&&(buffer[bSize-1] != 0)){
                    		message = message + new String(buffer, 0, bytes);
                    		bytes = mmInStream.read(buffer);
                    	}
                    	message = message + new String(buffer, 0, bytes -1);
                    	Log.d(TAG, message);
                    	mHandler.obtainMessage(SynthFlute.MESSAGE_READ, 0, -1, smallBuffer).sendToTarget();
                    	*/
                    	/*
	                    for(int i=0; i<buffer.length; i++){
	                    	if(buffer[i] == BUFFER_DELIMITER && beginFlag){
	                    		smallBuffer = new byte[i-startoffset];
	                    		System.arraycopy(buffer, startoffset, smallBuffer, 0, i-startoffset);
	                    		mHandler.obtainMessage(SynthFlute.MESSAGE_READ, 0, -1, smallBuffer).sendToTarget(); //mHandler.obtainMessage(SynthFlute.MESSAGE_READ, startoffset, -1, smallBuffer).sendToTarget();
	                    		beginFlag = false;
	                    		Log.d(TAG, "END-LIMITER HIT");
	                    	} else if (buffer[i] == BUFFER_BEGINLIMITER){
	                    		beginFlag = true;
	                    		startoffset = i;
	                    		Log.d(TAG, "BEGIN-LIMITER");
	                    	}
	                    	Log.d(TAG, Character.toString((char) buffer[i]));
	                  }
	                    */
 //                   }
                    //mmSocket.getInputStream(); //p-sure we don't need this
                      

                	
                	
                	/*
                    boolean endFlag = false;
      
                            // Read from the InputStream
                            //bytes = mmInStream.read(buffer);

                            //wait until (space) NEW BUFFERING METHOD CODE CHANGE
                            int bAvail = mmInStream.available();

                            if (bAvail > 0){
                            	byte[] packetBytes = new byte[bAvail];
                            	mmInStream.read(packetBytes);
                                for(int i=0; i<bAvail; i++){
                                	byte b = packetBytes[i];
                                	if(b == BUFFER_DELIMITER && beginFlag){
                                		//byte[] encodedBytes = new byte[readBufferPosition];
                                		encodedBytes = new byte[readBufferPosition];
                                		System.arraycopy(buffer, 0, encodedBytes, 0, encodedBytes.length); //public static void arraycopy (Object src, int srcPos, Object dst, int dstPos, int length)
                                		readBufferPosition = 0;
                                		mHandler.obtainMessage(SynthFlute.MESSAGE_READ, readBufferPosition, -1, readBuffer).sendToTarget();
                                		beginFlag = false;
                                	} else if (b == BUFFER_BEGINLIMITER){
                                		beginFlag = true;
                                		//encodedBytes = new byte[readBufferPosition];
                                		readBuffer = new byte[bSize]; //erase!
                                	} else {
                                    	readBuffer[readBufferPosition++] = b;
                                    }
                                }
                            } 
                            */
                            // Send the obtained bytes to the UI Activity
                            //mHandler.obtainMessage(SynthFlute.MESSAGE_READ, bytes, -1, buffer).sendToTarget();
                            //new
                            
                        } catch (IOException e) {
                            Log.e(TAG, "disconnected", e);
                    	    audioTrack.stop();
                    	    audioTrack.release();
                    	    
                            connectionLost();
                            // Start the service over to restart listening mode
                            BTService.this.start();
                            break;
                        }
                
                    }
        }
        

    	public void setPressedKeys(int touched){
    		//use bit-shifts to convert from a number to a set of keys
    		boolean[] tmpArr = new boolean[12];
    		for(int i=0; i<12; i++){
    			tmpArr[i] = (touched & ( 1 << i )) > 0;
    		}
    		
    		//Log.d(TAG,"setPressedKeys received int: " + touched + " Printing array: " + Arrays.toString(tmpArr));
    		
    		keyArr = tmpArr;
    	}
    	

        /**
         * Write to the connected OutStream.
         * @param buffer  The bytes to write
         */
        public void write(byte[] buffer) {
            try {
                mmOutStream.write(buffer);

                // Share the sent message back to the UI Activity
                mHandler.obtainMessage(SynthFlute.MESSAGE_WRITE, -1, -1, buffer)
                        .sendToTarget();
            } catch (IOException e) {
                Log.e(TAG, "Exception during write", e);
            }
        }

        public void cancel() {
            try {
                mmSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "close() of connect socket failed", e);
            }
        }
    }
}
