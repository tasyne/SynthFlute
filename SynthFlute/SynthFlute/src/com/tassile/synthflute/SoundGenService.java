package com.tassile.synthflute;

import java.util.Arrays;
import java.util.Stack;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

class SoundGenService extends Thread{
	public Handler mHandler;
	int sr = 44100;
	boolean isRunning = true;
	int sliderval = 100;
	int buffsize = 0;
	AudioTrack audioTrack; // = null;
	private static final String TAG = "BluetoothChat";
	
	public boolean[] keyArr = new boolean[12]; // holds currently pressed keys
	private Stack<Short> sampleStack = new Stack<Short>(); // makes for a loop 
	private double[] notes = new double[96]; //because (7*12)+11+1 (the +1 is zero'th position)

	public void run(){
		setPriority(Thread.MAX_PRIORITY);
		
		if (Looper.myLooper() == null){
			Looper.prepare();
		}
		
		mHandler = new Handler() {
			public void handleMessage(Message msg){
				// :D do whatchoo want
			}
		};
		
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
    	int i = 0;
    	int offset = 0;
	    while(isRunning){
	    	
	    	//
	    	
		    fr =  A; //440 + 440*sliderval; // A = A0
		    for( i=0; i < buffsize; i++){
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
		    if(note+octave > 0){
		    	Log.d(TAG, "note: " + note + " octave: " + octave);
		    	Log.d(TAG, "freq: " + fr);
		    }
		    audioTrack.write(samples, 0, buffsize);
	    }
	    audioTrack.stop();
	    audioTrack.release();
		Looper.loop();
	};
	
	public synchronized void play(int freq){
		sliderval = freq;
		isRunning = true;
	}
	
	public synchronized void halt(){
		isRunning = false;
	}
	
	public synchronized void setPressedKeys(int touched){
		//use bit-shifts to convert from a number to a set of keys
		boolean[] tmpArr = new boolean[12];
		for(int i=0; i<12; i++){
			tmpArr[i] = (touched & ( 1 << i )) > 0;
		}
		
		//Log.d(TAG,"setPressedKeys received int: " + touched + " Printing array: " + Arrays.toString(tmpArr));
		
		keyArr = tmpArr;
	}
	
	public synchronized void setPressedKeys(boolean[] keys){
		keyArr = keys;
	}
	
}