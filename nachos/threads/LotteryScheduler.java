package nachos.threads;

import nachos.machine.*;

/**
 * A scheduler that chooses threads using a lottery.
 *
 * <p>
 * A lottery scheduler associates a number of tickets with each thread. When a
 * thread needs to be dequeued, a random lottery is held, among all the tickets
 * of all the threads waiting to be dequeued. The thread that holds the winning
 * ticket is chosen.
 *
 * <p>
 * Note that a lottery scheduler must be able to handle a lot of tickets
 * (sometimes billions), so it is not acceptable to maintain state for every
 * ticket.
 *
 * <p>
 * A lottery scheduler must partially solve the priority inversion problem; in
 * particular, tickets must be transferred through locks, and through joins.
 * Unlike a priority scheduler, these tickets add (as opposed to just taking the
 * maximum).
 */
public class LotteryScheduler extends PriorityScheduler {
	/**
	 * Allocate a new lottery scheduler.
	 */
	public LotteryScheduler() {
	}

	/**
	 * Allocate a new lottery thread queue.
	 *
	 * @param transferPriority
	 *            <tt>true</tt> if this queue should transfer tickets from
	 *            waiting threads to the owning thread.
	 * @return a new lottery thread queue.
	 */
	public ThreadQueue newThreadQueue(boolean transferPriority) {
		return new LotteryQueue(transferPriority);
	}

	public void setPriority(KThread thread, int priority) {
		Lib.assertTrue(Machine.interrupt().disabled());

		Lib.assertTrue(priority >= priorityMinimum && priority <= priorityMaximum);

		getThreadState(thread).setPriority(priority);
	}

	public boolean increasePriority() {
		boolean intStatus = Machine.interrupt().disable();

		KThread thread = KThread.currentThread();

		int priority = getPriority(thread);
		if (priority == priorityMaximum)
			return false;

		setPriority(thread, priority + 1);

		Machine.interrupt().restore(intStatus);
		return true;
	}

	public boolean decreasePriority() {
		boolean intStatus = Machine.interrupt().disable();

		KThread thread = KThread.currentThread();

		int priority = getPriority(thread);
		if (priority == priorityMinimum)
			return false;

		setPriority(thread, priority - 1);

		Machine.interrupt().restore(intStatus);
		return true;
	}

	protected LotteryThreadState getThreadState(KThread thread) {
		if (thread.schedulingState == null)
			thread.schedulingState = new LotteryThreadState(thread);

		return (LotteryThreadState) thread.schedulingState;
	}

	public static void selfTest() {
		Scheduler scheduler = ThreadedKernel.scheduler;
		Runnable r1 = new Runnable() {
			@Override
			public void run() {
				for (int i = 0; i < 100; i++) {
					KThread.yield();
					boolean intStatus = Machine.interrupt().disable();
					System.out.println("I'm T1. I have " + scheduler.getPriority() + " tickets.");
					testCount++;
					Machine.interrupt().restore(intStatus);
				}
			}
		};
		Runnable r2 = new Runnable() {
			@Override
			public void run() {
				for (int i = 0; i < 100; i++) {
					KThread.yield();
					boolean intStatus = Machine.interrupt().disable();
					System.out.println("I'm T2. I have " + scheduler.getPriority() + " tickets.");
					testCount++;
					Machine.interrupt().restore(intStatus);
				}
			}
		};
		Runnable r3 = new Runnable() {
			@Override
			public void run() {
				for (int i = 0; i < 100; i++) {
					KThread.yield();
					boolean intStatus = Machine.interrupt().disable();
					System.out.println("I'm T3. I have " + scheduler.getPriority() + " tickets.");
					testCount++;
					Machine.interrupt().restore(intStatus);
				}
			}
		};
		KThread t1 = new KThread(r1).setName("T1");
		KThread t2 = new KThread(r2).setName("T2");
		KThread t3 = new KThread(r3).setName("T3");
		boolean intStatus = Machine.interrupt().disable();
		scheduler.setPriority(t1, 3);
		scheduler.setPriority(t2, 7);
		scheduler.setPriority(t3, 10);
		Machine.interrupt().restore(intStatus);
		t1.fork();
		t2.fork();
		t3.fork();
		while (testCount < 100) {
			ThreadedKernel.alarm.waitUntil(100);
		}
	}

