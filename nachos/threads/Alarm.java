package nachos.threads;

import java.util.Comparator;
import java.util.PriorityQueue;

import nachos.machine.*;

/**
 * Uses the hardware timer to provide preemption, and to allow threads to sleep
 * until a certain time.
 */
public class Alarm {

	/**
	 * Allocate a new Alarm. Set the machine's timer interrupt handler to this
	 * alarm's callback.
	 *
	 * <p>
	 * <b>Note</b>: Nachos will not function correctly with more than one alarm.
	 */
	public Alarm() {
		waitQueue = new PriorityQueue<>(11, waitingTimeComparator);
		Machine.timer().setInterruptHandler(new Runnable() {
			public void run() {
				timerInterrupt();
			}
		});
	}

	/**
	 * The timer interrupt handler. This is called by the machine's timer
	 * periodically (approximately every 500 clock ticks). Causes the current
	 * thread to yield, forcing a context switch if there is another thread that
	 * should be run.
	 */
	public void timerInterrupt() {
		boolean intStatus = Machine.interrupt().disable();

		while (waitQueue.peek() != null && waitQueue.peek().getWaketime() < Machine.timer().getTime()) {
			KThread thread = waitQueue.poll().getThread();
			thread.ready();
		}

		Machine.interrupt().restore(intStatus);
	}

	/**
	 * Put the current thread to sleep for at least <i>x</i> ticks, waking it up
	 * in the timer interrupt handler. The thread must be woken up (placed in
	 * the scheduler ready set) during the first timer interrupt where
	 *
	 * <p>
	 * <blockquote> (current time) >= (WaitUntil called time)+(x) </blockquote>
	 *
	 * @param x
	 *            the minimum number of clock ticks to wait.
	 *
	 * @see nachos.machine.Timer#getTime()
	 */
	public void waitUntil(long x) {
		// for now, cheat just to get something working (busy waiting is bad)
		long wakeTime = Machine.timer().getTime() + x;
		while (wakeTime > Machine.timer().getTime()) {
			boolean intStatus = Machine.interrupt().disable();

			waitQueue.add(new WaitingThread(wakeTime, KThread.currentThread()));
		    KThread.sleep();

			Machine.interrupt().restore(intStatus);
		}
	}

	private PriorityQueue<WaitingThread> waitQueue;
	private Comparator<WaitingThread> waitingTimeComparator = new Comparator<WaitingThread>() {
		@Override
		public int compare(WaitingThread t1, WaitingThread t2) {
			return new Long(t1.getWaketime()).compareTo(new Long(t2.getWaketime()));
		}
	};
	
	private class WaitingThread {
		public WaitingThread(long waketime, KThread thread) {
			this.waketime = waketime;
			this.thread = thread;
		}

		public long getWaketime() {
			return waketime;
		}

		public KThread getThread() {
			return thread;
		}
		
		private long waketime;
		private KThread thread;
	}
}
