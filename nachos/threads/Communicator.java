package nachos.threads;

import nachos.machine.*;

/**
 * A <i>communicator</i> allows threads to synchronously exchange 32-bit
 * messages. Multiple threads can be waiting to <i>speak</i>, and multiple
 * threads can be waiting to <i>listen</i>. But there should never be a time
 * when both a speaker and a listener are waiting, because the two threads can
 * be paired off at this point.
 */
public class Communicator {
	/**
	 * Allocate a new communicator.
	 */
	public Communicator() {
		lock = new Lock();
		nonSpeaker = new Condition2(lock);
		nonListener = new Condition2(lock);
		sendingWord = null;
	}

	/**
	 * Wait for a thread to listen through this communicator, and then transfer
	 * <i>word</i> to the listener.
	 *
	 * <p>
	 * Does not return until this thread is paired up with a listening thread.
	 * Exactly one listener should receive <i>word</i>.
	 *
	 * @param word
	 *            the integer to transfer.
	 */
	public void speak(int word) {
		lock.acquire();
		while (listenerCount == 0 || sendingWord != null)
			nonListener.sleep();
		sendingWord = word;
		nonSpeaker.wake();
		listenerCount--;
		lock.release();
	}

	/**
	 * Wait for a thread to speak through this communicator, and then return the
	 * <i>word</i> that thread passed to <tt>speak()</tt>.
	 *
	 * @return the integer transferred.
	 */
	public int listen() {
		int word;
		lock.acquire();
		while (sendingWord == null){
			nonListener.wake();
			listenerCount++;
			nonSpeaker.sleep();
		}
		word = sendingWord;
		sendingWord = null;
		nonListener.wake();
		lock.release();
		return word;
	}

	private Lock lock;
	private Condition2 nonSpeaker;
	private Condition2 nonListener;
	private Integer sendingWord;	//正在发送的word
	
	private int listenerCount = 0;
}