	// now priority means the number of tickets belong to a thread
	/**
	 * The default priority for a new thread. Do not change this value.
	 */
	public static final int priorityDefault = 1;
	/**
	 * The minimum priority that a thread can have. Do not change this value.
	 */
	public static final int priorityMinimum = 1;
	/**
	 * The maximum priority that a thread can have. Do not change this value.
	 */
	public static final int priorityMaximum = Integer.MAX_VALUE;

	private static int testCount = 0;

	/**
	 * A <tt>ThreadQueue</tt> that sorts threads by priority.
	 */
	protected class LotteryQueue extends PriorityQueue {

		LotteryQueue(boolean transferPriority) {
			super(transferPriority);
		}

		public void waitForAccess(KThread thread) {
			Lib.assertTrue(Machine.interrupt().disabled());
			getThreadState(thread).waitForAccess(this);
		}

		public void acquire(KThread thread) {
			Lib.assertTrue(Machine.interrupt().disabled());
			getThreadState(thread).acquire(this);
		}

		public KThread nextThread() {
			Lib.assertTrue(Machine.interrupt().disabled());

			if (resourceHolder != null) { // 当资源被释放，下一个线程开始执行时，如果当前资源持有者不为null
				ThreadState ts = getThreadState(resourceHolder);
				ts.gottenResources.remove(this); // 从resourceHolder持有资源的队列中删除当前队列
				ts.setEffectivePriority(ts.priority - ts.effectivePriority); // resourceHolder的优先级变回原本的优先级
				resourceHolder = null;
			}
			if (waitQueue.isEmpty())
				return null;
			KThread next = pickNextThread();
			ThreadState ts = getThreadState(next);
			waitQueue.remove(next);
			ts.acquire(this);
			totalTicketNum -= ts.priority;
			effectiveTotalTicketNum -= ts.effectivePriority;
			return next;
		}

		protected KThread pickNextThread() {
			int lottery = transferPriority ? Lib.random(effectiveTotalTicketNum) : Lib.random(totalTicketNum);
			int count = 0;
			for (KThread t : waitQueue) {
				count += transferPriority ? getThreadState(t).effectivePriority : getThreadState(t).priority;
				if (count > lottery)
					return t;
			}
			return null;
		}

		private int totalTicketNum;
		private int effectiveTotalTicketNum;
	}

	protected class LotteryThreadState extends ThreadState {

		public LotteryThreadState(KThread thread) {
			super(thread);
		}

		public void setPriority(int priority) {
			int ticketChange = (priority - this.priority);// 实际票数中*本队列票数*部分变化
			setEffectivePriority(ticketChange);
			this.priority = priority;
		}

		public void setEffectivePriority(int ticketChange) {
			if (ticketChange == 0)
				return;

			this.effectivePriority += ticketChange;
			if (waitingResource != null) { // 如果等待在某个队列上
				((LotteryQueue) waitingResource).effectiveTotalTicketNum += ticketChange;
				if (waitingResource.resourceHolder != null) {
					ThreadState ts = getThreadState(waitingResource.resourceHolder);
					if (ts.waitingResource == null || this.thread != ts.waitingResource.resourceHolder) //防止重复递归！
						ts.setEffectivePriority(ticketChange);
				}
			}
		}

		public void waitForAccess(LotteryQueue waitQueue) {
			Lib.assertTrue(Machine.interrupt().disabled());
			waitQueue.waitQueue.add(thread);
			waitQueue.totalTicketNum += this.priority;
			waitQueue.effectiveTotalTicketNum += this.effectivePriority;
			if (waitQueue.resourceHolder == this.thread) {// 如果资源持有者要等待了，那就不是资源持有者了
				waitQueue.resourceHolder = null;
				waitingResource = waitQueue;
				setEffectivePriority(priority - effectivePriority);
			}
			if (waitQueue.resourceHolder != null) { // 存在持有本队列资源的线程
				ThreadState ts = getThreadState(waitQueue.resourceHolder);
				// 解决join时线程1捐献给线程2，线程2又通过join捐献给线程1的问题！！！！！
				if (ts.waitingResource != null && this.thread == ts.waitingResource.resourceHolder)
					ts.setEffectivePriority(ts.priority - ts.effectivePriority);
				ts.effectivePriority += this.effectivePriority;
				if (ts.waitingResource != null)
					((LotteryQueue) ts.waitingResource).effectiveTotalTicketNum += this.effectivePriority;
				waitingResource = waitQueue;
			}
		}

	}

}
